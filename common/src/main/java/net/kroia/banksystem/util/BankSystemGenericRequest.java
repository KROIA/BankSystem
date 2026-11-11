package net.kroia.banksystem.util;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankaccount.ISyncServerBankAccount;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.modutilities.networking.client_server.arrs.GenericRequest;
import net.kroia.modutilities.networking.client_server.arrs.RequestManager;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public abstract class BankSystemGenericRequest<IN, OUT> extends GenericRequest<IN, OUT> {

    /**
     * Issue #85: requester wait for slow requests, returned by their {@link #getTimeoutMs()} override.
     * Reads that queue on the DB worker or wait on a further hop get it. Requests whose requester never refunds,
     * and where a false failure invites a retry of a value action, also get it. Value requests that refund or
     * park on failure keep {@link RequestManager#DEFAULT_TIMEOUT_MS}.
     */
    // ponytail: outlasts a backup pause; a routed client→slave→master read waits the full 150 s if the master link drops (the client timer is the only timer)
    public static final long SLOW_READ_TIMEOUT_MS = DatabaseManager.DEFAULT_PAUSE_SAFETY_TIMEOUT_MS + RequestManager.DEFAULT_TIMEOUT_MS;

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


    /**
     * Read-access rule for account data (Task #100, Task #103): account members always,
     * BankSystem admins unless the request came through an untrusted slave (forgeable sender UUID).
     * A server-internal call (null player sender) counts as admin: full data from a trusted slave,
     * nothing from an untrusted one.
     */
    public static boolean isAccessAllowed(boolean isMember, boolean isAdmin, boolean fromUntrustedSlave) {
        return isMember || (isAdmin && !fromUntrustedSlave);
    }

    /**
     * Like {@link #isBlockedForUntrustedSlave} but without logging: true when the request
     * came from a slave that is not trusted. Master-originated requests ({@code slaveID} empty) return false.
     */
    protected boolean isFromUntrustedSlave(String slaveID)
    {
        if (slaveID == null || slaveID.isEmpty())
            return false;
        ISyncServerBankManager manager = getServerBankManager();
        return manager == null || !manager.isSlaveServerTrusted(slaveID);
    }

    /**
     * Task #104: reads about one user (admin flag, account numbers, full user record) go to that
     * user themselves or to a full reader (see {@link #isFullRead}).
     */
    public static boolean isSelfOrFullRead(@Nullable UUID sender, @Nullable UUID subject, boolean fullRead) {
        return fullRead || (sender != null && sender.equals(subject));
    }

    /**
     * Task #103/#104: true if the caller may read every account: a BankSystem admin or a
     * server-internal call (null sender), never through an untrusted slave.
     */
    protected boolean isFullRead(@Nullable UUID playerSender, String slaveID)
    {
        return isAccessAllowed(false, playerSender == null || playerIsAdmin(playerSender), isFromUntrustedSlave(slaveID));
    }

    /** Task #104: {@link #isAccessAllowed} applied to one account. False if the account is null. */
    protected boolean canReadAccount(@Nullable UUID playerSender, String slaveID, @Nullable ISyncServerBankAccount account)
    {
        return account != null && isAccessAllowed(playerSender != null && account.hasUser(playerSender),
                playerSender == null || playerIsAdmin(playerSender), isFromUntrustedSlave(slaveID));
    }

    public CompletableFuture<OUT> handleOnServer(IN input, ServerPlayer sender) {
        if(needsRoutingToMaster())
        {
            warn("Received packet that should have been sent to the master: "+getRequestTypeID());
        }
        // Task #167: a null reply cannot be encoded (encodeOutput NPEs); fail cleanly instead.
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                getRequestTypeID() + " is answered by the bank master only"));
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
