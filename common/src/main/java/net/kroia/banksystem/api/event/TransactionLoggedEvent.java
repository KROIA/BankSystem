package net.kroia.banksystem.api.event;

import net.kroia.banksystem.api.ledger.LedgerActor;
import net.kroia.banksystem.api.ledger.LedgerType;
import org.jetbrains.annotations.Nullable;

/**
 * Payload of {@link net.kroia.banksystem.api.IBankSystemEvents#getTransactionLoggedEvent()}.
 * <p>
 * One transaction-ledger row written on the <b>master JVM only</b>, fired right after the row
 * was queued for saving and before the admin-audit and suspicion checks run on it.
 *
 * @param accountNumber the bank account the row belongs to
 * @param type          what kind of movement this is
 * @param itemId        short ItemID of the moved item ({@code 0} for rows without an item, e.g. freeze/edit)
 * @param amount        signed raw amount (scaled by {@code ITEM_FRACTION_SCALE_FACTOR}); {@code 0} if none
 * @param actor         who caused the row
 * @param otherAccount  counterparty account (transfers, payouts), or {@code null}
 * @param companyId     company involved (payouts, dividends, shares), or {@code null}
 * @param timeMs        row time, epoch milliseconds
 * @param note          free-form note, or {@code null}
 * @since 2.2.0
 */
public record TransactionLoggedEvent(int accountNumber, LedgerType type, short itemId, long amount,
                                     LedgerActor actor, @Nullable Integer otherAccount, @Nullable Integer companyId,
                                     long timeMs, @Nullable String note) {
}
