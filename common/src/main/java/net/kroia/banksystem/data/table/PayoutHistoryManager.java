package net.kroia.banksystem.data.table;

import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.data.table.record.PayoutHistoryRecord;
import org.jetbrains.annotations.Nullable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Task #45 (v2.1.0) — SQLite writer/reader for the {@code PayoutHistory} table.
 * <p>
 * Master-only. Mirrors {@link TransactionLogManager}'s pattern: writes run async on the
 * shared {@code banksystem-db-worker} executor, each write commits its own transaction.
 */
public class PayoutHistoryManager implements ITableManager<PayoutHistoryRecord> {

    private final DatabaseManager databaseManager;

    public static final String INSERT = "INSERT INTO PayoutHistory " +
            "(company_id, schedule_id, source_account, target_uuid, amount, ts, status, " +
            "target_player_name, target_account_name, currency_item, type) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    public static final String SELECT_COLS =
            "id, company_id, schedule_id, source_account, target_uuid, amount, ts, status, " +
            "target_player_name, target_account_name, currency_item, type";

    public PayoutHistoryManager(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    @Override
    public CompletableFuture<Void> save(PayoutHistoryRecord data) {
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
    public CompletableFuture<Void> save(List<PayoutHistoryRecord> data) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(INSERT)) {
                for (PayoutHistoryRecord row : data) queueRecord(stmt, row);
                stmt.executeBatch();
                databaseManager.commitTransaction();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, databaseManager.getDatabaseThread());
    }

    @Override
    public void queueRecord(PreparedStatement stmt, PayoutHistoryRecord data) {
        try {
            stmt.setInt(1, data.companyId());
            stmt.setLong(2, data.scheduleId());
            stmt.setInt(3, data.sourceAccount());
            stmt.setString(4, data.targetUuid() == null ? "" : data.targetUuid().toString());
            stmt.setLong(5, data.amount());
            stmt.setLong(6, data.time());
            stmt.setString(7, data.status().name());
            stmt.setString(8, data.targetPlayerName() == null ? "" : data.targetPlayerName());
            stmt.setString(9, data.targetAccountName() == null ? "" : data.targetAccountName());
            stmt.setInt(10, data.currencyItem());
            stmt.setString(11, (data.type() == null ? PayoutHistoryRecord.Type.NORMAL : data.type()).name());
            stmt.addBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to queue payout-history record", e);
        }
    }

    /** Newest-first rows for a single schedule. */
    public CompletableFuture<List<PayoutHistoryRecord>> getByScheduleId(long scheduleId, int limit) {
        String sql = "SELECT " + SELECT_COLS + " FROM PayoutHistory WHERE schedule_id = ? " +
                "ORDER BY ts DESC" + (limit > 0 ? " LIMIT ?" : "");
        return CompletableFuture.supplyAsync(() -> queryOne(sql, ps -> {
            ps.setLong(1, scheduleId);
            if (limit > 0) ps.setInt(2, limit);
        }), databaseManager.getDatabaseThread());
    }

    /** Newest-first rows for every schedule owned by a company. */
    public CompletableFuture<List<PayoutHistoryRecord>> getByCompany(int companyId, int limit) {
        String sql = "SELECT " + SELECT_COLS + " FROM PayoutHistory WHERE company_id = ? " +
                "ORDER BY ts DESC" + (limit > 0 ? " LIMIT ?" : "");
        return CompletableFuture.supplyAsync(() -> queryOne(sql, ps -> {
            ps.setInt(1, companyId);
            if (limit > 0) ps.setInt(2, limit);
        }), databaseManager.getDatabaseThread());
    }

    /** Total OK-status amount paid on a schedule — used by the UI footer. */
    public CompletableFuture<Long> getTotalPaidForSchedule(long scheduleId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT COALESCE(SUM(amount), 0) FROM PayoutHistory " +
                    "WHERE schedule_id = ? AND status = ?";
            try (PreparedStatement ps = databaseManager.getConnection().prepareStatement(sql)) {
                ps.setLong(1, scheduleId);
                ps.setString(2, PayoutHistoryRecord.Status.OK.name());
                try (ResultSet rs = ps.executeQuery()) {
                    databaseManager.commitTransaction();
                    if (rs.next()) return rs.getLong(1);
                    return 0L;
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, databaseManager.getDatabaseThread());
    }

    /**
     * Task #45a — count of non-OK payout attempts for a company since {@code sinceTs}. Used by the
     * payouts overview footer ("Failed (24h): N"). Master-only.
     */
    public CompletableFuture<Long> countFailuresSinceForCompany(int companyId, long sinceTs) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT COUNT(*) FROM PayoutHistory " +
                    "WHERE company_id = ? AND status != ? AND ts >= ?";
            try (PreparedStatement ps = databaseManager.getConnection().prepareStatement(sql)) {
                ps.setInt(1, companyId);
                ps.setString(2, PayoutHistoryRecord.Status.OK.name());
                ps.setLong(3, sinceTs);
                try (ResultSet rs = ps.executeQuery()) {
                    databaseManager.commitTransaction();
                    if (rs.next()) return rs.getLong(1);
                    return 0L;
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, databaseManager.getDatabaseThread());
    }

    // ------------------------------------------------------------------
    @FunctionalInterface
    private interface Binder { void bind(PreparedStatement ps) throws SQLException; }

    private List<PayoutHistoryRecord> queryOne(String sql, Binder binder) {
        List<PayoutHistoryRecord> result = new ArrayList<>();
        try (PreparedStatement ps = databaseManager.getConnection().prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                databaseManager.commitTransaction();
                while (rs.next()) {
                    PayoutHistoryRecord row = mapRow(rs);
                    if (row != null) result.add(row);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    private @Nullable PayoutHistoryRecord mapRow(ResultSet rs) {
        try {
            long id = rs.getLong(1);
            int companyId = rs.getInt(2);
            long scheduleId = rs.getLong(3);
            int sourceAccount = rs.getInt(4);
            String targetStr = rs.getString(5);
            UUID target = null;
            if (targetStr != null && !targetStr.isEmpty()) {
                try { target = UUID.fromString(targetStr); }
                catch (IllegalArgumentException ignored) { target = null; }
            }
            long amount = rs.getLong(6);
            long time = rs.getLong(7);
            String statusStr = rs.getString(8);
            PayoutHistoryRecord.Status status;
            try { status = PayoutHistoryRecord.Status.valueOf(statusStr); }
            catch (IllegalArgumentException ignored) { status = PayoutHistoryRecord.Status.UNKNOWN; }
            String playerName = rs.getString(9);
            String accountName = rs.getString(10);
            short currencyItem = (short) rs.getInt(11);
            PayoutHistoryRecord.Type type;
            try { type = PayoutHistoryRecord.Type.valueOf(rs.getString(12)); }
            catch (IllegalArgumentException | NullPointerException ignored) { type = PayoutHistoryRecord.Type.NORMAL; }
            return new PayoutHistoryRecord(id, companyId, scheduleId, sourceAccount, target,
                    amount, time, status,
                    playerName == null ? "" : playerName,
                    accountName == null ? "" : accountName,
                    currencyItem, type);
        } catch (SQLException e) {
            return null;
        }
    }
}
