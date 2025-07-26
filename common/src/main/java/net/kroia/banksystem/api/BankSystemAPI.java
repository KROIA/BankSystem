package net.kroia.banksystem.api;

import net.kroia.banksystem.banking.ClientBankManager;

public interface BankSystemAPI {

    String getModID();
    IBankSystemEvents getEvents();

    IServerBankManager getServerBankManager();
    ClientBankManager getClientBankManager();

}
