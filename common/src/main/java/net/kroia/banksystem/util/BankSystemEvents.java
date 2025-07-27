package net.kroia.banksystem.util;

import net.kroia.banksystem.api.IBankSystemEvents;
import net.kroia.banksystem.banking.eventdata.CloseItemBankEventData;
import net.kroia.modutilities.event.DataEvent;
import net.kroia.modutilities.event.Signal;

public class BankSystemEvents implements IBankSystemEvents {

    public final DataEvent<CloseItemBankEventData> CLOSE_ITEM_BANK_EVENT = new DataEvent<>();

    public final Signal BANK_DATA_SAVED_TO_FILE = new Signal();
    public final Signal BANK_DATA_LOADED_FROM_FILE = new Signal();

    public final Signal SETTINGS_SAVED_TO_FILE = new Signal();
    public final Signal SETTINGS_LOADED_FROM_FILE = new Signal();


    @Override
    public void removeListeners() {
        CLOSE_ITEM_BANK_EVENT.removeListeners();
        BANK_DATA_SAVED_TO_FILE.removeListeners();
        BANK_DATA_LOADED_FROM_FILE.removeListeners();
        SETTINGS_SAVED_TO_FILE.removeListeners();
        SETTINGS_LOADED_FROM_FILE.removeListeners();
    }

    @Override
    public DataEvent<CloseItemBankEventData> getCloseItemBankEvent() {
        return CLOSE_ITEM_BANK_EVENT;
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
