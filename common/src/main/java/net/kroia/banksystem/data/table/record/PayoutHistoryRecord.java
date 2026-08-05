package net.kroia.banksystem.data.table.record;

import java.util.UUID;

/**
 * Task #45 (v2.0.8) — one row in the {@code PayoutHistory} SQLite table.
 * <p>
 * Written by {@code PayoutExecutor} on every payout tick attempt (success or
 * failure). {@link Status} distinguishes the outcome — a failed payout still
 * writes a row so operators can audit misconfigured schedules.
 */
public record PayoutHistoryRecord(
        long id,
        int companyId,
        long scheduleId,
        int sourceAccount,
        UUID targetUuid,
        long amount,
        long time,
        Status status
) {
    public static final long UNSAVED_ID = -1L;

    public enum Status {
        OK,
        INSUFFICIENT_FUNDS,
        TARGET_MISSING
    }

    public static PayoutHistoryRecord of(int companyId, long scheduleId, int sourceAccount,
                                         UUID targetUuid, long amount, long time, Status status) {
        return new PayoutHistoryRecord(UNSAVED_ID, companyId, scheduleId, sourceAccount,
                targetUuid, amount, time, status);
    }
}
