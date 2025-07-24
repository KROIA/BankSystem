package net.kroia.banksystem.util;

import net.kroia.banksystem.api.BankSystemEventsAPI;
import net.kroia.banksystem.banking.eventdata.CloseItemBankEventData;
import net.kroia.modutilities.event.DataEvent;
import net.kroia.modutilities.event.Signal;

public class BankSystemEvents implements BankSystemEventsAPI {

    public final DataEvent<CloseItemBankEventData> CLOSE_ITEM_BANK_EVENT = new DataEvent<>();

    public final Signal BANK_DATA_SAVED_TO_FILE = new Signal();
    public final Signal BANK_DATA_LOADED_FROM_FILE = new Signal();

    public final Signal SETTINGS_SAVED_TO_FILE = new Signal();
    public final Signal SETTINGS_LOADED_FROM_FILE = new Signal();


    @Override
    public void clearListeners() {
        CLOSE_ITEM_BANK_EVENT.clearListeners();
        BANK_DATA_SAVED_TO_FILE.clearListeners();
        BANK_DATA_LOADED_FROM_FILE.clearListeners();
        SETTINGS_SAVED_TO_FILE.clearListeners();
        SETTINGS_LOADED_FROM_FILE.clearListeners();
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
