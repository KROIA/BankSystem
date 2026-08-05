package net.kroia.banksystem.api.event;

/**
 * Task #49 (v2.0.8) — payload for {@code IBankSystemEvents.getDividendPaidEvent()}.
 * <p>
 * Fired on the master server after a successful one-shot dividend run
 * (see {@code IDividendPayer.payDividend}). Never fires for a refused run.
 * Dispatched on the server thread after the transfers and the SQL history
 * writes have been submitted.
 */
public record DividendPaidEvent(
        int companyId,
        long perShareAmount,
        long totalPaid,
        int holderCount,
        long time
) {
}
