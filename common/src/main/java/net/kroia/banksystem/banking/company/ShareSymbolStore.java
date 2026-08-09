package net.kroia.banksystem.banking.company;

import com.google.gson.*;
import net.kroia.banksystem.util.BankSystemLogger;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Server-side authoritative store for share symbol textures.
 * Master mode: owns the symbol folder, seeds from bundled assets on first start.
 * Mirror mode (slave): reads the folder populated by the S2S sync layer.
 *
 * <p>All public methods are server-thread-only. Not thread-safe.
 */
public class ShareSymbolStore {

    // ── Constants ─────────────────────────────────────────────────────────────

    public static final int MAX_TEXTURE_BYTES = 131072; // 128 KiB
    public static final int MAX_TEXTURE_DIM   = 64;
    public static final int MAX_SYMBOLS       = 256;

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_]{1,32}");

    /** 45 bundled symbol ids in canonical order (matches SharePresetRegistry). */
    public static final List<String> BUNDLED_IDS = List.of(
        "leaf","gear","anvil","pickaxe","sword","shield","crown","star",
        "diamond","emerald","gold_ingot","iron_ingot","wheat","apple","fish",
        "boat","bow","potion","book","map","compass","clock","key","lantern",
        "torch","bell","heart","skull","flame","snowflake",
        "coin","scales","chest","barrel","house","tower","tree","mountain",
        "sun","moon","bolt","anchor","hammer","axe","minecart"
    );

    // ── Inner record ──────────────────────────────────────────────────────────

    public record SymbolEntry(String id, int ordinal, byte[] sha256, int size) {
        public String sha256Hex() {
            StringBuilder sb = new StringBuilder(64);
            for (byte b : sha256) sb.append(String.format("%02x", b));
            return sb.toString();
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private final BankSystemLogger logger;
    private boolean mirrorMode;
    private Path symbolDir;
    private Path inboxDir;

    private final List<SymbolEntry> entries = new ArrayList<>();
    private final Map<String, SymbolEntry> byId  = new LinkedHashMap<>();
    private int revision = 0;

    private Runnable broadcastListener = null;

    // ── Constructor ───────────────────────────────────────────────────────────

    public ShareSymbolStore(BankSystemLogger logger) {
        this.logger = logger;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Open (or create + seed) the store.
     *
     * @param worldDir  server world root ({@code server.getWorldPath(LevelResource.ROOT)})
     * @param mirrorMode {@code true} on slave servers — no seeding, no admin mutations
     */
    public void open(Path worldDir, boolean mirrorMode) {
        this.mirrorMode = mirrorMode;
        this.symbolDir  = worldDir.resolve("banksystem").resolve("share_symbols");
        this.inboxDir   = symbolDir.resolve("inbox");

        try {
            Files.createDirectories(symbolDir);
            if (!mirrorMode) Files.createDirectories(inboxDir);
        } catch (IOException e) {
            logger.error("[ShareSymbolStore] Failed to create directories: " + e.getMessage());
            return;
        }

        Path manifestPath = symbolDir.resolve("manifest.json");
        if (Files.exists(manifestPath)) {
            loadManifest(manifestPath);
        } else if (!mirrorMode) {
            seedFromBundled();
        }
    }

    public void close() {
        // manifest already written on every mutation; nothing extra needed
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public boolean isValidSymbolId(String id) {
        return id != null && byId.containsKey(id);
    }

    public int getRevision() { return revision; }

    public List<SymbolEntry> getEntries() { return Collections.unmodifiableList(entries); }

    /** Raw PNG bytes for the given id, or {@code null} if not found / unreadable. */
    public byte[] getSymbolBytes(String id) {
        if (!byId.containsKey(id)) return null;
        try {
            return Files.readAllBytes(symbolDir.resolve(id + ".png"));
        } catch (IOException e) {
            logger.warn("[ShareSymbolStore] Cannot read " + id + ".png: " + e.getMessage());
            return null;
        }
    }

    /** Register a listener called after every revision bump (add/remove/reload). */
    public void setBroadcastListener(Runnable onRevisionBump) {
        this.broadcastListener = onRevisionBump;
    }

    // ── Admin mutations ───────────────────────────────────────────────────────

    /**
     * Add a symbol from the inbox file {@code <symbolDir>/inbox/<id>.png}.
     *
     * @return {@code null} on success, or a human-readable error string.
     */
    public String adminAdd(String id) {
        if (mirrorMode) return "Run this command on the master server.";
        if (!ID_PATTERN.matcher(id).matches()) return "Invalid id '" + id + "' (must match [a-z0-9_]{1,32}).";
        if (entries.size() >= MAX_SYMBOLS && !byId.containsKey(id))
            return "Symbol limit reached (" + MAX_SYMBOLS + "). Remove a symbol first.";

        Path src = inboxDir.resolve(id + ".png");
        if (!Files.exists(src)) return "Inbox file not found: " + src;

        byte[] bytes;
        try { bytes = Files.readAllBytes(src); } catch (IOException e) {
            return "Cannot read inbox file: " + e.getMessage();
        }

        String err = validatePng(bytes);
        if (err != null) return "PNG validation failed: " + err;

        byte[] hash = sha256(bytes);
        Path dest = symbolDir.resolve(id + ".png");
        try {
            Files.write(dest, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.deleteIfExists(src);
        } catch (IOException e) {
            return "Failed to write symbol file: " + e.getMessage();
        }

        if (byId.containsKey(id)) {
            // Replace existing: keep ordinal, update hash/size
            SymbolEntry old = byId.get(id);
            SymbolEntry updated = new SymbolEntry(id, old.ordinal(), hash, bytes.length);
            int idx = entries.indexOf(old);
            entries.set(idx, updated);
            byId.put(id, updated);
        } else {
            int ordinal = entries.isEmpty() ? 0 : entries.get(entries.size() - 1).ordinal() + 1;
            SymbolEntry entry = new SymbolEntry(id, ordinal, hash, bytes.length);
            entries.add(entry);
            byId.put(id, entry);
        }

        bumpRevision();
        return null;
    }

    /**
     * Remove a symbol by id. Auto-compacts ordinals after removal.
     *
     * @return {@code null} on success, or an error string.
     */
    public String adminRemove(String id) {
        if (mirrorMode) return "Run this command on the master server.";
        if (!byId.containsKey(id)) return "Unknown symbol id '" + id + "'.";

        try { Files.deleteIfExists(symbolDir.resolve(id + ".png")); }
        catch (IOException e) { logger.warn("[ShareSymbolStore] Could not delete " + id + ".png: " + e.getMessage()); }

        entries.removeIf(e -> e.id().equals(id));
        byId.remove(id);
        compact();
        bumpRevision();
        return null;
    }

    /**
     * Rescan the folder: re-hash all PNGs, update manifest if anything changed.
     *
     * @return {@code null} if unchanged, an info string describing changes otherwise.
     */
    public String adminReload() {
        if (mirrorMode) return "Run this command on the master server.";
        int changed = 0;

        // Check existing entries for file changes
        List<SymbolEntry> updated = new ArrayList<>();
        for (SymbolEntry e : entries) {
            byte[] bytes = getSymbolBytes(e.id());
            if (bytes == null) { changed++; continue; } // file deleted
            byte[] hash = sha256(bytes);
            if (!Arrays.equals(hash, e.sha256())) {
                updated.add(new SymbolEntry(e.id(), e.ordinal(), hash, bytes.length));
                changed++;
            } else {
                updated.add(e);
            }
        }
        entries.clear(); entries.addAll(updated);
        updated.forEach(e -> byId.put(e.id(), e));

        if (changed > 0) {
            bumpRevision();
            return "Reloaded: " + changed + " file(s) changed.";
        }
        return null;
    }

    // ── Mirror-mode write (used by S2S networking) ────────────────────────────

    /**
     * Write a symbol entry received from the master (mirror mode).
     * Validates bytes before writing to disk.
     */
    public String mirrorWrite(String id, int ordinal, byte[] sha256, byte[] pngBytes) {
        String err = validatePng(pngBytes);
        if (err != null) return "PNG validation failed for '" + id + "': " + err;

        byte[] actualHash = sha256(pngBytes);
        if (!Arrays.equals(actualHash, sha256))
            return "SHA-256 mismatch for '" + id + "'.";

        try {
            Files.write(symbolDir.resolve(id + ".png"), pngBytes,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            return "Disk write failed for '" + id + "': " + e.getMessage();
        }

        SymbolEntry entry = new SymbolEntry(id, ordinal, sha256, pngBytes.length);
        byId.put(id, entry);
        // Insert/replace at correct ordinal position
        entries.removeIf(e -> e.id().equals(id));
        int insertIdx = entries.size();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).ordinal() > ordinal) { insertIdx = i; break; }
        }
        entries.add(insertIdx, entry);
        return null;
    }

    /** Replace the entire manifest from a received manifest packet (mirror mode). */
    public void mirrorApplyManifest(int newRevision, List<SymbolEntry> newEntries) {
        // Remove local entries not in the new manifest
        Set<String> newIds = new HashSet<>();
        newEntries.forEach(e -> newIds.add(e.id()));
        entries.removeIf(e -> !newIds.contains(e.id()));
        byId.keySet().retainAll(newIds);

        // Update/add entries (don't overwrite bytes we already have)
        for (SymbolEntry e : newEntries) {
            if (!byId.containsKey(e.id()) || !Arrays.equals(byId.get(e.id()).sha256(), e.sha256())) {
                byId.put(e.id(), e);
                entries.removeIf(existing -> existing.id().equals(e.id()));
                entries.add(e);
            }
        }
        entries.sort(Comparator.comparingInt(SymbolEntry::ordinal));

        this.revision = newRevision;
        saveManifest();
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private void seedFromBundled() {
        int ordinal = 0;
        for (String id : BUNDLED_IDS) {
            String resource = "/assets/banksystem/textures/item/share_symbol/" + id + ".png";
            try (InputStream in = ShareSymbolStore.class.getResourceAsStream(resource)) {
                if (in == null) {
                    logger.warn("[ShareSymbolStore] Bundled resource missing: " + resource);
                    continue;
                }
                byte[] bytes = in.readAllBytes();
                String err = validatePng(bytes);
                if (err != null) {
                    logger.warn("[ShareSymbolStore] Bundled PNG invalid (" + id + "): " + err);
                    continue;
                }
                byte[] hash = sha256(bytes);
                Files.write(symbolDir.resolve(id + ".png"), bytes,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                SymbolEntry entry = new SymbolEntry(id, ordinal, hash, bytes.length);
                entries.add(entry);
                byId.put(id, entry);
                ordinal++;
            } catch (IOException e) {
                logger.warn("[ShareSymbolStore] Failed to seed '" + id + "': " + e.getMessage());
            }
        }
        revision = 1;
        saveManifest();
        logger.info("[ShareSymbolStore] Seeded " + entries.size() + " symbols (revision 1).");
    }

    private void loadManifest(Path manifestPath) {
        try {
            String json = Files.readString(manifestPath);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            revision = root.get("revision").getAsInt();
            JsonArray arr = root.getAsJsonArray("symbols");
            entries.clear();
            byId.clear();
            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                String id  = obj.get("id").getAsString();
                int ord    = obj.get("ordinal").getAsInt();
                String hex = obj.get("sha256").getAsString();
                int size   = obj.get("size").getAsInt();
                byte[] hash = hexToBytes(hex);
                SymbolEntry entry = new SymbolEntry(id, ord, hash, size);
                entries.add(entry);
                byId.put(id, entry);
            }
            entries.sort(Comparator.comparingInt(SymbolEntry::ordinal));
            logger.info("[ShareSymbolStore] Loaded " + entries.size() + " symbols (revision " + revision + ").");
        } catch (Exception e) {
            logger.error("[ShareSymbolStore] Failed to load manifest: " + e.getMessage());
        }
    }

    private void saveManifest() {
        JsonObject root = new JsonObject();
        root.addProperty("revision", revision);
        JsonArray arr = new JsonArray();
        for (SymbolEntry e : entries) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id",      e.id());
            obj.addProperty("ordinal", e.ordinal());
            obj.addProperty("sha256",  e.sha256Hex());
            obj.addProperty("size",    e.size());
            arr.add(obj);
        }
        root.add("symbols", arr);

        Path manifest = symbolDir.resolve("manifest.json");
        Path tmp      = symbolDir.resolve("manifest.json.tmp");
        try {
            Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(root));
            Files.move(tmp, manifest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.error("[ShareSymbolStore] Failed to save manifest: " + e.getMessage());
        }
    }

    /** Renumber all entries 0,1,2,… preserving relative order. */
    private void compact() {
        List<SymbolEntry> compacted = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            SymbolEntry old = entries.get(i);
            compacted.add(new SymbolEntry(old.id(), i, old.sha256(), old.size()));
        }
        entries.clear();
        byId.clear();
        for (SymbolEntry e : compacted) {
            entries.add(e);
            byId.put(e.id(), e);
        }
    }

    private void bumpRevision() {
        revision++;
        saveManifest();
        if (broadcastListener != null) broadcastListener.run();
    }

    // ── PNG validation (no NativeImage — server-safe) ─────────────────────────

    /** Returns null on success, or an error description. */
    public static String validatePng(byte[] bytes) {
        if (bytes == null || bytes.length < 25) return "Too small to be a valid PNG.";
        if (bytes.length > MAX_TEXTURE_BYTES)
            return "Exceeds max size (" + MAX_TEXTURE_BYTES + " bytes).";

        // PNG signature
        byte[] sig = {(byte)0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A};
        for (int i = 0; i < 8; i++) {
            if (bytes[i] != sig[i]) return "Not a valid PNG (bad signature).";
        }
        // IHDR chunk type
        if (bytes[12] != 'I' || bytes[13] != 'H' || bytes[14] != 'D' || bytes[15] != 'R')
            return "Not a valid PNG (missing IHDR chunk).";

        int w = ByteBuffer.wrap(bytes, 16, 4).getInt();
        int h = ByteBuffer.wrap(bytes, 20, 4).getInt();
        if (w <= 0 || h <= 0) return "Invalid dimensions.";
        if (w != h)           return "Must be square (got " + w + "x" + h + ").";
        if (!isPow2(w))       return "Dimensions must be a power of two (got " + w + ").";
        if (w > MAX_TEXTURE_DIM)
            return "Dimension " + w + " exceeds max " + MAX_TEXTURE_DIM + ".";
        return null;
    }

    private static boolean isPow2(int n) { return n > 0 && (n & (n - 1)) == 0; }

    // ── SHA-256 ───────────────────────────────────────────────────────────────

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2)
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                               + Character.digit(hex.charAt(i + 1), 16));
        return out;
    }
}
