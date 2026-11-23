package net.kroia.banksystem.banking.company;

import net.kroia.banksystem.api.bankmanager.IServerBankManager;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.util.ItemID;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v2.1.0 — Static SQL helpers for the Statistics tab of CompanyManagementScreen.
 * All methods are safe to call from any thread; they only read from the DB connection.
 *
 * Queries filter by account_number (the company's linked bank account) rather than
 * company_id, because regular deposits/withdrawals are stored with company_id = NULL.
 */
public final class CompanyStatsQuery {

    private CompanyStatsQuery() {}

    /**
     * BS-230: SQL condition for a row that takes value out of the account. Signed rows (TRANSFER,
     * and PAYOUT / DIVIDEND since BS-233) go by their sign; types stored unsigned that always leave
     * the account count by type; legacy unsigned PAYOUT / DIVIDEND rows (no balance_after) count as
     * paid out, as before. Legacy TRANSFER_OUT rows were migrated to negative TRANSFER rows.
     */
    static final String IS_OUT = "(amount < 0 OR type_code IN (" + codes(LedgerType.WITHDRAW, LedgerType.ADMIN_REMOVE,
            LedgerType.BANK_CLOSED, LedgerType.CONVERTER_WITHDRAW, LedgerType.DOWNLOAD_WITHDRAW, LedgerType.CRAFT_CONSUME,
            LedgerType.SHARE_STAMP, LedgerType.ACCOUNT_DELETE_PAYOUT, LedgerType.ADMIN_DISALLOW_ITEM,
            LedgerType.ACCOUNT_DELETE_LOSS) + ") OR (type_code IN (" + codes(LedgerType.PAYOUT, LedgerType.DIVIDEND)
            + ") AND balance_after IS NULL))";
    /** BS-267: marker rows that move no balance; left out of earnings and spendings. */
    private static final String NO_MARKER = " AND type_code <> " + LedgerType.REFUND_LOST.code();

    private static String codes(LedgerType... types) {
        StringBuilder sb = new StringBuilder();
        for (LedgerType t : types) sb.append(sb.isEmpty() ? "" : ",").append(t.code());
        return sb.toString();
    }

    public record CashflowBucket(long bucketStart, long earnings, long spendings) {}

    public record CompanyHeadlineMetrics(long totalEarnings, long totalSpendings, long netCashflow,
                                         int missedPayoutCount, long missedPayoutAmount) {}

    public record ShareholderEntry(int accountNr, String accountName, long shares, float pct) {}

    /**
     * Returns per-bucket cashflow data for the company's linked bank account over the given window.
     * Buckets are aligned to fromMs with width bucketMs.
     *
     * @param accountNr the company's linked bank account number
     */
    public static List<CashflowBucket> getCashflowSeries(
            Connection conn, int accountNr, short itemIdShort, long fromMs, long toMs, long bucketMs) {
        List<CashflowBucket> result = new ArrayList<>();
        if (conn == null || bucketMs <= 0) return result;
        // Earnings = anything NOT classified as a spending kind.
        // This catches all deposit paths regardless of which exact kind they use.
        String sql = "SELECT CAST((ts - ?) / ? AS INTEGER) AS bucket, " +
                "SUM(CASE WHEN " + IS_OUT + " THEN 0 ELSE ABS(amount) END) AS earnings, " +
                "SUM(CASE WHEN " + IS_OUT + " THEN ABS(amount) ELSE 0 END) AS spendings " +
                "FROM TransactionLog " +
                "WHERE account_number = ? AND item_id = ? AND ts >= ? AND ts <= ?" + NO_MARKER + " " +
                "GROUP BY bucket ORDER BY bucket ASC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, fromMs);
            ps.setLong(2, bucketMs);
            ps.setInt(3, accountNr);
            ps.setShort(4, itemIdShort);
            ps.setLong(5, fromMs);
            ps.setLong(6, toMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long bucket = rs.getLong("bucket");
                    long earnings = rs.getLong("earnings");
                    long spendings = rs.getLong("spendings");
                    result.add(new CashflowBucket(fromMs + bucket * bucketMs, earnings, spendings));
                }
            }
        } catch (SQLException e) {
            // fail-open
        }
        return result;
    }

    /**
     * Returns headline totals (earnings, spendings) for the company's linked account since fromMs.
     *
     * @param accountNr the company's linked bank account number
     */
    public static CompanyHeadlineMetrics getHeadlineMetrics(
            Connection conn, int accountNr, short itemIdShort, long fromMs) {
        if (conn == null) return new CompanyHeadlineMetrics(0L, 0L, 0L, 0, 0L);
        String sql = "SELECT " +
                "SUM(CASE WHEN " + IS_OUT + " THEN 0 ELSE ABS(amount) END) AS earn, " +
                "SUM(CASE WHEN " + IS_OUT + " THEN ABS(amount) ELSE 0 END) AS spend " +
                "FROM TransactionLog WHERE account_number = ? AND item_id = ? AND ts >= ?" + NO_MARKER;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, accountNr);
            ps.setShort(2, itemIdShort);
            ps.setLong(3, fromMs);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long earn = rs.getLong("earn");
                    long spend = rs.getLong("spend");
                    return new CompanyHeadlineMetrics(earn, spend, earn - spend, 0, 0L);
                }
            }
        } catch (SQLException e) {
            // fail-open
        }
        return new CompanyHeadlineMetrics(0L, 0L, 0L, 0, 0L);
    }

    /** Every registered stamped-share ItemID that belongs to {@code companyId} (reverse lookup). */
    public static List<ItemID> shareItemIds(int companyId) {
        List<ItemID> ids = new ArrayList<>();
        for (ItemID id : net.kroia.banksystem.util.ItemIDManager.getItemIDMap().keySet()) {
            Integer cid = net.kroia.banksystem.minecraft.item.custom.share.StampedShareItem.getCompanyIdForItemID(id);
            if (cid != null && cid == companyId) ids.add(id);
        }
        return ids;
    }

    /** BS-238: free + locked raw share balance per account, summed over every share ItemID (variant) of the company. */
    public static Map<Integer, Long> sharesRawPerAccount(net.kroia.banksystem.api.bankmanager.ISyncServerBankManager bm, int companyId) {
        return sharesRawPerAccount(bm, shareItemIds(companyId));
    }

    /** Same as {@link #sharesRawPerAccount(net.kroia.banksystem.api.bankmanager.ISyncServerBankManager, int)} for the given ItemIDs. */
    public static Map<Integer, Long> sharesRawPerAccount(net.kroia.banksystem.api.bankmanager.ISyncServerBankManager bm, List<ItemID> shareIds) {
        Map<Integer, Long> perAccount = new LinkedHashMap<>();
        for (ItemID shareItemId : shareIds) {
            for (int accountNr : bm.listAccountsHolding(shareItemId)) {
                IServerBankAccount acct = bm.getBankAccount(accountNr);
                net.kroia.banksystem.api.bank.IServerBank bank = acct == null ? null : acct.getBank(shareItemId);
                if (bank != null) perAccount.merge(accountNr, bank.getTotalBalance(), Long::sum);
            }
        }
        return perAccount;
    }

    /**
     * Returns top N shareholders sorted by shares descending.
     */
    public static List<ShareholderEntry> getTopShareholders(
            IServerBankManager bm, int companyId, long totalIssued, int limit) {
        List<ShareholderEntry> result = new ArrayList<>();
        if (bm == null) return result;

        // BS-230: every share ItemID of the company (variants), free + locked, summed per account.
        Map<Integer, Long> perAccount = sharesRawPerAccount(bm, companyId);
        List<long[]> entries = new ArrayList<>();
        for (Map.Entry<Integer, Long> e : perAccount.entrySet())
            if (e.getValue() > 0L) entries.add(new long[]{e.getKey(), e.getValue()});

        entries.sort((a, b) -> Long.compare(b[1], a[1]));

        long base = totalIssued > 0L ? totalIssued : 1L;
        int count = Math.min(limit, entries.size());
        for (int i = 0; i < count; i++) {
            int acctNr = (int) entries.get(i)[0];
            long shares = entries.get(i)[1];
            IServerBankAccount acct = bm.getBankAccount(acctNr);
            String name = acct != null ? acct.getAccountName() : String.valueOf(acctNr);
            float pct = (float)(shares / net.kroia.banksystem.BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR) / (float) Math.max(1L, base);
            result.add(new ShareholderEntry(acctNr, name, shares, pct));
        }
        return result;
    }

    /**
     * Estimates days until the company account runs out of money.
     *
     * @param accountNr the company's linked bank account number
     * @param itemIdShort the company currency; only its rows count as outflow
     * @param scheduleObligationPerDay snapshot from {@link #scheduleObligationPerDay}, taken on the
     *        master thread so the DB worker never reads live Company state (Issue #93)
     * @return days to insolvency (floored), or -1 if outflow is zero (safe)
     */
    public static long getDaysToInsolvency(Connection conn, int accountNr, short itemIdShort, double scheduleObligationPerDay, long currentBalance) {
        double avgDailyOutflow = 0.0;
        if (conn != null) {
            long thirtyDaysMs = 30L * 86_400_000L;
            long fromMs = System.currentTimeMillis() - thirtyDaysMs;
            String sql = "SELECT SUM(CASE WHEN " + IS_OUT + " THEN ABS(amount) ELSE 0 END) AS total " +
                    "FROM TransactionLog WHERE account_number = ? AND item_id = ? AND ts >= ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, accountNr);
                ps.setShort(2, itemIdShort);
                ps.setLong(3, fromMs);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long total = rs.getLong("total");
                        avgDailyOutflow = total / 30.0;
                    }
                }
            } catch (SQLException e) {
                // fail-open
            }
        }

        avgDailyOutflow += scheduleObligationPerDay;

        if (avgDailyOutflow <= 0.0) return -1L;
        return (long) (currentBalance / avgDailyOutflow);
    }

    /** Daily payout obligation of the company's active schedules. Call on the thread that owns Company state. */
    public static double scheduleObligationPerDay(Company company) {
        double scheduleObl = 0.0;
        for (PayoutSchedule s : company.getPayoutSchedules()) {
            if (s.isPaused()) continue;
            long intervalTicks = s.getIntervalTicks();
            if (intervalTicks <= 0L) continue;
            scheduleObl += s.getAmount() / (intervalTicks / 24000.0);
        }
        return scheduleObl;
    }
}
