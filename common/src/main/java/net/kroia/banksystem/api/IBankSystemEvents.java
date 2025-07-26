package net.kroia.banksystem.api;

import net.kroia.banksystem.banking.eventdata.CloseItemBankEventData;
import net.kroia.modutilities.event.DataEvent;
import net.kroia.modutilities.event.Signal;

public interface IBankSystemEvents {
    DataEvent<CloseItemBankEventData> getCloseItemBankEvent();

    Signal getBankDataSavedToFileSignal();
    Signal getBankDataLoadedFromFileSignal();

    Signal getSettingsSavedToFileSignal();
    Signal getSettingsLoadedFromFileSignal();

    void clearListeners();
}
