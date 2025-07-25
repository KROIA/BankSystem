package net.kroia.banksystem.api;

import net.kroia.banksystem.banking.ClientBankManager;

public interface BankSystemAPI {

    String getModID();
    BankSystemEventsAPI getEvents();

    ServerBankManagerAPI getServerBankManager();
    ClientBankManager getClientBankManager();

}
