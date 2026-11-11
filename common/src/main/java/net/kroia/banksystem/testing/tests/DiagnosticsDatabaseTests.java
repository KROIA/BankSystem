package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.data.DiagnosticsDatabase;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Task #123 — {@link DiagnosticsDatabase}: lazy create + Meta, created_ms kept on reopen, Meta upsert,
 * corrupt file renamed and recreated, writer thread, disabled instance, full queue, live instance path;
 * Task #130: an add-on file gets only Meta + its own schema, version and thread.
 * <p>
 * <b>Server type:</b> BOTH (the class runs on slaves too). Every case uses its own file in a temp dir,
 * never the world's diagnostics file.
 */
public class DiagnosticsDatabaseTests extends TestSuite {

    private static final long WAIT_MS = 10_000;
    private static final String K = DiagnosticsDatabase.META_BANK_LAST_SAVE_MS;

    private Path dir;

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.LIFECYCLE;
    }

    @Override
    public void registerTests() {
        addTest("first_write_creates_file_with_meta", () -> run(this::firstWrite));
        addTest("created_ms_survives_reopen", () -> run(this::reopen));
        addTest("set_meta_upserts_one_row", () -> run(this::upsert));
        addTest("corrupt_file_renamed_and_recreated", () -> run(this::corrupt));
        addTest("writes_run_on_diag_thread", () -> run(this::writerThread));
        addTest("disabled_instance_is_noop", () -> run(this::disabled));
        addTest("full_queue_drops_without_blocking", () -> run(this::overflow));
        addTest("live_instance_targets_world_file", () -> run(this::live));
        addTest("addon_file_has_meta_and_own_schema_only", () -> run(this::addonFile));
    }

    @Override
    public void setup() {
        try {
            dir = Files.createTempDirectory("banksystem-diag-test");
        } catch (Exception e) {
            dir = null;
        }
    }

    @Override
    public void teardown() {
        if (dir == null) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (Exception ignored) {
            // best effort
        }
        dir = null;
    }

    private TestResult firstWrite() throws Exception {
        Path f = dir.resolve("first").resolve(DiagnosticsDatabase.FILE_NAME); // parent folder missing too
        DiagnosticsDatabase db = new DiagnosticsDatabase(f);
        if (Files.exists(f)) return fail("file exists before the first write");
        db.setMeta(K, "123");
        await(db.flush(), WAIT_MS);
        boolean created = Files.exists(f);
        db.close();
        Map<String, String> m = meta(f);
        if (!created) return fail("file not created by the first write");
        Set<String> t = tables(f);
        if (!t.equals(Set.of("Meta", "TransferJournal", "NoteFlow"))) return fail("tables " + t);
        if (!m.keySet().equals(Set.of("schema_version", "created_ms", K))) return fail("keys " + m);
        if (!DiagnosticsDatabase.SCHEMA_VERSION.equals(m.get("schema_version"))) return fail("schema_version " + m);
        long createdMs = Long.parseLong(m.get("created_ms"));
        if (createdMs <= 0 || createdMs > System.currentTimeMillis()) return fail("created_ms " + createdMs);
        return pass("first write created the file; Meta = schema_version " + m.get("schema_version") + ", created_ms, " + K);
    }

    private TestResult reopen() throws Exception {
        Path f = dir.resolve("reopen.db");
        DiagnosticsDatabase db = new DiagnosticsDatabase(f);
        db.setMeta(K, "1");
        await(db.flush(), WAIT_MS);
        db.close();
        String before = meta(f).get("created_ms");
        pump(20); // a recreated row would carry a later time
        db = new DiagnosticsDatabase(f);
        db.setMeta(K, "2");
        await(db.flush(), WAIT_MS);
        db.close();
        Map<String, String> m = meta(f);
        if (before == null || !before.equals(m.get("created_ms"))) return fail("created_ms " + before + " -> " + m.get("created_ms"));
        if (!"2".equals(m.get(K))) return fail(K + " " + m.get(K));
        return pass("created_ms " + before + " unchanged after close + reopen");
    }

    private TestResult upsert() throws Exception {
        Path f = dir.resolve("upsert.db");
        DiagnosticsDatabase db = new DiagnosticsDatabase(f);
        db.setMeta(K, "1");
        db.setMeta(K, "2");
        await(db.flush(), WAIT_MS);
        db.close();
        List<String> rows = new ArrayList<>();
        for (String[] r : rows(f)) if (K.equals(r[0])) rows.add(r[1]);
        return rows.equals(List.of("2")) ? pass("one " + K + " row with the latest value") : fail(K + " rows " + rows);
    }

    private TestResult corrupt() throws Exception {
        Path f = dir.resolve("corrupt.db");
        byte[] junk = new byte[4096];
        new Random(123).nextBytes(junk);
        Files.write(f, junk);
        DiagnosticsDatabase db = new DiagnosticsDatabase(f);
        db.setMeta(K, "7");
        await(db.flush(), WAIT_MS);
        boolean off = db.isDisabled();
        db.close();
        List<Path> moved;
        try (Stream<Path> s = Files.list(dir)) {
            moved = s.filter(p -> p.getFileName().toString().startsWith("corrupt.db.corrupt-")).toList();
        }
        if (off) return fail("instance disabled instead of recreating");
        if (moved.size() != 1) return fail("renamed files " + moved);
        if (!Arrays.equals(junk, Files.readAllBytes(moved.get(0)))) return fail("renamed file content differs");
        Map<String, String> m = meta(f);
        if (!"7".equals(m.get(K)) || !DiagnosticsDatabase.SCHEMA_VERSION.equals(m.get("schema_version")))
            return fail("recreated Meta " + m);
        return pass("garbage moved to " + moved.get(0).getFileName() + ", new file has Meta, no exception");
    }

    private TestResult writerThread() throws Exception {
        Path f = dir.resolve("thread.db");
        DiagnosticsDatabase db = new DiagnosticsDatabase(f);
        String caller = Thread.currentThread().getName();
        db.setMeta(K, "1");
        String writer = await(db.flush(), WAIT_MS);
        db.close();
        MinecraftServer s = getServer();
        if (!DiagnosticsDatabase.THREAD_NAME.equals(writer)) return fail("writer thread " + writer);
        if (writer.equals(caller) || (s != null && s.getRunningThread().getName().equals(writer)))
            return fail("writer " + writer + " is the caller/server thread");
        return pass("caller " + caller + ", writer " + writer);
    }

    private TestResult disabled() throws Exception {
        Path blocker = dir.resolve("not-a-folder");
        Files.writeString(blocker, "x");
        Path f = blocker.resolve(DiagnosticsDatabase.FILE_NAME); // parent is a file: cannot be created
        DiagnosticsDatabase db = new DiagnosticsDatabase(f);
        db.setMeta(K, "1");
        await(db.flush(), WAIT_MS);
        boolean off = db.isDisabled();
        db.setMeta(K, "2");
        db.execute("not even sql");
        String after = await(db.flush(), WAIT_MS);
        db.close();
        db.setMeta(K, "3"); // after close: still a no-op
        if (!off) return fail("instance not disabled");
        if (after != null) return fail("write queued on a disabled instance (" + after + ")");
        if (Files.exists(f)) return fail("file created");
        return pass("open failure disabled the instance; later writes are no-ops, no exception");
    }

    private TestResult overflow() throws Exception {
        Path f = dir.resolve("overflow.db");
        int capacity = 8, writes = 40;
        DiagnosticsDatabase db = new DiagnosticsDatabase(f, capacity);
        db.setMeta(K, "0");
        await(db.flush(), WAIT_MS); // open and commit before the file is locked
        long ms;
        long dropped;
        try (Connection lock = DiagnosticsDatabase.connect(f); Statement st = lock.createStatement()) {
            st.execute("BEGIN EXCLUSIVE"); // the writer now waits in SQLite's busy handler
            long t0 = System.nanoTime();
            for (int i = 1; i <= writes; i++) db.setMeta(K, Integer.toString(i));
            ms = (System.nanoTime() - t0) / 1_000_000L;
            dropped = db.dropped();
            st.execute("ROLLBACK");
        }
        db.close(); // drains what was queued behind the lock
        String last = meta(f).get(K);
        long min = writes - capacity - 1; // one write may already sit in the blocked writer
        if (ms > 1000) return fail(writes + " writes took " + ms + " ms against a locked file");
        if (dropped < min || dropped > writes - capacity) return fail("dropped " + dropped + ", expected " + min + ".." + (writes - capacity));
        if (last == null || last.equals("0")) return fail("queued writes not applied after the lock was released: " + last);
        return pass(writes + " writes in " + ms + " ms with the file locked, " + dropped + " dropped, last kept " + last);
    }

    private TestResult live() {
        MinecraftServer s = getServer();
        if (s == null) return skip("no server");
        boolean slave = BankSystemModBackend.getInstances_forTesting().isSlaveServer;
        DiagnosticsDatabase db = BankSystemModBackend.getInstances_forTesting().DIAGNOSTICS_DB;
        if (db == null) return fail("no live instance on " + (slave ? "slave" : "master"));
        Path expected = s.getWorldPath(LevelResource.ROOT).resolve(DatabaseManager.DATABASE_PATH)
                .resolve(DiagnosticsDatabase.FILE_NAME).toAbsolutePath().normalize();
        if (!expected.equals(db.getFile().normalize())) return fail("file " + db.getFile() + ", expected " + expected);
        if (db.isDisabled()) return fail("live instance disabled");
        return pass((slave ? "slave" : "master") + " instance at " + expected);
    }

    /** Task #130: an add-on's own file gets Meta + its schema, its version and its thread, nothing of ours. */
    private TestResult addonFile() throws Exception {
        Path f = dir.resolve("addon.db");
        DiagnosticsDatabase db = new DiagnosticsDatabase(f, "test-diag-db", "7", List.of("CREATE TABLE IF NOT EXISTS Foo (a INTEGER)"));
        db.execute("INSERT INTO Foo (a) VALUES (?)", 1);
        String writer = await(db.flush(), WAIT_MS); // same single thread that ran the execute
        db.close();
        Set<String> t = tables(f);
        Map<String, String> m = meta(f);
        long fooRows;
        try (Connection c = DiagnosticsDatabase.connect(f); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM Foo")) {
            fooRows = rs.next() ? rs.getLong(1) : -1;
        }
        if (!"test-diag-db".equals(writer)) return fail("writer thread " + writer);
        if (!t.equals(Set.of("Meta", "Foo"))) return fail("tables " + t);
        if (!m.keySet().equals(Set.of("schema_version", "created_ms")) || !"7".equals(m.get("schema_version")))
            return fail("Meta " + m);
        if (fooRows != 1) return fail("Foo rows " + fooRows);
        return pass("tables " + t + ", schema_version 7, writer " + writer);
    }

    // ------------------------------------------------------------------ helpers

    private TestResult run(Callable<TestResult> body) {
        if (dir == null) return fail("temp dir missing");
        try {
            return body.call();
        } catch (Exception e) {
            return fail(e.toString());
        }
    }

    private static List<String[]> rows(Path f) throws SQLException {
        List<String[]> out = new ArrayList<>();
        try (Connection c = DiagnosticsDatabase.connect(f); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT key, value FROM Meta")) {
            while (rs.next()) out.add(new String[]{rs.getString(1), rs.getString(2)});
        }
        return out;
    }

    /** Table names, without SQLite's own sqlite_sequence. */
    private static Set<String> tables(Path f) throws SQLException {
        Set<String> out = new HashSet<>();
        try (Connection c = DiagnosticsDatabase.connect(f); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name <> 'sqlite_sequence'")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static Map<String, String> meta(Path f) throws SQLException {
        Map<String, String> m = new HashMap<>();
        for (String[] r : rows(f)) m.put(r[0], r[1]);
        return m;
    }
}
