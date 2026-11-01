package net.kroia.banksystem.util;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.banking.User;
import net.kroia.modutilities.networking.client_server.arrs.GenericRequest;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
                return user.isBanksystemAdmin();
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
                return user.isBanksystemAdmin();
            }
        }
        return false;
        //return player.hasPermissions(BACKEND_INSTANCES.SERVER_SETTINGS.UTILITIES.ADMIN_PERMISSION_LEVEL.get());
    }


    protected ISyncServerBankManager getServerBankManager()
    {
        return BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
    }

    /**
     * Untrusted-slave write gate (Task #26). Returns {@code true} when this request arrived
     * from a slave server that is NOT marked trusted — in which case the caller MUST reject
     * the operation (return a no-op result) before performing any write.
     * <p>
     * Master-originated requests ({@code slaveID} empty) and requests from trusted slaves
     * return {@code false} (allowed to proceed).
     * <p>
     * <b>Why the slave-trust check and not just the per-player permission check:</b> a
     * slave&rarr;master forwarded request carries a {@code playerSender} UUID chosen by the
     * sending slave. Only the {@code slaveID} is authenticated at the master (ModUtilities binds
     * it to the connecting socket); the player UUID is NOT. An untrusted slave could therefore
     * forge any player's UUID — including a BankSystem admin's — to satisfy
     * {@link #playerIsAdmin(UUID)} / account permission checks. Blocking untrusted slaves at the
     * door is the only sound gate for write operations. Trusted slaves are relied upon to forward
     * the genuine initiating player's UUID, so their per-player checks remain meaningful.
     *
     * @param slaveID the authenticated originating slave id ("" for master-originated)
     * @return {@code true} if the request must be refused as an untrusted-slave write
     */
    protected boolean isBlockedForUntrustedSlave(String slaveID)
    {
        if (slaveID == null || slaveID.isEmpty())
            return false; // master-originated (direct client or internal) — not a slave write
        ISyncServerBankManager manager = getServerBankManager();
        if (manager == null || !manager.isSlaveServerTrusted(slaveID)) {
            warn("The slave server: '" + slaveID + "' tried to call " + getRequestTypeID()
                    + " which is not allowed for an untrusted slave server!");
            return true;
        }
        return false;
    }


    public CompletableFuture<OUT> handleOnServer(IN input, ServerPlayer sender) {
        if(needsRoutingToMaster())
        {
            warn("Received packet that should have been sent to the master: "+getRequestTypeID());
        }
        return CompletableFuture.completedFuture(null);
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
