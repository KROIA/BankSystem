package net.kroia.banksystem.banking.bankmanager;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.api.bankmanager.IAsyncBankManager;
import net.kroia.banksystem.api.bankmanager.IBankManager;
import net.kroia.banksystem.api.bankmanager.IClientBankManager;
import net.kroia.banksystem.api.bankmanager.IServerBankManager;
import net.kroia.banksystem.util.MultiServerUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class BankManager implements IBankManager {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;

        ServerBankManager.setBackend(BACKEND_INSTANCES);
        AsyncBankManager.setBackend(BACKEND_INSTANCES);
        // Task #43 (v2.1.0) — CompanyManager master registry + founder-invariant hook.
        net.kroia.banksystem.banking.company.CompanyManager.setBackend(BACKEND_INSTANCES);
        // Task #43g (v2.1.0) — AsyncCompanyManager needs the backend handle for master-side dispatch.
        net.kroia.banksystem.banking.company.AsyncCompanyManager.setBackend(BACKEND_INSTANCES);
    }


    private final @NotNull IAsyncBankManager asyncServerBankManager;
    private final @Nullable IServerBankManager serverBankManager;


    public static BankManager createMaster()
    {
        ServerBankManager syncManager = new ServerBankManager();
        // Task #43 (v2.1.0) — install the CompanyManager singleton on master.
        net.kroia.banksystem.banking.company.CompanyManager.install();
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
        if(!BACKEND_INSTANCES.isSlaveServer)
            return true;
        return MultiServerUtils.canInteractWithBankSystem();
    }
    @Override
    public IAsyncBankManager getAsync()
    {
        return asyncServerBankManager;
    }
    @Override
    public @Nullable IServerBankManager getSync()
    {
        return serverBankManager;
    }

    @Override
    public boolean isSlave()
    {
        return serverBankManager == null;
    }
    @Override
    public boolean isMaster()
    {
        return serverBankManager != null;
    }



    public static long convertToRawAmountStatic(double realAmount)
    {
        return Math.round(realAmount * BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR);
    }
    public static long convertToRawAmountStatic(double realAmount, int itemFractionScaleFactor)
    {
        return Math.round(realAmount * itemFractionScaleFactor);
    }
    public static double convertToRealAmountStatic(long rawAmount)
    {
        return (double)rawAmount / (double) BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
    }
    public static double convertToRealAmountStatic(long rawAmount, int itemFractionScaleFactor)
    {
        return (double)rawAmount / (double)itemFractionScaleFactor;
    }
}
