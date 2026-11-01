package net.kroia.banksystem.banking.company;

import net.kroia.banksystem.api.bankmanager.IServerBankManager;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * v2.1.0 — Static SQL helpers for the Statistics tab of CompanyManagementScreen.
 * All methods are safe to call from any thread; they only read from the DB connection.
 *
 * Queries filter by account_number (the company's linked bank account) rather than
 * company_id, because regular deposits/withdrawals are stored with company_id = NULL.
 */
public final class CompanyStatsQuery {

    private CompanyStatsQuery() {}

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
                "SUM(CASE WHEN kind IN ('PAYOUT','DIVIDEND','WITHDRAW','TRANSFER_OUT','SHARE_STAMP') THEN 0 ELSE ABS(amount) END) AS earnings, " +
                "SUM(CASE WHEN kind IN ('PAYOUT','DIVIDEND','WITHDRAW','TRANSFER_OUT','SHARE_STAMP') THEN ABS(amount) ELSE 0 END) AS spendings " +
                "FROM TransactionLog " +
                "WHERE account_number = ? AND item_id = ? AND ts >= ? AND ts <= ? " +
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
                "SUM(CASE WHEN kind IN ('PAYOUT','DIVIDEND','WITHDRAW','TRANSFER_OUT','SHARE_STAMP') THEN 0 ELSE ABS(amount) END) AS earn, " +
                "SUM(CASE WHEN kind IN ('PAYOUT','DIVIDEND','WITHDRAW','TRANSFER_OUT','SHARE_STAMP') THEN ABS(amount) ELSE 0 END) AS spend " +
                "FROM TransactionLog WHERE account_number = ? AND item_id = ? AND ts >= ?";
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

    /**
     * Returns top N shareholders sorted by shares descending.
     */
    public static List<ShareholderEntry> getTopShareholders(
            IServerBankManager bm, int companyId, long totalIssued, int limit) {
        List<ShareholderEntry> result = new ArrayList<>();
        if (bm == null) return result;

        net.kroia.banksystem.util.ItemID shareItemId = null;
        for (var e : net.kroia.banksystem.util.ItemIDManager.getItemIDMap().entrySet()) {
            Integer cid = net.kroia.banksystem.minecraft.item.custom.share.StampedShareItem
                    .getCompanyIdForItemID(e.getKey());
            if (cid != null && cid == companyId) {
                shareItemId = e.getKey();
                break;
            }
        }
        if (shareItemId == null) return result;

        java.util.Set<Integer> accountNrs = bm.listAccountsHolding(shareItemId);
        if (accountNrs == null || accountNrs.isEmpty()) return result;

        List<long[]> entries = new ArrayList<>();
        for (int accountNr : accountNrs) {
            IServerBankAccount acct = bm.getBankAccount(accountNr);
            if (acct == null) continue;
            net.kroia.banksystem.api.bank.IServerBank bank = acct.getBank(shareItemId);
            if (bank == null) continue;
            long shares = bank.getBalance();
            if (shares <= 0L) continue;
            entries.add(new long[]{accountNr, shares});
        }

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
     * @return days to insolvency (floored), or -1 if outflow is zero (safe)
     */
    public static long getDaysToInsolvency(Connection conn, int accountNr, Company company, long currentBalance) {
        if (company == null) return -1L;

        double avgDailyOutflow = 0.0;
        if (conn != null) {
            long thirtyDaysMs = 30L * 86_400_000L;
            long fromMs = System.currentTimeMillis() - thirtyDaysMs;
            String sql = "SELECT SUM(CASE WHEN kind IN ('PAYOUT','DIVIDEND','WITHDRAW','TRANSFER_OUT','SHARE_STAMP') THEN ABS(amount) ELSE 0 END) AS total " +
                    "FROM TransactionLog WHERE account_number = ? AND ts >= ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, accountNr);
                ps.setLong(2, fromMs);
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

        double scheduleObl = 0.0;
        for (PayoutSchedule s : company.getPayoutSchedules()) {
            if (s.isPaused()) continue;
            long intervalTicks = s.getIntervalTicks();
            if (intervalTicks <= 0L) continue;
            double perDay = s.getAmount() / (intervalTicks / 24000.0);
            scheduleObl += perDay;
        }
        avgDailyOutflow += scheduleObl;

        if (avgDailyOutflow <= 0.0) return -1L;
        return (long) (currentBalance / avgDailyOutflow);
    }
}
