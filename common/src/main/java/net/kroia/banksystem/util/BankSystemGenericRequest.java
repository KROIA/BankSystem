package net.kroia.banksystem.util;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.banking.ServerBankManager;
import net.kroia.banksystem.banking.User;
import net.kroia.modutilities.networking.client_server.arrs.GenericRequest;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public abstract class BankSystemGenericRequest<IN, OUT> extends GenericRequest<IN, OUT> {

    protected static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    protected boolean playerIsAdmin(ServerPlayer player)
    {
        ServerBankManager manager = (ServerBankManager) BACKEND_INSTANCES.SERVER_BANK_MANAGER;
        if(manager != null)
        {
            User user = manager.getUserByUUID_direct(player.getUUID());
            if(user != null)
            {
                return user.isBankModAdmin();
            }
        }
        return false;
        //return player.hasPermissions(BACKEND_INSTANCES.SERVER_SETTINGS.UTILITIES.ADMIN_PERMISSION_LEVEL.get());
    }
    protected boolean playerIsAdmin(UUID playerUUID)
    {
        ServerBankManager manager = (ServerBankManager) BACKEND_INSTANCES.SERVER_BANK_MANAGER;
        if(manager != null)
        {
            User user = manager.getUserByUUID_direct(playerUUID);
            if(user != null)
            {
                return user.isBankModAdmin();
            }
        }
        return false;
        //return player.hasPermissions(BACKEND_INSTANCES.SERVER_SETTINGS.UTILITIES.ADMIN_PERMISSION_LEVEL.get());
    }

}
