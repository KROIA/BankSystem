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
import java.util.concurrent.TimeUnit;

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

    /**
     * Bucketed balance-history query (Task #40). Returns at most {@code maxPoints}
     * samples per {@code item_id} for the account, taking the last (newest) row of
     * each equal-width time bucket in the {@code [fromMs, toMs]} range. Empty buckets
     * simply produce no row — the client renders straight lines across the gaps,
     * which is the semantically-correct visualization for step-function balances
     * where no finer data exists in the bucket window.
     * <p>
     * <b>Anchor rows</b> (newest snapshot before {@code fromMs}) are returned in
     * addition to in-window bucketed rows, so clients rendering step-function
     * balances can draw the correct starting value at the left edge. Exactly one
     * anchor row per {@code item_id} that has any pre-window data is returned,
     * mixed into the same result list in ascending {@code time} order. When the
     * query is dispatched via {@code ALL_HISTORY_SENTINEL} (either bound is the
     * corresponding {@code Long.MIN_VALUE}/{@code Long.MAX_VALUE}), no anchor rows
     * are returned — {@code effectiveFrom} is resolved to {@code MIN(time)} for
     * the account, so by construction no row exists before it.
     * <p>
     * If {@code fromMs == Long.MIN_VALUE} or {@code toMs == Long.MAX_VALUE}, the
     * effective range is resolved from the underlying table via a first-pass
     * {@code MIN(time) / MAX(time)} lookup on the account. When the account has zero
     * rows, an empty list is returned.
     * <p>
     * The wealth synthetic series ({@code BalanceHistoryRecord.WEALTH_ITEM_ID}) is
     * treated identically — it is just another {@code item_id} in the table and gets
     * its own {@code maxPoints}-budget bucketed slice plus its own anchor row, no
     * special-case needed.
     * <p>
     * Runs on the DB thread; safe against SQL injection (all params via
     * {@link PreparedStatement}). Two sequential {@code PreparedStatement}s are
     * issued (anchor query then bucketed query) on the same DB thread executor —
     * the extra roundtrip is negligible next to the win of drawing the left edge
     * of a viewport with pre-window data.
     *
     * @param accountNumber account whose history to sample
     * @param fromMs        inclusive lower time bound in epoch millis, or
     *                      {@link Long#MIN_VALUE} for "resolve from MIN(time)"
     * @param toMs          inclusive upper time bound in epoch millis, or
     *                      {@link Long#MAX_VALUE} for "resolve from MAX(time)"
     * @param maxPoints     per-item point budget; {@code <= 0} disables bucketing and
     *                      falls back to the unbucketed {@link #getHistory} path so
     *                      admin tooling can still fetch every row when needed
     * @return future completing with the sampled rows (plus at most one anchor row
     *         per {@code item_id}) in ascending {@code time} order
     */
    public CompletableFuture<List<BalanceHistoryRecord>> getHistoryBucketed(
            int accountNumber, long fromMs, long toMs, int maxPoints) {
        if (maxPoints <= 0) {
            return getHistory(
                    Optional.empty(),
                    Optional.of(new EqualityFilter(accountNumber)),
                    Optional.empty(),
                    0
            );
        }
        // Capture whether either bound was the ALL_HISTORY sentinel before resolution;
        // in that mode the effective range is expanded to the full table extent, so an
        // anchor query with "time < effectiveFrom" would be nonsensical (nothing precedes
        // MIN(time)). Skip it entirely.
        final boolean sentinelUsed = (fromMs == Long.MIN_VALUE) || (toMs == Long.MAX_VALUE);
        return CompletableFuture.supplyAsync(() -> {
            try {
                long effectiveFrom = fromMs;
                long effectiveTo = toMs;
                if (effectiveFrom == Long.MIN_VALUE || effectiveTo == Long.MAX_VALUE) {
                    long[] range = queryTimeRange(accountNumber);
                    if (range == null) return new ArrayList<BalanceHistoryRecord>();
                    if (effectiveFrom == Long.MIN_VALUE) effectiveFrom = range[0];
                    if (effectiveTo == Long.MAX_VALUE) effectiveTo = range[1];
                }
                if (effectiveTo < effectiveFrom) {
                    long swap = effectiveFrom; effectiveFrom = effectiveTo; effectiveTo = swap;
                }
                long span = effectiveTo - effectiveFrom;
                long bucketWidth = Math.max(1L, span / maxPoints);

                List<BalanceHistoryRecord> result = new ArrayList<>();

                // 1) Anchor query — newest row per item_id with time < effectiveFrom. Skipped
                // when the caller used ALL_HISTORY_SENTINEL, since there is nothing before
                // the account's MIN(time) by definition.
                if (!sentinelUsed) {
                    String anchorSql =
                            "WITH anchor AS (" +
                            "  SELECT account_number, item_id, balance, locked_balance, time," +
                            "         ROW_NUMBER() OVER (" +
                            "             PARTITION BY item_id" +
                            "             ORDER BY time DESC" +
                            "         ) AS rn" +
                            "  FROM BalanceHistory" +
                            "  WHERE account_number = ? AND time < ?" +
                            ") " +
                            "SELECT account_number, item_id, balance, locked_balance, time " +
                            "FROM anchor WHERE rn = 1 ORDER BY time ASC";
                    try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(anchorSql)) {
                        stmt.setInt(1, accountNumber);
                        stmt.setLong(2, effectiveFrom);
                        try (ResultSet rs = stmt.executeQuery()) {
                            databaseManager.commitTransaction();
                            while (rs.next()) {
                                BalanceHistoryRecord row = mapRow(rs);
                                if (row != null) result.add(row);
                            }
                        }
                    }
                }

                // 2) Bucketed in-window query — the primary payload.
                String sql =
                        "WITH bucketed AS (" +
                        "  SELECT account_number, item_id, balance, locked_balance, time," +
                        "         ROW_NUMBER() OVER (" +
                        "             PARTITION BY item_id, ((time - ?) / ?)" +
                        "             ORDER BY time DESC" +
                        "         ) AS rn" +
                        "  FROM BalanceHistory" +
                        "  WHERE account_number = ? AND time >= ? AND time <= ?" +
                        ") " +
                        "SELECT account_number, item_id, balance, locked_balance, time " +
                        "FROM bucketed WHERE rn = 1 ORDER BY time ASC";

                try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql)) {
                    stmt.setLong(1, effectiveFrom);
                    stmt.setLong(2, bucketWidth);
                    stmt.setInt(3, accountNumber);
                    stmt.setLong(4, effectiveFrom);
                    stmt.setLong(5, effectiveTo);
                    try (ResultSet rs = stmt.executeQuery()) {
                        databaseManager.commitTransaction();
                        while (rs.next()) {
                            BalanceHistoryRecord row = mapRow(rs);
                            if (row != null) result.add(row);
                        }
                    }
                }
                return result;
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, databaseManager.getDatabaseThread());
    }

    /**
     * Returns {@code [min(time), max(time)]} for the given account, or {@code null}
     * when the account has no rows. Called on the DB thread from
     * {@link #getHistoryBucketed} to resolve "all history" queries.
     */
    private long[] queryTimeRange(int accountNumber) throws SQLException {
        String sql = "SELECT MIN(time), MAX(time) FROM BalanceHistory WHERE account_number = ?";
        try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql)) {
            stmt.setInt(1, accountNumber);
            try (ResultSet rs = stmt.executeQuery()) {
                databaseManager.commitTransaction();
                if (!rs.next()) return null;
                long min = rs.getLong(1);
                if (rs.wasNull()) return null;
                long max = rs.getLong(2);
                return new long[]{min, max};
            }
        }
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
     * Task #41 (v2.0.7). Applies the tiered retention plan to the entire
     * {@code BalanceHistory} table. Idempotent — deletes rows that are not bucket-keepers
     * for their age band, then hard-drops rows older than one year.
     * <p>
     * Bands (relative to {@code now}):
     * <ul>
     *   <li>0..24h -&gt; untouched (full resolution)</li>
     *   <li>24h..7d -&gt; 15-minute buckets, newest-in-bucket kept</li>
     *   <li>7d..30d -&gt; 1-hour buckets, newest-in-bucket kept</li>
     *   <li>30d..1y -&gt; 1-day buckets, newest-in-bucket kept</li>
     *   <li>&gt; 1y -&gt; deleted</li>
     * </ul>
     * Bucket-keeper means the row with the newest {@code time} within an
     * {@code (account_number, item_id, bucketIndex)} partition. The shape of the balance
     * curve is preserved by construction — the newest row of every populated bucket stays.
     * <p>
     * Runs async on the DB thread; each band DELETE + the final tail DELETE commit their
     * own transactions.
     *
     * @param now current epoch millis; the band boundaries are computed relative to this
     * @return future that completes once every band DELETE has been committed
     */
    public CompletableFuture<Void> applyTieredRetention(long now) {
        return CompletableFuture.runAsync(() -> {
            try {
                long h24  = now - TimeUnit.DAYS.toMillis(1);
                long d7   = now - TimeUnit.DAYS.toMillis(7);
                long d30  = now - TimeUnit.DAYS.toMillis(30);
                long d365 = now - TimeUnit.DAYS.toMillis(365);

                // Bands are traversed newest-first so the (rare) overlap of a moving boundary
                // between two DELETE statements only ever REMOVES rows that a later band's
                // WHERE clause would already have refused to bucket-anchor. Idempotent under
                // repeated calls: after the first pass every band contains at most one row
                // per (account, item, bucketIndex) — so ROW_NUMBER partitioning still yields
                // rn=1 for every survivor on the next sweep.
                downsampleBand(d7,   h24, TimeUnit.MINUTES.toMillis(15)); // 24h..7d @ 15min
                downsampleBand(d30,  d7,  TimeUnit.HOURS.toMillis(1));    // 7d..30d @ 1h
                downsampleBand(d365, d30, TimeUnit.DAYS.toMillis(1));     // 30d..1y @ 1d

                try (PreparedStatement stmt = databaseManager.getConnection()
                        .prepareStatement("DELETE FROM BalanceHistory WHERE time < ?")) {
                    stmt.setLong(1, d365);
                    stmt.executeUpdate();
                    databaseManager.commitTransaction();
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, databaseManager.getDatabaseThread());
    }

    /**
     * Task #41 helper: deletes every row in {@code [startInclusive, endExclusive)} that is
     * not the newest-in-bucket for its {@code (account_number, item_id, bucketIndex)}
     * partition, where {@code bucketIndex = (time - startInclusive) / bucketWidthMs}.
     * <p>
     * The row-id-based inner query is what makes this safe under SQLite: we cannot
     * DELETE FROM t WHERE t.rowid IN (SELECT ... FROM t) directly (the reader would see
     * the writes), but wrapping the inner SELECT in another SELECT materializes it into
     * a temp table first. Uses the schema's explicit {@code id INTEGER PRIMARY KEY
     * AUTOINCREMENT} column (see {@code common/src/main/resources/sql/BalanceHistory.sql}).
     */
    private void downsampleBand(long startInclusive, long endExclusive, long bucketWidthMs) throws SQLException {
        String sql =
            "DELETE FROM BalanceHistory WHERE id IN (" +
            "  SELECT id FROM (" +
            "    SELECT id, ROW_NUMBER() OVER (" +
            "      PARTITION BY account_number, item_id, ((time - ?) / ?)" +
            "      ORDER BY time DESC" +
            "    ) AS rn FROM BalanceHistory" +
            "    WHERE time >= ? AND time < ?" +
            "  ) WHERE rn > 1" +
            ")";
        try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql)) {
            stmt.setLong(1, startInclusive);
            stmt.setLong(2, bucketWidthMs);
            stmt.setLong(3, startInclusive);
            stmt.setLong(4, endExclusive);
            stmt.executeUpdate();
            databaseManager.commitTransaction();
        }
    }

    /**
     * Prunes old records so that each (account_number, item_id) pair keeps at most
     * {@code maxRecordsPerItem} entries (newest retained). Runs async on the DB thread.
     * <p>
     * <b>Deprecated as of v2.0.7 (Task #41).</b> The flat cap has been superseded by
     * {@link #applyTieredRetention(long)}, which preserves the full shape of the balance
     * curve at varying resolutions per age band. This method is retained for any
     * third-party callers that reach it directly, but is no longer invoked from the
     * BankSystem backend.
     *
     * @param maxRecordsPerItem max records to keep per account+item. If <= 0, no pruning occurs.
     */
    @Deprecated
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
