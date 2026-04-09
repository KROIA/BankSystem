package net.kroia.banksystem.banking.bankmanager;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankmanager.*;
import net.kroia.banksystem.banking.bankaccount.ServerBankAccount;

public class BankManager implements IBankManager {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
        ServerBankAccount.setBackend(backend);
        SyncBankManager.setBackend(BACKEND_INSTANCES);
        AsyncBankManager.setBackend(BACKEND_INSTANCES);
    }


    private final IAsyncBankManager asyncServerBankManager;
    private final IServerBankManager serverBankManager;


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


    private BankManager(IAsyncBankManager asyncBankManager, IServerBankManager syncManager)
    {
        asyncServerBankManager = asyncBankManager;
        serverBankManager = syncManager;
    }

    @Override
    public boolean hasSyncAccess()
    {
        return serverBankManager != null;
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
    public IServerBankManager getSync()
    {
        return serverBankManager;
    }
}
