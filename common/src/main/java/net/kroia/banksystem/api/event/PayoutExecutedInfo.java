package net.kroia.banksystem.api.event;

import net.kroia.banksystem.data.table.record.PayoutHistoryRecord;

import java.util.UUID;

/**
 * Task #45 (v2.1.0) — payload for {@code IBankSystemEvents.getPayoutExecutedEvent()}.
 * <p>
 * Fired on the master server every time {@code PayoutExecutor} evaluates a schedule —
 * success or failure. Downstream mods listen to build reactive UIs, badges, or metrics.
 * The event fires on the server thread AFTER the bank transfer (if any) and the SQL
 * write have been dispatched.
 * <p>
 * {@code status} is {@link PayoutHistoryRecord.Status}, which lives outside {@code api/}:
 * compare by constant, not by ordinal; values are only appended. {@code amount} is raw units
 * of the schedule's item.
 */
public record PayoutExecutedInfo(
        int companyId,
        long scheduleId,
        int sourceAccount,
        UUID targetUuid,
        long amount,
        PayoutHistoryRecord.Status status,
        long time
) {
}
