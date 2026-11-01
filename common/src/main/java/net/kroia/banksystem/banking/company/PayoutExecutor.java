package net.kroia.banksystem.banking.company;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.ISyncServerBank;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.api.event.PayoutExecutedInfo;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.data.table.PayoutHistoryManager;
import net.kroia.banksystem.data.table.record.PayoutHistoryRecord;
import net.kroia.banksystem.data.table.record.TransactionLogRecord;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.util.BankSystemEvents;
import net.kroia.banksystem.util.ItemID;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Task #45 (v2.1.0) — recurring payout tick executor. Master-only.
 * <p>
 * Called every {@link #PAYOUT_TICK_INTERVAL} ticks from
 * {@code BankSystemModBackend.onServerTick}. Iterates every Company via
 * {@code CompanyManager.forEach}; for each schedule that is due and not paused, resolves the
 * source currency bank (company's bank account) and the target's currency bank, attempts
 * a transfer, and writes a {@code PayoutHistory} row with the outcome status. Advances the
 * schedule's {@code nextRunTick} even on failure so a broken schedule does not hammer.
 * <p>
 * Spec A.8/A.9/B.1–B.4 (v2.1.0):
 * <ul>
 *   <li>Typed {@link PayoutFailureReason} set at the actual failure site.</li>
 *   <li>Target player + account names snapshotted into the history row at write time.</li>
 *   <li>Explicit target account (spec B.1) with DEPOSIT-right verification.</li>
 *   <li>{@code DIVIDEND}-mode schedules split the amount across all shareholders
 *       proportional to holdings at fire time (spec B.2).</li>
 *   <li>Non-money currency items (spec B.3) — failure {@code CURRENCY_ITEM_MISSING}.</li>
 *   <li>Failed fires accumulate onto the schedule's missed-payout counter (spec B.4).</li>
 * </ul>
 */
public final class PayoutExecutor {

    /** Interval, in ticks, between payout scheduler evaluations. Default 20 ticks = 1s. */
    public static final long PAYOUT_TICK_INTERVAL = 20L;

    /**
     * Spec A.4 — last server tick observed by the executor. Lets ARRS read handlers
     * translate a schedule's absolute {@code nextRunTick} into a countdown for the UI.
     */
    private static volatile long lastObservedTick = 0L;

    public static long getLastObservedTick() { return lastObservedTick; }

    /** Backend snapshot for logging (BankSystemLogger via Instances). Set on every tick. */
    private static volatile BankSystemModBackend.Instances backend;

    private static void logWarn(String msg) {
        BankSystemModBackend.Instances b = backend;
        if (b != null && b.LOGGER != null) b.LOGGER.warn("[PayoutExecutor] " + msg);
    }

    private static void logInfo(String msg) {
        BankSystemModBackend.Instances b = backend;
        if (b != null && b.LOGGER != null) b.LOGGER.info("[PayoutExecutor] " + msg);
    }

    private PayoutExecutor() {}

    /**
     * Run one evaluation pass. Skips work when master state is not yet available (early
     * boot, shutdown). {@code nowTick} is a monotonic tick counter used against
     * {@link PayoutSchedule#getNextRunTick()}.
     */
    public static void tick(long nowTick, BankSystemModBackend.Instances instances) {
        if (instances == null) return;
        if (instances.isSlaveServer) return;
        backend = instances;
        lastObservedTick = nowTick;
        CompanyManager cm = CompanyManager.get();
        if (cm == null) return;
        if (instances.SERVER_BANK_MANAGER == null) return;
        ISyncServerBankManager bm = instances.SERVER_BANK_MANAGER.getSync();
        if (bm == null) return;

        long nowMs = System.currentTimeMillis();
        PayoutHistoryManager historyManager = instances.PAYOUT_HISTORY_MANAGER;
        BankSystemEvents events = (instances.SERVER_EVENTS instanceof BankSystemEvents bse) ? bse : null;
        List<PayoutHistoryRecord> pendingHistoryRows = new ArrayList<>();
        List<TransactionLogRecord> pendingLedgerRows = new ArrayList<>();
        List<PayoutExecutedInfo> pendingEvents = new ArrayList<>();

        cm.forEach(company -> {
            List<PayoutSchedule> schedules = new ArrayList<>(company.getPayoutSchedules());
            for (PayoutSchedule schedule : schedules) {
                if (schedule.isPaused()) continue;
                if (schedule.getNextRunTick() > nowTick) continue;
                Outcome outcome = executeOne(company, schedule, schedule.getAmount(), bm, nowMs,
                        pendingLedgerRows);
                PayoutHistoryRecord.Status status = outcome.reason == null
                        ? PayoutHistoryRecord.Status.OK : outcome.reason.toStatus();
                pendingHistoryRows.add(PayoutHistoryRecord.of(
                        company.getCompanyId(), schedule.getScheduleId(),
                        company.getBankAccountNr(), schedule.getTargetUUID(),
                        schedule.getAmount(), nowMs, status,
                        outcome.targetPlayerName, outcome.targetAccountName,
                        schedule.getCurrencyItem(), PayoutHistoryRecord.Type.NORMAL));
                pendingEvents.add(new PayoutExecutedInfo(company.getCompanyId(),
                        schedule.getScheduleId(), company.getBankAccountNr(),
                        schedule.getTargetUUID(), schedule.getAmount(), status, nowMs));
                boolean oneTime = schedule.getMode() == PayoutSchedule.Mode.ONE_TIME;
                if (outcome.reason == null && oneTime) {
                    // Feature C — ONE_TIME success: self-delete; no advancement needed.
                    cm.deleteSchedule(company.getCompanyId(), schedule.getScheduleId());
                } else {
                    if (outcome.reason != null && !oneTime) {
                        // Spec B.4 — accumulate the missed execution (not for ONE_TIME).
                        cm.recordMissedExecution(company.getCompanyId(), schedule.getScheduleId(),
                                schedule.getAmount());
                    }
                    // Feature C — ONE_TIME failure: retry at next tick interval (1 s) instead of
                    // the full schedule interval (which could be hours away).
                    long advance = oneTime ? PAYOUT_TICK_INTERVAL : schedule.getIntervalTicks();
                    cm.advanceSchedule(company.getCompanyId(), schedule.getScheduleId(),
                            nowTick + advance);
                }
            }
        });

        if (historyManager != null && !pendingHistoryRows.isEmpty()) {
            historyManager.save(pendingHistoryRows);
        }
        if (instances.TRANSACTION_LOG_MANAGER != null && !pendingLedgerRows.isEmpty()) {
            instances.TRANSACTION_LOG_MANAGER.save(pendingLedgerRows);
        }
        if (events != null) {
            for (PayoutExecutedInfo info : pendingEvents) events.PAYOUT_EXECUTED.notifyListeners(info);
        }
    }

    /**
     * Result of one execution attempt — {@code reason == null} means success.
     * Name snapshots are resolved best-effort even on failure (spec A.9).
     */
    public static final class Outcome {
        public final @Nullable PayoutFailureReason reason;
        public final String targetPlayerName;
        public final String targetAccountName;

        Outcome(@Nullable PayoutFailureReason reason, String targetPlayerName, String targetAccountName) {
            this.reason = reason;
            this.targetPlayerName = targetPlayerName == null ? "" : targetPlayerName;
            this.targetAccountName = targetAccountName == null ? "" : targetAccountName;
        }
    }

    /**
     * Execute a single transfer of {@code amount} for {@code schedule} (mode-aware).
     * Shared between the scheduled tick path and the manual catch-up ARRS handler
     * (spec B.4 — catch-up re-runs the same transfer logic with a caller-chosen amount).
     */
    public static Outcome executeOne(Company company, PayoutSchedule schedule, long amount,
                                     ISyncServerBankManager bm, long nowMs,
                                     List<TransactionLogRecord> ledgerRows) {
        if (schedule.getMode() == PayoutSchedule.Mode.DIVIDEND) {
            return executeDividend(company, schedule, amount, bm, nowMs, ledgerRows);
        }
        return executeFixed(company, schedule, amount, bm, nowMs, ledgerRows);
    }

    /** Resolve the payout currency's ItemID ({@code 0} short → money). */
    private static @Nullable ItemID resolveCurrency(PayoutSchedule schedule) {
        if (schedule.isMoneyCurrency()) return MoneyItem.getItemID();
        // Task #57b — "unset" sentinel means no currency configured: refuse (null → CURRENCY_ITEM_MISSING).
        if (schedule.getCurrencyItem() == Company.CURRENCY_UNSET) return null;
        return new ItemID(schedule.getCurrencyItem());
    }

    private static Outcome executeFixed(Company company, PayoutSchedule schedule, long amount,
                                        ISyncServerBankManager bm, long nowMs,
                                        List<TransactionLogRecord> ledgerRows) {
        UUID target = schedule.getTargetUUID();
        if (target == null) return new Outcome(PayoutFailureReason.TARGET_NOT_FOUND, "", "");
        User targetUser = bm.getUserByUUID(target);
        String playerName = targetUser != null ? targetUser.getName()
                : (schedule.getTargetPlayerName().isEmpty() ? target.toString() : schedule.getTargetPlayerName());

        IServerBankAccount targetAccount;
        if (schedule.getTargetAccountNr() != PayoutSchedule.NO_TARGET_ACCOUNT) {
            targetAccount = bm.getBankAccount(schedule.getTargetAccountNr());
        } else {
            targetAccount = bm.getPersonalBankAccount(target);
        }
        if (targetAccount == null) {
            return new Outcome(PayoutFailureReason.TARGET_NOT_FOUND, playerName,
                    schedule.getTargetAccountName());
        }
        String accountName = targetAccount.getAccountName();
        // Spec B.1 — explicit target accounts require the target to hold DEPOSIT right.
        if (schedule.getTargetAccountNr() != PayoutSchedule.NO_TARGET_ACCOUNT
                && !targetAccount.hasPermission(target, BankPermission.DEPOSIT)) {
            return new Outcome(PayoutFailureReason.TARGET_NO_DEPOSIT_RIGHT, playerName, accountName);
        }

        IServerBankAccount sourceAccount = bm.getBankAccount(company.getBankAccountNr());
        if (sourceAccount == null) return new Outcome(PayoutFailureReason.UNKNOWN, playerName, accountName);

        ItemID currency = resolveCurrency(schedule);
        boolean money = schedule.isMoneyCurrency();
        if (currency == null) {
            return new Outcome(money ? PayoutFailureReason.UNKNOWN
                    : PayoutFailureReason.CURRENCY_ITEM_MISSING, playerName, accountName);
        }
        ISyncServerBank sourceBank = sourceAccount.getBank(currency);
        if (sourceBank == null) {
            return new Outcome(money ? PayoutFailureReason.INSUFFICIENT_FUNDS
                    : PayoutFailureReason.CURRENCY_ITEM_MISSING, playerName, accountName);
        }
        // BUG 4 fix (v2.1.0) — auto-create the receiver's item bank if it doesn't
        // exist yet. Deposit permission was already verified above; a missing item
        // bank on the target should NEVER surface as CURRENCY_ITEM_MISSING.
        ISyncServerBank targetBank = targetAccount.getBank(currency);
        if (targetBank == null) {
            targetBank = targetAccount.getOrCreateBank(currency);
            if (targetBank == null) {
                return new Outcome(PayoutFailureReason.TARGET_NOT_FOUND, playerName, accountName);
            }
        }
        if (sourceBank.getBalance() < amount) {
            return new Outcome(money ? PayoutFailureReason.INSUFFICIENT_FUNDS
                    : PayoutFailureReason.CURRENCY_ITEM_MISSING, playerName, accountName);
        }
        BankStatus status = sourceBank.transfer(amount, targetBank);
        if (status != BankStatus.SUCCESS) {
            return new Outcome(money ? PayoutFailureReason.INSUFFICIENT_FUNDS
                    : PayoutFailureReason.CURRENCY_ITEM_MISSING, playerName, accountName);
        }
        // TransactionLog PAYOUT rows — one on the source account (out), one on the target (in).
        short currencyShort = currency.getShort();
        ledgerRows.add(new TransactionLogRecord(TransactionLogRecord.UNSAVED_ID,
                company.getBankAccountNr(), null, TransactionLogRecord.Kind.PAYOUT, currencyShort,
                amount, targetAccount.getAccountNumber(), company.getCompanyId(),
                nowMs, null));
        ledgerRows.add(new TransactionLogRecord(TransactionLogRecord.UNSAVED_ID,
                targetAccount.getAccountNumber(), null, TransactionLogRecord.Kind.PAYOUT, currencyShort,
                amount, company.getBankAccountNr(), company.getCompanyId(),
                nowMs, null));
        return new Outcome(null, playerName, accountName);
    }

    /**
     * Spec B.2 — dividend-mode execution: {@code amount} is a total, split across all
     * accounts holding the company's stamped share proportional to their holdings at
     * fire time (mirrors {@link net.kroia.banksystem.banking.share.DividendPayer}'s
     * snapshot-then-transfer pattern, but with a fixed total instead of per-share).
     */
    private static Outcome executeDividend(Company company, PayoutSchedule schedule, long amount,
                                           ISyncServerBankManager bm, long nowMs,
                                           List<TransactionLogRecord> ledgerRows) {
        ItemID shareItemId = resolveShareItemID(company.getCompanyId());
        String dividendName = "Shareholders";
        if (shareItemId == null) {
            return new Outcome(PayoutFailureReason.TARGET_NOT_FOUND, dividendName, "");
        }
        int companyAccountNr = company.getBankAccountNr();
        Set<Integer> holderAccounts = bm.listAccountsHolding(shareItemId);
        List<long[]> snapshots = new ArrayList<>(); // {accountNr, shares}
        long totalShares = 0L;
        for (int accountNr : holderAccounts) {
            if (accountNr == companyAccountNr) continue;
            IServerBankAccount account = bm.getBankAccount(accountNr);
            if (account == null) continue;
            ISyncServerBank shareBank = account.getBank(shareItemId);
            if (shareBank == null) continue;
            long shares = shareBank.getTotalBalance();
            if (shares <= 0L) continue;
            snapshots.add(new long[]{accountNr, shares});
            totalShares += shares;
        }
        if (totalShares == 0L || snapshots.isEmpty()) {
            return new Outcome(PayoutFailureReason.TARGET_NOT_FOUND, dividendName, "");
        }

        IServerBankAccount sourceAccount = bm.getBankAccount(companyAccountNr);
        if (sourceAccount == null) return new Outcome(PayoutFailureReason.UNKNOWN, dividendName, "");
        ItemID currency = resolveCurrency(schedule);
        boolean money = schedule.isMoneyCurrency();
        if (currency == null) {
            return new Outcome(money ? PayoutFailureReason.UNKNOWN
                    : PayoutFailureReason.CURRENCY_ITEM_MISSING, dividendName, "");
        }
        ISyncServerBank sourceBank = sourceAccount.getBank(currency);
        if (sourceBank == null || sourceBank.getBalance() < amount) {
            return new Outcome(money ? PayoutFailureReason.INSUFFICIENT_FUNDS
                    : PayoutFailureReason.CURRENCY_ITEM_MISSING, dividendName, "");
        }

        short currencyShort = currency.getShort();
        int paidHolders = 0;
        long paidTotal = 0L;
        for (long[] snap : snapshots) {
            int accountNr = (int) snap[0];
            long shares = snap[1];
            long payAmount;
            try {
                payAmount = Math.multiplyExact(amount, shares) / totalShares;
            } catch (ArithmeticException e) {
                // Overflow-safe fallback: proportional via double, floored.
                payAmount = (long) Math.floor((double) amount * ((double) shares / (double) totalShares));
            }
            if (payAmount <= 0L) {
                logWarn("dividend schedule " + schedule.getScheduleId() + " company "
                        + company.getCompanyId() + ": holder account " + accountNr
                        + " share " + shares + "/" + totalShares
                        + " rounds to 0 for amount " + amount + " — skipped");
                continue;
            }
            IServerBankAccount targetAccount = bm.getBankAccount(accountNr);
            if (targetAccount == null) continue;
            // BUG 4 fix (v2.1.0) — auto-create the holder's item bank on demand so
            // dividend distribution can always deposit to a valid target.
            ISyncServerBank targetBank = targetAccount.getBank(currency);
            if (targetBank == null) {
                targetBank = targetAccount.getOrCreateBank(currency);
            }
            if (targetBank == null) {
                logWarn("dividend schedule " + schedule.getScheduleId() + " company "
                        + company.getCompanyId() + ": holder account " + accountNr
                        + " could not create a bank for the payout currency — skipped");
                continue;
            }
            BankStatus status = sourceBank.transfer(payAmount, targetBank);
            if (status != BankStatus.SUCCESS) {
                logWarn("dividend schedule " + schedule.getScheduleId() + " company "
                        + company.getCompanyId() + ": transfer of " + payAmount
                        + " to account " + accountNr + " failed with status " + status);
                continue;
            }
            paidHolders++;
            paidTotal += payAmount;
            ledgerRows.add(new TransactionLogRecord(TransactionLogRecord.UNSAVED_ID,
                    companyAccountNr, null, TransactionLogRecord.Kind.DIVIDEND, currencyShort,
                    payAmount, accountNr, company.getCompanyId(), nowMs, null));
            ledgerRows.add(new TransactionLogRecord(TransactionLogRecord.UNSAVED_ID,
                    accountNr, null, TransactionLogRecord.Kind.DIVIDEND, currencyShort,
                    payAmount, companyAccountNr, company.getCompanyId(), nowMs, null));
        }
        // BUG 4 fix (v2.1.0) — a run that moved no money must not report success.
        // Previously every transfer inside the loop could silently skip (rounding
        // to 0, missing target bank, failed transfer) and the schedule still wrote
        // an OK history row while no balance changed anywhere.
        if (paidHolders == 0) {
            logWarn("dividend schedule " + schedule.getScheduleId() + " company "
                    + company.getCompanyId() + ": no holder received a payment (amount="
                    + amount + ", totalShares=" + totalShares + ") — reporting failure");
            return new Outcome(money ? PayoutFailureReason.INSUFFICIENT_FUNDS
                    : PayoutFailureReason.CURRENCY_ITEM_MISSING, dividendName, "");
        }
        logInfo("dividend schedule " + schedule.getScheduleId() + " company "
                + company.getCompanyId() + ": paid " + paidTotal + " to " + paidHolders + " holder(s)");
        // Task #52 (v2.1.0) — record the scheduled dividend event for history.
        try {
            BankSystemModBackend.Instances b = backend;
            if (b != null && b.DIVIDEND_HISTORY_STORE != null) {
                b.DIVIDEND_HISTORY_STORE.insert(new DividendEvent(
                        company.getCompanyId(), (int) schedule.getScheduleId(), nowMs,
                        currencyShort, 0L, paidTotal, paidHolders, "SCHEDULE"));
            }
        } catch (Throwable t) {
            logWarn("Failed to record dividend history: " + t.getMessage());
        }
        return new Outcome(null, dividendName, "");
    }

    /**
     * Resolve the stamped-share ItemID bound to {@code companyId}. BUG 4 unification —
     * uses the same direct template lookup as the (working) manual
     * {@link net.kroia.banksystem.banking.share.DividendPayer} instead of reverse-iterating
     * the ItemID registry.
     */
    private static @Nullable ItemID resolveShareItemID(int companyId) {
        net.minecraft.world.item.ItemStack template =
                net.kroia.banksystem.minecraft.item.custom.share.StampedShareItem.ofCompany(
                        net.kroia.banksystem.minecraft.item.BankSystemItems.STAMPED_SHARE.get(), companyId);
        ItemID shareItemId = net.kroia.banksystem.util.ItemIDManager.getItemID(template);
        if (shareItemId == null || !shareItemId.isValid()) return null;
        return shareItemId;
    }
}
