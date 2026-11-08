package net.kroia.banksystem.data.table;

import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.data.table.record.SuspicionFlagRecord;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Task #110 (v2.2.0) — SQLite writer/reader for {@code SuspicionFlag}. Master only; runs on the
 * shared DB worker like {@link TransactionLogManager}. Raise flags through
 * {@code AdminAudit.raise}, which also writes the audit file line and the admin alert.
 */
public class SuspicionFlagManager {

    private final DatabaseManager databaseManager;

    public SuspicionFlagManager(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public CompletableFuture<Void> save(SuspicionFlagRecord r) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement ps = databaseManager.getConnection().prepareStatement(
                    "INSERT INTO SuspicionFlag (ts, account_number, flag_type, severity, amount, ref, details, acknowledged) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, r.time());
                ps.setInt(2, r.accountNumber());
                ps.setInt(3, r.flagType());
                ps.setInt(4, r.severity());
                ps.setLong(5, r.amount());
                if (r.ref() == null) ps.setNull(6, Types.VARCHAR); else ps.setString(6, r.ref());
                if (r.details() == null) ps.setNull(7, Types.VARCHAR); else ps.setString(7, r.details());
                ps.setInt(8, r.acknowledged() ? 1 : 0);
                ps.execute();
                databaseManager.commitTransaction();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, databaseManager.getDatabaseThread());
    }

    /**
     * Newest first.
     *
     * @param accountNumber account, or {@code < 0} for every account
     * @param sinceMs       lower bound on {@code ts} (inclusive); {@code <= 0} = no bound
     * @param limit         max rows ({@code <= 0} = unbounded)
     */
    public CompletableFuture<List<SuspicionFlagRecord>> getRecent(int accountNumber, long sinceMs, int limit) {
        String sql = "SELECT id, ts, account_number, flag_type, severity, amount, ref, details, acknowledged "
                + "FROM SuspicionFlag WHERE ts >= ?" + (accountNumber >= 0 ? " AND account_number = ?" : "")
                + " ORDER BY ts DESC, id DESC" + (limit > 0 ? " LIMIT " + limit : "");
        return CompletableFuture.supplyAsync(() -> {
            List<SuspicionFlagRecord> out = new ArrayList<>();
            try (PreparedStatement ps = databaseManager.getConnection().prepareStatement(sql)) {
                ps.setLong(1, Math.max(0L, sinceMs));
                if (accountNumber >= 0) ps.setInt(2, accountNumber);
                try (ResultSet rs = ps.executeQuery()) {
                    databaseManager.commitTransaction();
                    while (rs.next())
                        out.add(new SuspicionFlagRecord(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getInt(4),
                                rs.getInt(5), rs.getLong(6), rs.getString(7), rs.getString(8), rs.getInt(9) != 0));
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return out;
        }, databaseManager.getDatabaseThread());
    }

    public CompletableFuture<Void> acknowledge(long id) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement ps = databaseManager.getConnection().prepareStatement(
                    "UPDATE SuspicionFlag SET acknowledged = 1 WHERE id = ?")) {
                ps.setLong(1, id);
                ps.execute();
                databaseManager.commitTransaction();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, databaseManager.getDatabaseThread());
    }
}
