package net.kroia.banksystem.banking.company;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.payout.IPayoutManager;
import net.kroia.banksystem.data.table.PayoutHistoryManager;
import net.kroia.banksystem.data.table.record.PayoutHistoryRecord;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Task #45 (v2.0.8) — thin adapter that maps {@link IPayoutManager} onto {@link CompanyManager}
 * (schedule state, master-only) and {@link PayoutHistoryManager} (SQLite history, master-only).
 * <p>
 * On a slave (or before startup finishes) both underlying managers are {@code null} — every
 * operation returns {@link IPayoutManager.OpResult#NOT_MASTER}/an empty future.
 */
public final class PayoutManagerImpl implements IPayoutManager {

    private final BankSystemModBackend.Instances instances;

    public PayoutManagerImpl(BankSystemModBackend.Instances instances) {
        this.instances = instances;
    }

    @Override
    public CreateOutcome createSchedule(int companyId, UUID target, long amount, long intervalTicks,
                                        long nowTick, UUID createdBy) {
        CompanyManager cm = CompanyManager.get();
        if (cm == null) return CreateOutcome.NOT_MASTER;
        CompanyManager.ScheduleCreateOutcome out = cm.createSchedule(companyId, target, amount,
                intervalTicks, nowTick, createdBy);
        return new CreateOutcome(mapResult(out.result),
                out.schedule != null ? out.schedule.getScheduleId() : 0L);
    }

    @Override
    public CreateOutcome createSchedule(int companyId, UUID target, long amount, long intervalTicks,
                                        long nowTick, UUID createdBy, int targetAccountNr,
                                        String targetPlayerName, String targetAccountName,
                                        PayoutSchedule.Mode mode, short currencyItem) {
        CompanyManager cm = CompanyManager.get();
        if (cm == null) return CreateOutcome.NOT_MASTER;
        CompanyManager.ScheduleCreateOutcome out = cm.createSchedule(companyId, target, amount,
                intervalTicks, nowTick, createdBy, targetAccountNr, targetPlayerName,
                targetAccountName, mode, currencyItem);
        return new CreateOutcome(mapResult(out.result),
                out.schedule != null ? out.schedule.getScheduleId() : 0L);
    }

    @Override
    public OpResult updateSchedule(int companyId, long scheduleId, long newAmount, long newIntervalTicks) {
        CompanyManager cm = CompanyManager.get();
        if (cm == null) return OpResult.NOT_MASTER;
        return mapResult(cm.updateSchedule(companyId, scheduleId, newAmount, newIntervalTicks));
    }

    @Override
    public OpResult updateScheduleEx(int companyId, long scheduleId, long newAmount, long newIntervalTicks,
                                     long nowTick, UUID newTarget, int newTargetAccountNr,
                                     String newTargetPlayerName, String newTargetAccountName,
                                     PayoutSchedule.Mode newMode, short newCurrencyItem) {
        CompanyManager cm = CompanyManager.get();
        if (cm == null) return OpResult.NOT_MASTER;
        return mapResult(cm.updateScheduleEx(companyId, scheduleId, newAmount, newIntervalTicks,
                nowTick, newTarget, newTargetAccountNr, newTargetPlayerName, newTargetAccountName,
                newMode, newCurrencyItem));
    }

    @Override
    public OpResult pauseSchedule(int companyId, long scheduleId, boolean paused) {
        CompanyManager cm = CompanyManager.get();
        if (cm == null) return OpResult.NOT_MASTER;
        return mapResult(cm.pauseSchedule(companyId, scheduleId, paused));
    }

    @Override
    public OpResult deleteSchedule(int companyId, long scheduleId) {
        CompanyManager cm = CompanyManager.get();
        if (cm == null) return OpResult.NOT_MASTER;
        return mapResult(cm.deleteSchedule(companyId, scheduleId));
    }

    @Override
    public List<PayoutSchedule> listSchedulesFor(int companyId) {
        CompanyManager cm = CompanyManager.get();
        if (cm == null) return List.of();
        return cm.listSchedulesFor(companyId);
    }

    @Override
    public CompletableFuture<List<PayoutHistoryRecord>> getHistory(long scheduleId, int limit) {
        PayoutHistoryManager hm = instances != null ? instances.PAYOUT_HISTORY_MANAGER : null;
        if (hm == null) return CompletableFuture.completedFuture(List.of());
        return hm.getByScheduleId(scheduleId, limit);
    }

    @Override
    public CompletableFuture<Long> getTotalPaidForSchedule(long scheduleId) {
        PayoutHistoryManager hm = instances != null ? instances.PAYOUT_HISTORY_MANAGER : null;
        if (hm == null) return CompletableFuture.completedFuture(0L);
        return hm.getTotalPaidForSchedule(scheduleId);
    }

    private static OpResult mapResult(CompanyManager.PayoutMutation r) {
        return switch (r) {
            case OK -> OpResult.OK;
            case COMPANY_MISSING -> OpResult.COMPANY_MISSING;
            case SCHEDULE_MISSING -> OpResult.SCHEDULE_MISSING;
            case INVALID_INPUT -> OpResult.INVALID_INPUT;
        };
    }
}
