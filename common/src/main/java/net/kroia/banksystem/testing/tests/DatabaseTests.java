package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.data.DriverShim;
import net.kroia.banksystem.data.filter.DateFilter;
import net.kroia.banksystem.data.filter.EqualityFilter;
import net.kroia.banksystem.data.table.BalanceHistoryManager;
import net.kroia.banksystem.data.table.record.BalanceHistoryRecord;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class DatabaseTests extends TestSuite {

    private Connection connection;
    private BalanceHistoryManager manager;
    private TestDatabaseManager testDbManager;

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.DATABASE;
    }

    @Override
    public void registerTests() {
        addTest("record_field_access", this::record_field_access);
        addTest("dateFilter_clause", this::dateFilter_clause);
        addTest("equalityFilter_clause", this::equalityFilter_clause);
        addTest("save_single_record", this::save_single_record);
        addTest("save_batch_records", this::save_batch_records);
        addTest("query_with_account_filter", this::query_with_account_filter);
        addTest("query_with_item_filter", this::query_with_item_filter);
        addTest("query_with_date_filter", this::query_with_date_filter);
        addTest("query_combined_filters", this::query_combined_filters);
        addTest("query_ordered_by_time", this::query_ordered_by_time);
        addTest("query_with_limit", this::query_with_limit);
        addTest("remove_with_filter", this::remove_with_filter);
        addTest("record_count", this::record_count);
        addTest("timestamp_preserves_millis", this::timestamp_preserves_millis);
    }

    @Override
    public void setup() {
        try {
            Class<?> driverClass;
            try {
                driverClass = ClassLoader.getSystemClassLoader().loadClass("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                driverClass = DatabaseTests.class.getClassLoader().loadClass("org.sqlite.JDBC");
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

    private TestResult record_field_access() {
        BalanceHistoryRecord r = new BalanceHistoryRecord(42, (short) 7, 1000L, 250L, 1234567890L);
        TestResult res;
        res = assertEquals("accountNumber", 42, r.accountNumber());
        if (!res.passed()) return res;
        res = assertEquals("itemId", (short) 7, r.itemId());
        if (!res.passed()) return res;
        res = assertEquals("balance", 1000L, r.balance());
        if (!res.passed()) return res;
        res = assertEquals("lockedBalance", 250L, r.lockedBalance());
        if (!res.passed()) return res;
        res = assertEquals("time", 1234567890L, r.time());
        if (!res.passed()) return res;
        return pass("BalanceHistoryRecord fields correct");
    }

    private TestResult dateFilter_clause() {
        DateFilter filter = new DateFilter(100L, 200L);
        String clause = filter.getClause("time");
        TestResult res = assertEquals("clause", "time >= ? AND time <= ?", clause);
        if (!res.passed()) return res;
        return pass("DateFilter clause correct");
    }

    private TestResult equalityFilter_clause() {
        EqualityFilter filter = new EqualityFilter(42);
        String clause = filter.getClause("account_number");
        TestResult res = assertEquals("clause", "account_number = ?", clause);
        if (!res.passed()) return res;
        return pass("EqualityFilter clause correct");
    }

    private TestResult save_single_record() {
        clearTable();
        BalanceHistoryRecord record = new BalanceHistoryRecord(1, (short) 1, 500L, 100L, 1000L);
        try {
            manager.save(record).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return fail("save threw: " + e.getMessage());
        }
        List<BalanceHistoryRecord> results = queryAll();
        TestResult res = assertEquals("count", 1, results.size());
        if (!res.passed()) return res;
        res = assertEquals("balance", 500L, results.get(0).balance());
        if (!res.passed()) return res;
        return pass("Single record saved and retrieved");
    }

    private TestResult save_batch_records() {
        clearTable();
        List<BalanceHistoryRecord> batch = List.of(
                new BalanceHistoryRecord(1, (short) 1, 100L, 0L, 1000L),
                new BalanceHistoryRecord(1, (short) 2, 200L, 50L, 1000L),
                new BalanceHistoryRecord(2, (short) 1, 300L, 75L, 1000L)
        );
        try {
            manager.save(batch).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return fail("batch save threw: " + e.getMessage());
        }
        List<BalanceHistoryRecord> results = queryAll();
        return assertEquals("count", 3, results.size());
    }

    private TestResult query_with_account_filter() {
        clearTable();
        insertTestData();
        try {
            List<BalanceHistoryRecord> results = manager.getHistory(
                    Optional.empty(),
                    Optional.of(new EqualityFilter(1)),
                    Optional.empty(),
                    0
            ).get(5, TimeUnit.SECONDS);
            TestResult res = assertEquals("count for account 1", 2, results.size());
            if (!res.passed()) return res;
            for (BalanceHistoryRecord r : results) {
                res = assertEquals("accountNumber", 1, r.accountNumber());
                if (!res.passed()) return res;
            }
            return pass("Account filter works");
        } catch (Exception e) {
            return fail("query threw: " + e.getMessage());
        }
    }

    private TestResult query_with_item_filter() {
        clearTable();
        insertTestData();
        try {
            List<BalanceHistoryRecord> results = manager.getHistory(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(new EqualityFilter((short) 2)),
                    0
            ).get(5, TimeUnit.SECONDS);
            TestResult res = assertEquals("count for item 2", 1, results.size());
            if (!res.passed()) return res;
            res = assertEquals("itemId", (short) 2, results.get(0).itemId());
            if (!res.passed()) return res;
            return pass("Item filter works");
        } catch (Exception e) {
            return fail("query threw: " + e.getMessage());
        }
    }

    private TestResult query_with_date_filter() {
        clearTable();
        insertTestData();
        try {
            List<BalanceHistoryRecord> results = manager.getHistory(
                    Optional.of(new DateFilter(1500L, 2500L)),
                    Optional.empty(),
                    Optional.empty(),
                    0
            ).get(5, TimeUnit.SECONDS);
            TestResult res = assertEquals("count in range [1500,2500]", 1, results.size());
            if (!res.passed()) return res;
            res = assertEquals("time", 2000L, results.get(0).time());
            if (!res.passed()) return res;
            return pass("Date filter works");
        } catch (Exception e) {
            return fail("query threw: " + e.getMessage());
        }
    }

    private TestResult query_combined_filters() {
        clearTable();
        insertTestData();
        try {
            List<BalanceHistoryRecord> results = manager.getHistory(
                    Optional.of(new DateFilter(0L, 5000L)),
                    Optional.of(new EqualityFilter(1)),
                    Optional.of(new EqualityFilter((short) 1)),
                    0
            ).get(5, TimeUnit.SECONDS);
            TestResult res = assertEquals("count", 1, results.size());
            if (!res.passed()) return res;
            res = assertEquals("accountNumber", 1, results.get(0).accountNumber());
            if (!res.passed()) return res;
            res = assertEquals("itemId", (short) 1, results.get(0).itemId());
            if (!res.passed()) return res;
            return pass("Combined filters work");
        } catch (Exception e) {
            return fail("query threw: " + e.getMessage());
        }
    }

    private TestResult query_ordered_by_time() {
        clearTable();
        List<BalanceHistoryRecord> batch = List.of(
                new BalanceHistoryRecord(1, (short) 1, 100L, 0L, 3000L),
                new BalanceHistoryRecord(1, (short) 1, 200L, 0L, 1000L),
                new BalanceHistoryRecord(1, (short) 1, 300L, 0L, 2000L)
        );
        try {
            manager.save(batch).get(5, TimeUnit.SECONDS);
            List<BalanceHistoryRecord> results = manager.getHistory(
                    Optional.empty(), Optional.empty(), Optional.empty(), 0
            ).get(5, TimeUnit.SECONDS);
            TestResult res = assertEquals("first time", 1000L, results.get(0).time());
            if (!res.passed()) return res;
            res = assertEquals("second time", 2000L, results.get(1).time());
            if (!res.passed()) return res;
            res = assertEquals("third time", 3000L, results.get(2).time());
            if (!res.passed()) return res;
            return pass("Results ordered by time ASC");
        } catch (Exception e) {
            return fail("query threw: " + e.getMessage());
        }
    }

    private TestResult query_with_limit() {
        clearTable();
        insertTestData();
        try {
            List<BalanceHistoryRecord> results = manager.getHistory(
                    Optional.empty(), Optional.empty(), Optional.empty(), 2
            ).get(5, TimeUnit.SECONDS);
            return assertEquals("count limited to 2", 2, results.size());
        } catch (Exception e) {
            return fail("query threw: " + e.getMessage());
        }
    }

    private TestResult remove_with_filter() {
        clearTable();
        insertTestData();
        try {
            manager.removeHistory(
                    Optional.empty(),
                    Optional.of(new EqualityFilter(2)),
                    Optional.empty()
            ).get(5, TimeUnit.SECONDS);
            List<BalanceHistoryRecord> results = queryAll();
            TestResult res = assertEquals("remaining after delete", 2, results.size());
            if (!res.passed()) return res;
            for (BalanceHistoryRecord r : results) {
                res = assertEquals("accountNumber != 2", 1, r.accountNumber());
                if (!res.passed()) return res;
            }
            return pass("Remove with filter works");
        } catch (Exception e) {
            return fail("remove threw: " + e.getMessage());
        }
    }

    private TestResult record_count() {
        clearTable();
        insertTestData();
        try {
            int count = manager.getRecordCount(
                    Optional.empty(),
                    Optional.of(new EqualityFilter(1)),
                    Optional.empty()
            ).get(5, TimeUnit.SECONDS);
            return assertEquals("count for account 1", 2, count);
        } catch (Exception e) {
            return fail("count threw: " + e.getMessage());
        }
    }

    private TestResult timestamp_preserves_millis() {
        clearTable();
        long millis = 1715443200000L; // 2024-05-11T16:00:00.000Z
        BalanceHistoryRecord record = new BalanceHistoryRecord(1, (short) 1, 100L, 0L, millis);
        try {
            manager.save(record).get(5, TimeUnit.SECONDS);
            List<BalanceHistoryRecord> results = queryAll();
            TestResult res = assertEquals("count", 1, results.size());
            if (!res.passed()) return res;
            res = assertEquals("timestamp preserved", millis, results.get(0).time());
            if (!res.passed()) return res;
            return pass("Epoch millis timestamp round-trips correctly");
        } catch (Exception e) {
            return fail("save/query threw: " + e.getMessage());
        }
    }

    // --- helpers ---

    private void clearTable() {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM BalanceHistory");
            connection.commit();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void insertTestData() {
        List<BalanceHistoryRecord> batch = List.of(
                new BalanceHistoryRecord(1, (short) 1, 100L, 10L, 1000L),
                new BalanceHistoryRecord(1, (short) 2, 200L, 20L, 2000L),
                new BalanceHistoryRecord(2, (short) 1, 300L, 30L, 3000L)
        );
        try {
            manager.save(batch).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<BalanceHistoryRecord> queryAll() {
        try {
            return manager.getHistory(
                    Optional.empty(), Optional.empty(), Optional.empty(), 0
            ).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Minimal DatabaseManager stand-in that wraps an in-memory connection
     * so BalanceHistoryManager can operate without a real server.
     */
    private static class TestDatabaseManager extends DatabaseManager {
        private final Connection testConnection;
        private final java.util.concurrent.ExecutorService testExecutor =
                java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "test-db-worker");
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
