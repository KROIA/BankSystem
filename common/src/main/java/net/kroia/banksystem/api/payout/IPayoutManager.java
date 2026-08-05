package net.kroia.banksystem.api.payout;

import net.kroia.banksystem.banking.company.PayoutSchedule;
import net.kroia.banksystem.data.table.record.PayoutHistoryRecord;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Task #45 (v2.0.8) — public payout API. Reachable via {@code BankSystemAPI.getPayoutManager()}.
 * Master-only surface — calling on a slave returns fail-closed defaults (empty lists,
 * {@link OpResult#COMPANY_MISSING}). Downstream mods and UI code use this interface — never
 * the concrete manager.
 */
public interface IPayoutManager {

    enum OpResult { OK, COMPANY_MISSING, SCHEDULE_MISSING, INVALID_INPUT, NOT_MASTER }

    record CreateOutcome(OpResult result, long scheduleId) {
        public static final CreateOutcome NOT_MASTER = new CreateOutcome(OpResult.NOT_MASTER, 0L);
    }

    /**
     * @param companyId       Company owning the schedule.
     * @param target          worker to pay.
     * @param amount          per-tick amount in fixed-point money units. Must be {@code > 0}.
     * @param intervalTicks   ticks between payouts. Hard-floor 20 (1s) enforced.
     * @param nowTick         current server tick (used to compute {@code nextRunTick}).
     * @param createdBy       actor UUID (nullable).
     */
    CreateOutcome createSchedule(int companyId, UUID target, long amount, long intervalTicks,
                                 long nowTick, UUID createdBy);

    OpResult updateSchedule(int companyId, long scheduleId, long newAmount, long newIntervalTicks);

    OpResult pauseSchedule(int companyId, long scheduleId, boolean paused);

    OpResult deleteSchedule(int companyId, long scheduleId);

    /** Snapshot of the schedules currently attached to {@code companyId}. */
    List<PayoutSchedule> listSchedulesFor(int companyId);

    /** Last {@code limit} history rows for {@code scheduleId}, newest-first. */
    CompletableFuture<List<PayoutHistoryRecord>> getHistory(long scheduleId, int limit);

    /** Total OK-status amount paid on this schedule. */
    CompletableFuture<Long> getTotalPaidForSchedule(long scheduleId);
}
