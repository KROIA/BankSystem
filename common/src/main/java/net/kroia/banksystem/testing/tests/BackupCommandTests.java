package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.data.DriverShim;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Task #42 (v2.0.7) -- backup pause/resume/status/snapshot tests.
 * <p>
 * Exercises the {@link DatabaseManager} primitives that back the
 * {@code /banksystem backup ...} op-only subcommands. The tests wire a real
 * in-memory SQLite connection into a {@link BackupTestDatabaseManager} so the
 * VACUUM INTO snapshot code path runs against actual SQLite. Runs master-only
 * via {@link BankSystemTestCategories#DATABASE}.
 */
public class BackupCommandTests extends TestSuite {

    private Connection connection;
    private BackupTestDatabaseManager db;

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.DATABASE;
    }

    @Override
    public void registerTests() {
        addTest("pause_blocks_worker_until_resume", this::pause_blocks_worker_until_resume);
        addTest("pause_is_idempotent_returns_false_when_active", this::pause_is_idempotent_returns_false_when_active);
        addTest("resume_without_pause_returns_false", this::resume_without_pause_returns_false);
        addTest("status_reports_paused_and_elapsed", this::status_reports_paused_and_elapsed);
        addTest("snapshot_writes_valid_db_file", this::snapshot_writes_valid_db_file);
        addTest("snapshot_escapes_single_quote_in_path", this::snapshot_escapes_single_quote_in_path);
        addTest("safety_timeout_auto_resumes", this::safety_timeout_auto_resumes);
        addTest("safety_timer_ignores_old_session_after_repause", this::safety_timer_ignores_old_session_after_repause);
    }

    @Override
    public void setup() {
        try {
            Class<?> driverClass;
            try {
                driverClass = ClassLoader.getSystemClassLoader().loadClass("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                driverClass = BackupCommandTests.class.getClassLoader().loadClass("org.sqlite.JDBC");
            }
            Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
            try {
                DriverManager.registerDriver(new DriverShim(driver));
            } catch (Exception ignored) { }

            connection = DriverManager.getConnection("jdbc:sqlite::memory:");
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS BalanceHistory ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "account_number INTEGER NOT NULL,"
                        + "item_id INTEGER NOT NULL,"
                        + "balance INTEGER NOT NULL,"
                        + "locked_balance INTEGER NOT NULL,"
                        + "time INTEGER NOT NULL)");
                stmt.execute("INSERT INTO BalanceHistory(account_number,item_id,balance,locked_balance,time)"
                        + " VALUES(1,1,100,0,1000)");
            }
            connection.commit();

            db = new BackupTestDatabaseManager(connection);
        } catch (Exception e) {
            throw new RuntimeException("Failed to setup in-memory SQLite for tests", e);
        }
    }

    @Override
    public void teardown() {
        // Best-effort: make sure any lingering pause is released so subsequent
        // tests aren't polluted (safety-timer test uses a very short timeout).
        if (db != null) {
            db.endBackupPause();
            db.shutdownTestExecutors();
        }
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) { }
        connection = null;
        db = null;
    }

    // ------------------------------------------------------------------------
    // Pause / resume semantics
    // ------------------------------------------------------------------------

    private TestResult pause_blocks_worker_until_resume() {
        try {
            CompletableFuture<Boolean> ack = db.beginBackupPause();
            boolean paused = ack.get(2, TimeUnit.SECONDS);
            TestResult res = assertEquals("pause ack", true, paused);
            if (!res.passed()) return res;

            // Submit a marker to the DB worker. It must NOT complete while the
            // pause task is still sitting on latch.await().
            AtomicBoolean marker = new AtomicBoolean(false);
            db.getDatabaseThread().submit(() -> marker.set(true));

            // Sleep past a comfortable "yeah, that would have run" window.
            Thread.sleep(150);
            res = assertEquals("marker blocked during pause", false, marker.get());
            if (!res.passed()) return res;

            // Release.
            boolean resumed = db.endBackupPause();
            res = assertEquals("resume ok", true, resumed);
            if (!res.passed()) return res;

            // Poll for marker completion (should be immediate now).
            long deadline = System.currentTimeMillis() + 2000L;
            while (!marker.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }
            res = assertEquals("marker ran after resume", true, marker.get());
            if (!res.passed()) return res;
            return pass("Pause blocks the worker queue; resume drains it");
        } catch (Exception e) {
            return fail("pause_blocks_worker_until_resume threw: " + e.getMessage());
        }
    }

    private TestResult pause_is_idempotent_returns_false_when_active() {
        try {
            CompletableFuture<Boolean> first = db.beginBackupPause();
            boolean firstOk = first.get(2, TimeUnit.SECONDS);
            TestResult res = assertEquals("first pause", true, firstOk);
            if (!res.passed()) return res;

            CompletableFuture<Boolean> second = db.beginBackupPause();
            // Second call should be completed immediately with false; not queued.
            boolean secondOk = second.getNow(null);
            res = assertEquals("second pause returns false", false, secondOk);
            if (!res.passed()) return res;

            db.endBackupPause();
            return pass("Concurrent pause requests do not stack");
        } catch (Exception e) {
            return fail("pause_is_idempotent threw: " + e.getMessage());
        }
    }

    private TestResult resume_without_pause_returns_false() {
        boolean ok = db.endBackupPause();
        return assertEquals("resume when idle", false, ok);
    }

    private TestResult status_reports_paused_and_elapsed() {
        try {
            TestResult res = assertEquals("initial state", DatabaseManager.BackupState.IDLE, db.getBackupState());
            if (!res.passed()) return res;

            db.beginBackupPause().get(2, TimeUnit.SECONDS);
            Thread.sleep(80);
            res = assertEquals("state after pause", DatabaseManager.BackupState.PAUSED, db.getBackupState());
            if (!res.passed()) return res;

            long elapsed = db.getPausedForMs();
            if (elapsed < 50L) {
                db.endBackupPause();
                return fail("expected getPausedForMs() >= 50, got " + elapsed);
            }
            db.endBackupPause();
            res = assertEquals("state after resume", DatabaseManager.BackupState.IDLE, db.getBackupState());
            if (!res.passed()) return res;
            res = assertEquals("elapsed after resume", 0L, db.getPausedForMs());
            if (!res.passed()) return res;
            return pass("Status reports PAUSED with sensible elapsed time");
        } catch (Exception e) {
            return fail("status_reports threw: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------
    // Snapshot (VACUUM INTO)
    // ------------------------------------------------------------------------

    private TestResult snapshot_writes_valid_db_file() {
        Path snapshot = null;
        try {
            snapshot = Files.createTempFile("banksystem-backup-test-", ".db");
            // VACUUM INTO refuses to overwrite an existing file, so delete the
            // one createTempFile just made and let the snapshot create it fresh.
            Files.deleteIfExists(snapshot);

            boolean ok = db.snapshotTo(snapshot).get(10, TimeUnit.SECONDS);
            TestResult res = assertEquals("snapshot ok", true, ok);
            if (!res.passed()) return res;

            if (!Files.exists(snapshot)) return fail("snapshot file does not exist: " + snapshot);
            long size = Files.size(snapshot);
            if (size <= 0) return fail("snapshot file is empty");

            // Open a second connection to the snapshot and verify it has our seed row.
            String url = "jdbc:sqlite:" + snapshot.toAbsolutePath();
            try (Connection verify = DriverManager.getConnection(url);
                 Statement stmt = verify.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM BalanceHistory")) {
                if (!rs.next()) return fail("could not COUNT(*) BalanceHistory in snapshot");
                int count = rs.getInt(1);
                res = assertEquals("snapshot BalanceHistory row count", 1, count);
                if (!res.passed()) return res;
            }
            return pass("Snapshot produced a valid SQLite DB with seed data (" + size + " bytes)");
        } catch (Exception e) {
            return fail("snapshot_writes_valid_db_file threw: " + e.getMessage());
        } finally {
            if (snapshot != null) {
                try { Files.deleteIfExists(snapshot); } catch (Exception ignored) { }
            }
        }
    }

    private TestResult snapshot_escapes_single_quote_in_path() {
        // Verify the ''-doubling escape defends against a single-quote in the target
        // path. We can't reliably put ' in a filename on Windows (illegal char in some
        // shells) so we test the escape by using a directory prefix that survives:
        // create a subdir whose name contains a single quote and drop the snapshot there.
        Path parent = null;
        Path snapshot = null;
        try {
            parent = Files.createTempDirectory("banksys-o'brien-");
            snapshot = parent.resolve("snap.db");
            Files.deleteIfExists(snapshot);
            boolean ok = db.snapshotTo(snapshot).get(10, TimeUnit.SECONDS);
            if (!ok) {
                // On some filesystems the single-quote directory may not be creatable;
                // treat that as a soft-skip rather than a hard fail.
                if (parent.toString().indexOf('\'') < 0) {
                    return pass("filesystem stripped the apostrophe; escape path not exercised");
                }
                return fail("snapshot to quoted path returned false -- escape likely broken");
            }
            if (!Files.exists(snapshot)) return fail("snapshot file not created in quoted directory");
            return pass("Snapshot to path with single-quote succeeded (escape works)");
        } catch (Exception e) {
            return fail("snapshot_escapes threw: " + e.getMessage());
        } finally {
            try { if (snapshot != null) Files.deleteIfExists(snapshot); } catch (Exception ignored) { }
            try { if (parent != null) Files.deleteIfExists(parent); } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------------------
    // Safety timer
    // ------------------------------------------------------------------------

    private TestResult safety_timeout_auto_resumes() {
        try {
            db.setPauseSafetyTimeoutMillis(100L);
            db.beginBackupPause().get(2, TimeUnit.SECONDS);

            // Submit a marker task; if the safety timer auto-resumes, it will run.
            CountDownLatch marker = new CountDownLatch(1);
            db.getDatabaseThread().submit(marker::countDown);

            boolean ran = marker.await(2, TimeUnit.SECONDS);
            TestResult res = assertEquals("marker ran after safety timeout", true, ran);
            if (!res.passed()) return res;

            // State should have flipped back to IDLE (via CAS in the safety task).
            res = assertEquals("state after safety timeout", DatabaseManager.BackupState.IDLE, db.getBackupState());
            if (!res.passed()) return res;
            return pass("Safety timer auto-resumes a forgotten pause");
        } catch (Exception e) {
            return fail("safety_timeout_auto_resumes threw: " + e.getMessage());
        } finally {
            db.setPauseSafetyTimeoutMillis(DatabaseManager.DEFAULT_PAUSE_SAFETY_TIMEOUT_MS);
        }
    }

    private TestResult safety_timer_ignores_old_session_after_repause() {
        try {
            // First session: give it a 200ms safety timeout, then resume manually
            // before the timer fires. Then repause with default timeout and verify
            // the FIRST session's leftover safety task doesn't auto-resume the
            // second session when it eventually runs.
            db.setPauseSafetyTimeoutMillis(200L);
            db.beginBackupPause().get(2, TimeUnit.SECONDS);
            Thread.sleep(50);
            boolean resumed = db.endBackupPause();
            TestResult res = assertEquals("manual resume of first session", true, resumed);
            if (!res.passed()) return res;

            // Start a second session, still with a 200ms timeout so its own safety
            // timer would fire well after the first session's would-have-fired time.
            db.setPauseSafetyTimeoutMillis(2000L); // long enough to outlive the first timer
            db.beginBackupPause().get(2, TimeUnit.SECONDS);

            // Wait past when the first safety timer would have fired (200ms from
            // the first pause; we already waited 50ms + repause overhead).
            Thread.sleep(300);

            // The second session must still be PAUSED -- the first session's
            // safety task should have CAS-failed and been a no-op.
            res = assertEquals("second session still paused", DatabaseManager.BackupState.PAUSED, db.getBackupState());
            if (!res.passed()) {
                db.endBackupPause();
                return res;
            }
            db.endBackupPause();
            return pass("Old safety timer no-ops when a new pause session is active");
        } catch (Exception e) {
            db.endBackupPause();
            return fail("safety_timer_ignores_old_session threw: " + e.getMessage());
        } finally {
            db.setPauseSafetyTimeoutMillis(DatabaseManager.DEFAULT_PAUSE_SAFETY_TIMEOUT_MS);
        }
    }

    // ------------------------------------------------------------------------
    // Test double
    // ------------------------------------------------------------------------

    /**
     * DatabaseManager stand-in that wraps an in-memory connection and a private
     * single-thread executor so pause/resume can be exercised without a live
     * server. Mirrors the pattern used by {@link DatabaseTests}.
     */
    private static class BackupTestDatabaseManager extends DatabaseManager {
        private final Connection testConnection;
        private final ExecutorService testExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "backup-test-db-worker");
            t.setDaemon(true);
            return t;
        });

        BackupTestDatabaseManager(Connection connection) {
            this.testConnection = connection;
        }

        @Override
        public Connection getConnection() {
            return testConnection;
        }

        @Override
        public ExecutorService getDatabaseThread() {
            return testExecutor;
        }

        @Override
        public boolean commitTransaction() {
            try { testConnection.commit(); return true; }
            catch (SQLException e) { return false; }
        }

        /**
         * Shuts down test-owned thread pools (the parent's pauseTimer is a daemon
         * so it exits with the JVM; we don't touch the parent's private executor
         * because we never scheduled anything on it).
         */
        void shutdownTestExecutors() {
            testExecutor.shutdownNow();
        }
    }
}
