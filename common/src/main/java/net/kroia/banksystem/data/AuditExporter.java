package net.kroia.banksystem.data;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.architectury.platform.Mod;
import dev.architectury.platform.Platform;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.audit.AuditExport;
import net.kroia.banksystem.banking.company.DividendHistoryStore;
import net.kroia.banksystem.util.BankSystemDataHandler;
import net.kroia.modutilities.UtilitiesPlatform;
import net.kroia.modutilities.networking.multi_server.IServerIdentity;
import net.kroia.modutilities.networking.multi_server.MultiServerManager;
import net.kroia.modutilities.networking.multi_server.ServerIdentity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * BS-255 — {@code /banksystem backup export [name]}: one package with everything the audit tool reads, written to
 * {@code <world>/audit_exports/<yyyyMMdd-HHmmss>[-name]/} and zipped to the same name {@code .zip} (the folder stays).
 * Participants ({@link AuditExport#register}) run on the server thread; file copies happen right there, so they never
 * race a save (saves run on the server thread too, and chunk folders are swapped during a save). Database copies are
 * {@code VACUUM INTO} on each database's own thread; this waits for them off the server thread for at most
 * {@value #DB_TIMEOUT_MINUTES} minutes, then hashes every file, writes {@code manifest.json} and zips, on its own thread.
 */
public final class AuditExporter {
    public static final String EXPORTS_DIR = "audit_exports";
    public static final long DB_TIMEOUT_MINUTES = 5;
    public static final int MANIFEST_FORMAT = 1;
    private static final DateTimeFormatter FOLDER_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Map<String, Consumer<AuditExport.Context>> PARTICIPANTS = new LinkedHashMap<>();
    private static final Executor OWN_THREAD = r -> {
        Thread t = new Thread(r, "banksystem-audit-export");
        t.setDaemon(true);
        t.start();
    };
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;

    private AuditExporter() {
    }

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    public static synchronized void register(String modId, Consumer<AuditExport.Context> participant) {
        PARTICIPANTS.put(Objects.requireNonNull(modId, "modId"), Objects.requireNonNull(participant, "participant"));
    }

    /** Tests only: removes a participant again. */
    public static synchronized void unregister(String modId) {
        PARTICIPANTS.remove(modId);
    }

    /** @param zip the zip file, {@code folder} plus {@code .zip}; files = manifest entries that were copied */
    public record Result(Path folder, Path zip, int files, long bytes, List<String> warnings) {
    }

    /** Folder suffix: letters, digits, {@code _} and {@code -} only (no dots or separators), at most 64 chars. */
    public static String sanitizeName(@Nullable String name) {
        if (name == null) return "";
        String s = name.replaceAll("[^A-Za-z0-9_-]", "_");
        if (s.length() > 64) s = s.substring(0, 64);
        return s.isEmpty() ? "" : "-" + s;
    }

    /**
     * Starts an export. Call on the server thread; participants run before this returns.
     *
     * @return completes off the server thread with the result, or fails with a message for the command source
     */
    public static CompletableFuture<Result> export(MinecraftServer server, @Nullable String name) {
        Path exports = server.getWorldPath(LevelResource.ROOT).resolve(EXPORTS_DIR).toAbsolutePath().normalize();
        Path root = exports.resolve(FOLDER_TIME.format(LocalDateTime.now()) + sanitizeName(name));
        Path zip = root.resolveSibling(root.getFileName() + ".zip");
        if (Files.exists(root) || Files.exists(zip))
            return CompletableFuture.failedFuture(new IllegalStateException(root.getFileName() + " already exists"));
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(new IllegalStateException("cannot create " + root + ": " + e.getMessage()));
        }
        boolean slave = BACKEND_INSTANCES != null && BACKEND_INSTANCES.isSlaveServer;
        Map<String, Consumer<AuditExport.Context>> participants;
        synchronized (AuditExporter.class) {
            participants = new LinkedHashMap<>(PARTICIPANTS);
        }
        List<Part> parts = new ArrayList<>();
        for (Map.Entry<String, Consumer<AuditExport.Context>> p : participants.entrySet()) {
            Part part = new Part(p.getKey(), root, slave);
            parts.add(part);
            try {
                p.getValue().accept(part);
            } catch (RuntimeException e) {
                part.error = e.toString();
                warn("audit export participant " + p.getKey() + " failed: " + e);
            }
            part.closed = true;
        }
        CompletableFuture<?>[] dbs = parts.stream().flatMap(p -> p.entries.stream())
                .map(e -> e.db).filter(Objects::nonNull).toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(dbs).orTimeout(DB_TIMEOUT_MINUTES, TimeUnit.MINUTES)
                .handleAsync((v, err) -> finish(root, zip, slave, parts, err), OWN_THREAD);
    }

    private static Result finish(Path root, Path zip, boolean slave, List<Part> parts, @Nullable Throwable err) {
        if (err != null) {
            Throwable cause = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
            StringBuilder msg = new StringBuilder();
            for (Part p : parts)
                for (Entry e : p.entries) {
                    if (e.db == null) continue;
                    if (!e.db.isDone()) msg.append(msg.isEmpty() ? "" : ", ").append(e.relPath).append(" (not finished)");
                    else if (e.db.isCompletedExceptionally()) {
                        Throwable t = e.db.handle((x, ex) -> ex).join();
                        if (t instanceof CompletionException && t.getCause() != null) t = t.getCause();
                        msg.append(msg.isEmpty() ? "" : ", ").append(e.relPath).append(" (").append(t.getMessage()).append(')');
                    }
                }
            String head = cause instanceof TimeoutException
                    ? "database copies did not finish within " + DB_TIMEOUT_MINUTES + " minutes"
                    : "a database copy failed";
            String text = head + ": " + msg + ". The incomplete folder was left at " + root;
            warn("audit export " + text);
            throw new IllegalStateException(text);
        }
        try {
            List<String> warnings = new ArrayList<>();
            JsonObject manifest = new JsonObject();
            manifest.addProperty("format", MANIFEST_FORMAT);
            manifest.addProperty("created_utc", Instant.now().toString());
            IServerIdentity id = MultiServerManager.getIdentity();
            manifest.addProperty("server_uid", id == null ? null : id.serverUid().toString());
            manifest.addProperty("role", slave ? "slave" : MultiServerManager.isMaster() ? "master" : "single");
            manifest.addProperty("minecraft_version", Platform.getMinecraftVersion());
            manifest.addProperty("loader", Platform.isNeoForge() ? "neoforge"
                    : Platform.isModLoaded("quilt_loader") ? "quilt" : Platform.isFabric() ? "fabric" : "forge");
            JsonObject mods = new JsonObject();
            List<String> modIds = new ArrayList<>(List.of(BankSystemMod.MOD_ID, "modutilities"));
            parts.forEach(p -> { if (!modIds.contains(p.modId)) modIds.add(p.modId); });
            for (String modId : modIds)
                mods.addProperty(modId, Platform.getOptionalMod(modId).map(Mod::getVersion).orElse(null));
            manifest.add("mods", mods);
            JsonObject participants = new JsonObject();
            int files = 0;
            long bytes = 0;
            for (Part p : parts) {
                JsonObject po = new JsonObject();
                JsonArray list = new JsonArray();
                for (Entry e : p.entries) {
                    JsonObject f = new JsonObject();
                    f.addProperty("path", e.relPath);
                    f.addProperty("method", e.method);
                    if (!"missing".equals(e.method)) {
                        Path file = root.resolve(e.relPath);
                        long size = Files.size(file);
                        f.addProperty("size", size);
                        f.addProperty("sha256", sha256(file));
                        files++;
                        bytes += size;
                    }
                    list.add(f);
                }
                po.add("files", list);
                if (p.error != null) {
                    po.addProperty("error", p.error);
                    warnings.add(p.modId + ": " + p.error);
                }
                participants.add(p.modId, po);
            }
            manifest.add("participants", participants);
            Files.writeString(root.resolve("manifest.json"),
                    new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(manifest), StandardCharsets.UTF_8);
            zipFolder(root, zip);
            return new Result(root, zip, files, bytes, warnings);
        } catch (IOException e) {
            String text = "writing the manifest or zip failed: " + e.getMessage() + ". The incomplete folder was left at " + root;
            warn("audit export " + text);
            throw new IllegalStateException(text, e);
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** Zip entries are the folder's content (manifest.json at the zip root), not the folder itself. */
    private static void zipFolder(Path root, Path zip) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Path f : files) {
                zos.putNextEntry(new ZipEntry(root.relativize(f).toString().replace('\\', '/')));
                Files.copy(f, zos);
                zos.closeEntry();
            }
        } catch (IOException e) {
            Files.deleteIfExists(zip);
            throw e;
        }
    }

    /**
     * BankSystem's own participant. Saves the BankSystem files first (only what changed), so the NBT files match
     * memory at export time; the database copies are taken right after.
     */
    public static void contributeBankSystem(AuditExport.Context ctx) {
        BankSystemModBackend.Instances inst = BACKEND_INSTANCES;
        MinecraftServer server = UtilitiesPlatform.getServer();
        if (inst == null || server == null) return;
        if (inst.SERVER_DATA_HANDLER != null) inst.SERVER_DATA_HANDLER.saveAll(false);
        Path bs = server.getWorldPath(LevelResource.ROOT).resolve(BankSystemDataHandler.BASE_PATH);
        String rel = "data/BankSystem/";
        String diagRel = rel + "Database/" + DiagnosticsDatabase.FILE_NAME;
        DiagnosticsDatabase diag = inst.DIAGNOSTICS_DB;
        if (diag != null && !diag.isDisabled()) ctx.addDatabase(diagRel, diag::snapshotTo);
        else ctx.copyFile(bs.resolve("Database").resolve(DiagnosticsDatabase.FILE_NAME), diagRel); // no writer: plain copy
        if (!ctx.isSlave()) {
            DatabaseManager db = inst.DATABASE_MANAGER;
            if (db != null)
                ctx.addDatabase(rel + "Database/banksystem.db", t -> db.snapshotTo(t).thenAccept(ok -> {
                    if (!ok) throw new CompletionException(new SQLException("VACUUM INTO failed, see the server log"));
                }));
            DividendHistoryStore dividends = inst.DIVIDEND_HISTORY_STORE;
            if (dividends != null) ctx.addDatabase(rel + "dividend_history.db", dividends::snapshotTo);
            ctx.copyDir(bs.resolve("Bank_data"), rel + "Bank_data");
            ctx.copyDir(bs.resolve("Companies"), rel + "Companies");
            ctx.copyFile(bs.resolve("ItemIDs.nbt"), rel + "ItemIDs.nbt");
            ctx.copyFile(bs.resolve("BankAccountBindings.nbt"), rel + "BankAccountBindings.nbt");
        }
        ctx.copyFile(bs.resolve("Meta_data.nbt"), rel + "Meta_data.nbt");
        ctx.copyFile(bs.resolve("settings.json"), rel + "settings.json");
        ctx.copyDir(bs.resolve("audit"), rel + "audit");
        ctx.copyFile(ServerIdentity.file(server), "data/ModUtilities/server_identity.json");
    }

    private static void warn(String msg) {
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) BACKEND_INSTANCES.LOGGER.warn("[AuditExport] " + msg);
    }

    /** One manifest file entry; {@code db} is set for {@code vacuum_into}. */
    private record Entry(String relPath, String method, @Nullable CompletableFuture<Void> db) {
    }

    private static final class Part implements AuditExport.Context {
        final String modId;
        final Path root;
        final boolean slave;
        final List<Entry> entries = new ArrayList<>();
        @Nullable String error;
        boolean closed;

        Part(String modId, Path root, boolean slave) {
            this.modId = modId;
            this.root = root;
            this.slave = slave;
        }

        @Override
        public Path packageRoot() {
            return root;
        }

        @Override
        public boolean isSlave() {
            return slave;
        }

        @Override
        public void copyFile(Path src, String relPath) {
            Path target = target(relPath);
            String rel = root.relativize(target).toString().replace('\\', '/');
            if (!Files.isRegularFile(src)) {
                entries.add(new Entry(rel, "missing", null));
                return;
            }
            try {
                Files.createDirectories(target.getParent());
                Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
                entries.add(new Entry(rel, "file_copy", null));
            } catch (IOException e) {
                warn("audit export: copying " + src + " failed: " + e);
                entries.add(new Entry(rel, "missing", null));
            }
        }

        @Override
        public void copyDir(Path src, String relPath) {
            if (!Files.isDirectory(src)) {
                copyFile(src, relPath); // lists it as missing
                return;
            }
            List<Path> files;
            try (Stream<Path> walk = Files.walk(src)) {
                files = walk.filter(Files::isRegularFile).sorted().toList();
            } catch (IOException | RuntimeException e) {
                warn("audit export: listing " + src + " failed: " + e);
                return;
            }
            for (Path f : files) copyFile(f, relPath + "/" + src.relativize(f).toString().replace('\\', '/'));
        }

        @Override
        public void addDatabase(String relPath, Function<Path, CompletableFuture<Void>> writeConsistentCopy) {
            Path target = target(relPath);
            CompletableFuture<Void> f;
            try {
                Files.createDirectories(target.getParent());
                f = Objects.requireNonNull(writeConsistentCopy.apply(target), "participant returned no future");
            } catch (Exception e) {
                f = CompletableFuture.failedFuture(e);
            }
            entries.add(new Entry(root.relativize(target).toString().replace('\\', '/'), "vacuum_into", f));
        }

        private Path target(String relPath) {
            if (closed) throw new IllegalStateException("audit export context used after the participant call");
            Path t = root.resolve(relPath).normalize();
            if (!t.startsWith(root) || t.equals(root))
                throw new IllegalArgumentException("path outside the export package: " + relPath);
            return t;
        }
    }
}
