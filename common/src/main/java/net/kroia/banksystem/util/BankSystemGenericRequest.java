package net.kroia.banksystem.util;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
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
        ISyncServerBankManager manager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
        if(manager != null)
        {
            User user = manager.getUserByUUID(player.getUUID());
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
        ISyncServerBankManager manager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
        if(manager != null)
        {
            User user = manager.getUserByUUID(playerUUID);
            if(user != null)
            {
                return user.isBankModAdmin();
            }
        }
        return false;
        //return player.hasPermissions(BACKEND_INSTANCES.SERVER_SETTINGS.UTILITIES.ADMIN_PERMISSION_LEVEL.get());
    }


    protected ISyncServerBankManager getServerBankManager()
    {
        return BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
    }

    @Override
    public boolean needsRoutingToMaster() { return BACKEND_INSTANCES.isSlaveServer; }


    protected void info(String message) {
        BACKEND_INSTANCES.LOGGER.info("["+getRequestTypeID()+"] "+ message);
    }
    protected void error(String message) {
        BACKEND_INSTANCES.LOGGER.error("["+getRequestTypeID()+"] "+ message);
    }
    protected void error(String message, Throwable throwable) {
        BACKEND_INSTANCES.LOGGER.error("["+getRequestTypeID()+"] "+ message, throwable);
    }
    protected void warn(String message) {
        BACKEND_INSTANCES.LOGGER.warn("["+getRequestTypeID()+"] "+ message);
    }
    protected void debug(String message) {
        BACKEND_INSTANCES.LOGGER.debug("["+getRequestTypeID()+"] "+ message);
    }

}
