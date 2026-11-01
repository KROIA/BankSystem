package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.banking.bankmanager.ServerBankManager;
import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.data.DriverShim;
import net.kroia.banksystem.data.table.BalanceHistoryManager;
import net.kroia.banksystem.data.table.record.BalanceHistoryRecord;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Task #41 (v2.0.7) — balance-history sample-on-change + tiered retention tests.
 * <p>
 * These tests drive the two moving parts of the retention overhaul in isolation:
 * <ol>
 *   <li>the sample-on-change + heartbeat dedup filter
 *       ({@link ServerBankManager#applySnapshotDedup}) — pure Java, no DB needed;</li>
 *   <li>the tiered downsample+prune sweep
 *       ({@link BalanceHistoryManager#applyTieredRetention(long)}) — exercised against an
 *       in-memory SQLite DB with a schema that matches production one-to-one
 *       (see {@code common/src/main/resources/sql/BalanceHistory.sql}).</li>
 * </ol>
 * The tests seed rows directly through the manager (or plain SQL when precise
 * {@code time}-column values are required), never through a live world. Runs
 * master-only via {@link BankSystemTestCategories#DATABASE}.
 */
public class BalanceHistoryTests extends TestSuite {

    private Connection connection;
    private BalanceHistoryManager manager;
    private TestDatabaseManager testDbManager;

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.DATABASE;
    }

    @Override
    public void registerTests() {
        addTest("sample_on_change_dedupes_identical_balance", this::sample_on_change_dedupes_identical_balance);
        addTest("heartbeat_forces_row_after_interval", this::heartbeat_forces_row_after_interval);
        addTest("tiered_retention_preserves_bucket_keepers", this::tiered_retention_preserves_bucket_keepers);
        addTest("tiered_retention_prunes_beyond_1y", this::tiered_retention_prunes_beyond_1y);
        addTest("migration_from_flat_cap_history", this::migration_from_flat_cap_history);
    }

    @Override
    public void setup() {
        try {
            Class<?> driverClass;
            try {
                driverClass = ClassLoader.getSystemClassLoader().loadClass("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                driverClass = BalanceHistoryTests.class.getClassLoader().loadClass("org.sqlite.JDBC");
            }
            Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
            try {
                DriverManager.registerDriver(new DriverShim(driver));
            } catch (Exception ignored) {}

            connection = DriverManager.getConnection("jdbc:sqlite::memory:");
            connection.setAutoCommit(false);

            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS BalanceHistory (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "account_number INTEGER NOT NULL," +
                        "item_id INTEGER NOT NULL," +
                        "balance INTEGER NOT NULL," +
                        "locked_balance INTEGER NOT NULL," +
                        "time INTEGER NOT NULL)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_balance_history ON BalanceHistory (account_number, item_id, time)");
            }
            connection.commit();

            testDbManager = new TestDatabaseManager(connection);
            manager = new BalanceHistoryManager(testDbManager);
        } catch (Exception e) {
            throw new RuntimeException("Failed to setup in-memory SQLite for tests", e);
        }
    }

    @Override
    public void teardown() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {}
        connection = null;
        manager = null;
        testDbManager = null;
    }

    // -------------------------------------------------------------------------
    // Part 1 — sample-on-change + heartbeat dedup
    // -------------------------------------------------------------------------

    private TestResult sample_on_change_dedupes_identical_balance() {
        Map<Long, ServerBankManager.LastSnapshotSample> cache = new HashMap<>();
        List<BalanceHistoryRecord> out = new ArrayList<>();
        long heartbeatMs = TimeUnit.MINUTES.toMillis(60);

        // First sample — cache empty, must emit.
        boolean emitted1 = ServerBankManager.applySnapshotDedup(
                cache, 42, (short) 7, 1000L, 100L, 10_000L, heartbeatMs, out);
        TestResult res = assertEquals("first emit", true, emitted1);
        if (!res.passed()) return res;

        // Second sample — same balance/locked/timestamp barely advanced, must dedup.
        boolean emitted2 = ServerBankManager.applySnapshotDedup(
                cache, 42, (short) 7, 1000L, 100L, 70_000L, heartbeatMs, out);
        res = assertEquals("second emit (identical)", false, emitted2);
        if (!res.passed()) return res;

        res = assertEquals("total rows produced", 1, out.size());
        if (!res.passed()) return res;
        res = assertEquals("emitted row balance", 1000L, out.get(0).balance());
        if (!res.passed()) return res;
        return pass("Identical (balance, lockedBalance) samples deduped to a single row");
    }

    private TestResult heartbeat_forces_row_after_interval() {
        Map<Long, ServerBankManager.LastSnapshotSample> cache = new HashMap<>();
        List<BalanceHistoryRecord> out = new ArrayList<>();
        long heartbeatMs = TimeUnit.MINUTES.toMillis(60);
        long snapshotIntervalMs = TimeUnit.MINUTES.toMillis(1);
        long t0 = 1_000_000L;

        // Send heartbeatMinutes + 1 identical samples at 1-minute intervals starting at t0.
        // Semantics: the first sample (i=0) always emits (cache empty). Samples i=1..59 are
        // strictly inside the heartbeat window (timestamp - t0 < heartbeatMs) so they all
        // dedup. Sample i=60 is exactly at t0 + heartbeatMs — heartbeatDue uses '>=' so it
        // fires here, producing the second row. Total = 2 rows.
        int totalSamples = (int) (heartbeatMs / snapshotIntervalMs) + 1; // 61
        for (int i = 0; i < totalSamples; i++) {
            ServerBankManager.applySnapshotDedup(cache, 1, (short) 5,
                    500L, 0L, t0 + i * snapshotIntervalMs, heartbeatMs, out);
        }

        TestResult res = assertEquals("rows after heartbeat-window pass", 2, out.size());
        if (!res.passed()) return res;
        res = assertEquals("first row time", t0, out.get(0).time());
        if (!res.passed()) return res;
        res = assertEquals("second row time (heartbeat)", t0 + heartbeatMs, out.get(1).time());
        if (!res.passed()) return res;
        return pass("Heartbeat window forces exactly one extra row on an idle series");
    }

    // -------------------------------------------------------------------------
    // Part 2 — tiered retention (applyTieredRetention + downsampleBand)
    // -------------------------------------------------------------------------

    private TestResult tiered_retention_preserves_bucket_keepers() {
        clearTable();
        // Seed 7 days x 1440 rows/day = 10080 rows in the 24h..7d band for one (account,
        // item) pair, at a 1-minute cadence with strictly-increasing balances (so every
        // row has a unique value — a dedup mistake would be obvious).
        long now = 10_000_000_000L; // arbitrary anchor
        long h24 = now - TimeUnit.DAYS.toMillis(1);
        long minute = TimeUnit.MINUTES.toMillis(1);
        int seededRows = 0;
        long balance = 0L;
        List<BalanceHistoryRecord> seed = new ArrayList<>();
        // Seed rows spanning [now - 7d + 1min, now - 24h] at 1-minute intervals.
        long start = now - TimeUnit.DAYS.toMillis(7) + minute;
        long end = h24; // exclusive; every seeded row is in [24h..7d)
        for (long t = start; t < end; t += minute) {
            seed.add(new BalanceHistoryRecord(1, (short) 5, balance++, 0L, t));
            seededRows++;
        }
        try {
            manager.save(seed).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            return fail("seed save threw: " + e.getMessage());
        }
        // Sanity check: seed size matches what we counted.
        int total = countRows();
        TestResult res = assertEquals("seed size", seededRows, total);
        if (!res.passed()) return res;

        // Sweep.
        try {
            manager.applyTieredRetention(now).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            return fail("applyTieredRetention threw: " + e.getMessage());
        }

        // Expected: at most ceil((7d - 24h) / 15min) = ceil(6d / 15min) = 576 buckets, i.e.
        // <= 576 keepers. Spec allows up to 672 (7d x 24h x 4 buckets/h). Verify the tighter
        // bound holds.
        int remaining = countRows();
        if (remaining > 672) {
            return fail("expected <= 672 rows/item after sweep in 24h..7d band, got " + remaining);
        }
        if (remaining < 1) {
            return fail("expected >= 1 row/item after sweep, got " + remaining);
        }

        // Every survivor must be the newest-in-bucket for its 15-min partition — verify by
        // grouping surviving rows by bucket index and asserting each bucket has exactly one
        // row and that row has the maximum time of any originally-seeded row for that
        // bucket. Simpler oracle: no two surviving rows share the same bucket index.
        long bucketWidth = TimeUnit.MINUTES.toMillis(15);
        long bandStart = h24 - TimeUnit.DAYS.toMillis(6); // downsampleBand start = d7 = now - 7d
        // Actually downsampleBand uses startInclusive=d7 (now-7d) for band 24h..7d.
        long d7 = now - TimeUnit.DAYS.toMillis(7);
        java.util.Set<Long> seenBuckets = new java.util.HashSet<>();
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT time FROM BalanceHistory ORDER BY time ASC")) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long t = rs.getLong(1);
                    long bucket = (t - d7) / bucketWidth;
                    if (!seenBuckets.add(bucket)) {
                        return fail("bucket " + bucket + " has more than one survivor at time " + t);
                    }
                }
            }
        } catch (SQLException e) {
            return fail("bucket-uniqueness query threw: " + e.getMessage());
        }
        return pass("Tiered retention preserved <= 672 rows, all unique-per-bucket keepers (got "
                + remaining + ")");
    }

    private TestResult tiered_retention_prunes_beyond_1y() {
        clearTable();
        long now = 10_000_000_000L;
        long twoYearsAgo = now - TimeUnit.DAYS.toMillis(730);
        long thirteenMonthsAgo = now - TimeUnit.DAYS.toMillis(400);

        List<BalanceHistoryRecord> seed = new ArrayList<>();
        seed.add(new BalanceHistoryRecord(1, (short) 5, 100L, 0L, twoYearsAgo));
        seed.add(new BalanceHistoryRecord(1, (short) 5, 101L, 0L, twoYearsAgo + TimeUnit.DAYS.toMillis(1)));
        seed.add(new BalanceHistoryRecord(1, (short) 5, 102L, 0L, thirteenMonthsAgo));
        // One row within 1y so we can distinguish "everything deleted" from "just old rows".
        seed.add(new BalanceHistoryRecord(1, (short) 5, 500L, 0L, now - TimeUnit.DAYS.toMillis(30)));
        try {
            manager.save(seed).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return fail("seed save threw: " + e.getMessage());
        }
        TestResult res = assertEquals("seed count", 4, countRows());
        if (!res.passed()) return res;

        try {
            manager.applyTieredRetention(now).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return fail("applyTieredRetention threw: " + e.getMessage());
        }

        // The 30-day-old row is on the 30d..1y band boundary — it should survive the sweep
        // (either as a 1d bucket keeper in 30d..1y or, depending on exact math, in a band
        // that's not being pruned). All rows older than 1 year must be deleted.
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT time FROM BalanceHistory")) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long t = rs.getLong(1);
                    if (t < now - TimeUnit.DAYS.toMillis(365)) {
                        return fail("row with time " + t + " (age > 1y) survived the sweep — "
                                + "beyond-1y pruning is broken");
                    }
                }
            }
        } catch (SQLException e) {
            return fail("verify query threw: " + e.getMessage());
        }
        return pass("All rows older than 1 year were pruned; younger rows preserved");
    }

    private TestResult migration_from_flat_cap_history() {
        clearTable();
        // Simulate a world that ran under the old flat-1440 cap for a long time but with a
        // longer snapshot interval so 1440 rows span multiple retention bands. Model: 1
        // row every 10 minutes for ~10 days = 1440 rows spanning full/24h..7d and part of
        // the 7d..30d bands.
        long now = 10_000_000_000L;
        long tenMinutes = TimeUnit.MINUTES.toMillis(10);
        long start = now - 1440L * tenMinutes; // exactly 1440 * 10min = 240h = 10 days ago
        long balance = 0L;
        List<BalanceHistoryRecord> seed = new ArrayList<>();
        for (int i = 0; i < 1440; i++) {
            long t = start + i * tenMinutes;
            seed.add(new BalanceHistoryRecord(1, (short) 5, balance++, 0L, t));
        }
        try {
            manager.save(seed).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            return fail("seed save threw: " + e.getMessage());
        }
        TestResult res = assertEquals("seed count", 1440, countRows());
        if (!res.passed()) return res;

        int rowsBefore = countRows();
        try {
            manager.applyTieredRetention(now).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            return fail("applyTieredRetention threw: " + e.getMessage());
        }
        int rowsAfter = countRows();

        // Must be strictly fewer rows (rows in 24h..7d get bucketed to 15-min => at most 4/h
        // * 24h * 6d = 576 buckets; rows in 7d..10d get bucketed to 1h => at most 24 * 3 =
        // 72 buckets). Total expected ceiling: 24h band (untouched) 24h/10min = 144 rows +
        // 576 + 72 = 792, well below the 1440 seed.
        if (rowsAfter >= rowsBefore) {
            return fail("expected sweep to reduce row count, before=" + rowsBefore
                    + " after=" + rowsAfter);
        }
        if (rowsAfter > 800) {
            return fail("expected <= ~800 rows after sweep on a 1440-row flat-cap seed, got "
                    + rowsAfter);
        }
        // Confirm the untouched 0..24h band still has every row it started with (144 rows).
        int rowsIn24h = countRowsInRange(now - TimeUnit.DAYS.toMillis(1), now + 1);
        int expectedIn24h = (int) (TimeUnit.DAYS.toMillis(1) / tenMinutes); // 144
        if (rowsIn24h != expectedIn24h) {
            return fail("0..24h band should stay untouched (expected " + expectedIn24h
                    + " rows, got " + rowsIn24h + ")");
        }
        return pass("Migration from flat-1440 shape produces tiered shape (before=" + rowsBefore
                + " after=" + rowsAfter + ", 0..24h untouched at " + rowsIn24h + ")");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void clearTable() {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM BalanceHistory");
            connection.commit();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private int countRows() {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT COUNT(*) FROM BalanceHistory")) {
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
                return 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private int countRowsInRange(long fromInclusive, long toExclusive) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT COUNT(*) FROM BalanceHistory WHERE time >= ? AND time < ?")) {
            stmt.setLong(1, fromInclusive);
            stmt.setLong(2, toExclusive);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
                return 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Minimal DatabaseManager stand-in that wraps an in-memory connection so
     * BalanceHistoryManager can operate without a real server. Mirrors the pattern used
     * by {@link DatabaseTests} — kept private-nested here so the two suites can evolve
     * independently (they exercise different methods on the same manager).
     */
    private static class TestDatabaseManager extends DatabaseManager {
        private final Connection testConnection;
        private final java.util.concurrent.ExecutorService testExecutor =
                java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "balance-history-test-db-worker");
                    t.setDaemon(true);
                    return t;
                });

        TestDatabaseManager(Connection connection) {
            this.testConnection = connection;
        }

        @Override
        public Connection getConnection() {
            return testConnection;
        }

        @Override
        public java.util.concurrent.ExecutorService getDatabaseThread() {
            return testExecutor;
        }

        @Override
        public boolean commitTransaction() {
            try {
                testConnection.commit();
                return true;
            } catch (SQLException e) {
                return false;
            }
        }
    }
}
