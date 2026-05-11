package net.kroia.banksystem.data.table;

import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.data.table.record.BalanceHistoryRecord;
import net.kroia.banksystem.data.filter.DateFilter;
import net.kroia.banksystem.data.filter.EqualityFilter;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
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
