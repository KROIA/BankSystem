package net.kroia.banksystem.api;

/**
 * Task #49 (v2.1.0) — result of a one-shot dividend distribution.
 * <p>
 * Returned by {@code IDividendPayer.payDividend(...)}. On any non-{@link Reason#OK}
 * outcome, {@link #totalPaid} and {@link #holderCount} are zero — the distributor
 * either commits the whole run atomically or refuses the whole run (no state
 * change).
 */
public record PayDividendResult(Reason reason, long totalPaid, int holderCount) {

    public enum Reason {
        /** Everything paid; {@link #totalPaid} and {@link #holderCount} populated. */
        OK,
        /** Called on a slave (or before startup completed) — no state change. */
        NOT_MASTER,
        /** {@code companyId} does not resolve. */
        COMPANY_MISSING,
        /** {@code amountPerShare} was &le; 0. */
        INVALID_INPUT,
        /**
         * No account holds this company's shares — either no shares were ever
         * stamped, or every stamped share has been redeemed. Distinct from
         * {@link #INSUFFICIENT_FUNDS} so callers can render a specific message.
         */
        NO_SHARES,
        /** Company money bank balance &lt; total outflow; run refused wholesale. */
        INSUFFICIENT_FUNDS,
        /**
         * Master-side MANAGE gate rejected the caller (also raised by the ARRS
         * layer before {@code DividendPayer} is even invoked).
         */
        NO_PERMISSION,
        /** Internal error (bank manager missing, source account missing, ...). */
        INTERNAL,
        /**
         * The company's bank account does not hold the requested currency item —
         * raised when {@code currencyItem != MONEY_CURRENCY} and no matching item
         * bank exists on the source account.
         */
        CURRENCY_ITEM_MISSING
    }

    public boolean success() { return reason == Reason.OK; }

    public static PayDividendResult ok(long totalPaid, int holderCount) {
        return new PayDividendResult(Reason.OK, totalPaid, holderCount);
    }

    public static PayDividendResult of(Reason reason) {
        return new PayDividendResult(reason, 0L, 0);
    }
}
