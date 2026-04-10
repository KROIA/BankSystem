package net.kroia.banksystem.banking.bankmanager;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankmanager.IAsyncBankManager;
import net.kroia.banksystem.api.bankmanager.IBankManager;
import net.kroia.banksystem.api.bankmanager.IClientBankManager;
import net.kroia.banksystem.api.bankmanager.IServerBankManager;
import net.kroia.banksystem.banking.bankaccount.ServerBankAccount;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class BankManager implements IBankManager {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
        ServerBankAccount.setBackend(backend);
        ServerBankManager.setBackend(BACKEND_INSTANCES);
        AsyncBankManager.setBackend(BACKEND_INSTANCES);
    }


    private final @NotNull IAsyncBankManager asyncServerBankManager;
    private final @Nullable IServerBankManager serverBankManager;


    public static BankManager createMaster()
    {
        ServerBankManager syncManager = new ServerBankManager();
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


    private BankManager(@NotNull IAsyncBankManager asyncBankManager, @Nullable IServerBankManager syncManager)
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
        return true;
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
