package net.kroia.banksystem.api.bankmanager;

import net.kroia.banksystem.util.ItemID;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Set;

public interface IBankManager {

    /**
     * Checks if this instance has access to synchronized banksystem.
     * If this server is a master, it will have sync access.
     * @apiNote
     * Do not call this from client side!
     * @return true if this is a master or multiserver capability is turned off.
     */
    boolean hasSyncAccess();


    /**
     * Checks if this instance can use async banksystem interactions.
     * If this server is a master, it will have async access available.
     * @apiNote
     * Do not call this from client side!
     * @return  true if this is a master.
     *          true if this is a slave which is connected to a master
     *          false if this is a slave without connection to a master
     */
    boolean hasAsyncAccess();


    /**
     * @apiNote
     * Do not call this from client side!
     * @return the synchronized access interface.
     */
    @Nullable IServerBankManager getSync();


    /**
     * @apiNote
     * Do not call this from client side!
     * @return the asynchronous access interface.
     */
    IAsyncBankManager getAsync();


    boolean isSlave();

    boolean isMaster();


    /**
     * Task #48 (v2.1.0) — top-level convenience for the dividend distributor and share-holder
     * queries. Returns the set of account numbers currently holding a strictly positive total
     * balance of the given {@code itemID}.
     * <p>
     * Master-only path — delegates to {@link ISyncServerBankManager#listAccountsHolding(ItemID)}
     * when sync access is available. On a slave (no sync access) the caller is expected to
     * forward the query via ARRS; this default returns an empty set so no downstream logic
     * silently pays out based on partial data. Dividends run on master (Task #49), so the
     * empty-on-slave fallback is safe for that consumer.
     */
    default Set<Integer> listAccountsHolding(ItemID itemID) {
        if (!hasSyncAccess()) return Collections.emptySet();
        IServerBankManager sync = getSync();
        if (sync == null) return Collections.emptySet();
        return sync.listAccountsHolding(itemID);
    }





}
