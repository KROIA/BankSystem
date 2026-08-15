package net.kroia.banksystem.api.dividend;

import net.kroia.banksystem.api.PayDividendResult;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Task #49 (v2.1.0) — public dividend distributor API. Reachable via
 * {@code BankSystemAPI.getDividendPayer()}.
 * <p>
 * Master-only surface — calling on a slave returns
 * {@link PayDividendResult.Reason#NOT_MASTER}. Downstream mods and UI code use this
 * interface — never the concrete {@code DividendPayer}.
 */
public interface IDividendPayer {

    /**
     * Pay a one-shot dividend to every account that holds this Company's stamped
     * shares (as reported by {@code IBankManager.listAccountsHolding}). All-or-
     * nothing: if the company's money bank balance is below the total outflow, the
     * distribution is refused wholesale and no state changes.
     *
     * @param companyId              Company owning the stamped share.
     * @param amountPerShare         Fixed-point money units per share; must be
     *                               strictly {@code > 0}.
     * @param includeCompanyAccount  When {@code true}, the company's own bank
     *                               account (any treasury float) receives a
     *                               proportional payout too. Default is
     *                               {@code false}: treasury floats do not
     *                               self-pay.
     * @param actor                  Player who initiated the run; recorded on the
     *                               resulting {@code TransactionLog.DIVIDEND}
     *                               rows. May be {@code null} for system-initiated
     *                               runs.
     * @return the outcome; see {@link PayDividendResult.Reason}.
     */
    PayDividendResult payDividend(int companyId, long amountPerShare,
                                  boolean includeCompanyAccount,
                                  @Nullable UUID actor,
                                  short currencyItem);
}
