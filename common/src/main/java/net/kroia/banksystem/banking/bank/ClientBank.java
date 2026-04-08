package net.kroia.banksystem.banking.bank;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bank.IAsyncBank;
import net.kroia.banksystem.banking.bankaccount.SyncServerBankAccount;
import net.kroia.banksystem.util.ItemID;

public class ClientBank {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
        SyncServerBankAccount.setBackend(backend);
    }

    private final IAsyncBank asyncBank;


    public ClientBank(int accountNr, ItemID itemID)
    {
        asyncBank = AsyncBank.createClientBank(accountNr, itemID);
    }
}
