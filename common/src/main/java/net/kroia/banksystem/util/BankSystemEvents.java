package net.kroia.banksystem.util;

import net.kroia.banksystem.api.IBankAccount;
import net.kroia.banksystem.api.IBankSystemEvents;
import net.kroia.banksystem.banking.User;
import net.kroia.modutilities.event.DataEvent;
import net.kroia.modutilities.event.Signal;

public class BankSystemEvents implements IBankSystemEvents {

    public final DataEvent<User> USER_ADDED = new DataEvent<>();
    public final DataEvent<User> USER_REMOVED = new DataEvent<>();




    public final DataEvent<IBankAccount> BANK_ACCOUNT_CREATED = new DataEvent<>();
    public final DataEvent<IBankAccount> BANK_ACCOUNT_DELETED = new DataEvent<>();




    public final Signal BANK_DATA_SAVED_TO_FILE = new Signal();
    public final Signal BANK_DATA_LOADED_FROM_FILE = new Signal();

    public final Signal SETTINGS_SAVED_TO_FILE = new Signal();
    public final Signal SETTINGS_LOADED_FROM_FILE = new Signal();


    @Override
    public void removeListeners() {
        USER_ADDED.removeListeners();
        USER_REMOVED.removeListeners();
        BANK_ACCOUNT_CREATED.removeListeners();
        BANK_ACCOUNT_DELETED.removeListeners();
        BANK_DATA_SAVED_TO_FILE.removeListeners();
        BANK_DATA_LOADED_FROM_FILE.removeListeners();
        SETTINGS_SAVED_TO_FILE.removeListeners();
        SETTINGS_LOADED_FROM_FILE.removeListeners();
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
    public DataEvent<IBankAccount> getBankAccountCreatedEvent()
    {
        return BANK_ACCOUNT_CREATED;
    }

    @Override
    public DataEvent<IBankAccount> getBankAccountDeletedEvent()
    {
        return BANK_ACCOUNT_DELETED;
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
}
