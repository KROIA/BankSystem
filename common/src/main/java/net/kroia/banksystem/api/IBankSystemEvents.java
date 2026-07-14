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
     * Removes all listeners for the events and signals in this class.
     */
    void removeListeners();
}
