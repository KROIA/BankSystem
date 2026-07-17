package net.kroia.banksystem.util;

import net.kroia.banksystem.api.bankaccount.ISyncServerBankAccount;
import net.kroia.banksystem.api.IBankSystemEvents;
import net.kroia.banksystem.banking.User;
import net.kroia.modutilities.event.DataEvent;
import net.kroia.modutilities.event.Signal;

import java.util.Map;

public class BankSystemEvents implements IBankSystemEvents {

    public final DataEvent<User> USER_ADDED = new DataEvent<>();
    public final DataEvent<User> USER_REMOVED = new DataEvent<>();




    public final DataEvent<ISyncServerBankAccount> BANK_ACCOUNT_CREATED = new DataEvent<>();
    public final DataEvent<ISyncServerBankAccount> BANK_ACCOUNT_DELETED = new DataEvent<>();

    /**
     * Fired on the master server after an ItemID merge (see
     * {@code ItemIDManager.renormalizeAndMerge()}) has been fully consolidated into
     * BankSystem's own state (bank balances/locked balances, allowed items, account
     * icons). Payload: an <b>unmodifiable</b> map of merged ItemID (alias) → canonical
     * ItemID. Dependent mods (e.g. StockMarket) should consolidate their own
     * ItemID-keyed state (markets, orders, ...) when this fires.
     * Always dispatched on the server thread, master side only.
     */
    public final DataEvent<Map<ItemID, ItemID>> ITEM_IDS_MERGED = new DataEvent<>();




    public final Signal BANK_DATA_SAVED_TO_FILE = new Signal();
    public final Signal BANK_DATA_LOADED_FROM_FILE = new Signal();

    public final Signal SETTINGS_SAVED_TO_FILE = new Signal();
    public final Signal SETTINGS_LOADED_FROM_FILE = new Signal();

    public final Signal BANKSYSTEM_SETUP_COMPLETED = new Signal();
    public final Signal MASTER_SERVER_SLAVE_CONNECTED = new Signal();

    /**
     * Fired on the slave when the slave&rarr;master handshake completes (the
     * {@code onSlaveConnectionAccepted} callback from {@code SlaveServerClient}).
     * See {@link IBankSystemEvents#getSlaveConnectionAcceptedSignal()} for the
     * intended use case (dependent-mod caches that need the async forwarder to
     * be live before they can query the master).
     */
    public final Signal SLAVE_CONNECTION_ACCEPTED = new Signal();

    /**
     * Fired on the slave when the established connection to the master drops
     * ({@code onSlaveConnectionLost} or {@code onSlaveDisconnected}). Paired
     * with {@link #SLAVE_CONNECTION_ACCEPTED} to let dependent-mod caches
     * invalidate their master-derived state until the next successful handshake.
     */
    public final Signal SLAVE_CONNECTION_LOST = new Signal();


    @Override
    public void removeListeners() {
        USER_ADDED.removeListeners();
        USER_REMOVED.removeListeners();
        BANK_ACCOUNT_CREATED.removeListeners();
        BANK_ACCOUNT_DELETED.removeListeners();
        ITEM_IDS_MERGED.removeListeners();
        BANK_DATA_SAVED_TO_FILE.removeListeners();
        BANK_DATA_LOADED_FROM_FILE.removeListeners();
        SETTINGS_SAVED_TO_FILE.removeListeners();
        SETTINGS_LOADED_FROM_FILE.removeListeners();
        BANKSYSTEM_SETUP_COMPLETED.removeListeners();
        MASTER_SERVER_SLAVE_CONNECTED.removeListeners();
        SLAVE_CONNECTION_ACCEPTED.removeListeners();
        SLAVE_CONNECTION_LOST.removeListeners();
    }


    @Override
    public DataEvent<User> getUserAddedEvent()
    {
        return USER_ADDED;
    }

    @Override
    public DataEvent<User> getUserRemovedEvent()
    {
        return USER_REMOVED;
    }


    @Override
    public DataEvent<ISyncServerBankAccount> getBankAccountCreatedEvent()
    {
        return BANK_ACCOUNT_CREATED;
    }

    @Override
    public DataEvent<ISyncServerBankAccount> getBankAccountDeletedEvent()
    {
        return BANK_ACCOUNT_DELETED;
    }

    @Override
    public DataEvent<Map<ItemID, ItemID>> getItemIDsMergedEvent()
    {
        return ITEM_IDS_MERGED;
    }

    @Override
    public Signal getBankDataSavedToFileSignal() {
        return BANK_DATA_SAVED_TO_FILE;
    }

    @Override
    public Signal getBankDataLoadedFromFileSignal() {
        return BANK_DATA_LOADED_FROM_FILE;
    }

    @Override
    public Signal getSettingsSavedToFileSignal() {
        return SETTINGS_SAVED_TO_FILE;
    }

    @Override
    public Signal getSettingsLoadedFromFileSignal() {
        return SETTINGS_LOADED_FROM_FILE;
    }

    @Override
    public Signal getBanksystemSetupCompleteSignal()
    {
        return BANKSYSTEM_SETUP_COMPLETED;
    }

    @Override
    public Signal getMasterServerSlaveConnected()
    {
        return MASTER_SERVER_SLAVE_CONNECTED;
    }

    @Override
    public Signal getSlaveConnectionAcceptedSignal()
    {
        return SLAVE_CONNECTION_ACCEPTED;
    }

    @Override
    public Signal getSlaveConnectionLostSignal()
    {
        return SLAVE_CONNECTION_LOST;
    }
}
