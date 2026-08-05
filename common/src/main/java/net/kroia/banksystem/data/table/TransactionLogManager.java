package net.kroia.banksystem.data.table;

import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.data.table.record.TransactionLogRecord;
import org.jetbrains.annotations.Nullable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Task #44 (v2.0.8) — SQLite writer + reader for the {@code TransactionLog} table.
 * <p>
 * Mirrors {@link BalanceHistoryManager}'s pattern:
 * <ul>
 *     <li>writes run async on the shared {@code banksystem-db-worker} executor;</li>
 *     <li>each write commits its own transaction;</li>
 *     <li>reads issue a single {@link PreparedStatement} on the DB worker.</li>
 * </ul>
 * Master-only. Slave-side callers must skip through {@link BankSystemModBackend#getTransactionLogManager()}'s
 * nullability guard.
 * <p>
 * The read API is used by the future Company screen "Ledger" tab (Task #44 UI follow-up,
 * currently deferred) and by admin tooling. It exposes:
 * <ul>
 *     <li>{@link #getByAccount(int, int)} — newest-first N rows for one account;</li>
 *     <li>{@link #getByCompany(int, int)} — newest-first N rows for one company id;</li>
 *     <li>{@link #getByAccountAndKind(int, TransactionLogRecord.Kind, int)} — filtered view.</li>
 * </ul>
 */
public class TransactionLogManager implements ITableManager<TransactionLogRecord> {

    private final DatabaseManager databaseManager;

    public static final String INSERT = "INSERT INTO TransactionLog " +
            "(account_number, actor_uuid, kind, item_id, amount, other_account, company_id, ts, note) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    public static final String SELECT_COLS =
            "id, account_number, actor_uuid, kind, item_id, amount, other_account, company_id, ts, note";

    public TransactionLogManager(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    @Override
    public CompletableFuture<Void> save(TransactionLogRecord data) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(INSERT)) {
                queueRecord(stmt, data);
                stmt.execute();
                databaseManager.commitTransaction();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, databaseManager.getDatabaseThread());
    }

    @Override
    public CompletableFuture<Void> save(List<TransactionLogRecord> data) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(INSERT)) {
                for (TransactionLogRecord row : data) queueRecord(stmt, row);
                stmt.executeBatch();
                databaseManager.commitTransaction();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, databaseManager.getDatabaseThread());
    }

    @Override
    public void queueRecord(PreparedStatement stmt, TransactionLogRecord data) {
        try {
            stmt.setInt(1, data.accountNumber());
            if (data.actorUuid() == null) stmt.setNull(2, java.sql.Types.VARCHAR);
            else stmt.setString(2, data.actorUuid().toString());
            stmt.setString(3, data.kind().name());
            stmt.setShort(4, data.itemId());
            stmt.setLong(5, data.amount());
            if (data.otherAccount() == null) stmt.setNull(6, java.sql.Types.INTEGER);
            else stmt.setInt(6, data.otherAccount());
            if (data.companyId() == null) stmt.setNull(7, java.sql.Types.INTEGER);
            else stmt.setInt(7, data.companyId());
            stmt.setLong(8, data.time());
            if (data.note() == null) stmt.setNull(9, java.sql.Types.VARCHAR);
            else stmt.setString(9, data.note());
            stmt.addBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to queue transaction-log record", e);
        }
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * @param accountNumber account whose history to fetch
     * @param limit         max rows ({@code <= 0} means unbounded)
     * @return future completing with newest-first rows
     */
    public CompletableFuture<List<TransactionLogRecord>> getByAccount(int accountNumber, int limit) {
        String sql = "SELECT " + SELECT_COLS + " FROM TransactionLog WHERE account_number = ? " +
                "ORDER BY ts DESC" + (limit > 0 ? " LIMIT ?" : "");
        return CompletableFuture.supplyAsync(() -> queryOne(sql, ps -> {
            ps.setInt(1, accountNumber);
            if (limit > 0) ps.setInt(2, limit);
        }), databaseManager.getDatabaseThread());
    }

    public CompletableFuture<List<TransactionLogRecord>> getByCompany(int companyId, int limit) {
        String sql = "SELECT " + SELECT_COLS + " FROM TransactionLog WHERE company_id = ? " +
                "ORDER BY ts DESC" + (limit > 0 ? " LIMIT ?" : "");
        return CompletableFuture.supplyAsync(() -> queryOne(sql, ps -> {
            ps.setInt(1, companyId);
            if (limit > 0) ps.setInt(2, limit);
        }), databaseManager.getDatabaseThread());
    }

    public CompletableFuture<List<TransactionLogRecord>> getByAccountAndKind(
            int accountNumber, TransactionLogRecord.Kind kind, int limit) {
        String sql = "SELECT " + SELECT_COLS + " FROM TransactionLog " +
                "WHERE account_number = ? AND kind = ? " +
                "ORDER BY ts DESC" + (limit > 0 ? " LIMIT ?" : "");
        return CompletableFuture.supplyAsync(() -> queryOne(sql, ps -> {
            ps.setInt(1, accountNumber);
            ps.setString(2, kind.name());
            if (limit > 0) ps.setInt(3, limit);
        }), databaseManager.getDatabaseThread());
    }

    public CompletableFuture<Integer> getCount(int accountNumber) {
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement ps = databaseManager.getConnection().prepareStatement(
                    "SELECT COUNT(*) FROM TransactionLog WHERE account_number = ?")) {
                ps.setInt(1, accountNumber);
                try (ResultSet rs = ps.executeQuery()) {
                    databaseManager.commitTransaction();
                    if (rs.next()) return rs.getInt(1);
                    return 0;
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, databaseManager.getDatabaseThread());
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    @FunctionalInterface
    private interface Binder { void bind(PreparedStatement ps) throws SQLException; }

    private List<TransactionLogRecord> queryOne(String sql, Binder binder) {
        List<TransactionLogRecord> result = new ArrayList<>();
        try (PreparedStatement ps = databaseManager.getConnection().prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                databaseManager.commitTransaction();
                while (rs.next()) {
                    TransactionLogRecord row = mapRow(rs);
                    if (row != null) result.add(row);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    private @Nullable TransactionLogRecord mapRow(ResultSet rs) {
        try {
            long id = rs.getLong(1);
            int account = rs.getInt(2);
            String actorStr = rs.getString(3);
            UUID actor = null;
            if (actorStr != null && !actorStr.isEmpty()) {
                try { actor = UUID.fromString(actorStr); }
                catch (IllegalArgumentException ignored) { actor = null; }
            }
            String kindStr = rs.getString(4);
            TransactionLogRecord.Kind kind;
            try { kind = TransactionLogRecord.Kind.valueOf(kindStr); }
            catch (IllegalArgumentException ignored) { return null; }
            short itemId = rs.getShort(5);
            long amount = rs.getLong(6);
            int other = rs.getInt(7);
            Integer otherAccount = rs.wasNull() ? null : other;
            int comp = rs.getInt(8);
            Integer companyId = rs.wasNull() ? null : comp;
            long ts = rs.getLong(9);
            String note = rs.getString(10);
            return new TransactionLogRecord(id, account, actor, kind, itemId, amount,
                    otherAccount, companyId, ts, note);
        } catch (SQLException e) {
            return null;
        }
    }
}
