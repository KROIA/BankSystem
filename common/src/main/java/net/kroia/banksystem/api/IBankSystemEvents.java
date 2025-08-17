package net.kroia.banksystem.api;

import net.kroia.banksystem.banking.User;
import net.kroia.modutilities.event.DataEvent;
import net.kroia.modutilities.event.Signal;

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
    DataEvent<IBankAccount> getBankAccountCreatedEvent();

    /**
     * Gets the event that is emitted when a bank account is removed
     * @return The bank account updated event.
     */
    DataEvent<IBankAccount> getBankAccountDeletedEvent();

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
     * Removes all listeners for the events and signals in this class.
     */
    void removeListeners();
}
