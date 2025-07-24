package net.kroia.banksystem.api;

import net.kroia.banksystem.banking.ClientBankManager;
import net.kroia.banksystem.banking.ServerBankManager;

public interface BankSystemAPI {

    String getModID();
    BankSystemEventsAPI getEvents();

    ServerBankManager getServerBankManager();
    ClientBankManager getClientBankManager();

}
