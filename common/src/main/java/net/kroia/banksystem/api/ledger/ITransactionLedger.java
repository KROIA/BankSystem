package net.kroia.banksystem.api.ledger;

import net.kroia.banksystem.util.ItemID;
import org.jetbrains.annotations.Nullable;

/**
 * Task #109 — write access to the BankSystem transaction ledger, obtained via
 * {@code BankSystemAPI.getLedger()}.
 * <p>
 * Every call is fire-and-forget and thread-safe. Writes are best-effort: they never throw and
 * never affect the banking operation they describe. On the master the row is queued on the
 * database worker. On a slave server, before startup or after shutdown the call does nothing:
 * call it on the master, where the value actually moves.
 * <p>
 * Raw {@code IServerBank} deposit/withdraw/lock calls do <b>not</b> write ledger rows. A mod
 * that moves value through them should describe the movement here.
 * <p>
 * Rows written here are treated like BankSystem's own: they fire
 * {@code IBankSystemEvents.getTransactionLoggedEvent()} and run through the admin-audit and suspicion checks.
 *
 * @since 2.2.0
 */
public interface ITransactionLedger {

    /**
     * Writes one trade row ({@link LedgerType#TRADE_BUY} or {@link LedgerType#TRADE_SELL},
     * source STOCKMARKET). Rows are deltas; per {@code ref} they sum to the order totals.
     */
    void logTrade(TradeLogEntry entry);

    /**
     * Writes a generic row.
     *
     * @param accountNumber account the row belongs to
     * @param type          row type
     * @param item          item of the balance that changed
     * @param amount        raw amount (unsigned, except {@link LedgerType#TRANSFER})
     * @param actor         who caused it
     * @param ref           optional reference that groups related rows
     * @param note          optional free text
     */
    void log(int accountNumber, LedgerType type, ItemID item, long amount, LedgerActor actor,
             @Nullable String ref, @Nullable String note);
}
