package net.kroia.banksystem.banking.ledger;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.banking.bankaccount.ServerBankAccount;
import net.kroia.banksystem.banking.company.Company;
import net.kroia.banksystem.banking.company.CompanyManager;
import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.data.table.TransactionLogManager;
import net.kroia.banksystem.data.table.record.BalanceHistoryRecord;
import net.kroia.banksystem.data.table.record.TransactionLogRecord;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.MoneyFormat;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Tasks #111 / #114 / #115 (v2.2.0) — suspicious-activity checks. Every flag goes through
 * {@link AdminAudit#raise} (SuspicionFlag row + audit-file line + admin alert).
 * <p>
 * Master only. {@link #onLedgerRow} is called from {@link TransactionLedger} for every row it
 * writes; it never throws and only does cheap in-memory checks on the caller's thread. Database
 * reads run on the DB worker through indexed queries (actor/ts, account/ts, account/item/time).
 * Thresholds and toggles: {@link BankSystemModSettings.Audit}.
 */
public final class SuspicionChecks {

    // ---- flag_type codes: append-only, never renumber -------------------------------------

    /**
     * Banknote deposit of at least {@code LAUNDERING_MIN_AMOUNT} while the depositor's own net
     * money withdrawals in the last {@code LAUNDERING_WINDOW_HOURS} are smaller than the deposit
     * (notes from someone else).
     */
    public static final int BANKNOTE_LAUNDERING = 1;
    /**
     * Money withdrawn by one actor from one account in one burst (rows until no new row for the
     * refund grace period, so split across note types counts as one), net of the burst's
     * refunds, reached {@code LARGE_NOTE_WITHDRAWAL_AMOUNT}. One flag per burst.
     */
    public static final int LARGE_NOTE_WITHDRAWAL = 2;
    /**
     * Account wealth (price-weighted WEALTH series) grew by more than {@code WEALTH_JUMP_PERCENT}
     * and more than {@code WEALTH_JUMP_AMOUNT} within the last hour. Market price spikes can
     * trigger it; the details carry the cash delta.
     */
    public static final int WEALTH_JUMP = 3;
    /**
     * Incoming TRANSFER / PAYOUT / DIVIDEND of at least {@code NEW_ACCOUNT_TRANSFER_AMOUNT} into an
     * account younger than {@code NEW_ACCOUNT_AGE_HOURS}.
     */
    public static final int TRANSFER_TO_NEW_ACCOUNT = 4;

    /** Note prefix that terminal and upload-block code writes on banknote rows. */
    public static final String BANKNOTE_NOTE = "banknote ";
    /** Task #114: fixed look-back of the wealth-jump check. */
    public static final long WEALTH_WINDOW_MS = 3_600_000L;
    /** Task #111: quiet time that closes a withdrawal burst (also the wait for its refund row). */
    public static volatile long refundGraceMs = 30_000L;

    private static final long HOUR_MS = 3_600_000L;
    /** Task #115: creation time of legacy accounts (no "createdAt"); -1 = no history = old. */
    private static final Map<Integer, Long> LEGACY_CREATED_AT = new ConcurrentHashMap<>();
    /** Task #111: open withdrawal bursts per actor + account. */
    private static final Map<BurstKey, Burst> BURSTS = new ConcurrentHashMap<>();
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;

    private SuspicionChecks() {}

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
        LEGACY_CREATED_AT.clear();
        BURSTS.clear();
    }

    /** Test hook: forgets cached legacy creation times. */
    public static void clearLegacyCache() {
        LEGACY_CREATED_AT.clear();
    }

    // ---- ledger hook ------------------------------------------------------------------------

    /** Called for every ledger row written on the master. Never throws. */
    public static void onLedgerRow(TransactionLogRecord r) {
        try {
            if (BACKEND_INSTANCES == null || BACKEND_INSTANCES.isSlaveServer || BACKEND_INSTANCES.SERVER_SETTINGS == null
                    || r.amount() == 0 || !isMoney(r.itemId())) return;
            BankSystemModSettings.Audit s = BACKEND_INSTANCES.SERVER_SETTINGS.AUDIT;
            switch (r.type()) {
                case DEPOSIT, UPLOAD_DEPOSIT -> {
                    if (s.LAUNDERING_FLAG_ENABLED.get() && r.actorUuid() != null && isBanknote(r)
                            && r.amount() >= raw(s.LAUNDERING_MIN_AMOUNT.get()))
                        checkLaundering(r, s.LAUNDERING_WINDOW_HOURS.get() * HOUR_MS);
                }
                case WITHDRAW -> {
                    if (s.LARGE_NOTE_WITHDRAWAL_FLAG_ENABLED.get() && r.actorUuid() != null) noteWithdrawal(r);
                }
                case TRANSFER, PAYOUT, DIVIDEND -> {
                    if (s.NEW_ACCOUNT_TRANSFER_FLAG_ENABLED.get() && r.amount() >= raw(s.NEW_ACCOUNT_TRANSFER_AMOUNT.get())
                            && isIncoming(r))
                        checkNewAccount(r, s.NEW_ACCOUNT_AGE_HOURS.get() * HOUR_MS);
                }
                default -> {}
            }
        } catch (RuntimeException ignored) {
            // best-effort, never into the banking path
        }
    }

    // ---- #111 banknote laundering / large note withdrawal --------------------------------------

    private static void checkLaundering(TransactionLogRecord deposit, long windowMs) {
        TransactionLogManager mgr = BACKEND_INSTANCES.TRANSACTION_LOG_MANAGER;
        if (mgr == null) return;
        // The deposit row itself is already queued on the same DB worker, so it is in the result.
        mgr.getByActor(deposit.actorUuid(), deposit.time() - windowMs, 0).thenAccept(rows -> {
            long withdrawn = 0, refunded = 0, noteDeposits = 0;
            for (TransactionLogRecord row : rows) {
                if (row.time() > deposit.time() || !isMoney(row.itemId())) continue;
                switch (row.type()) {
                    case WITHDRAW -> withdrawn += row.amount();
                    case WITHDRAW_REFUND -> refunded += row.amount();
                    case DEPOSIT, UPLOAD_DEPOSIT -> { if (isBanknote(row)) noteDeposits += row.amount(); }
                    default -> {}
                }
            }
            long earlierNoteDeposits = noteDeposits - deposit.amount();
            long net = withdrawn - refunded - earlierNoteDeposits;
            if (net >= deposit.amount() || !enabled(BACKEND_INSTANCES.SERVER_SETTINGS.AUDIT.LAUNDERING_FLAG_ENABLED)) return;
            AdminAudit.raise(deposit.accountNumber(), BANKNOTE_LAUNDERING, 2, deposit.amount(), null,
                    actorText(deposit) + " deposited " + MoneyFormat.format(deposit.amount())
                            + " in banknotes into account " + deposit.accountNumber() + "; own net withdrawals in the last "
                            + (windowMs / HOUR_MS) + "h: " + MoneyFormat.format(net));
        }).exceptionally(t -> null);
    }

    private record BurstKey(java.util.UUID actor, int account) {}

    /** Guarded by {@code BURSTS.compute} on its key. */
    private static final class Burst {
        final long start;
        long last;
        Burst(long t) { start = t; last = t; }
    }

    /** Opens or extends the actor+account burst; only a map update per row, no DB work. */
    private static void noteWithdrawal(TransactionLogRecord w) {
        BurstKey key = new BurstKey(w.actorUuid(), w.accountNumber());
        boolean[] opened = {false};
        BURSTS.compute(key, (k, b) -> {
            if (b == null) {
                opened[0] = true;
                return new Burst(w.time());
            }
            b.last = Math.max(b.last, w.time());
            return b;
        });
        if (opened[0]) scheduleBurstClose(key, refundGraceMs);
    }

    private static void scheduleBurstClose(BurstKey key, long delayMs) {
        CompletableFuture.runAsync(() -> closeBurst(key),
                CompletableFuture.delayedExecutor(Math.max(0L, delayMs), TimeUnit.MILLISECONDS));
    }

    /** Closes the burst once it was quiet for the grace period, else waits for the rest. */
    private static void closeBurst(BurstKey key) {
        try {
            long grace = refundGraceMs;
            long now = System.currentTimeMillis();
            Burst[] closed = {null};
            long[] wait = {0L};
            BURSTS.compute(key, (k, b) -> {
                if (b == null) return null;
                long end = b.last + grace;
                if (now < end) {
                    wait[0] = end - now;
                    return b;
                }
                closed[0] = b;
                return null;
            });
            if (closed[0] != null) judgeBurst(key, closed[0].start, closed[0].last + grace);
            else if (wait[0] > 0) scheduleBurstClose(key, wait[0]);
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }

    /** Sums the burst's WITHDRAW and WITHDRAW_REFUND money rows on its account; flags once. */
    private static void judgeBurst(BurstKey key, long start, long end) {
        if (BACKEND_INSTANCES == null || BACKEND_INSTANCES.SERVER_SETTINGS == null) return;
        BankSystemModSettings.Audit s = BACKEND_INSTANCES.SERVER_SETTINGS.AUDIT;
        TransactionLogManager mgr = BACKEND_INSTANCES.TRANSACTION_LOG_MANAGER;
        if (mgr == null || !s.LARGE_NOTE_WITHDRAWAL_FLAG_ENABLED.get()) return;
        long threshold = raw(s.LARGE_NOTE_WITHDRAWAL_AMOUNT.get());
        mgr.getByActor(key.actor(), start, 0).thenAccept(rows -> {
            long withdrawn = 0, refund = 0;
            int count = 0;
            TransactionLogRecord any = null;
            for (TransactionLogRecord row : rows) {
                if (row.accountNumber() != key.account() || row.time() > end || !isMoney(row.itemId())) continue;
                if (row.type() == net.kroia.banksystem.api.ledger.LedgerType.WITHDRAW) {
                    withdrawn += row.amount();
                    count++;
                    any = row;
                } else if (row.type() == net.kroia.banksystem.api.ledger.LedgerType.WITHDRAW_REFUND) {
                    refund += row.amount();
                }
            }
            long net = withdrawn - Math.min(refund, withdrawn);
            if (any == null || net < threshold) return;
            AdminAudit.raise(key.account(), LARGE_NOTE_WITHDRAWAL, 1, net, null,
                    actorText(any) + " withdrew " + MoneyFormat.format(net) + " from account " + key.account()
                            + " in " + count + " withdrawal row(s)"
                            + (refund > 0 ? " (" + MoneyFormat.format(withdrawn) + " minus a refund of " + MoneyFormat.format(refund) + ")" : ""));
        }).exceptionally(t -> null);
    }

    // ---- #115 transfer to new account ----------------------------------------------------------

    private static boolean isIncoming(TransactionLogRecord r) {
        if (r.type() == net.kroia.banksystem.api.ledger.LedgerType.TRANSFER) return r.amount() > 0;
        // PAYOUT / DIVIDEND rows are unsigned; the outgoing one is on the company's own account.
        if (r.companyId() == null) return false;
        CompanyManager cm = CompanyManager.get();
        Company c = cm == null ? null : cm.getById(r.companyId());
        return c != null && c.getBankAccountNr() != r.accountNumber();
    }

    private static void checkNewAccount(TransactionLogRecord r, long maxAgeMs) {
        if (BACKEND_INSTANCES.SERVER_BANK_MANAGER == null) return;
        if (!(BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().getBankAccount(r.accountNumber()) instanceof ServerBankAccount acc))
            return;
        long createdAt = acc.getCreatedAt();
        if (createdAt > 0) {
            judgeNewAccount(r, createdAt, maxAgeMs);
            return;
        }
        legacyCreatedAt(r.accountNumber(), r.time()).thenAccept(c -> judgeNewAccount(r, c, maxAgeMs))
                .exceptionally(t -> null);
    }

    private static void judgeNewAccount(TransactionLogRecord r, long createdAt, long maxAgeMs) {
        if (createdAt <= 0 || r.time() - createdAt >= maxAgeMs) return;
        // re-checked here: the legacy-age lookup is async and the toggle may have changed
        if (!enabled(BACKEND_INSTANCES.SERVER_SETTINGS.AUDIT.NEW_ACCOUNT_TRANSFER_FLAG_ENABLED)) return;
        long ageMin = Math.max(0, (r.time() - createdAt) / 60_000L);
        AdminAudit.raise(r.accountNumber(), TRANSFER_TO_NEW_ACCOUNT, 1, r.amount(), null,
                r.type().name() + " of " + MoneyFormat.format(r.amount()) + " into account " + r.accountNumber()
                        + " (" + ageMin / 60 + "h " + ageMin % 60 + "m old)"
                        + (r.otherAccount() != null ? " from account " + r.otherAccount() : "") + " by " + actorText(r));
    }

    /**
     * Earliest ledger or balance-history row of a legacy account (before {@code beforeMs}, so the
     * row being judged does not count), cached; -1 when it has none (treated as old).
     */
    private static CompletableFuture<Long> legacyCreatedAt(int account, long beforeMs) {
        Long cached = LEGACY_CREATED_AT.get(account);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        DatabaseManager db = BankSystemModBackend.getDatabaseManager();
        if (db == null) return CompletableFuture.completedFuture(-1L);
        return CompletableFuture.supplyAsync(() -> {
            long first = -1;
            Connection c = db.getConnection();
            try (PreparedStatement a = c.prepareStatement("SELECT MIN(ts) FROM TransactionLog WHERE account_number = ? AND ts < ?");
                 PreparedStatement b = c.prepareStatement("SELECT MIN(time) FROM BalanceHistory WHERE account_number = ?")) {
                a.setInt(1, account);
                a.setLong(2, beforeMs);
                b.setInt(1, account);
                for (PreparedStatement ps : List.of(a, b))
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            long t = rs.getLong(1);
                            if (!rs.wasNull() && t > 0) first = first < 0 ? t : Math.min(first, t);
                        }
                    }
                db.commitTransaction();
            } catch (SQLException e) {
                return -1L;
            }
            LEGACY_CREATED_AT.put(account, first);
            return first;
        }, db.getDatabaseThread());
    }

    // ---- #114 wealth jump ----------------------------------------------------------------------

    /**
     * Checks the given accounts' WEALTH series over the last hour. Runs on the DB worker.
     *
     * @return number of flags raised
     */
    public static CompletableFuture<Integer> sweepWealth(Collection<Integer> accounts, long now) {
        DatabaseManager db = BankSystemModBackend.getDatabaseManager();
        if (db == null || BACKEND_INSTANCES == null || BACKEND_INSTANCES.isSlaveServer || BACKEND_INSTANCES.SERVER_SETTINGS == null)
            return CompletableFuture.completedFuture(0);
        BankSystemModSettings.Audit s = BACKEND_INSTANCES.SERVER_SETTINGS.AUDIT;
        if (!s.WEALTH_JUMP_FLAG_ENABLED.get()) return CompletableFuture.completedFuture(0);
        long minGain = raw(s.WEALTH_JUMP_AMOUNT.get());
        int percent = s.WEALTH_JUMP_PERCENT.get();
        long cooldownSince = now - s.WEALTH_JUMP_COOLDOWN_HOURS.get() * HOUR_MS;
        long from = now - WEALTH_WINDOW_MS;
        short money = MoneyItem.getItemID().getShort();
        List<Integer> list = new ArrayList<>(accounts);
        return CompletableFuture.supplyAsync(() -> {
            int raised = 0;
            Connection c = db.getConnection();
            try (PreparedStatement last = c.prepareStatement("SELECT balance + locked_balance, time FROM BalanceHistory "
                    + "WHERE account_number = ? AND item_id = ? AND time <= ? ORDER BY time DESC LIMIT 1");
                 PreparedStatement firstIn = c.prepareStatement("SELECT balance + locked_balance FROM BalanceHistory "
                         + "WHERE account_number = ? AND item_id = ? AND time > ? ORDER BY time ASC LIMIT 1");
                 PreparedStatement flagged = c.prepareStatement("SELECT 1 FROM SuspicionFlag "
                         + "WHERE account_number = ? AND flag_type = ? AND ts >= ? LIMIT 1")) {
                for (int nr : list) {
                    long[] w = delta(last, firstIn, nr, BalanceHistoryRecord.WEALTH_ITEM_ID, from, now);
                    if (w == null) continue;
                    long gain = w[1] - w[0];
                    if (gain <= minGain) continue;
                    if (w[0] > 0 && (double) gain * 100.0 <= (double) percent * w[0]) continue;
                    flagged.setInt(1, nr);
                    flagged.setInt(2, WEALTH_JUMP);
                    flagged.setLong(3, cooldownSince);
                    try (ResultSet rs = flagged.executeQuery()) {
                        if (rs.next()) continue; // cooldown
                    }
                    long[] cash = delta(last, firstIn, nr, money, from, now);
                    long cashDelta = cash == null ? 0 : cash[1] - cash[0];
                    AdminAudit.raise(nr, WEALTH_JUMP, 1, gain, null,
                            "wealth of account " + nr + " grew from " + MoneyFormat.format(w[0]) + " to " + MoneyFormat.format(w[1])
                                    + " in the last hour; cash change " + MoneyFormat.format(cashDelta)
                                    + " (wealth is price-weighted, so a market price spike can cause this)");
                    raised++;
                }
                db.commitTransaction();
            } catch (SQLException e) {
                if (BACKEND_INSTANCES.LOGGER != null) BACKEND_INSTANCES.LOGGER.warn("[SuspicionChecks] wealth sweep failed: " + e.getMessage());
            }
            return raised;
        }, db.getDatabaseThread());
    }

    /**
     * @return {start, end} of a series over {@code (from, now]}: start = last row at or before
     *         {@code from} (else the first row in the window), end = newest row; null when the
     *         series did not change in the window.
     */
    private static long @Nullable [] delta(PreparedStatement last, PreparedStatement firstIn, int nr, short item,
                                           long from, long now) throws SQLException {
        long end, endTime;
        last.setInt(1, nr);
        last.setShort(2, item);
        last.setLong(3, now);
        try (ResultSet rs = last.executeQuery()) {
            if (!rs.next()) return null;
            end = rs.getLong(1);
            endTime = rs.getLong(2);
        }
        if (endTime <= from) return null;
        Long start = null;
        last.setLong(3, from);
        try (ResultSet rs = last.executeQuery()) {
            if (rs.next()) start = rs.getLong(1);
        }
        if (start == null) {
            firstIn.setInt(1, nr);
            firstIn.setShort(2, item);
            firstIn.setLong(3, from);
            try (ResultSet rs = firstIn.executeQuery()) {
                start = rs.next() ? rs.getLong(1) : end;
            }
        }
        return new long[]{start, end};
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static boolean enabled(net.kroia.modutilities.setting.Setting<Boolean> toggle) {
        return BACKEND_INSTANCES != null && BACKEND_INSTANCES.SERVER_SETTINGS != null && Boolean.TRUE.equals(toggle.get());
    }

    private static boolean isMoney(short itemId) {
        return itemId != 0 && MoneyItem.isMoney(new ItemID(itemId));
    }

    static boolean isBanknote(TransactionLogRecord r) {
        return r.note() != null && r.note().contains(BANKNOTE_NOTE);
    }

    /** Whole money units from a setting to raw ledger units. */
    private static long raw(long whole) {
        return Math.multiplyExact(whole, MoneyFormat.SCALE);
    }

    private static String actorText(TransactionLogRecord r) {
        String name = r.actorName() != null ? r.actorName() : r.source().name();
        return name + (r.actorUuid() != null ? " (" + r.actorUuid() + ")" : "");
    }
}
