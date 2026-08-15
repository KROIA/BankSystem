package net.kroia.banksystem.client.company;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.company.ShareSymbolStore;
import net.kroia.banksystem.networking.general.C2SShareSymbolDataRequest;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Task #54 (v2.1.0) — CLIENT-ONLY registry for share symbol PNG data.
 * <p>
 * Receives the manifest from
 * {@link net.kroia.banksystem.networking.general.S2CShareSymbolManifestPacket}
 * and PNG chunks from
 * {@link net.kroia.banksystem.networking.general.S2CShareSymbolDataPacket}.
 * Symbols are cached on disk at {@code <gameDir>/banksystem/symbol_cache/<sha256hex>.png}.
 */
public final class ClientSymbolRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientSymbolRegistry.class);

    public enum SymbolStatus { DECLARED, READY }

    public record SymbolState(String id, int ordinal, byte[] sha256, int size, SymbolStatus status) {}

    private static int revision = 0;
    private static final Map<String, SymbolState> symbols = new LinkedHashMap<>();
    /** sha256 hex → reassembly byte buffer (full size, filled as chunks arrive). */
    private static final Map<String, byte[]> pendingBuffers = new HashMap<>();
    /** sha256 hex → set of received chunk indices. */
    private static final Map<String, Set<Integer>> receivedChunkSets = new HashMap<>();
    /** ids whose DynamicTexture has been registered with the TextureManager. */
    private static final Set<String> dynamicTextureIds = new HashSet<>();
    private static Path diskCacheDir;

    private ClientSymbolRegistry() {}

    /** Called once at client startup — initialises the on-disk cache directory. */
    public static void init() {
        diskCacheDir = net.minecraft.client.Minecraft.getInstance()
                .gameDirectory.toPath().resolve("banksystem").resolve("symbol_cache");
        try {
            Files.createDirectories(diskCacheDir);
        } catch (Exception ignored) {}
    }

    /**
     * Called when {@link net.kroia.banksystem.networking.general.S2CShareSymbolManifestPacket} arrives.
     * Marks entries as DECLARED (unless the correct bytes are already cached),
     * then fires C2S requests for any missing hashes.
     */
    public static void handleManifest(int newRevision, List<ShareSymbolStore.SymbolEntry> entries) {
        if (newRevision <= revision && revision > 0) return; // stale
        revision = newRevision;

        Set<String> newIds = new HashSet<>();
        for (ShareSymbolStore.SymbolEntry e : entries) {
            newIds.add(e.id());
            SymbolState existing = symbols.get(e.id());
            if (existing != null && Arrays.equals(existing.sha256(), e.sha256())
                    && existing.status() == SymbolStatus.READY) {
                // already up-to-date — keep READY
                continue;
            }
            // Try disk cache
            if (diskCacheDir != null) {
                Path cached = diskCacheDir.resolve(toHex(e.sha256()) + ".png");
                if (Files.exists(cached)) {
                    try {
                        byte[] bytes = Files.readAllBytes(cached);
                        if (Arrays.equals(ShareSymbolStore.sha256(bytes), e.sha256())) {
                            symbols.put(e.id(), new SymbolState(e.id(), e.ordinal(), e.sha256(), e.size(), SymbolStatus.READY));
                            continue;
                        }
                    } catch (Exception ignored) {}
                    // cache corrupt — delete
                    try { Files.deleteIfExists(cached); } catch (Exception ignored) {}
                }
            }
            symbols.put(e.id(), new SymbolState(e.id(), e.ordinal(), e.sha256(), e.size(), SymbolStatus.DECLARED));
        }
        symbols.keySet().retainAll(newIds);
        requestMissingBytes();
    }

    /**
     * Called when one chunk from
     * {@link net.kroia.banksystem.networking.general.S2CShareSymbolDataPacket} arrives.
     * Assembles chunks; on completion verifies SHA-256, validates PNG, writes to disk.
     */
    public static void handleDataChunk(byte[] sha256, int totalSize, int chunkIndex, int chunkCount, byte[] chunkBytes) {
        String hex = toHex(sha256);
        byte[] buf = pendingBuffers.computeIfAbsent(hex, k -> new byte[totalSize]);
        int offset = chunkIndex * 65536;
        System.arraycopy(chunkBytes, 0, buf, offset, chunkBytes.length);
        receivedChunkSets.computeIfAbsent(hex, k -> new HashSet<>()).add(chunkIndex);
        if (receivedChunkSets.get(hex).size() == chunkCount) {
            byte[] assembled = buf;
            pendingBuffers.remove(hex);
            receivedChunkSets.remove(hex);
            finalizeTexture(hex, sha256, assembled);
        }
    }

    private static void finalizeTexture(String hex, byte[] sha256, byte[] bytes) {
        if (!Arrays.equals(ShareSymbolStore.sha256(bytes), sha256)) {
            LOGGER.warn("[ClientSymbolRegistry] SHA-256 mismatch for {}...", hex.substring(0, 8));
            return;
        }
        String err = ShareSymbolStore.validatePng(bytes);
        if (err != null) {
            LOGGER.warn("[ClientSymbolRegistry] PNG validation failed ({}...): {}", hex.substring(0, 8), err);
            return;
        }
        if (diskCacheDir != null) {
            Path cachePath = diskCacheDir.resolve(hex + ".png");
            try {
                Files.write(cachePath, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                LOGGER.warn("[ClientSymbolRegistry] Disk cache write failed: {}", e.getMessage());
            }
        }
        final byte[] finalBytes = bytes;
        for (Map.Entry<String, SymbolState> entry : symbols.entrySet()) {
            if (Arrays.equals(entry.getValue().sha256(), sha256)) {
                String symId = entry.getValue().id();
                symbols.put(symId, new SymbolState(
                        entry.getValue().id(), entry.getValue().ordinal(),
                        sha256, bytes.length, SymbolStatus.READY));
                // Register as DynamicTexture on the render thread (T5).
                net.minecraft.client.Minecraft.getInstance().execute(
                        () -> registerDynamicTexture(symId, finalBytes));
            }
        }
    }

    // ── T5: DynamicTexture registration ───────────────────────────────────────

    /**
     * Returns the {@link ResourceLocation} used to register/look up the
     * {@link net.minecraft.client.renderer.texture.DynamicTexture} for a dynamic symbol.
     * Used by {@link SharePresetRegistry#getTexture} and {@link net.kroia.banksystem.client.render.StampedShareRenderer}.
     */
    public static ResourceLocation getDynamicRL(String id) {
        return ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "dynamic/share_symbol/" + id);
    }

    /** {@code true} if the DynamicTexture for {@code id} has been registered with the TextureManager. */
    public static boolean hasDynamicTexture(String id) {
        return dynamicTextureIds.contains(id);
    }

    private static void registerDynamicTexture(String id, byte[] bytes) {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            com.mojang.blaze3d.platform.NativeImage img =
                    com.mojang.blaze3d.platform.NativeImage.read(
                            new ByteArrayInputStream(bytes));
            net.minecraft.client.renderer.texture.DynamicTexture tex =
                    new net.minecraft.client.renderer.texture.DynamicTexture(img);
            mc.getTextureManager().register(getDynamicRL(id), tex);
            dynamicTextureIds.add(id);
        } catch (Exception e) {
            LOGGER.warn("[ClientSymbolRegistry] DynamicTexture registration failed for {}: {}", id, e.getMessage());
        }
    }

    /** Returns the PNG bytes for a READY symbol, or {@code null} if not yet ready. */
    public static byte[] getReadyBytes(String id) {
        if (diskCacheDir == null) return null;
        SymbolState s = symbols.get(id);
        if (s == null || s.status() != SymbolStatus.READY) return null;
        Path cachePath = diskCacheDir.resolve(toHex(s.sha256()) + ".png");
        try {
            return Files.readAllBytes(cachePath);
        } catch (IOException e) {
            return null;
        }
    }

    public static boolean isValidSymbolId(String id) { return symbols.containsKey(id); }
    public static int getRevision() { return revision; }
    public static List<SymbolState> getStates() { return List.copyOf(symbols.values()); }

    /** Called on disconnect / world leave to purge in-flight state. */
    public static void clear() {
        symbols.clear();
        pendingBuffers.clear();
        receivedChunkSets.clear();
        dynamicTextureIds.clear(); // textures remain registered in TextureManager; re-registered on next connect
        revision = 0;
    }

    private static void requestMissingBytes() {
        List<byte[]> missing = symbols.values().stream()
                .filter(s -> s.status() == SymbolStatus.DECLARED)
                .map(SymbolState::sha256)
                .limit(8)
                .collect(Collectors.toList());
        if (!missing.isEmpty()) {
            C2SShareSymbolDataRequest.send(missing);
        }
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
