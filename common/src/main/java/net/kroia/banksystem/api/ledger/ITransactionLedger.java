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
 * BS-254: every change of a bank's total balance writes a row where the balance changes. A raw
 * {@code IServerBank} call (deposit, withdraw, setBalance, ...) writes a {@link LedgerType#RAW_CHANGE} row
 * (source {@code UNKNOWN}). A mod that describes its movements here itself makes the bank calls inside
 * {@link #callerLogs}, so one movement is not logged twice. Lock and unlock only move value between free
 * and locked and write no row.
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

    /**
     * BS-254: runs {@code body} on this thread without the automatic {@link LedgerType#RAW_CHANGE} rows, for a
     * caller that writes its own rows for the bank calls in it (e.g. trade settlement logged with
     * {@link #logTrade}). Transfers still write their TRANSFER pair. Master only; nests.
     *
     * @return what {@code body} returned
     */
    <T> T callerLogs(java.util.function.Supplier<T> body);
}
