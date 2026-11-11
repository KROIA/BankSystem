package net.kroia.banksystem.util;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.kroia.modutilities.networking.multi_server.MultiServerManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class MultiServerUtils {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }


    public static boolean checkConnectionToMaster()
    {
        return MultiServerManager.isRunning() && MultiServerManager.isSlave();
    }
    public static boolean checkConnectionToMaster(UUID executor)
    {
        if(checkConnectionToMaster())
            return true;
        ServerPlayer player = ServerPlayerUtilities.getOnlinePlayer(executor);
        if(player != null)
            player.sendSystemMessage(getNoMasterMessage());
        return false;
    }

    /** Chat line shown when a slave refuses an action because it has no connection to its master. */
    public static Component getNoMasterMessage()
    {
        return Component.translatable("message."+BankSystemMod.MOD_ID+".not_connected_to_master");
    }

    public static boolean canInteractWithBankSystem()
    {
        if(BACKEND_INSTANCES.isSlaveServer)
        {
            return checkConnectionToMaster();
        }
        return true;
    }
    public static boolean canInteractWithBankSystem(UUID executor)
    {
        if(BACKEND_INSTANCES.isSlaveServer)
        {
            return checkConnectionToMaster(executor);
        }
        return true;
    }

}
