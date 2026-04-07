package net.kroia.banksystem.banking;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.IAsyncServerBankManager;
import net.kroia.banksystem.api.IBankManager;
import net.kroia.banksystem.api.IClientBankManager;
import net.kroia.banksystem.api.ISyncServerBankManager;

public class BankManager implements IBankManager {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
        BankAccount.setBackend(backend);
        SyncServerBankManager.setBackend(BACKEND_INSTANCES);
        AsyncServerBankManager.setBackend(BACKEND_INSTANCES);
    }


    private final IAsyncServerBankManager asyncServerBankManager;
    private final ISyncServerBankManager syncServerBankManager;


    public static BankManager createMaster()
    {
        SyncServerBankManager syncManager = new SyncServerBankManager();
        return new BankManager(syncManager, syncManager);
    }
    public static BankManager createSlave()
    {
        AsyncServerBankManager asyncServerBankManager = new AsyncServerBankManager();
        return new BankManager(asyncServerBankManager, null);
    }
    public static IClientBankManager createClient()
    {
        return new ClientBankManager();
    }


    private BankManager(IAsyncServerBankManager asyncManager, ISyncServerBankManager syncManager)
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
    public IAsyncServerBankManager getAsync()
    {
        return asyncServerBankManager;
    }
    @Override
    public ISyncServerBankManager getSync()
    {
        return syncServerBankManager;
    }
}
