package net.kroia.banksystem.api.event;

import net.kroia.banksystem.data.table.record.PayoutHistoryRecord;

import java.util.UUID;

/**
 * Task #45 (v2.0.8) — payload for {@code IBankSystemEvents.getPayoutExecutedEvent()}.
 * <p>
 * Fired on the master server every time {@code PayoutExecutor} evaluates a schedule —
 * success or failure. Downstream mods listen to build reactive UIs, badges, or metrics.
 * The event fires on the server thread AFTER the bank transfer (if any) and the SQL
 * write have been dispatched.
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
