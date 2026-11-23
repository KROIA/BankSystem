package net.kroia.banksystem.api;

import net.kroia.banksystem.api.bankaccount.ISyncServerBankAccount;
import net.kroia.banksystem.api.event.DividendPaidEvent;
import net.kroia.banksystem.api.event.PayoutExecutedInfo;
import net.kroia.banksystem.api.event.SuspicionFlaggedEvent;
import net.kroia.banksystem.api.event.TransactionLoggedEvent;
import net.kroia.banksystem.api.event.TrustChangeInfo;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.event.DataEvent;
import net.kroia.modutilities.event.Signal;

import java.util.Map;
import java.util.Set;

public interface IBankSystemEvents {


    /**
     * Gets the event that is emitted when a user is added to the bank manager
     * @return The user added event.
     */
    DataEvent<User> getUserAddedEvent();

    /**
     * Gets the event that is emitted when a user is removed from the bank manager
     * @return The user removed event.
     */
    DataEvent<User> getUserRemovedEvent();


    /**
     * Gets the event that is emitted when a bank account is created.
     * @return The bank account created event.
     */
    DataEvent<ISyncServerBankAccount> getBankAccountCreatedEvent();

    /**
     * Gets the event that is emitted when a bank account is removed
     * @return The bank account updated event.
     */
    DataEvent<ISyncServerBankAccount> getBankAccountDeletedEvent();

    /**
     * Gets the event that is emitted on the <b>master server</b> after an ItemID merge
     * (volatile-component merge) has been fully consolidated into BankSystem's own state
     * (bank balances/locked balances, allowed items, account icons).
     * <p>
     * Payload: an <b>unmodifiable</b> map of merged ItemID (alias) → canonical ItemID.
     * Dependent mods (e.g. StockMarket) should listen to this event and consolidate their
     * own ItemID-keyed state (markets, orders, price histories, ...) under the canonical
     * IDs. The event is always dispatched on the server thread, after BankSystem's own
     * consolidation completed.
     *
     * @return the ItemIDs-merged event.
     */
    DataEvent<Map<ItemID, ItemID>> getItemIDsMergedEvent();

    /**
     * Emitted on the <b>master server</b> once per start in which the admin confirmed removing
     * the ItemIDs of missing mods ({@code CONFIRM_ITEMID_REMOVE_MISSING}). BankSystem has
     * already dropped their balances from every bank account and deleted the IDs (their numbers
     * are never reused). Dependent mods should delete their own state keyed by these IDs
     * (markets, orders, ...). Payload: <b>unmodifiable</b> set of the removed ItemIDs.
     * Dispatched on the server thread right after {@link #getBanksystemSetupCompleteSignal()}.
     *
     * @return the ItemIDs-removed event.
     */
    DataEvent<Set<ItemID>> getItemIDsRemovedEvent();

    /**
     * Signal gets emitted when bank data is saved to a file.
     */
    Signal getBankDataSavedToFileSignal();

    /**
     * Signal gets emitted when bank data is loaded from a file.
     */
    Signal getBankDataLoadedFromFileSignal();

    /**
     * Signal gets emitted when settings are saved to a file.
     */
    Signal getSettingsSavedToFileSignal();

    /**
     * Signal gets emitted when settings are loaded from a file.
     */
    Signal getSettingsLoadedFromFileSignal();

    /**
     * Signal emitted on every server once BankSystem finished its per-world setup (data loaded, managers ready).
     * Fired on the server thread. Attach work that needs BankSystem's state here.
     */
    Signal getBanksystemSetupCompleteSignal();

    /**
     * Signal emitted on the <b>master server</b> when a slave finished its handshake (version checked, ItemID sync
     * sent). Parameterless; see {@link #getMasterServerSlaveDisconnected()} for the counterpart with the slave id.
     * Fired from a network callback: do not block, and hop to the server thread before touching game state.
     */
    Signal getMasterServerSlaveConnected();

    /**
     * Event emitted on the <b>master server</b> when a connected slave
     * disconnects (unexpected drop or clean shutdown). Counterpart to
     * {@link #getMasterServerSlaveConnected()}.
     * <p>
     * Payload: the departing slave's ID, so dependent mods (e.g. StockMarket)
     * can evict any per-slave state they built while the slave was connected.
     * <p>
     * Fires on the master JVM only. Note the asymmetry with the connect signal,
     * which is a parameterless {@link Signal}: the disconnect event carries the
     * slaveID because per-slave eviction needs to know which slave left.
     *
     * @return the master-side slave-disconnected event.
     */
    DataEvent<String> getMasterServerSlaveDisconnected();

    /**
     * Signal emitted on the <b>slave server</b> once the slave&rarr;master TCP
     * handshake has completed and the master has accepted this slave — i.e. once
     * {@code MultiServerUtils.canInteractWithBankSystem()} genuinely returns
     * {@code true} and BankSystem's async forwarding channel is usable.
     * <p>
     * Dependent mods (e.g. StockMarket) that need to query the master via
     * BankSystem's async accessors (for example
     * {@link net.kroia.banksystem.api.bankmanager.IAsyncBankManager#isSlaveServerTrustedAsync(String)})
     * should attach to this signal to know when the round-trip is safe to
     * perform; issuing those requests earlier short-circuits synchronously
     * (returns the fail-closed default) because the handshake has not completed
     * yet.
     * <p>
     * The signal fires again on every reconnect (each successful re-handshake).
     * Fired on a Netty event-loop thread — listeners must not block.
     *
     * @return the slave-connection-accepted signal.
     */
    Signal getSlaveConnectionAcceptedSignal();

    /**
     * Signal emitted on the <b>slave server</b> when its established connection
     * to the master drops (either an unexpected disconnect or a clean shutdown).
     * <p>
     * Dependent mods that maintain per-connection cached state derived from the
     * master (see {@link #getSlaveConnectionAcceptedSignal()}) should attach to
     * this signal to invalidate that cache. When the connection is
     * re-established, {@link #getSlaveConnectionAcceptedSignal()} fires again
     * and cached state can be re-fetched.
     * <p>
     * <b>Edge-triggered (Issue #64):</b> fires exactly once per
     * connected&rarr;disconnected transition. The slave's auto-reconnect loop
     * retries on a fixed cadence while the master is down, but this signal is
     * gated by an internal latch so it does <em>not</em> re-fire on each failed
     * reconnect attempt — it fires again only after a subsequent
     * {@link #getSlaveConnectionAcceptedSignal()} re-arms the latch.
     * <p>
     * Fired on a Netty event-loop thread — listeners must not block.
     *
     * @return the slave-connection-lost signal.
     */
    Signal getSlaveConnectionLostSignal();

    /**
     * Event emitted on the <b>master server</b> after
     * {@code ServerBankManager.trustSlaveServer(String)} or
     * {@code ServerBankManager.untrustSlaveServer(String)} has mutated the
     * master's slave-trust set. The payload's {@link TrustChangeInfo#trusted()}
     * reflects the post-mutation state — subscribers can propagate it directly
     * without re-querying the master.
     * <p>
     * Dependent mods (e.g. StockMarket) subscribe to this event to push runtime
     * trust-toggle updates to their connected slaves and downstream clients
     * without polling and without waiting for the next reconnect handshake.
     * <p>
     * <b>Contract details:</b>
     * <ul>
     *   <li>Fires on the master JVM only. On the slave JVM this event never
     *       fires — slaves learn about their own trust status through S2S
     *       packets dispatched by mods that subscribe here on the master.
     *       Keeping the event master-only avoids leaking BankSystem-internal
     *       state ("which OTHER slaves does the master trust?") to slaves.</li>
     *   <li>Fires AFTER the trust set has been mutated (subscribers observe
     *       the new state either from the payload directly or by re-querying
     *       the manager if they need cross-slave visibility).</li>
     *   <li>Fires even when the write was a no-op set-to-same-value at the
     *       manager level (idempotent contract). The command surface today
     *       short-circuits before calling the mutator when the target state
     *       already holds, so in practice the event only fires on real
     *       transitions — but any caller of the manager mutators can rely on
     *       the event firing once per call.</li>
     *   <li>Fires on the server main thread (invoked from the mutator; not
     *       from an async worker).</li>
     * </ul>
     *
     * @return the trust-changed event.
     */
    DataEvent<TrustChangeInfo> getTrustChangedSignal();

    /**
     * Task #45 (v2.1.0) — event emitted on the <b>master server</b> every time a recurring
     * payout schedule ticks. Fires for both successful transfers and every failure status
     * ({@code INSUFFICIENT_FUNDS}, {@code TARGET_MISSING}, {@code TARGET_NO_DEPOSIT_RIGHT},
     * {@code CURRENCY_ITEM_MISSING}, {@code PAUSED}, {@code UNKNOWN}, {@code ACCOUNT_FROZEN}). See
     * {@link PayoutExecutedInfo}. Dispatched on the server thread after the bank transfer
     * and SQL history write have been submitted.
     */
    DataEvent<PayoutExecutedInfo> getPayoutExecutedEvent();

    /**
     * Task #49 (v2.1.0) — event emitted on the <b>master server</b> after a successful
     * one-shot dividend distribution. Never fires when the run was refused (invalid
     * input, insufficient funds, no holders). See {@link DividendPaidEvent}. Dispatched
     * on the server thread after the transfers and the SQL ledger writes have been
     * submitted.
     */
    DataEvent<DividendPaidEvent> getDividendPaidEvent();

    /**
     * Task #117 — fires on the <b>master server</b> once per real freeze/unfreeze of a bank
     * account, after the flag was written. See {@link net.kroia.banksystem.api.event.AccountFreezeChangedEvent}.
     *
     * @since 2.2.0
     */
    DataEvent<net.kroia.banksystem.api.event.AccountFreezeChangedEvent> getAccountFreezeChangedEvent();

    /**
     * Task #119 — fires on the <b>master server</b> once per transaction-ledger row, right after
     * the row was queued for saving. Never fires on a slave (its rows are written by the master).
     * See {@link TransactionLoggedEvent}.
     * <p>
     * <b>Threading:</b> listeners run on the writer's thread: the server thread, an ARRS/netty
     * handler thread (slave-forwarded operations) or a DB worker. Listeners must be thread-safe
     * and fast, and must hop to the server thread ({@code server.execute(...)}) before touching
     * game state. A throwing listener is logged and does not stop the write.
     *
     * @since 2.2.0
     */
    DataEvent<TransactionLoggedEvent> getTransactionLoggedEvent();

    /**
     * Task #119 — fires on the <b>master server</b> once per raised suspicion flag, after the flag
     * row was queued for saving. Never fires on a slave. See {@link SuspicionFlaggedEvent}.
     * <p>
     * <b>Threading:</b> listeners run on the raising thread: the server thread, an ARRS/netty
     * handler thread or a DB worker (periodic wealth / money-supply checks). Listeners must be
     * thread-safe and fast, and must hop to the server thread ({@code server.execute(...)})
     * before touching game state.
     *
     * @since 2.2.0
     */
    DataEvent<SuspicionFlaggedEvent> getSuspicionFlaggedEvent();

    /**
     * Removes all listeners for the events and signals in this class.
     */
    void removeListeners();
}
