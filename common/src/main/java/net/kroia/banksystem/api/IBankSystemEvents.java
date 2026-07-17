package net.kroia.banksystem.api;

import net.kroia.banksystem.api.bankaccount.ISyncServerBankAccount;
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
     * Removes all listeners for the events and signals in this class.
     */
    void removeListeners();
}
