package net.kroia.banksystem.banking.company;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.ISyncServerBank;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.api.event.PayoutExecutedInfo;
import net.kroia.banksystem.data.table.PayoutHistoryManager;
import net.kroia.banksystem.data.table.record.PayoutHistoryRecord;
import net.kroia.banksystem.data.table.record.TransactionLogRecord;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.util.BankSystemEvents;
import net.kroia.banksystem.util.ItemID;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Task #45 (v2.0.8) — recurring payout tick executor. Master-only.
 * <p>
 * Called every {@link #PAYOUT_TICK_INTERVAL} ticks from
 * {@code BankSystemModBackend.onServerTick}. Iterates every Company via
 * {@code CompanyManager.forEach}; for each schedule that is due and not paused, resolves the
 * source money bank (company's bank account) and the target's personal money bank, attempts
 * a transfer, and writes a {@code PayoutHistory} row with the outcome status. Advances the
 * schedule's {@code nextRunTick} even on failure so a broken schedule does not hammer.
 * <p>
 * Bank ops happen on the server thread (this method is invoked on server thread); SQL runs
 * async on the {@code banksystem-db-worker} executor via {@link PayoutHistoryManager}.
 */
public final class PayoutExecutor {

    /** Interval, in ticks, between payout scheduler evaluations. Default 20 ticks = 1s. */
    public static final long PAYOUT_TICK_INTERVAL = 20L;

    private PayoutExecutor() {}

    /**
     * Run one evaluation pass. Skips work when master state is not yet available (early
     * boot, shutdown). {@code nowTick} is a monotonic tick counter used against
     * {@link PayoutSchedule#getNextRunTick()}.
     */
    public static void tick(long nowTick, BankSystemModBackend.Instances instances) {
        if (instances == null) return;
        if (instances.isSlaveServer) return;
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
                PayoutHistoryRecord.Status status = executeOne(company, schedule, bm, nowMs,
                        pendingLedgerRows);
                pendingHistoryRows.add(PayoutHistoryRecord.of(
                        company.getCompanyId(), schedule.getScheduleId(),
                        company.getBankAccountNr(), schedule.getTargetUUID(),
                        schedule.getAmount(), nowMs, status));
                pendingEvents.add(new PayoutExecutedInfo(company.getCompanyId(),
                        schedule.getScheduleId(), company.getBankAccountNr(),
                        schedule.getTargetUUID(), schedule.getAmount(), status, nowMs));
                cm.advanceSchedule(company.getCompanyId(), schedule.getScheduleId(),
                        nowTick + schedule.getIntervalTicks());
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

    private static PayoutHistoryRecord.Status executeOne(Company company, PayoutSchedule schedule,
                                                         ISyncServerBankManager bm, long nowMs,
                                                         List<TransactionLogRecord> ledgerRows) {
        UUID target = schedule.getTargetUUID();
        if (target == null) return PayoutHistoryRecord.Status.TARGET_MISSING;
        IServerBankAccount targetAccount = bm.getPersonalBankAccount(target);
        if (targetAccount == null) return PayoutHistoryRecord.Status.TARGET_MISSING;
        IServerBankAccount sourceAccount = bm.getBankAccount(company.getBankAccountNr());
        if (sourceAccount == null) return PayoutHistoryRecord.Status.TARGET_MISSING;
        ItemID moneyId = MoneyItem.getItemID();
        ISyncServerBank sourceBank = sourceAccount.getBank(moneyId);
        ISyncServerBank targetBank = targetAccount.getBank(moneyId);
        if (sourceBank == null || targetBank == null) {
            return PayoutHistoryRecord.Status.TARGET_MISSING;
        }
        BankStatus status = sourceBank.transfer(schedule.getAmount(), targetBank);
        if (status != BankStatus.SUCCESS) {
            return PayoutHistoryRecord.Status.INSUFFICIENT_FUNDS;
        }
        // TransactionLog PAYOUT rows — one on the source account (out), one on the target (in).
        short moneyShort = moneyId.getShort();
        ledgerRows.add(new TransactionLogRecord(TransactionLogRecord.UNSAVED_ID,
                company.getBankAccountNr(), null, TransactionLogRecord.Kind.PAYOUT, moneyShort,
                schedule.getAmount(), targetAccount.getAccountNumber(), company.getCompanyId(),
                nowMs, null));
        ledgerRows.add(new TransactionLogRecord(TransactionLogRecord.UNSAVED_ID,
                targetAccount.getAccountNumber(), null, TransactionLogRecord.Kind.PAYOUT, moneyShort,
                schedule.getAmount(), company.getBankAccountNr(), company.getCompanyId(),
                nowMs, null));
        return PayoutHistoryRecord.Status.OK;
    }
}
