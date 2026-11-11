package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.data.DriverShim;
import net.kroia.banksystem.data.table.TransactionLogManager;
import net.kroia.banksystem.data.table.record.TransactionLogRecord;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Task #44 (v2.1.0) — round-trip + read-API coverage for the SQLite transaction ledger.
 * <p>
 * Same in-memory SQLite setup pattern as {@link BalanceHistoryTests}: schema mirrored
 * from {@code common/src/main/resources/sql/TransactionLog.sql}, backed by a private
 * {@link DatabaseManager} stand-in so the tests can exercise the manager without a live
 * server. Master-only category.
 */
public class TransactionLogManagerTests extends TestSuite {

    private Connection connection;
    private TransactionLogManager manager;
    private TestDatabaseManager testDbManager;

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.DATABASE;
    }

    @Override
    public boolean needsGame() {
        return false; // passes offline in gradlew :common:test
    }

    @Override
    public void registerTests() {
        addTest("insert_and_get_by_account", this::insert_and_get_by_account);
        addTest("get_by_company_filters_correctly", this::get_by_company_filters_correctly);
        addTest("get_by_account_and_kind_filters", this::get_by_account_and_kind_filters);
        addTest("null_actor_and_note_roundtrip", this::null_actor_and_note_roundtrip);
        addTest("newest_first_ordering", this::newest_first_ordering);
        addTest("close_drains_queued_writes", this::close_drains_queued_writes);
        addTest("audit_csv_export_escaping", this::audit_csv_export_escaping);
    }

    /** Issue #166: audit CSV — BOM, quoting, doubled quotes, formula defusing, negative amounts stay numbers. */
    private TestResult audit_csv_export_escaping() {
        String csv = net.kroia.banksystem.screen.custom.AuditExport.csv(new String[]{"a", "b"},
                List.of(new String[]{"x,y", "say \"hi\""}, new String[]{"=1+2", "-12.50"}, new String[]{"line\nbreak", null}));
        return assertEquals("csv", "﻿a,b\r\n\"x,y\",\"say \"\"hi\"\"\"\r\n'=1+2,-12.50\r\n\"line\nbreak\",\r\n", csv);
    }

    @Override
    public void setup() {
        try {
            Class<?> driverClass;
            try {
                driverClass = ClassLoader.getSystemClassLoader().loadClass("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                driverClass = TransactionLogManagerTests.class.getClassLoader().loadClass("org.sqlite.JDBC");
            }
            Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
            try {
                DriverManager.registerDriver(new DriverShim(driver));
            } catch (Exception ignored) {}

            connection = DriverManager.getConnection("jdbc:sqlite::memory:");
            createSchema(connection);

            testDbManager = new TestDatabaseManager(connection);
            manager = new TransactionLogManager(testDbManager);
        } catch (Exception e) {
            throw new RuntimeException("Failed to setup in-memory SQLite for tests", e);
        }
    }

    private static void createSchema(Connection connection) throws SQLException {
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS TransactionLog (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "account_number INTEGER NOT NULL," +
                        "actor_uuid TEXT," +
                        "kind TEXT NOT NULL," +
                        "item_id INTEGER NOT NULL," +
                        "amount INTEGER NOT NULL," +
                        "other_account INTEGER," +
                        "company_id INTEGER," +
                        "ts INTEGER NOT NULL," +
                        "note TEXT)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_tx_log_account_ts " +
                        "ON TransactionLog (account_number, ts DESC)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_tx_log_company_ts " +
                        "ON TransactionLog (company_id, ts DESC)");
            }
            // Task #109: bring the legacy schema up to date the same way a live world does.
            DatabaseManager.migrateTransactionLog(connection);
            connection.commit();
    }

    @Override
    public void teardown() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException ignored) {}
        connection = null;
        manager = null;
        testDbManager = null;
    }

    // ------------------------------------------------------------------

    private TestResult insert_and_get_by_account() {
        UUID actor = UUID.randomUUID();
        long t = 1_000L;
        try {
            manager.save(TransactionLogRecord.simple(42, actor,
                    LedgerType.DEPOSIT, (short) 7, 500L, t)).get(10, TimeUnit.SECONDS);
            manager.save(TransactionLogRecord.simple(42, actor,
                    LedgerType.WITHDRAW, (short) 7, 100L, t + 10)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return fail("save threw: " + e.getMessage());
        }
        List<TransactionLogRecord> rows;
        try {
            rows = manager.getByAccount(42, 0).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return fail("query threw: " + e.getMessage());
        }
        TestResult res = assertEquals("row count", 2, rows.size());
        if (!res.passed()) return res;

        TransactionLogRecord newest = rows.get(0);
        res = assertEquals("newest kind", LedgerType.WITHDRAW, newest.type());
        if (!res.passed()) return res;
        res = assertEquals("newest amount", 100L, newest.amount());
        if (!res.passed()) return res;
        res = assertEquals("newest actor", actor, newest.actorUuid());
        if (!res.passed()) return res;
        res = assertEquals("newest item id", (short) 7, newest.itemId());
        if (!res.passed()) return res;

        TransactionLogRecord older = rows.get(1);
        res = assertEquals("older kind", LedgerType.DEPOSIT, older.type());
        if (!res.passed()) return res;
        res = assertEquals("older amount", 500L, older.amount());
        if (!res.passed()) return res;
        return pass("Two rows round-tripped, newest-first ordering honored");
    }

    private TestResult get_by_company_filters_correctly() {
        long t = 5_000L;
        try {
            // Account row without company_id.
            manager.save(new TransactionLogRecord(TransactionLogRecord.UNSAVED_ID,
                    1, null, LedgerType.DEPOSIT, (short) 5, 10L,
                    null, null, t, null)).get(10, TimeUnit.SECONDS);
            // Company-scoped row.
            manager.save(new TransactionLogRecord(TransactionLogRecord.UNSAVED_ID,
                    2, null, LedgerType.PAYOUT, (short) 5, 200L,
                    null, 99, t + 5, "wage")).get(10, TimeUnit.SECONDS);
            manager.save(new TransactionLogRecord(TransactionLogRecord.UNSAVED_ID,
                    3, null, LedgerType.PAYOUT, (short) 5, 300L,
                    null, 99, t + 10, "wage")).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return fail("save threw: " + e.getMessage());
        }
        List<TransactionLogRecord> rows;
        try {
            rows = manager.getByCompany(99, 0).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return fail("query threw: " + e.getMessage());
        }
        TestResult res = assertEquals("company 99 rows", 2, rows.size());
        if (!res.passed()) return res;
        for (TransactionLogRecord r : rows) {
            if (r.companyId() == null || r.companyId() != 99) {
                return fail("row leaked from another company: " + r);
            }
        }
        res = assertEquals("newest amount", 300L, rows.get(0).amount());
        if (!res.passed()) return res;
        res = assertEquals("note preserved", "wage", rows.get(0).note());
        if (!res.passed()) return res;
        return pass("company filter isolates rows and preserves note/company_id");
    }

    private TestResult get_by_account_and_kind_filters() {
        long t = 9_000L;
        try {
            manager.save(TransactionLogRecord.simple(7, null,
                    LedgerType.DEPOSIT, (short) 1, 10L, t)).get(10, TimeUnit.SECONDS);
            manager.save(TransactionLogRecord.simple(7, null,
                    LedgerType.WITHDRAW, (short) 1, 5L, t + 1)).get(10, TimeUnit.SECONDS);
            manager.save(TransactionLogRecord.transfer(7, null,
                    (short) 1, -3L, 8, t + 2))
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return fail("save threw: " + e.getMessage());
        }
        try {
            List<TransactionLogRecord> deposits = manager.getByAccountAndType(7,
                    LedgerType.DEPOSIT.code(), 0, 0).get(10, TimeUnit.SECONDS);
            TestResult res = assertEquals("deposit rows", 1, deposits.size());
            if (!res.passed()) return res;

            List<TransactionLogRecord> transfers = manager.getByAccountAndType(7,
                    LedgerType.TRANSFER.code(), 0, 0).get(10, TimeUnit.SECONDS);
            res = assertEquals("transfer_out rows", 1, transfers.size());
            if (!res.passed()) return res;
            res = assertEquals("transfer other_account", Integer.valueOf(8),
                    transfers.get(0).otherAccount());
            if (!res.passed()) return res;
        } catch (Exception e) {
            return fail("query threw: " + e.getMessage());
        }
        int count;
        try {
            count = manager.getCount(7).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return fail("count threw: " + e.getMessage());
        }
        TestResult res = assertEquals("total rows for account 7", 3, count);
        if (!res.passed()) return res;
        return pass("kind-filtered read + count both correct");
    }

    private TestResult null_actor_and_note_roundtrip() {
        long t = 42_000L;
        try {
            manager.save(TransactionLogRecord.simple(11, null,
                    LedgerType.DEPOSIT, (short) 3, 77L, t)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return fail("save threw: " + e.getMessage());
        }
        List<TransactionLogRecord> rows;
        try {
            rows = manager.getByAccount(11, 0).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return fail("query threw: " + e.getMessage());
        }
        TestResult res = assertEquals("row count", 1, rows.size());
        if (!res.passed()) return res;
        TransactionLogRecord row = rows.get(0);
        if (row.actorUuid() != null) return fail("expected null actor, got " + row.actorUuid());
        if (row.note() != null) return fail("expected null note, got " + row.note());
        if (row.otherAccount() != null) return fail("expected null other_account, got " + row.otherAccount());
        if (row.companyId() != null) return fail("expected null company_id, got " + row.companyId());
        return pass("null actor / note / other_account / company_id round-tripped");
    }

    private TestResult newest_first_ordering() {
        long t = 100_000L;
        try {
            for (int i = 0; i < 5; i++) {
                manager.save(TransactionLogRecord.simple(20, null,
                        LedgerType.DEPOSIT, (short) 2, i,
                        t + i * 10L)).get(10, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            return fail("save threw: " + e.getMessage());
        }
        List<TransactionLogRecord> rows;
        try {
            rows = manager.getByAccount(20, 3).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return fail("query threw: " + e.getMessage());
        }
        TestResult res = assertEquals("limit honored", 3, rows.size());
        if (!res.passed()) return res;
        long prev = Long.MAX_VALUE;
        for (TransactionLogRecord r : rows) {
            if (r.time() > prev) return fail("rows not newest-first at time=" + r.time());
            prev = r.time();
        }
        res = assertEquals("newest amount (i=4)", 4L, rows.get(0).amount());
        if (!res.passed()) return res;
        return pass("limit + newest-first honored");
    }

    /**
     * Rows queued on the db-worker right before {@link DatabaseManager#close()} must all be
     * persisted: close() drains the worker before it closes the connection.
     */
    private TestResult close_drains_queued_writes() {
        java.nio.file.Path file = null;
        try {
            file = java.nio.file.Files.createTempFile("banksystem-close-drain-", ".db");
            String url = "jdbc:sqlite:" + file.toAbsolutePath();
            Connection c = DriverManager.getConnection(url);
            createSchema(c);
            TestDatabaseManager db = new TestDatabaseManager(c);
            TransactionLogManager mgr = new TransactionLogManager(db);

            // Hold the worker so the writes are still queued when close() starts.
            db.getDatabaseThread().submit(() -> {
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            });
            int n = 50;
            java.util.concurrent.atomic.AtomicInteger failed = new java.util.concurrent.atomic.AtomicInteger();
            for (int i = 0; i < n; i++) {
                mgr.save(TransactionLogRecord.simple(7, null, LedgerType.DEPOSIT, (short) 1, i + 1, 1_000L + i))
                        .exceptionally(t -> { failed.incrementAndGet(); return null; });
            }
            db.close();

            TestResult res = assertEquals("connection closed after close()", true, c.isClosed());
            if (!res.passed()) return res;
            res = assertEquals("failed writes", 0, failed.get());
            if (!res.passed()) return res;
            try (Connection verify = DriverManager.getConnection(url);
                 Statement stmt = verify.createStatement();
                 java.sql.ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM TransactionLog")) {
                rs.next();
                res = assertEquals("persisted rows", n, rs.getInt(1));
                if (!res.passed()) return res;
            }
            return pass("close() drained " + n + " queued ledger writes before closing");
        } catch (Exception e) {
            return fail("close_drains_queued_writes threw: " + e.getMessage());
        } finally {
            if (file != null) try { java.nio.file.Files.deleteIfExists(file); } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------------

    private static class TestDatabaseManager extends DatabaseManager {
        private final Connection testConnection;
        private final java.util.concurrent.ExecutorService testExecutor =
                java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "transaction-log-test-db-worker");
                    t.setDaemon(true);
                    return t;
                });

        TestDatabaseManager(Connection connection) {
            this.testConnection = connection;
        }

        @Override public Connection getConnection() { return testConnection; }
        @Override public java.util.concurrent.ExecutorService getDatabaseThread() { return testExecutor; }
        @Override public boolean commitTransaction() {
            try { testConnection.commit(); return true; }
            catch (SQLException e) { return false; }
        }
    }
}
