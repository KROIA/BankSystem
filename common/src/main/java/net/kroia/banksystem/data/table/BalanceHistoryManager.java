package net.kroia.banksystem.data.table;

import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.data.table.record.BalanceHistoryRecord;
import net.kroia.banksystem.data.filter.DateFilter;
import net.kroia.banksystem.data.filter.EqualityFilter;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class BalanceHistoryManager implements ITableManager<BalanceHistoryRecord> {

    private final DatabaseManager databaseManager;

    public static final String INSERT = "INSERT INTO BalanceHistory (account_number, item_id, balance, locked_balance, time) VALUES (?, ?, ?, ?, ?)";
    public static final String SELECT = "SELECT account_number, item_id, balance, locked_balance, time FROM BalanceHistory";
    public static final String DELETE = "DELETE FROM BalanceHistory";
    public static final String COUNT  = "SELECT COUNT(*) FROM BalanceHistory";

    public BalanceHistoryManager(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    @Override
    public CompletableFuture<Void> save(BalanceHistoryRecord data) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement preparedStatement = databaseManager.getConnection().prepareStatement(INSERT)) {
                queueRecord(preparedStatement, data);
                preparedStatement.execute();
                databaseManager.commitTransaction();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, databaseManager.getDatabaseThread());
    }

    @Override
    public CompletableFuture<Void> save(List<BalanceHistoryRecord> data) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement preparedStatement = databaseManager.getConnection().prepareStatement(INSERT)) {
                data.forEach(d -> queueRecord(preparedStatement, d));
                preparedStatement.executeBatch();
                databaseManager.commitTransaction();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, databaseManager.getDatabaseThread());
    }

    @Override
    public void queueRecord(PreparedStatement stmt, BalanceHistoryRecord data) {
        try {
            stmt.setInt(1, data.accountNumber());
            stmt.setShort(2, data.itemId());
            stmt.setLong(3, data.balance());
            stmt.setLong(4, data.lockedBalance());
            stmt.setLong(5, data.time());
            stmt.addBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to queue balance history record", e);
        }
    }

    public CompletableFuture<List<BalanceHistoryRecord>> getHistory(
            Optional<DateFilter> dateFilter,
            Optional<EqualityFilter> accountFilter,
            Optional<EqualityFilter> itemFilter,
            int limit) {
        return query(dateFilter, accountFilter, itemFilter, SELECT, limit);
    }

    public CompletableFuture<Void> removeHistory(
            Optional<DateFilter> dateFilter,
            Optional<EqualityFilter> accountFilter,
            Optional<EqualityFilter> itemFilter) {
        return CompletableFuture.runAsync(() -> {
            try {
                String statement = buildFilteredStatement(DELETE, dateFilter, accountFilter, itemFilter);

                try (PreparedStatement preparedStatement = databaseManager.getConnection().prepareStatement(statement)) {
                    bindFilters(preparedStatement, 1, dateFilter, accountFilter, itemFilter);
                    preparedStatement.executeUpdate();
                    databaseManager.commitTransaction();
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, databaseManager.getDatabaseThread());
    }

    public CompletableFuture<Integer> getRecordCount(
            Optional<DateFilter> dateFilter,
            Optional<EqualityFilter> accountFilter,
            Optional<EqualityFilter> itemFilter) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String statement = buildFilteredStatement(COUNT, dateFilter, accountFilter, itemFilter);

                try (PreparedStatement preparedStatement = databaseManager.getConnection().prepareStatement(statement)) {
                    bindFilters(preparedStatement, 1, dateFilter, accountFilter, itemFilter);
                    try (ResultSet resultSet = preparedStatement.executeQuery()) {
                        databaseManager.commitTransaction();
                        if (resultSet.next()) {
                            return resultSet.getInt(1);
                        }
                    }
                }
                return 0;
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, databaseManager.getDatabaseThread());
    }

    private CompletableFuture<List<BalanceHistoryRecord>> query(
            Optional<DateFilter> dateFilter,
            Optional<EqualityFilter> accountFilter,
            Optional<EqualityFilter> itemFilter,
            String baseStatement,
            int limit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String statement = buildFilteredStatement(baseStatement, dateFilter, accountFilter, itemFilter);
                statement += " ORDER BY time ASC";
                if (limit > 0) {
                    statement += " LIMIT ?";
                }

                List<BalanceHistoryRecord> result = new ArrayList<>();
                try (PreparedStatement preparedStatement = databaseManager.getConnection().prepareStatement(statement)) {
                    int idx = bindFilters(preparedStatement, 1, dateFilter, accountFilter, itemFilter);
                    if (limit > 0) {
                        preparedStatement.setInt(idx, limit);
                    }
                    try (ResultSet resultSet = preparedStatement.executeQuery()) {
                        databaseManager.commitTransaction();
                        while (resultSet.next()) {
                            BalanceHistoryRecord row = mapRow(resultSet);
                            if (row != null)
                                result.add(row);
                        }
                    }
                }
                return result;
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, databaseManager.getDatabaseThread());
    }

    private BalanceHistoryRecord mapRow(ResultSet rs) {
        try {
            return new BalanceHistoryRecord(
                    rs.getInt(1),
                    rs.getShort(2),
                    rs.getLong(3),
                    rs.getLong(4),
                    rs.getLong(5)
            );
        } catch (SQLException e) {
            return null;
        }
    }

    /**
     * Batch-deletes every balance-history row whose {@code item_id} column matches one of the
     * given ItemID shorts. Intended for the merge-consolidation path in
     * {@link net.kroia.banksystem.util.ItemIDManager#consolidatePendingMerges()}: when several
     * ItemIDs are collapsed into a canonical one, the alias shorts leave the registry and their
     * chart-visible history is stale — a chart lookup by the deleted alias short would still
     * find rows that no longer correspond to a live identity.
     * <p>
     * Runs async on the single-threaded DB executor and commits the pending transaction, mirroring
     * the connection lifecycle used by {@link #save} / {@link #removeHistory}. Shorts are bound
     * via {@code PreparedStatement.setShort} (no string concatenation into SQL) so any short
     * value is safely passed.
     * <p>
     * <b>Master-only.</b> The balance-history DB lives on the master server; slave servers and
     * clients never construct a {@code BalanceHistoryManager} (see
     * {@code BankSystemModBackend#onServerStart}). Callers on non-master paths must skip this
     * call — the guard in {@code consolidatePendingMerges()} already does so before it reaches
     * here.
     * <p>
     * Empty collection is a no-op that returns a completed future — no SQL executed.
     *
     * @param aliasShorts ItemID shorts whose history rows should be purged.
     *                    Null / empty → no-op.
     * @return future that completes once the batch delete has been committed (or immediately for
     *         a no-op input); completes exceptionally only on unrecoverable SQL errors.
     */
    public CompletableFuture<Void> deleteAllRowsForItemIDs(Collection<Short> aliasShorts) {
        if (aliasShorts == null || aliasShorts.isEmpty())
            return CompletableFuture.completedFuture(null);
        // Copy defensively — the caller's collection could be mutated between now and the async
        // DB thread executing the delete (the shorts are drained from ItemIDManager's pending
        // consolidation map on the server thread, which is a different thread from the DB one).
        final List<Short> shorts = new ArrayList<>(aliasShorts);
        return CompletableFuture.runAsync(() -> {
            // Build an IN (?, ?, ...) placeholder list of the exact size — safe against SQL
            // injection (no user-controlled string concatenation) and lets a single statement
            // remove every row in one round trip regardless of batch size.
            StringBuilder sql = new StringBuilder("DELETE FROM BalanceHistory WHERE item_id IN (");
            for (int i = 0; i < shorts.size(); i++) {
                if (i > 0) sql.append(',');
                sql.append('?');
            }
            sql.append(')');
            try (PreparedStatement preparedStatement = databaseManager.getConnection().prepareStatement(sql.toString())) {
                for (int i = 0; i < shorts.size(); i++) {
                    preparedStatement.setShort(i + 1, shorts.get(i));
                }
                preparedStatement.executeUpdate();
                databaseManager.commitTransaction();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, databaseManager.getDatabaseThread());
    }

    /**
     * Prunes old records so that each (account_number, item_id) pair keeps at most
     * {@code maxRecordsPerItem} entries (newest retained). Runs async on the DB thread.
     *
     * @param maxRecordsPerItem max records to keep per account+item. If <= 0, no pruning occurs.
     */
    public CompletableFuture<Void> pruneOldRecords(long maxRecordsPerItem) {
        if (maxRecordsPerItem <= 0) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            try {
                String sql = "DELETE FROM BalanceHistory WHERE id NOT IN (" +
                        "SELECT id FROM (" +
                        "SELECT id, ROW_NUMBER() OVER (PARTITION BY account_number, item_id ORDER BY time DESC) AS rn " +
                        "FROM BalanceHistory) WHERE rn <= ?)";
                try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql)) {
                    stmt.setLong(1, maxRecordsPerItem);
                    stmt.executeUpdate();
                    databaseManager.commitTransaction();
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, databaseManager.getDatabaseThread());
    }

    private String buildFilteredStatement(
            String base,
            Optional<DateFilter> dateFilter,
            Optional<EqualityFilter> accountFilter,
            Optional<EqualityFilter> itemFilter) {
        StringBuilder sb = new StringBuilder(base);
        boolean started = false;
        if (dateFilter.isPresent()) {
            sb.append(" WHERE ").append(dateFilter.get().getClause("time"));
            started = true;
        }
        if (accountFilter.isPresent()) {
            sb.append(started ? " AND " : " WHERE ").append(accountFilter.get().getClause("account_number"));
            started = true;
        }
        if (itemFilter.isPresent()) {
            sb.append(started ? " AND " : " WHERE ").append(itemFilter.get().getClause("item_id"));
        }
        return sb.toString();
    }

    private int bindFilters(
            PreparedStatement stmt,
            int idx,
            Optional<DateFilter> dateFilter,
            Optional<EqualityFilter> accountFilter,
            Optional<EqualityFilter> itemFilter) throws SQLException {
        if (dateFilter.isPresent()) {
            idx = dateFilter.get().bindParameters(stmt, idx);
        }
        if (accountFilter.isPresent()) {
            idx = accountFilter.get().bindParameters(stmt, idx);
        }
        if (itemFilter.isPresent()) {
            idx = itemFilter.get().bindParameters(stmt, idx);
        }
        return idx;
    }
}
