package net.kroia.banksystem.testing.tests;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.audit.AuditExport;
import net.kroia.banksystem.api.ledger.LedgerActor;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.data.AuditExporter;
import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.data.DiagnosticsDatabase;
import net.kroia.banksystem.data.table.TransactionLogManager;
import net.kroia.banksystem.data.table.record.TransactionLogRecord;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.server.MinecraftServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * BS-255 — {@code /banksystem backup export}: a package taken while the ledger, the diagnostics file and an add-on
 * database are written to has only databases that pass {@code PRAGMA integrity_check}, a manifest that lists every
 * file with a matching SHA-256, and the files of a participant registered through {@link AuditExport#register}.
 */
public class AuditExportTests extends TestSuite {
    private static final String TEST_MOD = "banksystem_audit_export_test";
    private static final String REF = "audit-export-test";
    private static final String META_KEY = "audit_export_test_load";
    private static final int LOAD_ACCOUNT = -255;

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.DATA_PERSISTENCE;
    }

    @Override
    public void registerTests() {
        addTest("export_under_write_load_is_consistent", this::exportUnderWriteLoad);
        addTest("export_name_is_sanitized", this::nameSanitized);
    }

    private TestResult nameSanitized() {
        TestResult r = assertEquals("traversal", "-___evil_x", AuditExporter.sanitizeName("../evil/x"));
        if (!r.passed()) return r;
        r = assertEquals("no name", "", AuditExporter.sanitizeName(null));
        if (!r.passed()) return r;
        return assertEquals("plain", "-before_update-2", AuditExporter.sanitizeName("before_update-2"));
    }

    private TestResult exportUnderWriteLoad() {
        MinecraftServer server = getServer();
        BankSystemModBackend.Instances inst = BankSystemModBackend.getInstances_forTesting();
        DatabaseManager db = inst.DATABASE_MANAGER;
        TransactionLogManager ledger = inst.TRANSACTION_LOG_MANAGER;
        DiagnosticsDatabase diag = inst.DIAGNOSTICS_DB;
        if (server == null || db == null || ledger == null || diag == null) return fail("live master setup missing");

        Path tmp = null;
        DiagnosticsDatabase addonDb = null;
        Thread loader = null;
        AtomicBoolean stop = new AtomicBoolean();
        Result result = null;
        try {
            tmp = Files.createTempDirectory("banksystem-audit-export-test");
            Path note = Files.writeString(tmp.resolve("note.txt"), "participant file", StandardCharsets.UTF_8);
            Path absent = tmp.resolve("absent.txt");
            DiagnosticsDatabase addon = addonDb = new DiagnosticsDatabase(tmp.resolve("addon.db"), "audit-export-test-db", "1",
                    List.of("CREATE TABLE IF NOT EXISTS T (id INTEGER PRIMARY KEY AUTOINCREMENT, v TEXT)"));
            AuditExport.register(TEST_MOD, ctx -> {
                ctx.copyFile(note, "data/AuditExportTest/note.txt");
                ctx.copyFile(absent, "data/AuditExportTest/absent.txt");
                ctx.addDatabase("data/AuditExportTest/addon.db", addon::snapshotTo);
            });

            // Write load on the ledger, the diagnostics file and the add-on database until the export is done.
            AtomicLong written = new AtomicLong();
            AtomicReference<Throwable> loadError = new AtomicReference<>();
            AtomicReference<String> loadStep = new AtomicReference<>("thread start");
            loader = new Thread(() -> {
                try {
                    for (int i = 0; !stop.get(); i++) {
                        List<TransactionLogRecord> rows = new ArrayList<>();
                        for (int k = 0; k < 20; k++)
                            rows.add(TransactionLogRecord.of(LOAD_ACCOUNT, LedgerType.DEPOSIT, (short) 1, 1L,
                                    LedgerActor.system(), null, null, REF, "audit export load", null));
                        // 30 s like the start window below, so a slow save stalls the window instead of failing alone.
                        loadStep.set("ledger save");
                        ledger.save(rows).get(30, TimeUnit.SECONDS);
                        loadStep.set("diag meta");
                        diag.setMeta(META_KEY, Integer.toString(i));
                        loadStep.set("add-on insert");
                        addon.execute("INSERT INTO T (v) VALUES (?)", "row " + i);
                        written.addAndGet(rows.size());
                    }
                } catch (Throwable t) {
                    loadError.set(t);
                }
            }, "audit-export-test-load");
            loader.setDaemon(true);
            loader.start();
            if (!pumpUntil(() -> written.get() >= 200 || loadError.get() != null, 30_000))
                return fail("write load did not start within 30 s: stalled at " + loadStep.get() + ", " + written.get() + " rows written");
            if (loadError.get() != null)
                return fail("write load failed at " + loadStep.get() + " after " + written.get() + " rows: " + loadError.get());

            CompletableFuture<AuditExporter.Result> export = AuditExporter.export(server, "autotest");
            if (!pumpUntil(export::isDone, 120_000)) return fail("export did not finish within 120 s");
            long writtenDuring = written.get();
            stop.set(true);
            loader.join(10_000);
            if (loadError.get() != null) return fail("write load failed: " + loadError.get());
            AuditExporter.Result r;
            try {
                r = export.join();
            } catch (Exception e) {
                return fail("export failed: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
            }
            result = new Result(r.folder(), r.zip());
            return verify(r, writtenDuring);
        } catch (Exception e) {
            return fail("threw " + e);
        } finally {
            stop.set(true);
            AuditExporter.unregister(TEST_MOD);
            if (addonDb != null) addonDb.close();
            if (result != null) {
                deleteTree(result.folder);
                try {
                    Files.deleteIfExists(result.zip);
                } catch (Exception ignored) {
                }
            }
            deleteTree(tmp);
            try {
                db.getDatabaseThread().submit(() -> {
                    try (PreparedStatement ps = db.getConnection().prepareStatement("DELETE FROM TransactionLog WHERE ref = ?")) {
                        ps.setString(1, REF);
                        ps.executeUpdate();
                    }
                    db.commitTransaction();
                    return null;
                }).get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            diag.execute("DELETE FROM Meta WHERE key = ?", META_KEY);
        }
    }

    private record Result(Path folder, Path zip) {
    }

    private TestResult verify(AuditExporter.Result r, long rowsWritten) throws Exception {
        Path root = r.folder();
        if (!Files.isRegularFile(r.zip())) return fail("no zip at " + r.zip());
        JsonObject m = JsonParser.parseString(Files.readString(root.resolve("manifest.json"))).getAsJsonObject();
        if (m.get("format").getAsInt() != 1) return fail("format " + m.get("format"));
        if (!Set.of("master", "single").contains(m.get("role").getAsString())) return fail("role " + m.get("role"));
        for (String key : List.of("created_utc", "server_uid", "minecraft_version", "loader", "mods"))
            if (!m.has(key)) return fail("manifest lacks " + key);
        if (!m.getAsJsonObject("mods").has("banksystem")) return fail("mods lacks banksystem");

        // Every listed file exists with the listed size and SHA-256; every file in the folder is listed.
        Map<String, Map<String, String>> methods = new HashMap<>();
        Set<String> listed = new TreeSet<>();
        JsonObject parts = m.getAsJsonObject("participants");
        for (String mod : parts.keySet()) {
            Map<String, String> byPath = new HashMap<>();
            for (JsonElement el : parts.getAsJsonObject(mod).getAsJsonArray("files")) {
                JsonObject f = el.getAsJsonObject();
                String path = f.get("path").getAsString();
                String method = f.get("method").getAsString();
                byPath.put(path, method);
                if ("missing".equals(method)) continue;
                Path file = root.resolve(path);
                if (!Files.isRegularFile(file)) return fail(path + " listed but not in the package");
                if (Files.size(file) != f.get("size").getAsLong()) return fail(path + " size differs from the manifest");
                if (!sha256(file).equals(f.get("sha256").getAsString())) return fail(path + " sha256 differs from the manifest");
                listed.add(path);
            }
            methods.put(mod, byPath);
        }
        Set<String> present = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(p -> present.add(root.relativize(p).toString().replace('\\', '/')));
        }
        present.remove("manifest.json");
        if (!present.equals(listed)) return fail("folder " + present + " != manifest " + listed);

        Map<String, String> bs = methods.getOrDefault("banksystem", Map.of());
        for (String db : List.of("data/BankSystem/Database/banksystem.db", "data/BankSystem/Database/banksystem_diagnostics.db",
                "data/BankSystem/dividend_history.db"))
            if (!"vacuum_into".equals(bs.get(db))) return fail(db + " method " + bs.get(db));
        for (String f : List.of("data/BankSystem/settings.json", "data/BankSystem/ItemIDs.nbt", "data/BankSystem/Meta_data.nbt"))
            if (!"file_copy".equals(bs.get(f))) return fail(f + " method " + bs.get(f));
        if (!bs.containsKey("data/ModUtilities/server_identity.json")) return fail("server identity not listed");
        Map<String, String> test = methods.get(TEST_MOD);
        if (test == null) return fail("test participant missing from the manifest");
        if (!"file_copy".equals(test.get("data/AuditExportTest/note.txt"))) return fail("note.txt " + test);
        if (!"missing".equals(test.get("data/AuditExportTest/absent.txt"))) return fail("absent.txt " + test);
        if (!"vacuum_into".equals(test.get("data/AuditExportTest/addon.db"))) return fail("addon.db " + test);

        try (ZipFile zip = new ZipFile(r.zip().toFile())) {
            Set<String> zipped = new TreeSet<>();
            zip.stream().forEach(e -> zipped.add(e.getName()));
            Set<String> expected = new TreeSet<>(present);
            expected.add("manifest.json");
            if (!zipped.equals(expected)) return fail("zip " + zipped + " != folder " + expected);
        }

        // Last: opening a copy may add -wal/-shm files next to it.
        List<String> dbs = new ArrayList<>();
        for (String path : listed) {
            if (!path.endsWith(".db")) continue;
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve(path).toAbsolutePath());
                 Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
                String ok = rs.next() ? rs.getString(1) : null;
                if (!"ok".equals(ok)) return fail(path + " integrity_check: " + ok);
            }
            dbs.add(path);
        }
        long ledgerRows = count(root.resolve("data/BankSystem/Database/banksystem.db"),
                "SELECT COUNT(*) FROM TransactionLog WHERE ref = '" + REF + "'");
        long addonRows = count(root.resolve("data/AuditExportTest/addon.db"), "SELECT COUNT(*) FROM T");
        if (ledgerRows <= 0) return fail("ledger copy has none of the load rows");
        if (addonRows <= 0) return fail("add-on copy has none of the load rows");
        return pass("integrity ok for " + dbs + "; " + listed.size() + " files match the manifest; ledger copy has "
                + ledgerRows + " of " + rowsWritten + " load rows, add-on copy " + addonRows);
    }

    private static long count(Path db, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (Exception ignored) {
            // best effort
        }
    }
}
