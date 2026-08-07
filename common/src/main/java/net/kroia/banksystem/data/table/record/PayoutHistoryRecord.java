package net.kroia.banksystem.data.table.record;

import java.util.UUID;

/**
 * Task #45 (v2.0.8) — one row in the {@code PayoutHistory} SQLite table.
 * <p>
 * Written by {@code PayoutExecutor} on every payout tick attempt (success or
 * failure). {@link Status} distinguishes the outcome — a failed payout still
 * writes a row so operators can audit misconfigured schedules.
 * <p>
 * Spec A.9 / B.3 / B.4 (v2.0.8) additions — all load-compatible with existing
 * databases; missing columns default at read time:
 * <ul>
 *   <li>{@code targetPlayerName} / {@code targetAccountName} — snapshots resolved
 *       at write time (player names change; do NOT re-resolve on read). Default {@code ""}.</li>
 *   <li>{@code currencyItem} — the payout currency's ItemID short value; {@code 0}
 *       means money (the default currency).</li>
 *   <li>{@code type} — {@link Type#NORMAL} for scheduled fires, {@link Type#CATCH_UP}
 *       for manual missed-payout catch-up tranches. Default {@code NORMAL}.</li>
 * </ul>
 */
public record PayoutHistoryRecord(
        long id,
        int companyId,
        long scheduleId,
        int sourceAccount,
        UUID targetUuid,
        long amount,
        long time,
        Status status,
        String targetPlayerName,
        String targetAccountName,
        short currencyItem,
        Type type
) {
    public static final long UNSAVED_ID = -1L;

    /**
     * Outcome of a payout attempt. Ordinals are wire-stable — new values are
     * appended, never inserted. {@code TARGET_MISSING} is the legacy name for
     * {@code PayoutFailureReason.TARGET_NOT_FOUND} (kept for old DB rows).
     */
    public enum Status {
        OK,
        INSUFFICIENT_FUNDS,
        TARGET_MISSING,
        TARGET_NO_DEPOSIT_RIGHT,
        CURRENCY_ITEM_MISSING,
        PAUSED,
        UNKNOWN
    }

    /** Spec B.4 — normal scheduled fire vs manual missed-payout catch-up. */
    public enum Type {
        NORMAL,
        CATCH_UP
    }

    public static PayoutHistoryRecord of(int companyId, long scheduleId, int sourceAccount,
                                         UUID targetUuid, long amount, long time, Status status,
                                         String targetPlayerName, String targetAccountName,
                                         short currencyItem, Type type) {
        return new PayoutHistoryRecord(UNSAVED_ID, companyId, scheduleId, sourceAccount,
                targetUuid, amount, time, status,
                targetPlayerName == null ? "" : targetPlayerName,
                targetAccountName == null ? "" : targetAccountName,
                currencyItem, type == null ? Type.NORMAL : type);
    }

    /** Legacy-shape factory — money currency, NORMAL type, no name snapshots. */
    public static PayoutHistoryRecord of(int companyId, long scheduleId, int sourceAccount,
                                         UUID targetUuid, long amount, long time, Status status) {
        return of(companyId, scheduleId, sourceAccount, targetUuid, amount, time, status,
                "", "", (short) 0, Type.NORMAL);
    }
}
