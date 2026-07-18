package net.kroia.banksystem.api;

import net.kroia.banksystem.api.bankaccount.ISyncServerBankAccount;
import net.kroia.banksystem.api.event.TrustChangeInfo;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.event.DataEvent;
import net.kroia.modutilities.event.Signal;

import java.util.Map;

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

    Signal getBanksystemSetupCompleteSignal();

    Signal getMasterServerSlaveConnected();

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
     * Removes all listeners for the events and signals in this class.
     */
    void removeListeners();
}
