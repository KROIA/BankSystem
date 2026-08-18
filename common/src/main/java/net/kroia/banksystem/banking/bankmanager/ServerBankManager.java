package net.kroia.banksystem.banking.bankmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.architectury.event.events.common.TickEvent;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.IAsyncBank;
import net.kroia.banksystem.api.bank.IServerBank;
import net.kroia.banksystem.api.bank.ISyncServerBank;
import net.kroia.banksystem.api.bankaccount.IAsyncBankAccount;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankaccount.ISyncServerBankAccount;
import net.kroia.banksystem.api.bankmanager.IServerBankManager;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager.DisallowedHolder;
import net.kroia.banksystem.api.currency.ExternalAccount;
import net.kroia.banksystem.api.currency.ExternalAccountRef;
import net.kroia.banksystem.api.currency.ExternalCurrencyProvider;
import net.kroia.banksystem.api.event.TrustChangeInfo;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.bank.ServerBank;
import net.kroia.banksystem.banking.bankaccount.ServerBankAccount;
import net.kroia.banksystem.banking.binding.BankAccountBindings;
import net.kroia.banksystem.banking.binding.BindingRow;
import net.kroia.banksystem.api.ItemPriceProvider;
import net.kroia.banksystem.data.table.record.BalanceHistoryRecord;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.banking.clientdata.BankManagerData;
import net.kroia.banksystem.banking.clientdata.ItemInfoData;
import net.kroia.banksystem.banking.clientdata.UserData;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.networking.multi_server.DropItemsInPlayerInventoryRequest;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.JsonUtilities;
import net.kroia.modutilities.UtilitiesPlatform;
import net.kroia.modutilities.networking.multi_server.MultiServerManager;
import net.kroia.modutilities.persistence.ServerSaveableChunked;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class ServerBankManager implements ServerSaveableChunked, IServerBankManager {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
        ServerBankAccount.setBackend(backend);
    }


    /**
     * Using the player UUID as key
     */
    private final Map<UUID, User> userMap = new HashMap<>();


    /**
     * List of all items that are allowed to be stored inside a bank account
     */
    private final Set<ItemID> allowedItemIDs = new HashSet<>();

    /**
     * Task #57: runtime (admin-set) blacklist. The static {@code INITIAL_BLACKLIST_ITEMS}
     * setting covers items that may never be banked (air, command blocks, banknote
     * denominations). This set holds items an admin disallowed at runtime via
     * {@code /banksystem disallowItem} — including {@code banksystem:money}. It is persisted
     * alongside the allowed set and always wins over ALLOW_ALL_ITEMS. Canonical IDs only.
     */
    private final Set<ItemID> blacklistedItemIDs = new HashSet<>();


    /**
     * Using the account number as key.
     */
    private final Map<Integer, ServerBankAccount> bankAccounts = new HashMap<>();

    private final Set<String> trustedSlaveServers = new HashSet<>();

    private int nextAccountNumber = 1; // Start with account number 1
    private int tickCounter = 0;

    /**
     * Task #55: persistence dirty flag for the whole bank-data save unit ({@code Bank_data/}).
     * Distinct from the per-bank/per-account {@code changeFlag}/{@code hasChanges} network
     * change-stream signals (which are cleared by the publish path and would drop saves if
     * reused). Set true when any persisted state changes:
     * <ul>
     *   <li>per-account/per-bank balance, lock, name, icon, user or bank mutations — caught
     *       wholesale in {@link #update(MinecraftServer)} at the same 1&nbsp;Hz chokepoint the
     *       network change stream drains (see the {@code hasChanges()} probe there), so the
     *       high-frequency money-movement surface needs no per-site instrumentation;</li>
     *   <li>manager-global structural fields not owned by any single account
     *       ({@code allowedItemIDs}, {@code trustedSlaveServers}, {@code userMap},
     *       {@code nextAccountNumber}, account add/remove) — marked explicitly at each such
     *       mutating API below.</li>
     * </ul>
     * Reset to false only after a confirmed successful {@code Bank_data/} write.
     * NOTE: deliberately NOT set from {@link #load(Map)} so a freshly loaded, unmutated world
     * stays clean and the first timer save skips it.
     */
    private boolean persistDirty = false;

    /** Task #55: mark the bank-data save unit dirty (a mutation to persisted state occurred). */
    public void markPersistDirty() { persistDirty = true; }
    /** Task #55: @return whether the bank-data save unit has unsaved changes. */
    public boolean isPersistDirty() { return persistDirty; }
    /** Task #55: clear the dirty flag — called by the data handler after a successful write. */
    public void clearPersistDirty() { persistDirty = false; }

    /**
     * Task #41 (v2.0.7): last (balance, lockedBalance, time) written for each (account, item)
     * key. Cache is authoritative for the snapshot dedup path — populated lazily on the
     * first snapshot after server start (empty cache on boot forces every row to emit once,
     * which is a natural "server started" audit record). Cleared implicitly on world unload:
     * the whole {@link ServerBankManager} instance is dropped by
     * {@code BankSystemModBackend.onServerStop}, so the map is GC'd with it.
     */
    private final Map<Long, LastSnapshotSample> lastSnapshotCache = new HashMap<>();

    /**
     * Task #41 (v2.0.7): the {@link #lastSnapshotCache} value type. Public so the in-game
     * balance-history tests can construct their own cache map and drive the dedup filter
     * directly via {@link #applySnapshotDedup} without a live world.
     */
    public static final class LastSnapshotSample {
        public final long balance;
        public final long lockedBalance;
        public final long time;
        public LastSnapshotSample(long balance, long lockedBalance, long time) {
            this.balance = balance;
            this.lockedBalance = lockedBalance;
            this.time = time;
        }
    }

    /**
     * Task #41 (v2.0.7): packs an (accountNumber, itemId) pair into a single long map key.
     * The high 32 bits carry the account number (signed) and the low 16 bits carry the item
     * id (masked with {@code 0xFFFFL} so negative shorts round-trip unambiguously). Bits
     * 16..31 stay zero — no collision with the account number in the high half.
     */
    public static long snapshotKey(int accountNumber, short itemId) {
        return ((long) accountNumber << 32) | (itemId & 0xFFFFL);
    }

    /**
     * Task #41 (v2.0.7). Applies the sample-on-change + heartbeat dedup filter to one
     * candidate snapshot row. If the sample should be emitted, appends a
     * {@link BalanceHistoryRecord} to {@code out} and updates the {@code cache}. Otherwise
     * no-op. Returns whether a row was emitted.
     * <p>
     * The rule:
     * <ul>
     *   <li>No prior entry in {@code cache} for this (account, item) key -&gt; emit.</li>
     *   <li>Prior entry with a different {@code balance} or {@code lockedBalance} -&gt; emit.</li>
     *   <li>{@code heartbeatMs > 0} and {@code timestamp - prev.time >= heartbeatMs} -&gt; emit.</li>
     *   <li>Otherwise -&gt; skip.</li>
     * </ul>
     * {@code heartbeatMs <= 0} disables the heartbeat leg entirely (only balance changes
     * produce rows), matching the {@code BALANCE_SNAPSHOT_HEARTBEAT_MINUTES = 0} contract.
     * <p>
     * Public + static so the in-game test suite can drive the algorithm with a locally
     * constructed cache map (no need to instantiate a full {@link ServerBankManager}).
     * Production call site is {@link #collectBalanceSnapshot}.
     */
    public static boolean applySnapshotDedup(
            Map<Long, LastSnapshotSample> cache,
            int accountNumber, short itemId,
            long balance, long lockedBalance, long timestamp, long heartbeatMs,
            List<BalanceHistoryRecord> out) {
        long key = snapshotKey(accountNumber, itemId);
        LastSnapshotSample prev = cache.get(key);
        boolean changed = prev == null
                || prev.balance != balance
                || prev.lockedBalance != lockedBalance;
        boolean heartbeatDue = prev != null
                && heartbeatMs > 0L
                && (timestamp - prev.time) >= heartbeatMs;
        if (changed || heartbeatDue) {
            out.add(new BalanceHistoryRecord(accountNumber, itemId, balance, lockedBalance, timestamp));
            cache.put(key, new LastSnapshotSample(balance, lockedBalance, timestamp));
            return true;
        }
        return false;
    }


    /**
     * Deliberately performs <b>no ItemID registration</b> (Task #16 root-cause fix).
     * <p>
     * Historically this constructor "warmed up" {@link #getBlacklistedItems()} and
     * {@link #setupDefaultItems()} — both of which
     * REGISTER ItemIDs. Because {@code BankManager.createMaster()} runs BEFORE
     * {@code loadDataFromFiles()} (see {@code BankSystemModBackend.onServerStart}), every
     * master boot minted ~27 fresh low shorts (bedrock=1, ..., money200=19, ...) into the
     * just-cleared registry before {@code ItemIDs.nbt} was read — the same bug class fixed
     * for {@code createDefaultItemIDs()} in v2.0.3 ("load persisted item ids before
     * registering defaults"). On worlds whose persisted layout differs from that fresh
     * assignment, the pre-load keys either survived {@code load()} with a stale cached name
     * (correct template, wrong name — e.g. a Diorite deposit rejected as "money200") or got
     * merged/renumbered by the healing merge (persisted bedrock@71 aliased to session
     * bedrock@1, balance history of short 71 purged).
     * <p>
     * Nothing is lost by removing the warm-up: the blacklist results were
     * discarded here and are recomputed (register-if-absent) on every later call, and
     * {@code setupDefaultItems()} is invoked post-load by
     * {@code BankSystemDataHandler.load_bank()} / {@link #load(Map)} on both the
     * fresh-world and the existing-world path. The {@link ItemIDManager} registration
     * latch additionally rejects any master-side registration that would run before
     * {@code load_itemIDs()} completes (defense in depth).
     */
    public ServerBankManager() {
        TickEvent.SERVER_POST.register(this::update);
    }

    public void update(MinecraftServer server)
    {
        tickCounter++;
        if(tickCounter < 20)
            return; // Only process bank updates once per second to save some performance
        tickCounter = 0;

        // Issue #67 (v2.0.6): watchdog pass FIRST — detect out-of-band external-mod
        // mutations (player used Numismatics/Lightman's own UI while a BankSystem
        // terminal was open elsewhere) and flip changeFlag on drift so the
        // account.update pass below publishes to subscribed BankTerminalScreens.
        // Running BEFORE account.update means the cache-update in pollExternalDrift
        // silences the phantom-drift signal on the next tick after any bound-branch
        // mutation, avoiding a redundant notify.
        for(ServerBankAccount account : bankAccounts.values())
            account.pollAllExternalDrifts();

        for(ServerBankAccount account : bankAccounts.values()) {
            // Task #55: persistence dirty aggregation. account.hasChanges() aggregates the
            // account's own field flag AND every child bank's changeFlag — the exact set the
            // network change stream is about to publish. We observe it HERE, at 1 Hz, right
            // BEFORE account.update() clears those flags for the publish, and copy the signal
            // into the persistence-dedicated flag (never cleared by publish). Any balance,
            // lock, name, icon, user or bank mutation on any account therefore marks the
            // bank-data save unit dirty without instrumenting each low-level mutation site.
            if(account.hasChanges())
                persistDirty = true;
            account.update(server);
        }
    }


    @Override
    public void subscribeBankChanges(int accountNr, Consumer<BankAccountData> callback)
    {
        ServerBankAccount account = bankAccounts.get(accountNr);
        if(account != null)
        {
            account.subscribeBankChanges(callback);
        }
    }
    @Override
    public void unsubscribeBankChanges(int accountNr, Consumer<BankAccountData> callback)
    {
        ServerBankAccount account = bankAccounts.get(accountNr);
        if(account != null)
        {
            account.unsubscribeBankChanges(callback);
        }
    }

    @Override
    public BankManagerData getBankManagerData() {
        return new BankManagerData(
                getBankManagerUserMapData(),
                getBankManagerBankAccountsData(),
                getAllowedItems(),
                getBlacklistedItems()
        );
    }

    @Override
    public CompletableFuture<BankManagerData> getBankManagerDataAsync() {
        return CompletableFuture.completedFuture(getBankManagerData());
    }


    @Override
    public BankManagerData.UserMapData getBankManagerUserMapData() {
        Map<UUID, UserData> userDataMap = new HashMap<>();
        for (Map.Entry<UUID, User> entry : userMap.entrySet()) {
            userDataMap.put(entry.getKey(), entry.getValue().getUserData());
        }
        return new BankManagerData.UserMapData(userDataMap);
    }

    @Override
    public CompletableFuture<BankManagerData.UserMapData> getBankManagerUserMapDataAsync() {
        return CompletableFuture.completedFuture(getBankManagerUserMapData());
    }


    @Override
    public BankManagerData.BankAccountsData getBankManagerBankAccountsData() {
        Map<Integer, BankAccountData> bankAccountDataMap = new HashMap<>();
        for (Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            bankAccountDataMap.put(entry.getKey(), entry.getValue().getAccountData());
        }
        return new BankManagerData.BankAccountsData(bankAccountDataMap);
    }

    @Override
    public CompletableFuture<BankManagerData.BankAccountsData> getBankManagerBankAccountsDataAsync() {
        return CompletableFuture.completedFuture(getBankManagerBankAccountsData());
    }

    @Override
    public boolean setBanksystemAdminMode(UUID playerUUID, boolean isAdmin) {
        User user = userMap.get(playerUUID);
        if (user == null)
            return false;
        user.setBanksystemAdmin(isAdmin);
        markPersistDirty(); // Task #55 (User field is serialized in the bank-data save unit)
        return true;
    }

    @Override
    public CompletableFuture<Boolean> setBanksystemAdminModeAsync(UUID playerUUID, boolean isAdmin) {
        return CompletableFuture.completedFuture(setBanksystemAdminMode(playerUUID, isAdmin));
    }

    @Override
    public boolean isBanksystemAdmin(UUID playerUUID) {
        User user = userMap.get(playerUUID);
        if (user == null)
            return false;
        return user.isBanksystemAdmin();
    }
    @Override
    public CompletableFuture<Boolean> isBanksystemAdminAsync(UUID playerUUID) {
        User user = userMap.get(playerUUID);
        if (user == null)
            return CompletableFuture.completedFuture(false);
        return CompletableFuture.completedFuture(user.isBanksystemAdmin());
    }



    @Override
    public boolean isSlaveServerTrusted(String slaveID)
    {
        return trustedSlaveServers.contains(slaveID);
    }
    @Override
    public CompletableFuture<Boolean> isSlaveServerTrustedAsync(String slaveID)
    {
        return CompletableFuture.completedFuture(isSlaveServerTrusted(slaveID));
    }


    @Override
    public Set<String> getTrustedSlaveServers()
    {
        return Collections.unmodifiableSet(trustedSlaveServers);
    }



    @Override
    public void trustSlaveServer(String slaveID)
    {
        trustedSlaveServers.add(slaveID);
        markPersistDirty(); // Task #55
        // T-128 (cross-repo): notify dependent mods (e.g. StockMarket) that the
        // trust set changed so they can propagate the new state to their own
        // connected slaves/clients without polling. Fired AFTER the mutation so
        // listeners see the post-change state. Idempotent contract — the event
        // fires even when the caller set-to-same-value; subscribers filter if
        // that matters to them (in practice the admin-command surface
        // short-circuits before calling us, so this only fires on real flips).
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.SERVER_EVENTS != null) {
            BACKEND_INSTANCES.SERVER_EVENTS.TRUST_CHANGED.notifyListeners(
                    new TrustChangeInfo(slaveID, true));
        }
    }
    @Override
    public void untrustSlaveServer(String slaveID)
    {
        trustedSlaveServers.remove(slaveID);
        markPersistDirty(); // Task #55
        // T-128 (cross-repo): see trustSlaveServer above for the full rationale.
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.SERVER_EVENTS != null) {
            BACKEND_INSTANCES.SERVER_EVENTS.TRUST_CHANGED.notifyListeners(
                    new TrustChangeInfo(slaveID, false));
        }
    }


    @Override
    public List<ItemID> getAllowedItems() {
        return allowedItemIDs.stream().toList();
    }

    @Override
    public CompletableFuture<List<ItemID>> getAllowedItemsAsync() {
        return CompletableFuture.completedFuture(getAllowedItems());
    }


    @Override
    public List<ItemID> getBlacklistedItems() {
        // Static (settings) blacklist ∪ runtime (admin-set, Task #57) blacklist.
        List<ItemID> ids = new ArrayList<>(
                ItemIDManager.registerItemStackServerSide_direct(BACKEND_INSTANCES.SERVER_SETTINGS.BANK.INITIAL_BLACKLIST_ITEMS));
        for (ItemID id : blacklistedItemIDs) {
            if (!ids.contains(id))
                ids.add(id);
        }
        return ids;
    }

    @Override
    public CompletableFuture<List<ItemID>> getBlacklistedItemsAsync() {
        return CompletableFuture.completedFuture(getBlacklistedItems());
    }

    @Override
    public ItemInfoData getItemInfoData(@NotNull ItemID itemID) {
        double totalSupply = 0;
        double totalLocked = 0;
        List<BankAccountData> bankAccounts = new java.util.ArrayList<>();

        for (Map.Entry<Integer, ServerBankAccount> entry : this.bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            ISyncServerBank bank = account.getBank(itemID);
            if (bank == null)
                continue;
            totalSupply += bank.getRealTotalBalance();
            totalLocked += bank.getRealLockedBalance();
            bankAccounts.add(account.getAccountData(itemID));
        }
        return new ItemInfoData(itemID, totalSupply, totalLocked, bankAccounts);
    }

    @Override
    public CompletableFuture<ItemInfoData> getItemInfoDataAsync(@NotNull ItemID itemID) {
        return CompletableFuture.completedFuture(getItemInfoData(itemID));
    }

    @Override
    public void addUser(@NotNull ServerPlayer player) {
        addUser(new User(player.getUUID(), player.getName().getString(), true));
    }

    @Override
    public void addUserAsync(@NotNull ServerPlayer player) {
        addUser(player);
    }

    @Override
    public void addUser(@NotNull UUID playerUUID, @NotNull String playerName) {
        addUser(new User(playerUUID, playerName, true));
    }

    @Override
    public void addUserAsync(@NotNull UUID playerUUID, @NotNull String playerName) {
        addUser(playerUUID, playerName);
    }

    @Override
    public void addUser(@NotNull User user) {
        UUID userUUID = user.getUUID();
        if (userMap.containsKey(userUUID)) {
            warn("User with UUID " + userUUID + " already exists. Not adding again.");
            return;
        }
        userMap.put(userUUID, user);
        markPersistDirty(); // Task #55
        info("Added new user: " + user.getName() + " with UUID: " + userUUID);
        BACKEND_INSTANCES.SERVER_EVENTS.USER_ADDED.notifyListeners(user);
    }

    @Override
    public void addUserAsync(@NotNull User user) {
        addUser(user);
    }

    @Override
    public boolean removeUser(UUID userUUID) {
        if (userMap.containsKey(userUUID)) {
            User user = userMap.remove(userUUID);
            markPersistDirty(); // Task #55 (also cascades account removals below)

            // Collect non-zero balances from accounts that will be deleted or are personal accounts of the removed user
            Map<ItemID, Long> itemsToDrop = new HashMap<>();
            List<Integer> emptyAccounts = new ArrayList<>();
            List<Integer> personalAccountsToDrop = new ArrayList<>();

            for (Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
                ServerBankAccount account = entry.getValue();

                // Check if this is the user's personal bank account
                User personalOwner = account.getPersonalBankOwner();
                if (personalOwner != null && personalOwner.getUUID().equals(userUUID)) {
                    collectAccountBalances(account, itemsToDrop);
                    personalAccountsToDrop.add(entry.getKey());
                    continue;
                }

                if (account.hasUser(userUUID)) {
                    account.removeUser(userUUID);
                    if (!account.hasAnyUser()) {
                        collectAccountBalances(account, itemsToDrop);
                        emptyAccounts.add(entry.getKey());
                    }
                }
            }

            // Drop collected items to the player before deleting accounts
            if (!itemsToDrop.isEmpty()) {
                dropItemsToPlayer(userUUID, itemsToDrop);
            }

            // Delete empty non-personal accounts
            for (int accountNr : emptyAccounts) {
                deleteBankAccount(accountNr);
                info("Removed empty bank account with number: " + accountNr);
            }

            // Force-delete personal accounts (bypass the personal account protection since the user is being removed)
            BankAccountBindings cascadeBindings = BankAccountBindings.get();
            for (int accountNr : personalAccountsToDrop) {
                ServerBankAccount removed = bankAccounts.remove(accountNr);
                if (removed != null) {
                    // Task #33 (v2.0.5): cascade-drop external-currency binding rows for
                    // the departing personal account. Idempotent when no rows exist.
                    if (cascadeBindings != null) cascadeBindings.removeAllForAccount(accountNr);
                    BACKEND_INSTANCES.SERVER_EVENTS.BANK_ACCOUNT_DELETED.notifyListeners(removed);
                }
                info("Removed personal bank account with number: " + accountNr + " for deleted user: " + userUUID);
            }

            BACKEND_INSTANCES.SERVER_EVENTS.USER_REMOVED.notifyListeners(user);
            info("Removed user with UUID: " + userUUID);
            return true;
        } else {
            warn("No user found with UUID: " + userUUID);
            return false;
        }
    }

    /**
     * Collects all non-zero balances (free + locked) from the given bank account into the target map.
     */
    private void collectAccountBalances(ServerBankAccount account, Map<ItemID, Long> target) {
        for (Map.Entry<ItemID, IServerBank> bankEntry : account.getAllBanks().entrySet()) {
            ItemID itemID = bankEntry.getKey();
            ISyncServerBank bank = bankEntry.getValue();
            if (bank == null) continue;
            long totalBalance = bank.getTotalBalance();
            if (totalBalance > 0) {
                target.merge(itemID, totalBalance, Long::sum);
            }
        }
    }

    /**
     * Snapshots every account's per-item balances and (when a price provider and currency are
     * configured) a synthetic "Total Wealth" record for the balance-history screen.
     * <p>
     * <b>Cash detection uses BankSystem's own money predicate</b>, not a single cached currency
     * short. {@code currencyItemId} is supplied once by StockMarket (via {@code setPriceCurrencyItem})
     * and is fragile: it can point at the WRONG money denomination or go stale after an ItemID
     * remap. If a bank's cash balance is mis-classified as a non-cash item, it falls into the
     * market-price branch — money has no market, so its price is 0 and the player's entire cash
     * balance contributes 0 to wealth (the reported "wealth rapidly decreasing / cash missing" bug).
     * <p>
     * To stay robust, a bank is recognized as CASH when {@link MoneyItem#isMoney(ItemID)} is true
     * for the bank's key. {@code isMoney} matches ANY money denomination against
     * {@code BankSystemItems.getMoneyItems()} and is the authoritative, denomination-agnostic
     * source used elsewhere server-side. All money-denomination balances are stored in scaled
     * base-money units, so treating any money bank as face-value cash is correct. As an additional
     * accept, a bank whose canonical key equals the caller-supplied {@code currencyItemId} is also
     * treated as cash (fallback for a future non-{@code MoneyItem} currency). The wealth
     * formula/units are unchanged — only the "is this the money bank?" decision was hardened.
     *
     * @param timestamp      snapshot timestamp (ms) stamped on every produced record
     * @param priceProvider  StockMarket-supplied per-item price source; {@code null} disables wealth
     * @param currencyItemId currency short from {@code setPriceCurrencyItem}; used only as a
     *                       secondary accept — cash is primarily detected via {@code isMoney}
     * @return the balance records plus one wealth record per account (only when wealth is enabled)
     */
    public List<BalanceHistoryRecord> collectBalanceSnapshot(long timestamp, ItemPriceProvider priceProvider, short currencyItemId) {
        List<BalanceHistoryRecord> records = new ArrayList<>();
        // Task #41 (v2.0.7): sample-on-change + heartbeat dedup. Look up the heartbeat once
        // per snapshot pass — the setting is a boot-time config value, not something we want
        // to re-read per row. If BACKEND_INSTANCES / SERVER_SETTINGS are unavailable (test
        // harness), heartbeat defaults to 0 (disabled) and dedup runs pure sample-on-change.
        final long heartbeatMs = (BACKEND_INSTANCES != null && BACKEND_INSTANCES.SERVER_SETTINGS != null)
                ? BACKEND_INSTANCES.SERVER_SETTINGS.UTILITIES.BALANCE_SNAPSHOT_HEARTBEAT_MINUTES.get() * 60_000L
                : 0L;

        for (Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            int accountNumber = entry.getKey();
            ServerBankAccount account = entry.getValue();
            long totalWealth = 0;
            // Wealth is only computed when a price provider AND a currency were configured
            // (preserves the original "no provider/currency -> no wealth record" behavior).
            boolean hasWealth = priceProvider != null && currencyItemId != 0;
            for (Map.Entry<ItemID, IServerBank> bankEntry : account.getAllBanks().entrySet()) {
                ISyncServerBank bank = bankEntry.getValue();
                if (bank == null) continue;
                short itemId = bankEntry.getKey().getShort();
                long balance = bank.getBalance();
                long lockedBalance = bank.getLockedBalance();
                // Task #41: dedup — only emit when the (balance, lockedBalance) changed since
                // the previous snapshot for this (account, item) OR the heartbeat window has
                // elapsed. Skipped rows leave the DB entirely unchanged for that key.
                applySnapshotDedup(lastSnapshotCache, accountNumber, itemId,
                        balance, lockedBalance, timestamp, heartbeatMs, records);
                if (hasWealth) {
                    long totalBalance = balance + lockedBalance;
                    ItemID bankItemID = bankEntry.getKey();
                    // Primary cash test: BankSystem's denomination-agnostic money predicate.
                    // This is robust against the currency short pointing at the wrong money
                    // denomination or going stale after an ItemID remap — any money bank is
                    // stored in scaled base-money units and counts as face-value cash.
                    // Secondary accept: exact match against the caller-supplied currency short
                    // (canonicalized) as a fallback for a future non-MoneyItem currency.
                    boolean isCash = MoneyItem.isMoney(bankItemID)
                            || (currencyItemId != 0
                                && ItemIDManager.resolveAlias(bankItemID).getShort() == currencyItemId);
                    if (isCash) {
                        totalWealth += totalBalance;
                    } else {
                        long price = priceProvider.getItemPrice(itemId);
                        if (price > 0) {
                            // Task #38b: bound slots have a per-item ratio (e.g. LC gold = 81).
                            // "price per physical item" × "physical item count" = wealth in the
                            // aggregate cent unit; physical count = balance / ratio.
                            long ratio = BankAccountBindings.getRawUnitsPerItem(accountNumber, bankItemID);
                            totalWealth += totalBalance * price / ratio;
                        }
                    }
                }
            }
            if (hasWealth) {
                // Task #41: dedup wealth row too — one row per account per event, deduped
                // against the previous wealth value with the same heartbeat rule as per-item
                // rows. WEALTH_ITEM_ID = Short.MAX_VALUE is a reserved sentinel and can never
                // collide with a real item id, so the shared cache is safe.
                applySnapshotDedup(lastSnapshotCache, accountNumber,
                        BalanceHistoryRecord.WEALTH_ITEM_ID, totalWealth, 0L,
                        timestamp, heartbeatMs, records);
            }
        }
        return records;
    }

    /**
     * Drops items to the player using DropItemsInPlayerInventoryRequest with forceDrop=true.
     * Tries locally first, then sends to all connected slave servers for remaining items.
     */
    private void dropItemsToPlayer(UUID playerUUID, Map<ItemID, Long> items) {
        // Try to drop locally first (player might be on the master server)
        MinecraftServer server = UtilitiesPlatform.getServer();
        Map<ItemID, Long> remaining = DropItemsInPlayerInventoryRequest.dropItems(server, playerUUID, items, true);

        // Remove zero-amount entries
        remaining.entrySet().removeIf(e -> e.getValue() <= 0);

        if (remaining.isEmpty()) {
            return;
        }

        // Player not on master server; try all connected slave servers
        if (MultiServerManager.isRunning() && MultiServerManager.isMaster()) {
            List<String> slaves = MultiServerManager.getConnectedSlaveIDs();
            for (String slaveID : slaves) {
                final Map<ItemID, Long> itemsToSend = new HashMap<>(remaining);
                DropItemsInPlayerInventoryRequest.sendToSlave(slaveID, playerUUID, itemsToSend, true)
                        .whenComplete((notDropped, ex) -> {
                            if (ex != null) {
                                warn("Failed to drop items to player " + playerUUID + " on slave " + slaveID + ": " + ex.getMessage());
                            }
                        });
            }
        } else {
            // Not in multi-server mode and player not on this server — items are lost
            warn("Could not drop items to offline player " + playerUUID + " during account deletion. Lost items: " + remaining);
        }
    }

    @Override
    public CompletableFuture<Boolean> removeUserAsync(UUID userUUID) {
        return CompletableFuture.completedFuture(removeUser(userUUID));
    }


    @Override
    public boolean userExists(UUID userUUID) {
        return userMap.containsKey(userUUID);
    }

    @Override
    public boolean updateUserName(UUID playerUUID, String playerName)
    {
        User user = userMap.get(playerUUID);
        if (user == null)
            return false;
        if(user.getName().equals(playerName))
            return false;
        User newUser = User.createWithChangedName(user,  playerName);
        userMap.put(playerUUID, newUser);
        markPersistDirty(); // Task #55
        return true;
    }

    @Override
    public CompletableFuture<Boolean> userExistsAsync(UUID userUUID) {
        return CompletableFuture.completedFuture(userExists(userUUID));
    }

    @Override
    public @Nullable User getUserByUUID(UUID userUUID) {
        return userMap.get(userUUID);
    }

    @Override
    public CompletableFuture<@Nullable User> getUserByUUIDAsync(UUID userUUID) {
        return CompletableFuture.completedFuture(getUserByUUID(userUUID));
    }

    @Override
    public @Nullable User getUserByName(String name) {
        String lowerCaseName = name.toLowerCase();
        for (User user : userMap.values()) {
            if (user.getName().toLowerCase().equals(lowerCaseName)) {
                return user;
            }
        }
        return null;
    }

    @Override
    public CompletableFuture<@Nullable User> getUserByNameAsync(String name) {
        return CompletableFuture.completedFuture(getUserByName(name));
    }


    @Override
    public boolean bankAccountExists(int accountNumber) {
        return bankAccounts.containsKey(accountNumber);
    }

    @Override
    public CompletableFuture<Boolean> bankAccountExistsAsync(int accountNumber) {
        return CompletableFuture.completedFuture(bankAccounts.containsKey(accountNumber));
    }


    @Override
    public boolean bankAccountHasBank(int accountNumber, ItemID itemID) {
        ServerBankAccount account = bankAccounts.get(accountNumber);
        if (account == null)
            return false;
        return account.hasBank(itemID);
    }
    @Override
    public CompletableFuture<Boolean> bankAccountHasBankAsync(int accountNumber, ItemID itemID) {
        return CompletableFuture.completedFuture(bankAccountHasBank(accountNumber, itemID));
    }




    @Override
    public @Nullable BankAccountData getBankAccountData(int accountNumber)
    {
        ServerBankAccount account = bankAccounts.get(accountNumber);
        if (account == null)
            return null;
        return account.getAccountData();
    }
    @Override
    public CompletableFuture<@Nullable BankAccountData> getBankAccountDataAsync(int accountNumber)
    {
        return CompletableFuture.completedFuture(getBankAccountData(accountNumber));
    }






    @Override
    public @Nullable ServerBankAccount createPersonalBankAccount(UUID user) {
        return createPersonalBankAccount_internal(user);
    }
    @Override
    public CompletableFuture<@Nullable IAsyncBankAccount> createPersonalBankAccountAsync(UUID user) {
        return CompletableFuture.completedFuture(createPersonalBankAccount_internal(user));
    }




    @Override
    public int createPersonalBankAccountGetAccountNr(UUID user) {
        @Nullable ISyncServerBankAccount account = createPersonalBankAccount(user);
        if (account == null) {
            return ServerBankAccount.INVALID_ACCOUNT_NUMBER;
        }
        return account.getAccountNumber();
    }
    @Override
    public CompletableFuture<Integer> createPersonalBankAccountGetAccountNrAsync(UUID user)
    {
        return CompletableFuture.completedFuture(createPersonalBankAccountGetAccountNr(user));
    }




    @Override
    public int createPersonalBankAccountGetAccountNr(String userName)
    {
        User user = getUserByName(userName);
        if(user == null)
            return ServerBankAccount.INVALID_ACCOUNT_NUMBER;
        else
            return createPersonalBankAccountGetAccountNr(user.getUUID());
    }
    @Override
    public CompletableFuture<Integer> createPersonalBankAccountGetAccountNrAsync(String userName)
    {
        return CompletableFuture.completedFuture(createPersonalBankAccountGetAccountNr(userName));
    }






    @Override
    public int getPersonalBankAccountNr(UUID user) {
        ServerBankAccount account = getPersonalBankAccount_internal(user);
        if(account != null)
            return account.getAccountNumber();
        return ServerBankAccount.INVALID_ACCOUNT_NUMBER;
    }
    @Override
    public CompletableFuture<Integer> getPersonalBankAccountNrAsync(UUID user)
    {
        return CompletableFuture.completedFuture(getPersonalBankAccountNr(user));
    }
    @Override
    public int getPersonalBankAccountNr(String userName)
    {
        ServerBankAccount account = getPersonalBankAccount_internal(userName);
        if(account != null)
            return account.getAccountNumber();
        return ServerBankAccount.INVALID_ACCOUNT_NUMBER;
    }
    @Override
    public CompletableFuture<Integer> getPersonalBankAccountNrAsync(String userName)
    {
        return CompletableFuture.completedFuture(getPersonalBankAccountNr(userName));
    }





    @Override
    public @Nullable ServerBankAccount createBankAccount(String accountName)
    {
        if(accountName == null || accountName.isEmpty()) {
            accountName = "Unnamed Account";
        }
        int accountNumber = generateNewAccountNumber();
        ServerBankAccount account = ServerBankAccount.create(accountNumber);
        if(account == null)
        {
            warn("Failed to create bank account with number: " + accountNumber);
            return null;
        }
        account.setAccountName(accountName);
        account.setAccountIcon(ItemIDManager.registerItemStackServerSide_direct(Items.CHEST.getDefaultInstance()));
        bankAccounts.put(accountNumber, account);
        markPersistDirty(); // Task #55 (new account + advanced nextAccountNumber)
        info("Created new bank account with number: " + accountNumber + " and name: " + accountName);
        return account;
    }
    @Override
    public int createBankAccountGetAccountNr(String accountName)
    {
        @Nullable ISyncServerBankAccount account = createBankAccount(accountName);
        if(account == null) {
            return ServerBankAccount.INVALID_ACCOUNT_NUMBER;
        }
        return account.getAccountNumber();
    }
    @Override
    public CompletableFuture<@Nullable IAsyncBankAccount> createBankAccountAsync(String accountName) {
        if(accountName == null || accountName.isEmpty()) {
            accountName = "Unnamed Account";
        }
        int accountNumber = generateNewAccountNumber();
        ServerBankAccount account = ServerBankAccount.create(accountNumber);
        if(account == null)
        {
            warn("Failed to create bank account with number: " + accountNumber);
            return CompletableFuture.completedFuture(null);
        }
        account.setAccountName(accountName);
        account.setAccountIcon(ItemIDManager.registerItemStackServerSide_direct(Items.CHEST.getDefaultInstance()));
        bankAccounts.put(accountNumber, account);
        markPersistDirty(); // Task #55 (new account + advanced nextAccountNumber)
        info("Created new bank account with number: " + accountNumber + " and name: " + accountName);
        return CompletableFuture.completedFuture(account);
    }
    @Override
    public CompletableFuture<Integer> createBankAccountGetAccountNrAsync(String accountName)
    {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        CompletableFuture<@Nullable IAsyncBankAccount>  futureAccount = createBankAccountAsync(accountName);
        futureAccount.thenAccept(account -> {
            if(account == null) {
                future.complete(ServerBankAccount.INVALID_ACCOUNT_NUMBER);
            }
            else
                future.complete(account.getAccountNumberAsync());
        });
        return future;
    }

    @Override
    public @Nullable IServerBankAccount getBankAccount(int accountNumber)
    {
        return bankAccounts.get(accountNumber);
    }
    @Override
    public CompletableFuture<@Nullable IAsyncBankAccount> getBankAccountAsync(int accountNumber) {
        return CompletableFuture.completedFuture(bankAccounts.get(accountNumber));
    }



    @Override
    public List<IServerBankAccount> getBankAccounts(UUID userUUID)
    {
        List<IServerBankAccount> accounts = new ArrayList<>();
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            if(account.hasUser(userUUID)) {
                accounts.add(account); // Add the account if the user is a member
            }
        }
        return accounts; // Return all accounts the user is a member of
    }
    @Override
    public CompletableFuture<List<IAsyncBankAccount>> getBankAccountsAsync(UUID userUUID) {
        List<IAsyncBankAccount> accounts = new ArrayList<>();
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            if(account.hasUser(userUUID)) {
                accounts.add(account); // Add the account if the user is a member
            }
        }
        return CompletableFuture.completedFuture(accounts); // Return all accounts the user is a member of
    }





    @Override
    public @Nullable IServerBankAccount getBankAccountByName(String accountName)
    {
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            if(account.getAccountName().equals(accountName)) {
                return account;
            }
        }
        return null;
    }
    @Override
    public CompletableFuture<@Nullable IAsyncBankAccount> getBankAccountByNameAsync(String accountName)
    {
        return CompletableFuture.completedFuture(getBankAccountByName(accountName));
    }
    @Override
    public CompletableFuture<Integer> getBankAccountNrByNameAsync(String accountName)
    {
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            if(account.getAccountName().equals(accountName)) {
                return CompletableFuture.completedFuture(entry.getKey());
            }
        }
        return CompletableFuture.completedFuture(ServerBankAccount.INVALID_ACCOUNT_NUMBER);
    }



    @Override
    public List<Integer> getBankAccountNumbers(UUID userUUID)
    {
        List<Integer> accounts = new ArrayList<>();
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            if(account.hasUser(userUUID)) {
                accounts.add(account.getAccountNumber()); // Add the account if the user is a member
            }
        }
        return accounts; // Return all accounts the user is a member of
    }
    @Override
    public CompletableFuture<List<Integer>> getBankAccountNumbersAsync(UUID userUUID)
    {
        return CompletableFuture.completedFuture(getBankAccountNumbers(userUUID));
    }

    @Override
    public int countAccountsCreatedBy(UUID userUUID)
    {
        if (userUUID == null) return 0;
        int count = 0;
        for (ServerBankAccount account : bankAccounts.values()) {
            // Task #58 — creator-only count. Deliberately NOT hasUser/getBankAccountNumbers,
            // which count membership (shared accounts + company employees).
            if (userUUID.equals(account.getCreatorUUID())) {
                count++;
            }
        }
        return count;
    }





    @Override
    public List<Integer> getBankAccountNumbers(ItemID itemID)
    {
        List<Integer> accounts = new ArrayList<>();
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            if(account.hasBank(itemID)) {
                accounts.add(account.getAccountNumber()); // Add the account if the user is a member
            }
        }
        return accounts; // Return all accounts the user is a member of
    }
    @Override
    public CompletableFuture<List<Integer>> getBankAccountNumbersAsync(ItemID itemID)
    {
        return CompletableFuture.completedFuture(getBankAccountNumbers(itemID));
    }

    /**
     * Task #48 (v2.1.0) — enumerate accounts whose item bank for {@code itemID} holds a
     * strictly positive total balance. Consumed by the upcoming dividend distributor and
     * by the future "Companies" holder-count column. Linear scan over all accounts —
     * cheap enough for expected scales; may be revisited with a maintained reverse index
     * if it becomes hot (spec §Open Items).
     */
    @Override
    public java.util.Set<Integer> listAccountsHolding(ItemID itemID) {
        java.util.Set<Integer> accounts = new java.util.LinkedHashSet<>();
        if (itemID == null || !itemID.isValid()) return accounts;
        for (Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            net.kroia.banksystem.banking.bank.ServerBank bank = account.getBank(itemID);
            if (bank == null) continue;
            if (bank.getTotalBalance() > 0L) {
                accounts.add(account.getAccountNumber());
            }
        }
        return accounts;
    }




    @Override
    public List<BankAccountData> getBankAccountsData(UUID userUUID)
    {
        List<BankAccountData> accounts = new ArrayList<>();
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            if(account.hasUser(userUUID)) {
                accounts.add(account.getAccountData()); // Add the account if the user is a member
            }
        }
        return accounts; // Return all accounts the user is a member of
    }
    @Override
    public CompletableFuture<List<BankAccountData>> getBankAccountsDataAsync(UUID userUUID)
    {
        return CompletableFuture.completedFuture(getBankAccountsData(userUUID)); // Return all accounts the user is a member of
    }




    @Override
    public List<IServerBankAccount> getBankAccounts(ItemID itemID)
    {
        List<IServerBankAccount> accounts = new ArrayList<>();
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            if(account.hasBank(itemID)) {
                accounts.add(account); // Add the account if it has the bank for the given itemID
            }
        }
        return accounts; // Return all accounts that have a bank for the given itemID
    }
    @Override
    public CompletableFuture<List<IAsyncBankAccount>> getBankAccountsAsync(ItemID itemID) {
        List<IAsyncBankAccount> accounts = new ArrayList<>();
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            if(account.hasBank(itemID)) {
                accounts.add(account); // Add the account if it has the bank for the given itemID
            }
        }
        return CompletableFuture.completedFuture(accounts); // Return all accounts that have a bank for the given itemID
    }





    @Override
    public List<BankAccountData> getBankAccountsData(ItemID itemID)
    {
        List<BankAccountData> accounts = new ArrayList<>();
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            if(account.hasBank(itemID)) {
                accounts.add(account.getAccountData()); // Add the account if it has the bank for the given itemID
            }
        }
        return accounts; // Return all accounts that have a bank for the given itemID
    }
    @Override
    public CompletableFuture<List<BankAccountData>> getBankAccountsDataAsync(ItemID itemID)
    {
        return CompletableFuture.completedFuture(getBankAccountsData(itemID)); // Return all accounts that have a bank for the given itemID
    }





    @Override
    public @Nullable IServerBankAccount getPersonalBankAccount(UUID userUUID)
    {
        return getPersonalBankAccount_internal(userUUID);
    }
    @Override
    public CompletableFuture<@Nullable IAsyncBankAccount> getPersonalBankAccountAsync(UUID userUUID) {
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            User creator = account.getPersonalBankOwner();
            if(creator != null && creator.getUUID().equals(userUUID)) {
                return CompletableFuture.completedFuture(account); // Found the personal bank account
            }
        }
        return CompletableFuture.completedFuture(null); // No personal bank account found for this user
    }




    @Override
    public @Nullable BankAccountData getPersonalBankAccountData(UUID userUUID)
    {
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            User creator = account.getPersonalBankOwner();
            if(creator != null && creator.getUUID().equals(userUUID)) {
                return account.getAccountData(); // Found the personal bank account
            }
        }
        return null;
    }
    @Override
    public CompletableFuture<@Nullable BankAccountData> getPersonalBankAccountDataAsync(UUID userUUID)
    {
        return CompletableFuture.completedFuture(getPersonalBankAccountData(userUUID));
    }




    @Override
    public @Nullable IServerBankAccount getPersonalBankAccount(String userName)
    {
        return getPersonalBankAccount_internal(userName);
    }
    @Override
    public CompletableFuture<@Nullable IAsyncBankAccount> getPersonalBankAccountAsync(String userName) {
        return CompletableFuture.completedFuture(getPersonalBankAccount_internal(userName));
    }





    @Override
    public @Nullable BankAccountData getPersonalBankAccountData(String userName)
    {
        ISyncServerBankAccount personalAccount = getPersonalBankAccount(userName);
        if(personalAccount == null) {
            return null;
        }
        return personalAccount.getAccountData();
    }
    @Override
    public CompletableFuture<@Nullable BankAccountData> getPersonalBankAccountDataAsync(String userName)
    {
        User user = getUserByName(userName);
        if(user == null) {
            return CompletableFuture.completedFuture(null);
        }
        else
        {
            @Nullable ISyncServerBankAccount  account = getPersonalBankAccount(user.getUUID());
            if(account == null) {
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.completedFuture(account.getAccountData());
        }
    }




    @Override
    public @Nullable IServerBankAccount getOrCreatePersonalBankAccount(UUID userUUID)
    {
        return getOrCreatePersonalBankAccount_internal(userUUID);
    }
    @Override
    public CompletableFuture<@Nullable IAsyncBankAccount> getOrCreatePersonalBankAccountAsync(UUID userUUID) {
        IServerBankAccount account = getOrCreatePersonalBankAccount_internal(userUUID);
        return CompletableFuture.completedFuture(account);
    }




    @Override
    public @Nullable IServerBankAccount getOrCreatePersonalBankAccount(@NotNull String userName)
    {
        User user = getUserByName(userName);
        if(user == null)
            return null;
        else
            return getOrCreatePersonalBankAccount(user.getUUID());
    }
    @Override
    public CompletableFuture<@Nullable IAsyncBankAccount> getOrCreatePersonalBankAccountAsync(@NotNull String userName) {
        User user = getUserByName(userName);
        if(user == null)
            return CompletableFuture.completedFuture(null);
        else
            return getOrCreatePersonalBankAccountAsync(user.getUUID());
    }
    @Override
    public boolean userHasPersonalBankAccount(UUID userUUID)
    {
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            User creator = account.getPersonalBankOwner();
            if(creator != null && creator.getUUID().equals(userUUID)) {
                return true; // User has a personal bank account
            }
        }
        return false;
    }
    @Override
    public CompletableFuture<Boolean> userHasPersonalBankAccountAsync(UUID userUUID) {
        return CompletableFuture.completedFuture(userHasPersonalBankAccount(userUUID));
    }

    @Override
    public boolean deleteBankAccount(int accountNumber)
    {
        if(bankAccounts.containsKey(accountNumber)) {
            ServerBankAccount account = bankAccounts.get(accountNumber);
            if(account.getPersonalBankOwner() != null){
                error("Cannot delete personal bank account with number: " + accountNumber + ".");
                return false; // Cannot delete personal bank accounts
            }
            bankAccounts.remove(accountNumber);
            markPersistDirty(); // Task #55 (removed account is no longer iterated by update())
            // Task #33 (v2.0.5): cascade-drop every external-currency binding row for
            // the deleted account so stale rows never re-materialize on next load.
            BankAccountBindings bindings = BankAccountBindings.get();
            if (bindings != null) {
                int dropped = bindings.removeAllForAccount(accountNumber);
                if (dropped > 0) {
                    info("Dropped " + dropped + " external-currency binding row(s) alongside "
                            + "the deleted account (number: " + accountNumber + ").");
                }
            }
            BACKEND_INSTANCES.SERVER_EVENTS.BANK_ACCOUNT_DELETED.notifyListeners(account);
            info("Deleted bank account with number: " + accountNumber);
            return true;
        } else {
            warn("No bank account found with number: " + accountNumber);
        }
        return false;
    }
    @Override
    public CompletableFuture<Boolean> deleteBankAccountAsync(int accountNumber) {
        return CompletableFuture.completedFuture(deleteBankAccount(accountNumber));
    }



    @Override
    public boolean personalBankExists(UUID owner, ItemID itemID)
    {
        ISyncServerBank bank = getPersonalBank(owner, itemID);
        return bank != null;
    }
    @Override
    public CompletableFuture<Boolean> personalBankExistsAsync(UUID owner, ItemID itemID)
    {
        return CompletableFuture.completedFuture(personalBankExists(owner, itemID));
    }



    @Override
    public boolean personalBankExists(String ownerName, ItemID itemID)
    {
        ISyncServerBank bank = getPersonalBank(ownerName, itemID);
        return bank != null;
    }
    @Override
    public CompletableFuture<Boolean> personalBankExistsAsync(String ownerName, ItemID itemID)
    {
        return CompletableFuture.completedFuture(personalBankExists(ownerName, itemID));
    }






    @Override
    public @Nullable IServerBank getPersonalBank(UUID owner, ItemID itemID)
    {
        ISyncServerBankAccount account = getPersonalBankAccount(owner);
        if(account == null)
            return null;
        else
            return account.getBank(itemID);
    }
    @Override
    public CompletableFuture<@Nullable IAsyncBank> getPersonalBankAsync(UUID owner, ItemID itemID) {
        return CompletableFuture.completedFuture(getPersonalBank(owner, itemID));
    }







    @Override
    public @Nullable IServerBank getPersonalBank(String ownerName, ItemID itemID)
    {
        User owner = getUserByName(ownerName);
        if(owner == null)
        {
            return null;
        }
        ISyncServerBankAccount account = getPersonalBankAccount(owner.getUUID());
        if(account == null)
            return null;
        else
            return account.getBank(itemID);
    }
    @Override
    public CompletableFuture<@Nullable IAsyncBank> getPersonalBankAsync(String ownerName, ItemID itemID) {
        return CompletableFuture.completedFuture(getPersonalBank(ownerName, itemID));
    }







    @Override
    public @Nullable IServerBank getOrCreatePersonalBank(UUID owner, ItemID itemID)
    {
        ISyncServerBankAccount account = getOrCreatePersonalBankAccount(owner);
        if(account == null) {
            return null;
        }
        IServerBank bank = account.getBank(itemID);
        if(bank != null)
            return bank;
        // Task #57 chokepoint: never resurrect a slot for a blacklisted item (incl. a
        // disallowed money bank). Callers already tolerate null. account.createBank() also
        // gates on this, but the explicit check here keeps the intent obvious and skips the
        // account.createBank round trip.
        if(isItemIDBlacklisted(itemID))
            return null;
        return account.createBank(itemID, 0);
    }
    @Override
    public CompletableFuture<@Nullable IAsyncBank> getOrCreatePersonalBankAsync(UUID owner, ItemID itemID) {
        return CompletableFuture.completedFuture(getOrCreatePersonalBank(owner, itemID));
    }










    @Override
    public @Nullable IServerBank getOrCreatePersonalBank(String ownerName, ItemID itemID)
    {
        User owner = getUserByName(ownerName);
        if(owner == null)
            return null;
        else
            return getOrCreatePersonalBank(owner.getUUID(), itemID);
    }
    @Override
    public CompletableFuture<@Nullable IAsyncBank> getOrCreatePersonalBankAsync(String ownerName, ItemID itemID) {
        return CompletableFuture.completedFuture(getOrCreatePersonalBank(ownerName, itemID));
    }






    @Override
    public boolean isItemIDAllowed(ItemID itemID)
    {
        // Guard invalid inputs up front. The old code relied on "invalid ID cannot be in
        // the allow-set" to reject implicitly; adding the explicit gate makes the ALLOW_ALL
        // bypass safe (Task #39) — otherwise ALLOW_ALL would return true for an INVALID_ID.
        if (itemID == null || !itemID.isValid())
            return false;
        // Alias safety net: an ID merged into a canonical ID stays "allowed" iff its
        // canonical ID is allowed (the allowed set only stores canonical IDs after a
        // merge consolidation). O(1) map lookup.
        ItemID canonical = ItemIDManager.resolveAlias(itemID);
        // Blacklist always wins — same guarantee as allowItemID() enforces at add-time.
        // With ALLOW_ALL_ITEMS on this is the ONLY gate.
        if (isItemIDBlacklisted(canonical))
            return false;
        // Task #39: blacklist-only mode. When the admin has opted in via the mod-settings
        // screen, the explicit allow-list is bypassed and every non-blacklisted item is
        // bankable. Read live on every call (not cached) so toggling in-game takes effect
        // immediately without a server restart.
        if (BACKEND_INSTANCES.SERVER_SETTINGS.BANK.ALLOW_ALL_ITEMS.get())
            return true;
        return allowedItemIDs.contains(canonical);
    }
    @Override
    public CompletableFuture<Boolean> isItemIDAllowedAsync(ItemID itemID) {
        return CompletableFuture.completedFuture(isItemIDAllowed(itemID));
    }

    @Override
    public boolean allowItemID(ItemID itemID)
    {
        if(itemID == null || !itemID.isValid())
            return false;
        // Only ever store canonical IDs in the allowed set (see isItemIDAllowed).
        itemID = ItemIDManager.resolveAlias(itemID);
        // Static blacklist entries (air, command blocks, banknote denominations) can never be
        // re-allowed. A runtime disallow (Task #57) IS reversible: re-allowing clears the
        // runtime-blacklist entry first so a previously disallowed item (incl. money) becomes
        // bankable again and rejoins the default auto-create schedule (acceptance B).
        if(isStaticBlacklisted(itemID))
        {
            warn("It is not allowed to add the itemID: " + itemID + " because it is blacklisted.");
            return false;
        }
        // Perf: allowItemID runs on EVERY new bank slot (ServerBank.create). The O(companies×
        // schedules) resume scan below is only meaningful when this item was actually on the
        // runtime blacklist — a normal allowed deposit has no ban-marked schedules. Gate on the
        // remove() result so the common path (nothing removed) skips the scan entirely.
        boolean wasBlacklisted = this.blacklistedItemIDs.remove(itemID);
        this.allowedItemIDs.add(itemID);
        markPersistDirty(); // Task #55 / #57 (allowed-set + runtime-blacklist change)

        // Task #57b — re-allow resume: auto-resume ONLY schedules paused by this item's ban
        // (never a user-paused schedule). Company currency is NOT auto-restored. Master-only.
        if (wasBlacklisted) {
            net.kroia.banksystem.banking.company.CompanyManager cm =
                    net.kroia.banksystem.banking.company.CompanyManager.get();
            if (cm != null) {
                ItemID moneyId = MoneyItem.getItemID();
                boolean allowedIsMoney = moneyId != null && moneyId.isValid()
                        && moneyId.getShort() == itemID.getShort();
                cm.resumeCurrencyBannedSchedules(itemID.getShort(), allowedIsMoney);
            }
        }
        return true;
    }
    @Override
    public CompletableFuture<Boolean> allowItemIDAsync(ItemID itemID) {
        return CompletableFuture.completedFuture(allowItemID(itemID));
    }

    // No refund on disallow — multi-server item mapping makes refunds unreliable; admin responsibility
    @Override
    public boolean disallowItemID(ItemID itemID)
    {
        // Task #57: any item (incl. banksystem:money) can be disallowed. Delegate to the
        // reporting variant; a null return signals invalid input.
        return disallowItemIDAndReport(itemID) != null;
    }

    @Override
    public @Nullable List<DisallowedHolder> disallowItemIDAndReport(ItemID itemID)
    {
        if(itemID == null || !itemID.isValid())
            return null;
        // The allowed set and the account banks are keyed by canonical IDs only.
        itemID = ItemIDManager.resolveAlias(itemID);
        final ItemID finalItemID = itemID;
        final String itemName = itemID.getName();

        // 1) Audit BEFORE removal: capture every holder's free + locked balance, account
        //    number and owner name. Full dump goes to the server console; the returned list
        //    backs the (capped) chat summary shown to the command executor. NO REFUND.
        List<DisallowedHolder> cleared = new ArrayList<>();
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            if(!account.hasBank(finalItemID))
                continue;
            ServerBank bank = account.getBank(finalItemID);
            long free = bank != null ? bank.getBalance() : 0L;
            long locked = bank != null ? bank.getLockedBalance() : 0L;
            String ownerName = account.getAccountName();
            User owner = account.getPersonalBankOwner();
            if(owner != null)
                ownerName = owner.getName();
            cleared.add(new DisallowedHolder(entry.getKey(), ownerName, free, locked));
            info("[disallowItem] '" + itemName + "' held by account #" + entry.getKey()
                    + " (" + ownerName + "): free=" + free + " locked=" + locked
                    + " total=" + (free + locked) + " — clearing (NO REFUND)");
        }

        // 2) Remove the slot from every holder.
        for(DisallowedHolder holder : cleared) {
            ServerBankAccount account = bankAccounts.get(holder.accountNr());
            if(account != null)
                account.removeBank(finalItemID);
        }

        // 3) Drop from the allowed set and add to the runtime blacklist so the disallow sticks
        //    even with ALLOW_ALL_ITEMS on, and survives a restart (persisted, Task #57).
        allowedItemIDs.remove(finalItemID);
        blacklistedItemIDs.add(finalItemID);
        markPersistDirty(); // Task #55 (allowed-set change + cleared holder banks) / #57 (runtime blacklist)
        info("[disallowItem] '" + itemName + "' disallowed — cleared " + cleared.size()
                + " holder account(s).");

        // Task #57b — company-currency fallback + payout-schedule pause on ban. Master-only
        // direct call (CompanyManager is master-only; null on slave/test). Full console dump
        // happens inside cascadeCurrencyBan; the command handler reads getLastCurrencyBanReport()
        // for the capped admin-chat summary.
        net.kroia.banksystem.banking.company.CompanyManager cm =
                net.kroia.banksystem.banking.company.CompanyManager.get();
        if (cm != null) {
            ItemID moneyId = MoneyItem.getItemID();
            boolean bannedIsMoney = moneyId != null && moneyId.isValid()
                    && moneyId.getShort() == finalItemID.getShort();
            cm.cascadeCurrencyBan(finalItemID.getShort(), bannedIsMoney);
        }
        return cleared;
    }
    @Override
    public CompletableFuture<Boolean> disallowItemIDAsync(ItemID itemID) {
        return CompletableFuture.completedFuture(disallowItemID(itemID));
    }

    @Override
    public boolean isItemIDBlacklisted(ItemID itemID)
    {
        if (itemID == null)
            return false;
        ItemID canonical = ItemIDManager.resolveAlias(itemID);
        // Runtime (admin-set, Task #57) blacklist wins the same as the static list.
        if (blacklistedItemIDs.contains(canonical))
            return true;
        return isStaticBlacklisted(canonical);
    }

    /**
     * Task #57: the compile-time settings blacklist ({@code INITIAL_BLACKLIST_ITEMS}) only.
     * Items here can never be re-allowed (air, command blocks, banknote denominations),
     * unlike the runtime blacklist which {@code allowItemID} can clear.
     */
    private boolean isStaticBlacklisted(ItemID itemID)
    {
        List<ItemStack> blacklistItems = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.INITIAL_BLACKLIST_ITEMS;
        List<ItemID> itemIDs = ItemIDManager.registerItemStackServerSide_direct(blacklistItems);
        for(ItemID id : itemIDs)
        {
            if(id.equals(itemID))
            {
                return true;
            }
        }
        return false;
    }
    @Override
    public CompletableFuture<Boolean> isItemIDBlacklistedAsync(ItemID itemID) {
        return CompletableFuture.completedFuture(isItemIDBlacklisted(itemID));
    }



    @Override
    public int getItemFractionScaleFactor()
    {
        return BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
    }
    @Override
    public CompletableFuture<Integer> getItemFractionScaleFactorAsync()
    {
        return CompletableFuture.completedFuture(getItemFractionScaleFactor());
    }




    @Override
    public double getRealMoneyCirculation()
    {
        double total = 0;
        ItemID moneyItemID = MoneyItem.getItemID();
        for (Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            ISyncServerBank moneyBank = account.getBank(moneyItemID);
            if(moneyBank != null) {
                total += moneyBank.getRealTotalBalance();
            }
        }
        return total;
    }
    @Override
    public CompletableFuture<Double> getRealMoneyCirculationAsync() {
        return CompletableFuture.completedFuture(getRealMoneyCirculation());
    }

    @Override
    public double getRealLockedMoneyCirculation()
    {
        double total = 0;
        ItemID moneyItemID = MoneyItem.getItemID();
        for (Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            ISyncServerBank moneyBank = account.getBank(moneyItemID);
            if(moneyBank != null) {
                total += moneyBank.getRealLockedBalance();
            }
        }
        return total;
    }
    @Override
    public CompletableFuture<Double> getRealLockedMoneyCirculationAsync() {
        return CompletableFuture.completedFuture(getRealLockedMoneyCirculation());
    }

    @Override
    public double getRealItemCirculation(ItemID itemID)
    {
        double total = 0;
        for (Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            ISyncServerBank bank = account.getBank(itemID);
            if(bank != null)
                total += bank.getRealTotalBalance();
        }
        return total;
    }
    @Override
    public CompletableFuture<Double> getRealItemCirculationAsync(ItemID itemID) {
        return CompletableFuture.completedFuture(getRealItemCirculation(itemID));
    }

    @Override
    public double getRealLockedItemCirculation(ItemID itemID)
    {
        double total = 0;
        for (Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            ISyncServerBank bank = account.getBank(itemID);
            if(bank != null)
                total += bank.getRealLockedBalance();
        }
        return total;
    }
    @Override
    public CompletableFuture<Double> getRealLockedItemCirculationAsync(ItemID itemID) {
        return CompletableFuture.completedFuture(getRealLockedItemCirculation(itemID));
    }

    @Override
    public JsonElement getCirculationDataJson()
    {
        class Data
        {
            public ItemID itemID;
            public double lockedBalance = 0;
            public double freeBalance = 0;
        }
        Map<ItemID, Data> sums = new HashMap<>();
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet())
        {
            ServerBankAccount account = entry.getValue();
            for(Map.Entry<ItemID, IServerBank> bankEntry : account.getAllBanks().entrySet())
            {
                ItemID itemID = bankEntry.getKey();
                ISyncServerBank bank = bankEntry.getValue();
                if(bank == null)
                    continue;
                Data data = sums.computeIfAbsent(itemID, k -> new Data());
                data.itemID = itemID;
                data.lockedBalance += bank.getRealLockedBalance();
                data.freeBalance += bank.getRealBalance();
            }
        }

        JsonArray circulationData = new JsonArray();
        for(Map.Entry<ItemID, Data> entry : sums.entrySet())
        {
            Data data = entry.getValue();
            JsonObject itemData = new JsonObject();
            itemData.add("itemID", data.itemID.toJson());
            itemData.addProperty("lockedBalance", data.lockedBalance);
            itemData.addProperty("freeBalance", data.freeBalance);
            circulationData.add(itemData);
        }
        return circulationData;
    }
    @Override
    public CompletableFuture<JsonElement> getCirculationDataJsonAsync() {
        return CompletableFuture.completedFuture(getCirculationDataJson());
    }

    @Override
    public String getCirculationDataJsonString()
    {
        JsonElement circulationData = getCirculationDataJson();
        return JsonUtilities.toPrettyString(circulationData);
    }
    @Override
    public CompletableFuture<String> getCirculationDataJsonStringAsync() {
        return CompletableFuture.completedFuture(getCirculationDataJsonString());
    }


    @Override
    public long convertToRawAmount(double realAmount)
    {
        return BankManager.convertToRawAmountStatic(realAmount);
    }
    @Override
    public double convertToRealAmount(long rawAmount)
    {
        return BankManager.convertToRealAmountStatic(rawAmount);
    }


    @Override
    public JsonElement toJson()
    {
        JsonObject jsonObject = new JsonObject();

        JsonArray usersJson = new JsonArray();
        for (User user : userMap.values()) {
            usersJson.add(user.toJson());
        }
        jsonObject.add("users", usersJson);

        JsonArray itemScaleFactorsJson = new JsonArray();
        for (ItemID itemID : allowedItemIDs) {
            JsonObject itemScaleFactorJson = new JsonObject();
            itemScaleFactorJson.add("itemID", itemID.toJson());
            itemScaleFactorsJson.add(itemScaleFactorJson);
        }
        jsonObject.add("itemCentScaleFactors", itemScaleFactorsJson);

        JsonArray accountsJson = new JsonArray();
        for (ServerBankAccount account : bankAccounts.values()) {
            accountsJson.add(account.toJson());
        }
        jsonObject.add("bankAccounts", accountsJson);
        return jsonObject;
    }
    @Override
    public CompletableFuture<JsonElement> toJsonAsync() {
        return CompletableFuture.completedFuture(toJson());
    }





    @Override
    public String toJsonString()
    {
        return JsonUtilities.toPrettyString(toJson());
    }
    @Override
    public CompletableFuture<String> toJsonStringAsync() {
        return CompletableFuture.completedFuture(toJsonString());
    }


    @Override
    public void onPlayerJoin(UUID playerUUID, String playerName)
    {
        if(!userExists(playerUUID)) {
            addUser(playerUUID, playerName);
        }
        updateUserName(playerUUID, playerName);
        ServerBankAccount account = getPersonalBankAccount_internal(playerUUID);
        if(account == null)
        {
            createPersonalBankAccount(playerUUID);
        }
    }
    @Override
    public void onPlayerJoinAsync(UUID playerUUID, String playerName) {
        onPlayerJoin(playerUUID, playerName);
    }



    private int generateNewAccountNumber()
    {
        int newBankNumber = nextAccountNumber;
        while(bankAccounts.containsKey(newBankNumber)) {
            newBankNumber++;
        }
        nextAccountNumber = newBankNumber+1; // Increment for the next account number
        return newBankNumber;
    }

    public void setupDefaultItems()
    {
        // Check if all allowed items have a scale factor
        List<ItemStack> allowedItems = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.INITIAL_ALLOWED_ITEMS;
        List<ItemID> itemIDs = ItemIDManager.registerItemStackServerSide_direct(allowedItems);
        // Never admit INVALID_ID into the allowed set (Task #16 review): registration can
        // refuse (registration latch armed while ItemIDs.nbt failed to load, ItemID space
        // exhausted) and returns INVALID_ID then. An INVALID entry would be persisted by
        // save() and permanently poison the allowed set on the next load (allowedItems key
        // present -> clear() + restore {INVALID} -> nothing bankable ever again).
        int refused = 0;
        for (ItemID id : itemIDs) {
            if (id != null && id.isValid())
                allowedItemIDs.add(id);
            else
                refused++;
        }
        if (refused > 0)
            warn("setupDefaultItems: " + refused + " of " + itemIDs.size() + " default allowed "
                    + "item(s) could not be registered (see preceding ItemIDManager log) and "
                    + "were NOT added to the allowed set.");
        if (refused < itemIDs.size())
            markPersistDirty(); // Task #55 (seeded the default allowed set — fresh-world path)
    }

    /**
     * Consolidates all per-ItemID state of this (master) bank manager after an ItemID
     * alias merge (see {@code ItemIDManager.renormalizeAndMerge()}):
     * <ol>
     *   <li>the allowed-item set: aliased entries are replaced by their canonical ID
     *       (the {@code Set} dedupes automatically) — done FIRST so subsequent
     *       allowed-checks already see the canonical entries,</li>
     *   <li>every bank account: banks keyed by an alias are merged into the canonical
     *       bank, preserving both free and locked balances, and aliased account icons
     *       are rewritten (see {@link ServerBankAccount#consolidateMergedItemIDs}).</li>
     * </ol>
     * Called by {@code ItemIDManager.consolidatePendingMerges()} on the server thread,
     * master side only. Idempotent: re-running with the same map is a no-op because no
     * bank/entry is keyed by an alias anymore.
     *
     * @param aliasToCanonical merged ItemID (alias) → canonical ItemID pairs
     */
    public void consolidateMergedItemIDs(Map<ItemID, ItemID> aliasToCanonical)
    {
        if (aliasToCanonical == null || aliasToCanonical.isEmpty())
            return;

        // 1) Allowed-item set: alias entries become their canonical ID.
        int remappedAllowed = 0;
        for (Map.Entry<ItemID, ItemID> entry : aliasToCanonical.entrySet()) {
            if (allowedItemIDs.remove(entry.getKey())) {
                allowedItemIDs.add(entry.getValue());
                remappedAllowed++;
            }
            // Task #57: keep the runtime blacklist keyed by canonical IDs too.
            if (blacklistedItemIDs.remove(entry.getKey())) {
                blacklistedItemIDs.add(entry.getValue());
                remappedAllowed++;
            }
        }

        // 2) Bank accounts: merge alias-keyed banks into the canonical bank.
        int mergedBanks = 0;
        for (ServerBankAccount account : bankAccounts.values()) {
            mergedBanks += account.consolidateMergedItemIDs(aliasToCanonical);
        }

        if (remappedAllowed > 0 || mergedBanks > 0) {
            markPersistDirty(); // Task #55 (allowed-set remap / bank merges changed persisted state)
            info("Consolidated ItemID merge: " + mergedBanks + " bank(s) merged into their canonical ItemID, "
                    + remappedAllowed + " allowed-item entr(y/ies) remapped (" + aliasToCanonical.size()
                    + " alias pair(s)). Balances and locked balances were preserved.");
        }
    }

    @Override
    public boolean save(Map<String, ListTag> listTags) {
        CompoundTag metaData = new CompoundTag();
        metaData.putInt("version", 1); // Versioning for future changes
        metaData.putInt("nextAccountNumber", nextAccountNumber);
        ListTag metaTagList = new ListTag();
        metaTagList.add(metaData);
        listTags.put("meta", metaTagList);


        ListTag userList = new ListTag();
        for (Map.Entry<UUID, User> entry : userMap.entrySet()) {
            CompoundTag userTag = new CompoundTag();
            entry.getValue().save(userTag);
            userList.add(userTag);
        }
        listTags.put("users", userList);


        ListTag allowedItems = new ListTag();
        for (ItemID itemID : allowedItemIDs) {
            // Defensive (Task #16 review): never persist the INVALID short (0) into the
            // allowedItems list — a persisted INVALID entry survives every future load and
            // permanently degrades the allowed set. Should be unreachable now that
            // setupDefaultItems()/allowItemID() filter, hence the WARN if it ever trips.
            if (itemID == null || itemID.getShort() == ItemID.INVALID_ID.getShort()) {
                warn("save: skipped INVALID entry in the allowed-items set (should be "
                        + "unreachable — investigate how it was inserted).");
                continue;
            }
            CompoundTag pairTag = new CompoundTag();
            CompoundTag itemTag = new CompoundTag();
            itemID.save(itemTag);
            pairTag.put("itemID", itemTag);
            allowedItems.add(pairTag);
        }
        listTags.put("allowedItems", allowedItems);

        // Task #57: persist the runtime (admin-set) blacklist so a disallow (incl. money)
        // survives a restart. Same INVALID-short guard as the allowed set.
        ListTag blacklistedItems = new ListTag();
        for (ItemID itemID : blacklistedItemIDs) {
            if (itemID == null || itemID.getShort() == ItemID.INVALID_ID.getShort())
                continue;
            CompoundTag pairTag = new CompoundTag();
            CompoundTag itemTag = new CompoundTag();
            itemID.save(itemTag);
            pairTag.put("itemID", itemTag);
            blacklistedItems.add(pairTag);
        }
        listTags.put("blacklistedItems", blacklistedItems);

        ListTag accountsList = new ListTag();
        for (Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            CompoundTag accountTag = new CompoundTag();
            entry.getValue().save(accountTag);
            accountsList.add(accountTag);
        }
        listTags.put("bankAccounts", accountsList);


        ListTag trustedSlaves = new ListTag();
        for(String slaveID : trustedSlaveServers)
        {
            trustedSlaves.add(StringTag.valueOf(slaveID));
        }
        listTags.put("trustedSlaves", trustedSlaves);



        return true;
    }

    @Override
    public boolean load(Map<String, ListTag> listTags) {
        CompoundTag metaData = listTags.getOrDefault("meta", new ListTag()).getCompound(0);
        int version = metaData.getInt("version");
        nextAccountNumber = metaData.getInt("nextAccountNumber");


        // Load item cent scale factors
        if(listTags.containsKey("allowedItems")) {
            ListTag allowedItems = listTags.get("allowedItems");
            allowedItemIDs.clear();
            for (int i = 0; i < allowedItems.size(); i++) {
                CompoundTag idTag = allowedItems.getCompound(i);
                if(!idTag.contains("itemID")) {
                    continue; // Skip invalid entries
                }

                ItemID itemID = ItemID.createFromTag(idTag.getCompound("itemID"));
                // Defensive (Task #16 review): drop persisted INVALID shorts (0) — written
                // by builds without the save/setup filters. Restoring one would permanently
                // poison the allowed set (every save re-persists it).
                if (itemID.getShort() == ItemID.INVALID_ID.getShort()) {
                    warn("load: dropped a persisted INVALID entry from the allowed-items list "
                            + "(written by an earlier buggy recovery — healed on the next save).");
                    continue;
                }
                // Canonicalize at load time: a saved allowed-entry may reference an ID that
                // was merged into a canonical ID (possibly in an earlier session). The Set
                // dedupes collapsing entries automatically. This also heals worlds merged
                // before consolidation existed.
                allowedItemIDs.add(ItemIDManager.resolveAlias(itemID));
            }
            // An empty restored allowed set normally comes from a degenerate save — e.g. the
            // unreadable-ItemIDs.nbt recovery pass, where every default registration was
            // refused by the registration latch (Task #16 review). Re-seed the defaults so
            // the recovered world is bankable again. (Task #57: base money is no longer
            // "not-removable", so a legitimately empty allowed set is now also possible if an
            // admin disallowed everything — but re-seeding defaults is still the safe recovery
            // for the far-more-common degenerate case, and any runtime blacklist loaded below
            // still suppresses re-seeded-then-disallowed items.)
            if (allowedItemIDs.isEmpty()) {
                warn("load: the persisted allowed-items set restored EMPTY (degenerate save, "
                        + "e.g. an unreadable-ItemIDs recovery pass) — re-seeding the default "
                        + "allowed items.");
                setupDefaultItems();
            }
        }
        else {
            setupDefaultItems(); // Setup default items if no scale factors are present
        }

        // Task #57: restore the runtime (admin-set) blacklist. Loaded AFTER the allowed set so
        // a re-seeded default (e.g. money) that the admin had disallowed is pruned back out of
        // the allowed set here, keeping the two views consistent.
        blacklistedItemIDs.clear();
        if(listTags.containsKey("blacklistedItems")) {
            ListTag blacklistedItems = listTags.get("blacklistedItems");
            for (int i = 0; i < blacklistedItems.size(); i++) {
                CompoundTag idTag = blacklistedItems.getCompound(i);
                if(!idTag.contains("itemID"))
                    continue;
                ItemID itemID = ItemID.createFromTag(idTag.getCompound("itemID"));
                if (itemID.getShort() == ItemID.INVALID_ID.getShort())
                    continue;
                ItemID canonical = ItemIDManager.resolveAlias(itemID);
                blacklistedItemIDs.add(canonical);
                allowedItemIDs.remove(canonical);
            }
        }



        // Load users
        if(listTags.containsKey("users")) {
            ListTag userList = listTags.get("users");
            userMap.clear();
            for (int i = 0; i < userList.size(); i++) {
                CompoundTag userTag = userList.getCompound(i);
                User user = User.createFromTag(userTag);
                if(user != null) {
                    userMap.put(user.getUUID(), user);
                } else {
                    warn("Failed to load user from tag: " + userTag);
                }
            }
        }

        // Load bank accounts
        if(listTags.containsKey("bankAccounts")) {
            ListTag accountsList = listTags.get("bankAccounts");
            bankAccounts.clear();
            for (int i = 0; i < accountsList.size(); i++) {
                CompoundTag accountTag = accountsList.getCompound(i);
                ServerBankAccount account = ServerBankAccount.createFromTag(accountTag);
                if(account != null) {
                    bankAccounts.put(account.getAccountNumber(), account);
                } else {
                    warn("Failed to load bank account from tag: " + accountTag);
                }
            }
        }

        if(listTags.containsKey("trustedSlaves")) {
            ListTag trustedSlaves = listTags.get("trustedSlaves");
            trustedSlaveServers.clear();
            for (net.minecraft.nbt.Tag trustedSlave : trustedSlaves) {
                String slaveID = trustedSlave.getAsString();
                trustedSlaveServers.add(slaveID);
            }
        }

        reconcileAllowedItemsWithBanks();
        return true;
    }

    /**
     * Adds every ItemID that currently holds a bank slot to the allowed-items list.
     * <p>
     * Keeps the invariant "anything that sits in a bank stays bankable" true, which matters
     * in two places: the Bank Download block's item picker is built from the allowed list, so
     * without this it would silently omit items deposited while {@code ALLOW_ALL_ITEMS} was
     * on; and turning that setting back off would otherwise strand the balances those items
     * already have. New deposits are covered at the source by {@code ServerBank.create} —
     * this sweep heals worlds whose banks predate that.
     * <p>
     * Runs after the accounts are loaded (the allowed set is restored before them, so the
     * additions cannot be cleared again). Blacklisted IDs are skipped silently: the blacklist
     * always wins, and a blacklisted item holding a balance is reported elsewhere.
     */
    private void reconcileAllowedItemsWithBanks() {
        int added = 0;
        for (ServerBankAccount account : bankAccounts.values()) {
            for (ItemID itemID : account.getAllBanks().keySet()) {
                if (itemID == null || !itemID.isValid())
                    continue;
                ItemID canonical = ItemIDManager.resolveAlias(itemID);
                if (allowedItemIDs.contains(canonical) || isItemIDBlacklisted(canonical))
                    continue;
                if (allowItemID(canonical))
                    added++;
            }
        }
        if (added > 0)
            info("load: " + added + " item(s) hold a bank balance without being on the "
                    + "allowed-items list (deposited while ALLOW_ALL_ITEMS was on) — added.");
    }

    public boolean load_compatibilityMode_setNextAccountNumber(int nextAccountNumber)
    {
        this.nextAccountNumber = nextAccountNumber;
        return true;
    }
    /*public boolean load_compatibilityMode_setItemFractionScaleFactors(Map<ItemID, Integer> itemFractionScaleFactor)
    {
        this.itemFractionScaleFactor.clear();
        this.itemFractionScaleFactor.putAll(itemFractionScaleFactor);
        return true;
    }*/
    public boolean load_compatibilityMode_setUsers(Map<UUID, User> userMap)
    {
        this.userMap.clear();
        this.userMap.putAll(userMap);
        return true;
    }
    public boolean load_compatibilityMode_setBankAccounts(Map<Integer, ServerBankAccount> bankAccounts)
    {
        this.bankAccounts.clear();
        this.bankAccounts.putAll(bankAccounts);
        return true;
    }




    public @Nullable ServerBankAccount getPersonalBankAccount_internal(UUID userUUID)
    {
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            User creator = account.getPersonalBankOwner();
            if(creator != null && creator.getUUID().equals(userUUID)) {
                return account; // Found the personal bank account
            }
        }
        return null; // No personal bank account found for this user
    }
    public @Nullable ServerBankAccount getPersonalBankAccount_internal(String userName)
    {
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            User creator = account.getPersonalBankOwner();
            if(creator != null && creator.getName().equals(userName)) {
                return account; // Found the personal bank account
            }
        }
        return null; // No personal bank account found for this user
    }
    public @Nullable ServerBankAccount getOrCreatePersonalBankAccount_internal(UUID userUUID)
    {
        ServerBankAccount account = getPersonalBankAccount_internal(userUUID);
        if(account != null) {
            return account;
        }
        else {
            return createPersonalBankAccount_internal(userUUID);
        }
    }

    public @Nullable ServerBankAccount createPersonalBankAccount_internal(UUID user)
    {
        User creator = userMap.get(user);
        if(creator == null) {
            warn("No user found with UUID: " + user);
            return null;
        }

        ServerBankAccount existingAccount = getPersonalBankAccount_internal(user);
        if(existingAccount != null) {
            //warn("User with UUID: " + user + " already has a personal bank account with number: " + existingAccount.getAccountNumber());
            return existingAccount;
        }

        int accountNumber = generateNewAccountNumber();
        long startBalance = BACKEND_INSTANCES.SERVER_SETTINGS.PLAYER.STARTING_BALANCE.get();
        ServerBankAccount account = ServerBankAccount.createPersonal(accountNumber, creator, startBalance);
        if(account == null) {
            warn("Failed to create personal bank account for user with UUID: " + user);
            return null;
        }
        bankAccounts.put(accountNumber, account);
        markPersistDirty(); // Task #55 (new personal account + advanced nextAccountNumber)
        // Seed the default slots (money + each available external-currency provider) through
        // the allow/blacklist gate. Idempotent with createPersonal's own gated money seed, and
        // the single source of truth for what a fresh account starts with (Task #57).
        addDefaultBankSlots(account);
        return account;
    }

    /**
     * Seeds a freshly created bank account with the base {@code banksystem:money} slot plus
     * one slot per available external-currency provider (Numismatics, Lightman's, ...).
     * Also allowlists the seeded external items so downstream operations (deposit, banking
     * terminal display) treat them as valid bank items. Idempotent:
     * {@link IServerBankAccount#createBank} short-circuits on existing keys and
     * {@link #allowItemID(ItemID)} is a set-add, so calling this repeatedly is safe. Silently
     * skips providers whose base-currency item id cannot be resolved on this server. Shared
     * entry point for both {@code /bank create} and personal-account creation on player join.
     */
    public static void addDefaultBankSlots(IServerBankAccount account)
    {
        if (account == null || BACKEND_INSTANCES == null) return;
        IServerBankManager bankManager = BACKEND_INSTANCES.SERVER_BANK_MANAGER != null
                ? BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync() : null;

        // Task #57: ONE default-items list = { money } ∪ { each available external provider's
        // base currency item }. Every entry — money included — passes the same gate
        // (!blacklisted && (allowed || ALLOW_ALL)) before its slot is created. Money is just a
        // default-on entry now: a disallowed money item yields an account with no money slot,
        // no special-case path. External items are auto-allowlisted (as before) so they become
        // bankable; a blacklisted external is skipped deliberately (no phantom empty slot).
        seedDefaultSlot(account, bankManager, MoneyItem.getItemID(), false);

        Collection<ExternalCurrencyProvider> providers = BankSystemMod.getAPI().getCurrencyProviders();
        if (BACKEND_INSTANCES.LOGGER != null) {
            BACKEND_INSTANCES.LOGGER.info("[ServerBankManager] addDefaultBankSlots on account "
                    + account.getAccountNumber() + ": " + providers.size() + " currency provider(s) registered");
        }
        for (ExternalCurrencyProvider provider : providers)
        {
            if (provider == null) continue;
            String providerId = provider.providerId();
            if (!provider.isAvailable()) {
                if (BACKEND_INSTANCES.LOGGER != null)
                    BACKEND_INSTANCES.LOGGER.debug("[ServerBankManager] skipped provider '" + providerId
                            + "': not available");
                continue;
            }
            String itemIdStr = provider.getBaseCurrencyItemId();
            if (itemIdStr == null || itemIdStr.isEmpty()) {
                if (BACKEND_INSTANCES.LOGGER != null)
                    BACKEND_INSTANCES.LOGGER.debug("[ServerBankManager] skipped provider '" + providerId
                            + "': no base currency item declared");
                continue;
            }
            ItemStack stack = ItemUtilities.createItemStackFromId(itemIdStr);
            if (stack == null || stack.isEmpty() || stack.is(Items.AIR)) {
                if (BACKEND_INSTANCES.LOGGER != null)
                    BACKEND_INSTANCES.LOGGER.warn("[ServerBankManager] provider '" + providerId
                            + "' declares baseCurrencyItemId '" + itemIdStr
                            + "' but the item could not be resolved from the item registry — skipping slot seed");
                continue;
            }
            ItemID itemID = ItemIDManager.registerItemStackServerSide_direct(stack);
            if (itemID == null || !itemID.isValid()) {
                if (BACKEND_INSTANCES.LOGGER != null)
                    BACKEND_INSTANCES.LOGGER.warn("[ServerBankManager] failed to register itemID for '"
                            + itemIdStr + "' (provider '" + providerId + "') — skipping slot seed");
                continue;
            }
            boolean seeded = seedDefaultSlot(account, bankManager, itemID, true);
            if (seeded && BACKEND_INSTANCES.LOGGER != null)
                BACKEND_INSTANCES.LOGGER.info("[ServerBankManager] seeded '" + itemIdStr
                        + "' slot on account " + account.getAccountNumber() + " (provider '" + providerId
                        + "', itemID short=" + itemID.getShort() + ")");
        }
    }

    /**
     * Task #57: seeds a single default slot through the allow/blacklist gate. Returns true iff
     * a slot was created (or already existed). Skips deliberately — no phantom empty slot —
     * when the item is blacklisted or not allowed and ALLOW_ALL_ITEMS is off.
     *
     * @param autoAllow when true (external-currency items), the item is added to the allowed
     *                  set before the gate so it becomes bankable — matching the historical
     *                  behaviour. Money relies on the default allowed set instead.
     */
    private static boolean seedDefaultSlot(IServerBankAccount account, IServerBankManager bankManager,
                                           ItemID itemID, boolean autoAllow)
    {
        if (account == null || itemID == null || !itemID.isValid()) return false;
        if (bankManager != null) {
            if (bankManager.isItemIDBlacklisted(itemID))
                return false; // blacklist wins — no phantom slot (deliverable 2/E)
            if (autoAllow)
                bankManager.allowItemID(itemID); // idempotent; refuses static-blacklisted with a WARN
            if (!bankManager.isItemIDAllowed(itemID))
                return false; // not allowed and ALLOW_ALL off → skip deliberately
        }
        return account.createBank(itemID, 0) != null;
    }


    // =====================================================================
    // External-currency bindings (Task #33, v2.0.5).
    // -----------------------------------------------------------------------
    // These methods live here (rather than on ServerBankAccount) because bind
    // and unbind are cross-cutting: they consult the ExternalCurrencyProvider
    // registry, the account's shared / personal state, the target bank's
    // current balance, and the BankAccountBindings savedata — all reachable
    // from the master's bank manager. They are called from the UI layer
    // (Stage 3) and — in the meantime — from tests (Stage 4). Not exposed on
    // IServerBankManager: intentional scope-limit for Stage 2. Multi-server
    // bindings-over-the-wire are out of scope until Task #35 validation.
    // =====================================================================

    /**
     * Binds a BankSystem slot to an external currency-mod account (Task #33).
     * <p>
     * Preconditions (checked in this order, each with a WARN log on failure):
     * <ul>
     *   <li>The account exists ({@link BankStatus#FAILED_NO_BANK} otherwise).</li>
     *   <li>The item slot exists on the account ({@link BankStatus#FAILED_NO_BANK}).</li>
     *   <li>The bank's current {@code getTotalBalance()} is exactly 0 — the safest
     *       v1 policy. Non-zero balances must be withdrawn before binding to avoid
     *       ambiguity about what the external "starting balance" represents.
     *       ({@link BankStatus#FAILED_INVALID_ITEM_ID} otherwise — closest fit in
     *       the existing status enum for a bind-precondition violation.)</li>
     *   <li>The referenced provider is registered and available
     *       ({@link BankStatus#FAILED_EXTERNAL_UNAVAILABLE} otherwise).</li>
     *   <li>The ref resolves ({@code provider.open(ref) != null},
     *       {@link BankStatus#FAILED_EXTERNAL_UNAVAILABLE} otherwise).</li>
     *   <li>{@code ref.shared()} matches the account's shared state (personal →
     *       non-shared; non-personal → shared). Mismatch:
     *       {@link BankStatus#FAILED_WRONG_INSTANCE_TYPE}.</li>
     * </ul>
     * On success a fresh binding row is written and future balance operations
     * on the slot delegate to the external mod. Locked amount starts at 0.
     *
     * @param accountId BankSystem account number
     * @param itemId    item slot on the account
     * @param ref       external account to bind to
     * @return SUCCESS on success; a specific FAILED_* status on any precondition
     *         violation (WARN-logged with a human-readable reason)
     */
    public BankStatus bindExternalAccount(int accountId, ItemID itemId, ExternalAccountRef ref) {
        if (itemId == null || !itemId.isValid() || ref == null) {
            warn("bindExternalAccount refused: invalid arguments (accountId=" + accountId
                    + ", itemId=" + itemId + ", ref=" + ref + ")");
            return BankStatus.FAILED_INVALID_ITEM_ID;
        }
        itemId = ItemIDManager.resolveAlias(itemId);
        ServerBankAccount account = bankAccounts.get(accountId);
        if (account == null) {
            warn("bindExternalAccount refused: account " + accountId + " does not exist");
            return BankStatus.FAILED_NO_BANK;
        }
        IServerBank bank = account.getBank(itemId);
        if (bank == null || !(bank instanceof ServerBank serverBank)) {
            warn("bindExternalAccount refused: no bank slot for item " + itemId
                    + " on account " + accountId);
            return BankStatus.FAILED_NO_BANK;
        }
        // Auto-transfer local balance to external (Task #33 v2.0.5 refinement).
        // Free balance is deposited to external (split into whole-native + dust); locked
        // balance is carried over into the BindingRow so pending orders keep referencing it
        // through the bound slot. Reading getTotalBalance / getBalance / getLockedBalance is
        // safe even on a (still non-bound) slot — routes through the local logic.
        long localFree = serverBank.getBalance();
        long localLocked = serverBank.getLockedBalance();
        // Provider must exist and be available.
        ExternalCurrencyProvider provider = BankSystemMod.getAPI().getCurrencyProvider(ref.providerId());
        if (provider == null || !provider.isAvailable()) {
            warn("bindExternalAccount refused: provider '" + ref.providerId()
                    + "' is not registered or not available on this server");
            return BankStatus.FAILED_EXTERNAL_UNAVAILABLE;
        }
        // Ref must open.
        ExternalAccount external = provider.open(ref);
        if (external == null) {
            warn("bindExternalAccount refused: provider '" + ref.providerId()
                    + "' could not open account (ref=" + ref + ")");
            return BankStatus.FAILED_EXTERNAL_UNAVAILABLE;
        }
        // Shared-state must match: personal (owner != null) → non-shared;
        // non-personal (owner == null) → shared.
        boolean accountIsShared = account.getPersonalBankOwner() == null;
        if (accountIsShared != ref.shared()) {
            warn("bindExternalAccount refused: shared-state mismatch — account "
                    + accountId + " is " + (accountIsShared ? "shared" : "personal")
                    + " but ref " + ref + " is " + (ref.shared() ? "shared" : "non-shared"));
            return BankStatus.FAILED_WRONG_INSTANCE_TYPE;
        }
        // Currency-item match: if the provider declares a base currency item, the
        // BankSystem slot must be for that same item. Prevents nonsense bindings
        // like "diamond slot bound to Numismatics" where spur deposits would credit
        // diamonds. Providers returning null opt out of this check (e.g. LC).
        String providerCurrencyItem = provider.getBaseCurrencyItemId();
        if (providerCurrencyItem != null) {
            String slotItemName = itemId.getName();
            if (slotItemName == null || !providerCurrencyItem.equals(slotItemName)) {
                warn("bindExternalAccount refused: item mismatch — slot item '"
                        + slotItemName + "' does not match provider '"
                        + provider.providerId() + "' base currency '"
                        + providerCurrencyItem + "'");
                return BankStatus.FAILED_WRONG_INSTANCE_TYPE;
            }
        }
        // Transfer local free balance to external if non-zero. Split into whole-
        // native-unit portion (deposited) + sub-native remainder (preserved in the
        // new binding row as dust). This is the same conservation guarantee that
        // ongoing bound-slot ops make — no fraction is silently lost when binding
        // a slot that already had a fractional local balance. localLocked is NOT
        // deposited to external (it belongs to pending orders that reference this
        // slot) — it is carried into the binding row's lockedBalance and preserved
        // there until the pending order settles or the slot is unbound.
        //
        // Task #38b: local balance is in ITEM_FRACTION_SCALE_FACTOR (100:1) raw
        // units — that's the pre-bind ratio. The provider's ratio (e.g. LC gold
        // = 81:1) may differ, so we convert BEFORE depositing to keep the
        // physical-coin count invariant across the bind. E.g. localFree = 500
        // pre-bind (5 gold at 100:1) → 500 * 81 / 100 = 405 post-bind (5 gold at
        // 81:1). For coin slots this is always integer (localFree is always a
        // multiple of 100). Numismatics is unaffected: ratio = 100 = pre-bind,
        // so the multiplication is a no-op.
        long providerRatio = provider.baseUnitsPerItem(net.kroia.banksystem.util.ItemIDManager
                .getItemStackTemplate(itemId));
        if (providerRatio <= 0L) {
            providerRatio = BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
        }
        long convertedFree = providerRatio == BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR
                ? localFree
                : localFree * providerRatio / BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
        long convertedLocked = providerRatio == BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR
                ? localLocked
                : localLocked * providerRatio / BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
        long scale = Math.max(1L, external.nativeScale());
        long initialDust = 0L;
        if (convertedFree > 0L) {
            long dust = convertedFree % scale;
            long wholeNativePortion = convertedFree - dust;
            if (wholeNativePortion > 0L) {
                if (!external.deposit(wholeNativePortion)) {
                    warn("bindExternalAccount refused: slot " + accountId + "/" + itemId
                            + " has local balance " + localFree + " (converted " + convertedFree
                            + " at ratio " + providerRatio + ") whose whole-native portion ("
                            + wholeNativePortion + ") cannot be deposited to external "
                            + "(overflow or external refusal).");
                    return BankStatus.FAILED_OVERFLOW;
                }
            }
            initialDust = dust;
        }
        if (localFree > 0L || localLocked > 0L) {
            serverBank.writeLocalFieldsForUnbind_internal(0L, 0L);
        }
        // All checks passed — commit the binding.
        BankAccountBindings bindings = BankAccountBindings.get();
        if (bindings == null) {
            error("bindExternalAccount failed: BankAccountBindings backend is not available");
            return BankStatus.FAILED_EXTERNAL_UNAVAILABLE;
        }
        BindingRow row = bindings.bind(accountId, itemId, ref);
        if (initialDust > 0L) {
            bindings.setDust(accountId, itemId, initialDust);
        }
        if (convertedLocked > 0L) {
            // Task #38b: locked balance is stored in POST-BIND ratio units (matches
            // the free-balance ratio the bound slot uses for reads/writes).
            bindings.setLocked(accountId, itemId, convertedLocked);
        }
        // Issue #67 (v2.0.6): seed the drift-cache watchdog with the current external
        // balance so the very first pollExternalDrift() tick after bind does NOT fire
        // a spurious flag flip (cache would otherwise start at Long.MIN_VALUE and
        // always report drift on the first read).
        serverBank.primeDriftCache(external.getBalance());
        info("Bound slot " + accountId + "/" + itemId + " to external account " + ref
                + " (transferred " + (localFree - initialDust) + " raw units to external, "
                + "carried " + initialDust + " raw units dust + " + localLocked
                + " raw units locked into the binding row, boundAt=" + row.boundAt() + ")");
        return BankStatus.SUCCESS;
    }

    /**
     * Unbinds a BankSystem slot from its external currency-mod account
     * (Task #33). Idempotent: unbinding a non-bound slot returns SUCCESS.
     * <p>
     * Delegates to {@link #unbindExternalAccount(int, ItemID, boolean)} with
     * {@code keepOnBankSystem = true} for backward compatibility.
     *
     * @param accountId BankSystem account number
     * @param itemId    item slot on the account
     * @return SUCCESS in every reachable case (no-op unbind included)
     */
    public BankStatus unbindExternalAccount(int accountId, ItemID itemId) {
        return unbindExternalAccount(accountId, itemId, true);
    }

    /**
     * Unbinds a BankSystem slot from its external currency-mod account
     * (Task #33 v2.0.5 refinement). Idempotent: unbinding a non-bound slot
     * returns SUCCESS.
     * <p>
     * On success:
     * <ul>
     *   <li>If the provider is unavailable: the local {@code balance} and
     *       {@code lockedBalance} are set to 0. The player has not lost money
     *       on the external side — they simply detached from it. The binding
     *       row is removed. {@code keepOnBankSystem} is ignored in this case
     *       because no safe transfer can be performed.</li>
     *   <li>If the provider is available and {@code keepOnBankSystem == true}:
     *       withdraw all funds from external (ext + dust + locked), materialize
     *       into local (free = ext+dust, locked stays locked), remove row.</li>
     *   <li>If the provider is available and {@code keepOnBankSystem == false}:
     *       deposit (dust + locked) back to external as whole native units,
     *       accept fractional loss (< 1 external unit), zero local, remove row.
     *       Refuses with FAILED_OVERFLOW when the deposit would overflow the
     *       external account (player must choose "keep on BankSystem" instead).</li>
     * </ul>
     * Callers UI-side typically confirm the outcome with the player because
     * "provider unavailable at unbind" loses access to the funds until the mod
     * is reinstalled and a new bind is done.
     *
     * @param accountId         BankSystem account number
     * @param itemId            item slot on the account
     * @param keepOnBankSystem  {@code true} → recover all funds locally;
     *                          {@code false} → return funds to external with
     *                          fractional-dust loss accepted
     * @return SUCCESS in every reachable case when external is available or
     *         unavailable; FAILED_OVERFLOW when {@code keepOnBankSystem=false}
     *         and the deposit-back would overflow external
     */
    public BankStatus unbindExternalAccount(int accountId, ItemID itemId, boolean keepOnBankSystem) {
        if (itemId == null || !itemId.isValid()) {
            warn("unbindExternalAccount refused: invalid itemId (" + itemId + ")");
            return BankStatus.FAILED_INVALID_ITEM_ID;
        }
        itemId = ItemIDManager.resolveAlias(itemId);
        BankAccountBindings bindings = BankAccountBindings.get();
        if (bindings == null) {
            // Backend not available — nothing to unbind, treat as no-op success.
            return BankStatus.SUCCESS;
        }
        BindingRow row = bindings.getBinding(accountId, itemId);
        if (row == null) {
            // Idempotent no-op — the slot was not bound.
            return BankStatus.SUCCESS;
        }
        ServerBankAccount account = bankAccounts.get(accountId);
        IServerBank bank = account != null ? account.getBank(itemId) : null;

        ExternalCurrencyProvider provider = BankSystemMod.getAPI().getCurrencyProvider(row.ref().providerId());
        ExternalAccount external = (provider != null && provider.isAvailable()) ? provider.open(row.ref()) : null;

        if (external == null) {
            // Provider unavailable — detach without access to funds. The user will
            // need to reinstall the mod and re-bind to regain access.
            warn("unbindExternalAccount on " + accountId + "/" + itemId + ": provider '"
                    + row.ref().providerId() + "' is unavailable. Local balance will be set "
                    + "to 0. External funds are not lost — reinstall the mod and re-bind to "
                    + "regain access.");
            if (bank instanceof ServerBank serverBank) {
                serverBank.writeLocalFieldsForUnbind_internal(0L, 0L);
            }
            bindings.unbind(accountId, itemId);
            info("Unbound slot " + accountId + "/" + itemId + " from provider '"
                    + row.ref().providerId() + "' (provider unavailable, local zeroed)");
            return BankStatus.SUCCESS;
        }

        // Provider is available — perform the user-chosen transfer.
        long ext = external.getBalance();
        long dust = row.dustBalance();
        long locked = row.lockedBalance();
        // Task #38b: post-bind values (ext, locked, dust) are in the PROVIDER's ratio
        // (e.g. LC gold = 81:1). Local unbound storage uses ITEM_FRACTION_SCALE_FACTOR
        // (100:1). Convert post→pre to keep physical-coin count invariant.
        long providerRatio = provider.baseUnitsPerItem(net.kroia.banksystem.util.ItemIDManager
                .getItemStackTemplate(itemId));
        if (providerRatio <= 0L) {
            providerRatio = BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
        }

        if (keepOnBankSystem) {
            // Case A: recover all funds locally (no dust loss).
            // Withdraw everything from external.
            if (ext > 0L && !external.withdraw(ext)) {
                error("unbindExternalAccount on " + accountId + "/" + itemId
                        + ": cannot withdraw " + ext + " from external (provider '"
                        + row.ref().providerId() + "' refused). Aborting unbind.");
                return BankStatus.FAILED_EXTERNAL_UNAVAILABLE;
            }
            // Convert ext + dust from post-bind (provider ratio) to pre-bind (100:1) units.
            // Truncation loss is sub-raw-unit — negligible; log at info when present.
            long externalTotal = ext + dust;
            long convertedFree;
            long subUnitLoss = 0L;
            if (providerRatio == BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR) {
                convertedFree = externalTotal;
            } else {
                long numerator = externalTotal * BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
                convertedFree = numerator / providerRatio;
                subUnitLoss = numerator % providerRatio;
            }
            long convertedLocked = providerRatio == BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR
                    ? locked
                    : locked * BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR / providerRatio;
            // Materialize into local: free = convertedFree, locked = convertedLocked.
            if (bank instanceof ServerBank serverBank) {
                serverBank.writeLocalFieldsForUnbind_internal(convertedFree, convertedLocked);
            }
            bindings.unbind(accountId, itemId);
            if (subUnitLoss > 0L) {
                info("Unbound slot " + accountId + "/" + itemId + " from provider '"
                        + row.ref().providerId() + "' (recovered " + convertedFree
                        + " free + " + convertedLocked + " locked to local; ratio " + providerRatio
                        + "→" + BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR
                        + " truncated " + subUnitLoss + "/" + providerRatio + " sub-raw-unit)");
            } else {
                info("Unbound slot " + accountId + "/" + itemId + " from provider '"
                        + row.ref().providerId() + "' (recovered " + convertedFree
                        + " free + " + convertedLocked + " locked to local storage)");
            }
            return BankStatus.SUCCESS;
        } else {
            // Case B: leave funds on the provider. External balance is not touched — the
            // free portion is already there from bind + subsequent deposits. Locked funds
            // represent pending orders that reference this slot, so they cannot go to
            // external without orphaning those orders — they come home as local locked.
            // Only dust (< 1 native unit) is discarded, matching the UI's "fractional
            // amounts will be discarded" warning.
            long fractionalLoss = dust;
            long convertedLocked = providerRatio == BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR
                    ? locked
                    : locked * BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR / providerRatio;
            if (bank instanceof ServerBank serverBank) {
                serverBank.writeLocalFieldsForUnbind_internal(0L, convertedLocked);
            }
            bindings.unbind(accountId, itemId);
            if (fractionalLoss > 0L) {
                info("Unbound slot " + accountId + "/" + itemId + " from provider '"
                        + row.ref().providerId() + "' (kept " + ext + " on external, restored "
                        + convertedLocked + " raw units locked to local, discarded " + fractionalLoss
                        + " raw units of dust < 1 native unit)");
            } else {
                info("Unbound slot " + accountId + "/" + itemId + " from provider '"
                        + row.ref().providerId() + "' (kept " + ext + " on external, restored "
                        + convertedLocked + " raw units locked to local)");
            }
            return BankStatus.SUCCESS;
        }
    }


    @Override
    public String toString() {
        return toJsonString();
    }

    private void info(String msg)
    {
        BACKEND_INSTANCES.LOGGER.info("[ServerBankManager] " + msg);
    }
    private void error(String msg)
    {
        BACKEND_INSTANCES.LOGGER.error("[ServerBankManager] " + msg);
    }
    private void error(String msg, Throwable e)
    {
        BACKEND_INSTANCES.LOGGER.error("[ServerBankManager] " + msg, e);
    }
    private void warn(String msg)
    {
        BACKEND_INSTANCES.LOGGER.warn("[ServerBankManager] " + msg);
    }
    private void debug(String msg)
    {
        BACKEND_INSTANCES.LOGGER.debug("[ServerBankManager] " + msg);
    }
}
