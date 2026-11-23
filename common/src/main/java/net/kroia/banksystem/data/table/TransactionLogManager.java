package net.kroia.banksystem.data.table;

import net.kroia.banksystem.api.ledger.LedgerActor;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.banking.ledger.TransactionLedger;
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
 * Task #44 (v2.1.0) — SQLite writer + reader for the {@code TransactionLog} table.
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
 *     <li>{@link #getByAccountAndType(int, int, long, int)} — filtered view;</li>
 *     <li>{@link #getByRef(String)} / {@link #getByActor(UUID, long, int)} — Task #109.</li>
 * </ul>
 */
public class TransactionLogManager implements ITableManager<TransactionLogRecord> {

    private final DatabaseManager databaseManager;

    public static final String INSERT = "INSERT INTO TransactionLog " +
            "(account_number, actor_uuid, kind, item_id, amount, other_account, company_id, ts, note, " +
            "source_kind, tag, type_code, ref, money_amount, price, currency_item, actor_name, balance_after, virtual_qty, origin_server, " +
            "dimension, x, y, z) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    public static final String SELECT_COLS =
            "id, account_number, actor_uuid, kind, item_id, amount, other_account, company_id, ts, note, " +
            "source_kind, tag, type_code, ref, money_amount, price, currency_item, actor_name, balance_after, virtual_qty, origin_server, " +
            "dimension, x, y, z";

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
            setNullable(stmt, 2, data.actorUuid() == null ? null : data.actorUuid().toString());
            stmt.setString(3, data.type().name());
            stmt.setShort(4, data.itemId());
            stmt.setLong(5, data.amount());
            setNullable(stmt, 6, data.otherAccount());
            setNullable(stmt, 7, data.companyId());
            stmt.setLong(8, data.time());
            setNullable(stmt, 9, data.note());
            stmt.setString(10, data.source().name());
            setNullable(stmt, 11, data.tag());
            stmt.setInt(12, data.type().code());
            setNullable(stmt, 13, data.ref());
            setNullable(stmt, 14, data.moneyAmount());
            setNullable(stmt, 15, data.price());
            setNullable(stmt, 16, data.currencyItem() == null ? null : Integer.valueOf(data.currencyItem()));
            setNullable(stmt, 17, data.actorName());
            setNullable(stmt, 18, data.balanceAfter());
            setNullable(stmt, 19, data.virtualQty());
            setNullable(stmt, 20, data.originServer()); // BS-256
            TransactionLedger.LedgerPos pos = data.pos(); // BS-258
            setNullable(stmt, 21, pos == null ? null : pos.dimension());
            setNullable(stmt, 22, pos == null ? null : pos.x());
            setNullable(stmt, 23, pos == null ? null : pos.y());
            setNullable(stmt, 24, pos == null ? null : pos.z());
            stmt.addBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to queue transaction-log record", e);
        }
    }

    private static void setNullable(PreparedStatement stmt, int index, @Nullable Object value) throws SQLException {
        if (value == null) stmt.setNull(index, java.sql.Types.NULL);
        else stmt.setObject(index, value);
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
                "ORDER BY ts DESC, id DESC" + (limit > 0 ? " LIMIT ?" : "");
        return CompletableFuture.supplyAsync(() -> queryOne(sql, ps -> {
            ps.setInt(1, accountNumber);
            if (limit > 0) ps.setInt(2, limit);
        }), databaseManager.getDatabaseThread());
    }

    public CompletableFuture<List<TransactionLogRecord>> getByCompany(int companyId, int limit) {
        String sql = "SELECT " + SELECT_COLS + " FROM TransactionLog WHERE company_id = ? " +
                "ORDER BY ts DESC, id DESC" + (limit > 0 ? " LIMIT ?" : "");
        return CompletableFuture.supplyAsync(() -> queryOne(sql, ps -> {
            ps.setInt(1, companyId);
            if (limit > 0) ps.setInt(2, limit);
        }), databaseManager.getDatabaseThread());
    }

    /**
     * Task #109 — rows of one account with one type code, newest first.
     *
     * @param sinceMs lower bound on {@code ts} (inclusive); {@code <= 0} means no bound
     * @param limit   max rows ({@code <= 0} means unbounded)
     */
    public CompletableFuture<List<TransactionLogRecord>> getByAccountAndType(
            int accountNumber, int typeCode, long sinceMs, int limit) {
        String sql = "SELECT " + SELECT_COLS + " FROM TransactionLog " +
                "WHERE account_number = ? AND type_code = ? AND ts >= ? " +
                "ORDER BY ts DESC, id DESC" + (limit > 0 ? " LIMIT ?" : "");
        return CompletableFuture.supplyAsync(() -> queryOne(sql, ps -> {
            ps.setInt(1, accountNumber);
            ps.setInt(2, typeCode);
            ps.setLong(3, Math.max(0L, sinceMs));
            if (limit > 0) ps.setInt(4, limit);
        }), databaseManager.getDatabaseThread());
    }

    /** Task #109 — every row with this {@code ref} (e.g. all slices of one order), oldest first. */
    public CompletableFuture<List<TransactionLogRecord>> getByRef(String ref) {
        String sql = "SELECT " + SELECT_COLS + " FROM TransactionLog WHERE ref = ? ORDER BY ts ASC, id ASC";
        return CompletableFuture.supplyAsync(() -> queryOne(sql, ps -> ps.setString(1, ref)),
                databaseManager.getDatabaseThread());
    }

    /**
     * Task #109 — rows caused by one actor, newest first.
     *
     * @param sinceMs lower bound on {@code ts} (inclusive); {@code <= 0} means no bound
     * @param limit   max rows ({@code <= 0} means unbounded)
     */
    public CompletableFuture<List<TransactionLogRecord>> getByActor(UUID actor, long sinceMs, int limit) {
        String sql = "SELECT " + SELECT_COLS + " FROM TransactionLog WHERE actor_uuid = ? AND ts >= ? " +
                "ORDER BY ts DESC, id DESC" + (limit > 0 ? " LIMIT ?" : "");
        return CompletableFuture.supplyAsync(() -> queryOne(sql, ps -> {
            ps.setString(1, actor.toString());
            ps.setLong(2, Math.max(0L, sinceMs));
            if (limit > 0) ps.setInt(3, limit);
        }), databaseManager.getDatabaseThread());
    }

    /**
     * Task #110 — one page of an account's rows, newest first (ts DESC, id DESC).
     *
     * @param typeCode type code to keep, or {@code < 0} for all types
     * @param sinceMs  lower bound on {@code ts} (inclusive); {@code <= 0} = no bound
     * @param offset   rows to skip
     * @param limit    page size ({@code > 0})
     */
    public CompletableFuture<List<TransactionLogRecord>> getPage(int accountNumber, int typeCode, long sinceMs,
                                                                 int offset, int limit) {
        String sql = "SELECT " + SELECT_COLS + " FROM TransactionLog WHERE account_number = ? AND ts >= ?"
                + (typeCode >= 0 ? " AND type_code = ?" : "") + " ORDER BY ts DESC, id DESC LIMIT ? OFFSET ?";
        return CompletableFuture.supplyAsync(() -> queryOne(sql, ps -> {
            int i = 1;
            ps.setInt(i++, accountNumber);
            ps.setLong(i++, Math.max(0L, sinceMs));
            if (typeCode >= 0) ps.setInt(i++, typeCode);
            ps.setInt(i++, Math.max(1, limit));
            ps.setInt(i, Math.max(0, offset));
        }), databaseManager.getDatabaseThread());
    }

    /**
     * Task #113 — rows with one of {@code typeCodes} in {@code [fromMs, toMs)}, newest first.
     * Synchronous: call on the DB thread.
     */
    public List<TransactionLogRecord> getByTypesInRangeSync(int[] typeCodes, long fromMs, long toMs) {
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < typeCodes.length; i++) in.append(i == 0 ? "?" : ", ?");
        String sql = "SELECT " + SELECT_COLS + " FROM TransactionLog WHERE type_code IN (" + in + ") AND ts >= ? AND ts < ?"
                + " ORDER BY ts DESC, id DESC";
        return queryOne(sql, ps -> {
            int i = 1;
            for (int code : typeCodes) ps.setInt(i++, code);
            ps.setLong(i++, fromMs);
            ps.setLong(i, toMs);
        });
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
                while (rs.next()) result.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    /** Never drops a row: unknown codes map to UNKNOWN_LEGACY, unknown sources to SYSTEM. */
    private static TransactionLogRecord mapRow(ResultSet rs) throws SQLException {
        long id = rs.getLong(1);
        int account = rs.getInt(2);
        String actorStr = rs.getString(3);
        UUID actor = null;
        if (actorStr != null && !actorStr.isEmpty()) {
            try { actor = UUID.fromString(actorStr); }
            catch (IllegalArgumentException ignored) { actor = null; }
        }
        short itemId = rs.getShort(5);
        long amount = rs.getLong(6);
        Integer otherAccount = nullableInt(rs, 7);
        Integer companyId = nullableInt(rs, 8);
        long ts = rs.getLong(9);
        String note = rs.getString(10);
        LedgerActor.Source source;
        try { source = LedgerActor.Source.valueOf(rs.getString(11)); }
        catch (IllegalArgumentException | NullPointerException ignored) { source = LedgerActor.Source.SYSTEM; }
        String tag = rs.getString(12);
        LedgerType type = LedgerType.fromCode(rs.getInt(13));
        String ref = rs.getString(14);
        Long moneyAmount = nullableLong(rs, 15);
        Long price = nullableLong(rs, 16);
        Integer currency = nullableInt(rs, 17);
        String actorName = rs.getString(18);
        Long balanceAfter = nullableLong(rs, 19);
        Long virtualQty = nullableLong(rs, 20);
        String dimension = rs.getString(22); // BS-258
        TransactionLedger.LedgerPos pos = dimension == null ? null
                : new TransactionLedger.LedgerPos(dimension, rs.getInt(23), rs.getInt(24), rs.getInt(25));
        return new TransactionLogRecord(id, account, actor, actorName, source, type, itemId, amount,
                otherAccount, companyId, ts, note, ref, moneyAmount, price,
                currency == null ? null : (short) (int) currency, balanceAfter, virtualQty, tag, rs.getString(21), pos);
    }

    private static @Nullable Integer nullableInt(ResultSet rs, int col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static @Nullable Long nullableLong(ResultSet rs, int col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }
}
