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
 * <p>
 * No permission check happens here: direct callers are trusted server code, and
 * {@code actor} is only recorded. Players reach this through the company screen, whose
 * request to the master checks the MANAGE right before calling the payer.
 */
public interface IDividendPayer {

    /**
     * Pay a one-shot dividend to every account that holds this Company's shares
     * (free + locked, every share variant), except the company's own account. All-or-
     * nothing: if the company's money bank balance is below the total outflow, the
     * distribution is refused wholesale and no state changes.
     *
     * @param companyId              Company owning the stamped share.
     * @param amountPerShare         Fixed-point money units per share; must be
     *                               strictly {@code > 0}.
     * @param includeCompanyAccount  Ignored: the company's own account is never paid
     *                               (paying the company its own money changes nothing).
     *                               Kept for API and network compatibility.
     * @param actor                  Player who initiated the run; only recorded on the
     *                               resulting {@link net.kroia.banksystem.api.ledger.LedgerType#DIVIDEND}
     *                               ledger rows, not checked for permissions. May be
     *                               {@code null} for system-initiated runs.
     * @param currencyItem           Short ItemID of the paid item; {@code 0} = money.
     *                               {@code amountPerShare} is in raw units of this item.
     *                               {@link PayDividendResult.Reason#CURRENCY_ITEM_MISSING}
     *                               if the company account has no bank for it.
     * @return the outcome; see {@link PayDividendResult.Reason}.
     */
    PayDividendResult payDividend(int companyId, long amountPerShare,
                                  boolean includeCompanyAccount,
                                  @Nullable UUID actor,
                                  short currencyItem);
}
