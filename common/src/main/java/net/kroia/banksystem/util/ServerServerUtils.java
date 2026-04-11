package net.kroia.banksystem.util;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.kroia.modutilities.networking.server_server.ServerServerManager;

import java.util.UUID;

public class ServerServerUtils {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }


    public static boolean checkConnectionToMaster()
    {
        return ServerServerManager.isRunning() && ServerServerManager.isSlave();
    }
    public static boolean checkConnectionToMaster(UUID executor)
    {
        if(checkConnectionToMaster())
            return true;
        ServerPlayerUtilities.printToClientConsole(executor, "["+BankSystemMod.getAPI().getModID()+"] This is a slave server and it is not connected to a "+BankSystemMod.getAPI().getModID()+" master server!");
        return false;
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
