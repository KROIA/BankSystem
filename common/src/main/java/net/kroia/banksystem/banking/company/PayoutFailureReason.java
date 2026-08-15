package net.kroia.banksystem.banking.company;

import net.kroia.banksystem.data.table.record.PayoutHistoryRecord;

/**
 * Spec A.8 (v2.1.0) — typed failure reason set by the payout executor at the
 * actual failure site. Translated at display time; persisted through
 * {@link PayoutHistoryRecord.Status}.
 */
public enum PayoutFailureReason {
    INSUFFICIENT_FUNDS,
    TARGET_NOT_FOUND,
    TARGET_NO_DEPOSIT_RIGHT,
    CURRENCY_ITEM_MISSING,
    PAUSED,
    UNKNOWN;

    /** Map to the persisted history status. {@code TARGET_NOT_FOUND} keeps the legacy name. */
    public PayoutHistoryRecord.Status toStatus() {
        return switch (this) {
            case INSUFFICIENT_FUNDS -> PayoutHistoryRecord.Status.INSUFFICIENT_FUNDS;
            case TARGET_NOT_FOUND -> PayoutHistoryRecord.Status.TARGET_MISSING;
            case TARGET_NO_DEPOSIT_RIGHT -> PayoutHistoryRecord.Status.TARGET_NO_DEPOSIT_RIGHT;
            case CURRENCY_ITEM_MISSING -> PayoutHistoryRecord.Status.CURRENCY_ITEM_MISSING;
            case PAUSED -> PayoutHistoryRecord.Status.PAUSED;
            case UNKNOWN -> PayoutHistoryRecord.Status.UNKNOWN;
        };
    }
}
