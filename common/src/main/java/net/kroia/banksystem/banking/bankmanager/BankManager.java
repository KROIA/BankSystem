package net.kroia.banksystem.banking.bankmanager;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankmanager.IAsyncBankManager;
import net.kroia.banksystem.api.bankmanager.IBankManager;
import net.kroia.banksystem.api.bankmanager.IClientBankManager;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.banking.bankaccount.SyncServerBankAccount;

public class BankManager implements IBankManager {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
        SyncServerBankAccount.setBackend(backend);
        SyncBankManager.setBackend(BACKEND_INSTANCES);
        AsyncBankManager.setBackend(BACKEND_INSTANCES);
    }


    private final IAsyncBankManager asyncServerBankManager;
    private final ISyncServerBankManager syncServerBankManager;


    public static BankManager createMaster()
    {
        SyncBankManager syncManager = new SyncBankManager();
        return new BankManager(syncManager, syncManager);
    }
    public static BankManager createSlave()
    {
        AsyncBankManager asyncBankManager = AsyncBankManager.createSlaveServerManager();
        return new BankManager(asyncBankManager, null);
    }
    public static IClientBankManager createClient()
    {
        return new ClientBankManager();
    }


    private BankManager(IAsyncBankManager asyncManager, ISyncServerBankManager syncManager)
    {
        asyncServerBankManager = asyncManager;
        syncServerBankManager = syncManager;
    }

    @Override
    public boolean hasSyncAccess()
    {
        return syncServerBankManager != null;
    }
    @Override
    public boolean hasAsyncAccess()
    {
        return asyncServerBankManager != null;
    }
    @Override
    public IAsyncBankManager getAsync()
    {
        return asyncServerBankManager;
    }
    @Override
    public ISyncServerBankManager getSync()
    {
        return syncServerBankManager;
    }
}
