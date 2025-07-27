package net.kroia.banksystem.api;

import net.kroia.banksystem.banking.eventdata.CloseItemBankEventData;
import net.kroia.modutilities.event.DataEvent;
import net.kroia.modutilities.event.Signal;

public interface IBankSystemEvents {

    /**
     * Event triggered when an item bank is closed, providing data about lost items.
     */
    DataEvent<CloseItemBankEventData> getCloseItemBankEvent();

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
