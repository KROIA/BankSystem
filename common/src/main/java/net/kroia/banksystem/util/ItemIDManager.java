package net.kroia.banksystem.util;

import com.ibm.icu.impl.Pair;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.minecraft.item.BankSystemItems;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.networking.general.RegisterItemIDPacket;
import net.kroia.banksystem.networking.general.SyncItemIDsPacket;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.UtilitiesPlatform;
import net.kroia.modutilities.networking.multi_server.MultiServerManager;
import net.kroia.modutilities.persistence.ServerSaveable;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class ItemIDManager implements ServerSaveable {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    private static final ConcurrentHashMap<ItemID, ItemStack> itemIDMap = new ConcurrentHashMap<>();

    /**
     * Alias table: maps obsolete ItemIDs to their canonical replacement.
     * Entries are created by {@link #renormalizeAndMerge()} when several stored IDs collapse
     * to the same normalized identity (e.g. after volatile components such as {@code tfc:food}
     * are stripped from the templates of an existing world). Saved references (bank balances,
     * markets, ...) that still use an aliased ID keep resolving transparently through
     * {@link #getItemStack(ItemID)} / {@link #getItemStackTemplate(ItemID)}.
     * Alias chains are always flattened: every value in this map is a canonical ID
     * that is present in {@link #itemIDMap}.
     */
    private static final ConcurrentHashMap<ItemID, ItemID> itemIDAliasMap = new ConcurrentHashMap<>();

    /**
     * Alias→canonical pairs created by {@link #renormalizeAndMerge(Collection)} that have not
     * yet been consolidated into per-ItemID state (bank balances/locked balances, the
     * allowed-item set, account icons) and announced to dependent mods.
     * Filled by the merge pass; drained by {@link #consolidatePendingMerges()} — immediately
     * for runtime merges, or right after {@code load_bank()} when the merge happened during
     * world load (bank data is not available yet at that point).
     */
    private static final ConcurrentHashMap<ItemID, ItemID> pendingMergeConsolidation = new ConcurrentHashMap<>();

    private static ConcurrentHashMap<ItemID, ItemStack> singlePlayerServerBackupOnPlayerLeave = null;
    private static ConcurrentHashMap<ItemID, ItemID> singlePlayerServerAliasBackupOnPlayerLeave = null;
    /**
     * Backup companion of {@link #singlePlayerServerBackupOnPlayerLeave} for the monotonic
     * short counter. Kept in sync with the map backups so that
     * {@link #save(CompoundTag)} — which falls back to the backups when the live map is
     * empty (singleplayer server-leave path) — can still persist the correct counter value.
     * {@code -1} marks "no backup taken yet"; anything &gt; 0 is a valid captured counter.
     */
    private static int singlePlayerServerBackupCounterOnPlayerLeave = -1;
    private static Map<ItemStack, CompletableFuture<ItemID>> pendingItemIDs = new ConcurrentHashMap<>();
    private static List<Pair<List<ItemStack>, CompletableFuture<List<ItemID>>>> pendingItemIDGroups = new CopyOnWriteArrayList<>();

    /**
     * Monotonic short allocation counter used by
     * {@link #registerItemStackServerSide_direct(List)} to hand out fresh ItemID shorts.
     * <p>
     * <b>Persisted</b> under NBT key {@code "nextShortCounter"} in {@link #save(CompoundTag)},
     * restored in {@link #load(CompoundTag)}. Starts at {@code 1} on a brand-new world
     * (short {@code 0} is reserved for {@link ItemID#INVALID_ID}); on load of a legacy world
     * without the key, seeded to {@code max(shortsInItemIDMap ∪ shortsInItemIDAliasMap) + 1}
     * so no already-used short is ever re-issued.
     * <p>
     * <b>Never decrements.</b> Even when an entry is silently dropped at load (unresolvable
     * ItemStack — mod removed / NBT corrupt) its short remains "burned": the counter has
     * already moved past it and will never re-issue it. This is the whole point of the
     * counter — a dropped short cannot silently rebind to a new item and poison downstream
     * ItemID-keyed state (bank balances, StockMarket markets, plugin caches, ...).
     * <p>
     * Declared as {@code int}, not {@code short}, so overflow past {@link Short#MAX_VALUE}
     * is explicit and observable rather than silently wrapping into negative shorts. The
     * allocator returns {@link ItemID#INVALID_ID} once the counter exceeds the short range.
     */
    private static int nextShortCounter = 1;

    public static void clear()
    {
        singlePlayerServerBackupOnPlayerLeave = new ConcurrentHashMap<>(itemIDMap);
        singlePlayerServerAliasBackupOnPlayerLeave = new ConcurrentHashMap<>(itemIDAliasMap);
        singlePlayerServerBackupCounterOnPlayerLeave = nextShortCounter;
        itemIDMap.clear();
        itemIDAliasMap.clear();
        // Reset the counter to its blank-world default. The subsequent load() either
        // restores the persisted value or seeds from the map maxima (migration).
        nextShortCounter = 1;
        // Never carry unconsolidated merge pairs into the next session/world.
        pendingMergeConsolidation.clear();
    }

    /**
     * Hard cap on {@link #resolveAlias(ItemID)} chain-walk iterations (Task #13 Fix C).
     * <p>
     * A properly maintained alias table is flattened by {@link #renormalizeAndMerge(Collection)}
     * (every value already points at a canonical ID present in {@code itemIDMap}), so the walk
     * exits after ONE hop in the common case. The bound only ever fires under a corrupt state
     * — a chain longer than the short registry itself could ever produce (Short.MAX_VALUE is
     * 32767, but 64 is far more than any realistic accidental chain, cheap to bound with, and
     * matches the guard style used in {@link #renormalizeAndMerge(Collection)} pass 3).
     */
    private static final int MAX_ALIAS_CHAIN_HOPS = 64;

    /**
     * Repair counter recorded by the most recent {@link #load(CompoundTag)} — read by
     * {@code BankSystemDataHandler.load_itemIDs()} to decide whether to persist the repaired
     * state via {@code save_itemIDs()} (Task #13 Fix D one-shot durability). Cleared to
     * {@code 0} at the start of every {@link #load(CompoundTag)} invocation.
     */
    private static int lastLoadRepairCount = 0;

    /**
     * Walks the alias table from the given ItemID to its canonical terminal (an ID that is
     * <b>not</b> a key in {@code itemIDAliasMap}). IDs that were never merged resolve to
     * themselves in zero hops.
     * <p>
     * <b>Chain-walking (Task #13 Fix C):</b> A merged ID may itself have been merged into a
     * further canonical ID before {@link #renormalizeAndMerge(Collection)} flattened the
     * table, or a corrupt state (see {@link #repairCorruptAliasEntries()}) may leave
     * intermediate hops in {@code itemIDAliasMap}. This method follows every hop until the
     * current short is no longer a key in the alias table — the alias table is the
     * authoritative signal for "is this ID an alias?"; we do <b>not</b> gate on
     * {@code itemIDMap} presence (intermediate hops may or may not be in the map depending
     * on repair state).
     * <p>
     * <b>Cycle protection:</b> both a visited-set and a hard iteration bound
     * ({@link #MAX_ALIAS_CHAIN_HOPS}) guard against an accidentally-cyclic table. On cycle
     * detection or hop-bound exhaustion the method logs at ERROR with the chain traversed so
     * far and returns the <b>original input</b> unchanged (safest fallback — refusing to
     * pick a partially-resolved intermediate ID that could silently misroute reads/writes).
     * The repair pass in {@link #repairCorruptAliasEntries()} breaks such cycles at
     * load-time so this fallback is only ever a runtime safety net.
     *
     * @param itemID the (possibly aliased) ItemID to resolve; must not be {@code null}
     * @return the canonical (terminal) ItemID, or the original input if the chain contains
     *         a cycle or exceeds {@link #MAX_ALIAS_CHAIN_HOPS} hops
     */
    public static @NotNull ItemID resolveAlias(@NotNull ItemID itemID) {
        // Fast path: not an alias at all — return immediately, allocate nothing.
        if (!itemIDAliasMap.containsKey(itemID))
            return itemID;

        Set<ItemID> visited = new HashSet<>();
        visited.add(itemID);
        ItemID current = itemID;
        for (int hop = 0; hop < MAX_ALIAS_CHAIN_HOPS; hop++) {
            ItemID next = itemIDAliasMap.get(current);
            if (next == null)
                return current; // terminal reached
            if (!visited.add(next)) {
                // Cycle: log the traversed chain, fall back to the original input.
                if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
                    BACKEND_INSTANCES.LOGGER.error("[ItemIDManager] Alias-chain cycle detected while resolving "
                            + itemID + " (chain: " + visited + " -> " + next
                            + "). Returning input unchanged; run repairCorruptAliasEntries() to break the cycle.");
                }
                return itemID;
            }
            current = next;
        }
        // Bound exhausted without finding a terminal — treat as pathological, fall back.
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
            BACKEND_INSTANCES.LOGGER.error("[ItemIDManager] Alias-chain resolution exceeded "
                    + MAX_ALIAS_CHAIN_HOPS + " hops for " + itemID + " (chain: " + visited
                    + "). Returning input unchanged; run repairCorruptAliasEntries().");
        }
        return itemID;
    }

    /**
     * Returns a <b>defensive copy</b> of the template stack registered for the given ItemID.
     * Aliased IDs (see {@link #renormalizeAndMerge()}) transparently resolve to their
     * canonical entry. Callers may freely mutate the returned stack.
     *
     * @param itemID the ItemID to look up
     * @return a copy of the registered template, or {@link ItemStack#EMPTY} if unknown
     */
    public static @NotNull ItemStack getItemStack(@NotNull ItemID itemID) {
        ItemStack item = getItemStackTemplate(itemID);
        if(item.isEmpty())
        {
            return ItemStack.EMPTY;
        }
        return item.copy();
    }

    /**
     * Returns the <b>live template stack</b> registered for the given ItemID, without copying.
     * Aliased IDs transparently resolve to their canonical entry.
     * <p>
     * <b>Read-only!</b> The returned stack is the registry's internal template — callers must
     * never mutate it (no {@code setCount}, no component changes). Intended only for hot paths
     * (e.g. per-frame rendering) where the defensive copy of {@link #getItemStack(ItemID)}
     * would cause needless allocations.
     *
     * @param itemID the ItemID to look up
     * @return the live template stack, or {@link ItemStack#EMPTY} if unknown
     */
    public static @NotNull ItemStack getItemStackTemplate(@NotNull ItemID itemID) {
        ItemStack item = itemIDMap.get(itemID);
        if(item == null)
        {
            // The ID may have been merged into a canonical ID during migration.
            ItemID canonical = itemIDAliasMap.get(itemID);
            if(canonical != null)
                item = itemIDMap.get(canonical);
        }
        if(item == null)
        {
            return ItemStack.EMPTY;
        }
        return item;
    }

    public static ConcurrentHashMap<ItemID, ItemStack> getItemIDMap()
    {
        return new ConcurrentHashMap<>(itemIDMap);
    }

    /**
     * @return a copy of the alias table (merged ID → canonical ID).
     *         Used to sync aliases to clients and slave servers so that references
     *         keyed by a merged ID keep resolving on every side.
     */
    public static Map<ItemID, ItemID> getItemIDAliasMap()
    {
        return new HashMap<>(itemIDAliasMap);
    }

    /**
     * Adopts alias entries received from the master/server sync.
     * Existing local aliases are kept and updated; entries are never removed here
     * (a full re-sync happens on every player join / slave connect).
     *
     * @param aliases alias entries (merged ID → canonical ID) from a sync packet
     */
    public static void applyAliases(Map<ItemID, ItemID> aliases)
    {
        if(aliases == null || aliases.isEmpty())
            return;
        itemIDAliasMap.putAll(aliases);
    }

    public static CompletableFuture<ItemID> registerItemStackServerSide(@NotNull ItemStack itemStack)
    {
        // Work on a normalized copy (volatile components stripped) — identity is always
        // established on normalized stacks and the caller's stack is never mutated.
        ItemStack cpy = VolatileItemComponents.normalize(itemStack);
        cpy.setCount(1);
        CompletableFuture<ItemID>  future = new CompletableFuture<>();
        ItemID itemID = getItemID(cpy);
        if(itemID.isValid())
        {
            future.complete(itemID);
            return future;
        }

        if(MultiServerUtils.canInteractWithBankSystem())
        {
            future.complete(registerItemStackServerSide_direct(cpy));
        }
        else
        {
            RegisterItemIDPacket.sendRegisterItemIDPacketToMaster(cpy);
            pendingItemIDs.put(cpy, future);
        }
        return future;
    }
    /*public static CompletableFuture<List<ItemID>> registerItemStackServerSide(List<ItemStack> itemStacks) {
        List<ItemStack> cpyStacks = new ArrayList<>();
        CompletableFuture<List<ItemID>> groupFuture = new CompletableFuture<>();
        List<ItemID> foundItemIDs = new ArrayList<>();
        for (ItemStack itemStack : itemStacks)
        {
            ItemID itemID = getItemID(itemStack);
            if(!itemID.isValid()) {
                ItemStack cpy = itemStack.copy();
                cpy.setCount(1);
                cpyStacks.add(cpy);
                CompletableFuture<ItemID> future = new CompletableFuture<>();
                pendingItemIDs.put(cpy, future);
            }
            else
            {
                foundItemIDs.add(itemID);
            }
        }
        if(cpyStacks.isEmpty())
        {
            groupFuture.complete(foundItemIDs);
        }
        else {
            if(MultiServerManager.isRunning() && MultiServerManager.isMaster()) {
                pendingItemIDGroups.add(Pair.of(cpyStacks, groupFuture));
                RegisterItemIDPacket.sendRegisterItemIDPacketToMaster(cpyStacks);
            }
            else
            {
                for(ItemStack stack : cpyStacks)
                {
                    foundItemIDs.add(registerItemStackServerSide_direct(stack));
                }
            }
        }
        return groupFuture;
    }*/


    public static CompletableFuture<ItemID> registerItemStackClientSide(@NotNull ItemStack itemStack)
    {
        ItemID itemID = ItemIDManager.getItemID(itemStack);
        if(itemID.isValid())
            return CompletableFuture.completedFuture(itemID);
        // Register/await on a normalized copy so the pending-match in receiveSyncPacket
        // compares normalized stacks on both sides.
        ItemStack cpy = VolatileItemComponents.normalize(itemStack);
        cpy.setCount(1);
        CompletableFuture<ItemID>  future = new CompletableFuture<>();
        RegisterItemIDPacket.sendRegisterItemIDPacketToServer(cpy);
        pendingItemIDs.put(cpy, future);
        return future;
    }
    public static CompletableFuture<List<ItemID>> registerItemStackClientSide(List<ItemStack> itemStacks) {
        List<ItemStack> cpyStacks = new ArrayList<>();
        CompletableFuture<List<ItemID>> groupFuture = new CompletableFuture<>();
        List<ItemID> foundItemIDs = new ArrayList<>();
        for (ItemStack itemStack : itemStacks)
        {
            ItemID itemID = getItemID(itemStack);
            if(!itemID.isValid()) {
                // Normalized copy: see registerItemStackClientSide(ItemStack).
                ItemStack cpy = VolatileItemComponents.normalize(itemStack);
                cpy.setCount(1);
                cpyStacks.add(cpy);
                CompletableFuture<ItemID> future = new CompletableFuture<>();
                pendingItemIDs.put(cpy, future);
            }
            else
            {
                foundItemIDs.add(itemID);
            }
        }
        if(cpyStacks.isEmpty())
        {
            groupFuture.complete(foundItemIDs);
        }
        else {
            pendingItemIDGroups.add(Pair.of(cpyStacks, groupFuture));
            RegisterItemIDPacket.sendRegisterItemIDPacketToServer(cpyStacks);
        }
        return groupFuture;
    }


    public static @NotNull ItemID registerItemStackServerSide_direct(@NotNull ItemStack itemStack)
    {
        List<ItemStack> itemStacks  = new ArrayList<>();
        itemStacks.add(itemStack);
        List<ItemID> ids = registerItemStackServerSide_direct(itemStacks);
        if(ids.isEmpty())
            return ItemID.INVALID_ID;
        return ids.getFirst();
    }

    public static List<ItemID> registerItemStackServerSide_direct(List<ItemStack> itemStacks)
    {
        List<ItemID> ids = new ArrayList<>();
        Map<ItemID, ItemStack> newItemIDMap = new ConcurrentHashMap<>();
        synchronized (itemIDMap) {
            for (ItemStack stack : itemStacks) {
                // Search the entire list to check if the same item stack already is registered
                ItemID id = getItemID(stack);
                if (id != null && id.isValid()) {
                    ids.add(id);
                    continue;
                }

                if (stack.isEmpty()) {
                    ids.add(ItemID.INVALID_ID);
                    continue;
                }

                // Monotonic allocation: hand out the next unused short from the counter.
                // The counter has already moved past every short that was ever allocated
                // (including dropped-at-load shorts — see #load()), so a fresh short is
                // guaranteed never to collide with old saved data.
                //
                // Overflow: the counter is an int so exceeding Short.MAX_VALUE is explicit
                // rather than a silent wraparound into negative shorts. When the ItemID
                // space is exhausted we return INVALID_ID and refuse to touch the registry;
                // callers are expected to check id.isValid() (see criterion 5 in Task #11).
                if (nextShortCounter > Short.MAX_VALUE) {
                    logSpaceExhausted(stack);
                    ids.add(ItemID.INVALID_ID);
                    continue;
                }
                short newID = (short) nextShortCounter++;
                // Belt-and-suspenders: on a migrated legacy world the seed could theoretically
                // collide with an unrelated legacy alias short — skip past any occupied ID
                // and check the overflow bound again each time.
                while (itemIDMap.containsKey(new ItemID(newID)) || itemIDAliasMap.containsKey(new ItemID(newID))) {
                    if (nextShortCounter > Short.MAX_VALUE) {
                        logSpaceExhausted(stack);
                        ids.add(ItemID.INVALID_ID);
                        newID = 0; // signal outer branch to skip the put
                        break;
                    }
                    newID = (short) nextShortCounter++;
                }
                if (newID == 0)
                    continue; // overflow inside the belt-and-suspenders loop

                // Store a normalized template (volatile components stripped, count 1) so the
                // registry never contains time-varying component data.
                ItemStack cpy = VolatileItemComponents.normalize(stack);
                String name = ItemUtilities.getItemIDStr(cpy.getItem());
                id = new ItemID(newID, name);
                cpy.setCount(1);
                newItemIDMap.put(id, cpy);
                itemIDMap.put(id, cpy);
                ids.add(id);
            }
        }
        if(!newItemIDMap.isEmpty())
            onNewItemAdded(newItemIDMap);
        return ids;
    }

    /**
     * Looks up the ItemID registered for the given stack.
     * <p>
     * Identity is established on a <b>normalized copy</b> of the stack (volatile and
     * deposit-gated components stripped, count forced to 1) — the passed stack is <b>never
     * mutated</b>. Stored templates are normalized as well, so two stacks that differ only in
     * such components (e.g. a food-decay timestamp) resolve to the same ItemID, while
     * meaningful components (enchantments, potion contents, ...) still distinguish IDs.
     * Whether a gated stack's <i>state</i> is acceptable for deposit is a separate question,
     * answered by {@link VolatileItemComponents#isDepositEquivalent} at the credit boundaries.
     *
     * @param itemStack the stack to look up (not mutated)
     * @return the registered (canonical) ItemID, or {@link ItemID#INVALID_ID} if unknown
     */
    public static @NotNull ItemID getItemID(@NotNull ItemStack itemStack)
    {
        // Normalize into a working copy: never mutate the caller's stack
        // (the previous implementation temporarily changed its count).
        ItemStack query = VolatileItemComponents.normalize(itemStack);
        query.setCount(1);
        // Fast path: exact component comparison (works for items without registry-backed holders)
        for(Map.Entry<ItemID, ItemStack> entry : itemIDMap.entrySet())
        {
            if(ItemStack.isSameItemSameComponents(query, entry.getValue()))
            {
                return entry.getKey();
            }
        }
        // Fallback: compare by serialized NBT using the server's registry.
        // Tag.equals() compares string/int values, not Holder identity, so this
        // correctly matches enchanted books, potions, etc. across registry instances.
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if (access != null) {
            try {
                Tag searchTag = query.save(access);
                for (Map.Entry<ItemID, ItemStack> entry : itemIDMap.entrySet()) {
                    try {
                        Tag existingTag = entry.getValue().save(access);
                        if (searchTag.equals(existingTag)) {
                            return entry.getKey();
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }
        return ItemID.INVALID_ID;
    }

    /*private static @NotNull ItemID generateIdFromItemStack(@NotNull ItemStack itemStack)
    {
        if(itemStack.isEmpty())
        {
            UUID emptyUUID = UUID.nameUUIDFromBytes(("EMPTY").getBytes());
            return new ItemID(emptyUUID);
        }
        int oldAmount = itemStack.getCount();
        itemStack.setCount(1);
        Tag itemDataTag = ItemStack.CODEC.encodeStart(NbtOps.INSTANCE, itemStack).getOrThrow();
        String tagStr = itemDataTag.getAsString();
        UUID uuid = UUID.nameUUIDFromBytes(tagStr.getBytes());
        itemStack.setCount(oldAmount);
        return new ItemID(uuid);
    }*/




    private static void onNewItemAdded(Map<ItemID, ItemStack> newItems)
    {
        // Broadcast update to players
        SyncItemIDsPacket packet = new SyncItemIDsPacket(newItems);
        if(MultiServerManager.isRunning() && MultiServerManager.isMaster())
        {
            packet.broadcastToSlaves();
        }
        packet.broadcastToClients();
    }
    public static void onPlayerJoined(@NotNull ServerPlayer player)
    {
        Map<ItemID, ItemStack> items = new HashMap<>(itemIDMap);
        SyncItemIDsPacket packet = new SyncItemIDsPacket(items);
        packet.sendToClient(player);
    }
    public static void receiveSyncPacket(SyncItemIDsPacket packet)
    {
        receiveSyncPacket(packet, true);
    }

    /**
     * Handles an incoming ItemID sync packet.
     *
     * @param packet             the sync packet
     * @param adoptComponentLists whether to adopt the sender's config-sourced volatile /
     *                            deposit-gated component lists. {@code true} for remote
     *                            clients and slave servers (the sender's lists are
     *                            authoritative there). {@code false} on a singleplayer /
     *                            integrated-server client: both sides share the static
     *                            {@link VolatileItemComponents} state, so the packet's lists
     *                            are a possibly STALE snapshot of this side's own state —
     *                            adopting them would asynchronously roll back the applied
     *                            set and trigger spurious renormalize/merge passes
     *                            (observed corrupting a live registry).
     */
    public static void receiveSyncPacket(SyncItemIDsPacket packet, boolean adoptComponentLists)
    {
        // First sync after joining: snapshot the (vanilla-synced) datapack tags so later
        // runtime tag rebinds on this side cannot diverge from the master's applied set.
        // Later syncs on the same connection intentionally keep the existing snapshot.
        // NOTE: no merge guard here — clients and slave servers follow the master's
        // decision unconditionally (the master already ran the startup guard).
        VolatileItemComponents.captureTagSnapshotIfAbsent();
        if(adoptComponentLists)
        {
            // Adopt the sender's config-sourced volatile + deposit-gated component lists first,
            // so this side normalizes (and deposit-gates) exactly like the master/server that
            // produced the packet. Non-short-circuiting |: both lists must always be applied.
            // (The datapack-tag part of both sets is distributed by vanilla tag sync.)
            boolean componentSetsChanged = VolatileItemComponents.setConfigComponentIds(packet.getVolatileComponentIds());
            componentSetsChanged |= VolatileItemComponents.setGatedConfigComponentIds(packet.getDepositGatedComponentIds());
            if(componentSetsChanged)
            {
                renormalizeAndMerge();
            }
        }
        // Adopt the sender's alias table so bank balances / markets keyed by a merged ID
        // resolve on this side as well.
        applyAliases(packet.getAliases());
        Map<ItemID, ItemStack> items = packet.getItems();
        for(Map.Entry<ItemID, ItemStack> entry : items.entrySet())
        {
            // Search the entire list to check if the same item stack already is registered
            ItemStack itemStack = entry.getValue();

            //ItemID id = getItemID(itemStack);
            //if(id != null)
            //    continue;
            ItemID id = entry.getKey();
            // Re-normalize after network decode: stack construction during decode can
            // re-attach volatile components (e.g. TFC stamps a fresh creation date onto
            // any food stack built from the network stream).
            ItemStack cpy = VolatileItemComponents.normalize(itemStack);
            cpy.setCount(1);
            String name = ItemUtilities.getItemIDStr(cpy.getItem());
            entry.getKey().setNameCache_internal(name);

            CompletableFuture<ItemID> pendingItemID = null;
            ItemStack matchedKey = null;
            for (Map.Entry<ItemStack, CompletableFuture<ItemID>> pendingEntry : pendingItemIDs.entrySet()) {
                // Pending keys are stored normalized; compare against the normalized copy.
                if (ItemStack.isSameItemSameComponents(cpy, pendingEntry.getKey())) {
                    pendingItemID = pendingEntry.getValue();
                    matchedKey = pendingEntry.getKey();
                    break;
                }
            }
            if (pendingItemID != null) {
                pendingItemID.complete(id);
                pendingItemIDs.remove(matchedKey);
            }


            // putIfAbsent: in singleplayer the server and client share the same static map.
            // The server registers ItemStacks with correct server-registry Holders;
            // the client sync must not overwrite them with client-decoded copies that
            // have cross-registry Holder references (causes save failures).
            // Alias guard: never resurrect an ID that was merged away. Sync packets can sit
            // in the network queue while a renormalize pass merges the ID into a canonical
            // one — re-inserting it here would leave the ID both aliased AND registered
            // (an inconsistent registry that would even be saved to disk in singleplayer).
            if (!itemIDAliasMap.containsKey(id))
                itemIDMap.putIfAbsent(id, cpy);
            id.tryUpdateNameCache();
        }
        MoneyItem.resetItemID();
        List<Pair<List<ItemStack>, CompletableFuture<List<ItemID>>>> cpyList = new ArrayList<>(pendingItemIDGroups);
        for(Pair<List<ItemStack>, CompletableFuture<List<ItemID>>> pair : cpyList)
        {
            List<ItemID> completeList = new ArrayList<>();
            List<ItemStack> stacks = pair.first;
            boolean isComplete = true;
            for(ItemStack itemStack : stacks)
            {
                ItemID id = getItemID(itemStack);
                if(id == null)
                {
                    isComplete = false;
                    break;
                }
                completeList.add(id);
            }
            if(isComplete)
            {
                pendingItemIDGroups.remove(pair);
                pair.second.complete(completeList);
            }
        }
    }
    public static void receiveRegisterItemIDPacket(RegisterItemIDPacket packet)
    {
        // No explicit normalization needed here: network decode may re-attach volatile
        // components (mod constructor hooks), but registerItemStackServerSide_direct
        // normalizes both the identity lookup and the stored template.
        registerItemStackServerSide_direct(packet.getItems());
    }

    @Override
    public boolean save(CompoundTag tag) {
        ConcurrentHashMap<ItemID, ItemStack> usedMap = itemIDMap;
        Map<ItemID, ItemID> usedAliasMap = itemIDAliasMap;
        int usedCounter = nextShortCounter;
        if(usedMap.isEmpty())
        {
            if(singlePlayerServerBackupOnPlayerLeave != null)
                usedMap = singlePlayerServerBackupOnPlayerLeave;
            if(singlePlayerServerAliasBackupOnPlayerLeave != null)
                usedAliasMap = singlePlayerServerAliasBackupOnPlayerLeave;
            // Persist the counter captured alongside the maps on the last clear() so a
            // singleplayer save that fires after the server-leave clear keeps the counter's
            // monotonic guarantee across the restart.
            if(singlePlayerServerBackupCounterOnPlayerLeave > 0)
                usedCounter = singlePlayerServerBackupCounterOnPlayerLeave;
        }
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if(access == null)
            return false;
        ListTag listTag = new ListTag();
        for(Map.Entry<ItemID, ItemStack> entry : usedMap.entrySet())
        {
            ItemStack itemStack = entry.getValue();
            ItemID itemID = entry.getKey();
            CompoundTag pairTag = new CompoundTag();
            CompoundTag idTag = new CompoundTag();
            itemID.save(idTag);
            pairTag.put("itemID", idTag);
            CompoundTag itemStackTag = new CompoundTag();
            try {
                Tag stackTag = itemStack.save(access, itemStackTag);
                pairTag.put("itemStack", stackTag);
            } catch (Exception e) {
                // Skip items whose ItemStack can't be serialized in the current registry context
                // (e.g. enchanted books registered from a different registry context).
                // Never skip silently — a dropped entry means a dangling ItemID after reload.
                if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null)
                    BACKEND_INSTANCES.LOGGER.warn("[ItemIDManager] Failed to save ItemStack for " + itemID + ": " + e.getMessage());
                continue;
            }

            listTag.add(pairTag);
        }
        tag.put("itemIDs", listTag);

        // Persist the alias table so saved references to merged IDs keep resolving
        // across restarts (see renormalizeAndMerge()).
        ListTag aliasListTag = new ListTag();
        for(Map.Entry<ItemID, ItemID> entry : usedAliasMap.entrySet())
        {
            CompoundTag aliasTag = new CompoundTag();
            aliasTag.putShort("from", entry.getKey().getShort());
            aliasTag.putShort("to", entry.getValue().getShort());
            aliasListTag.add(aliasTag);
        }
        tag.put("itemIDAliases", aliasListTag);

        // Persist the monotonic short allocation counter. Ensures a dropped-at-load short is
        // never re-issued (Task #11 / ISSUES.md #56). Stored as an int so an admin can spot
        // the value approaching Short.MAX_VALUE (32767) in the world data.
        tag.putInt("nextShortCounter", usedCounter);
        return true;
    }

    @Override
    public boolean load(CompoundTag tag) {
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if(access == null)
            return false;

        ListTag listTag =  tag.getList("itemIDs", Tag.TAG_COMPOUND);
        for(Tag tagElement : listTag)
        {
            CompoundTag compoundTag = (CompoundTag)tagElement;
            if(compoundTag==null || !compoundTag.contains("itemID") || !compoundTag.contains("itemStack"))
                continue;

            CompoundTag itemStackTag = compoundTag.getCompound("itemStack");
            CompoundTag itemIDTag = compoundTag.getCompound("itemID");

            ItemID id = new ItemID(ItemID.INVALID_ID);
            if(!id.load(itemIDTag))
                continue;

            Optional<ItemStack> itemStackOptional = ItemStack.parse(access, itemStackTag);
            if(itemStackOptional.isEmpty()) {
                // Silent-drop hazard (Task #11 / ISSUES.md #56): the ItemStack no longer
                // resolves — its host mod may have been removed, or the stored NBT is
                // corrupt. The entry is dropped, but the short remains RESERVED — the
                // persisted monotonic nextShortCounter has already advanced past it, so
                // this short can never be re-issued to a different item. Logging at WARN
                // gives admins a hint when downstream ItemID-keyed data (bank balances,
                // StockMarket markets, plugin caches) references an ID that no longer
                // exists in the registry.
                if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
                    // ItemID.getName() falls back to the numeric short (as string) when the
                    // real registry name cannot be resolved — at the load-time drop site
                    // the registry is only partially populated, so a numeric fallback is
                    // usual. Distinguish real names from the placeholder and report the
                    // placeholder as "<unknown>" per Task #11 criterion 6.
                    String cachedName = id.getName();
                    if (cachedName == null || cachedName.isEmpty() || cachedName.equals(String.valueOf(id.getShort())))
                        cachedName = "<unknown>";
                    BACKEND_INSTANCES.LOGGER.warn("[ItemIDManager] Dropped ItemID entry (short="
                            + id.getShort() + ", cached-name=" + cachedName + ") — stored ItemStack "
                            + "no longer resolves (mod possibly removed or NBT corrupt). "
                            + "Short remains reserved (will not be reassigned).");
                }
                continue;
            }

            // Store the template RAW (un-normalized) for now: the startup merge guard below
            // needs the saved component data to tell healing merges apart from collapse
            // merges and to report the differing components. The guard's renormalizeAndMerge
            // pass normalizes every template in place before load() returns (this also
            // migrates worlds saved before volatile-component normalization existed).
            ItemStack itemStack = itemStackOptional.get();
            itemStack.setCount(1);

            itemIDMap.put(id, itemStack);
            id.tryUpdateNameCache();
        }

        // Restore previously persisted aliases (merged IDs from an earlier migration).
        ListTag aliasListTag = tag.getList("itemIDAliases", Tag.TAG_COMPOUND);
        for(Tag tagElement : aliasListTag)
        {
            CompoundTag aliasTag = (CompoundTag)tagElement;
            if(aliasTag == null || !aliasTag.contains("from") || !aliasTag.contains("to"))
                continue;
            itemIDAliasMap.put(new ItemID(aliasTag.getShort("from")), new ItemID(aliasTag.getShort("to")));
        }

        // Restore the monotonic short allocation counter (Task #11). Two branches:
        //   - Key present  → adopt the persisted value verbatim. Every subsequent save
        //                     persists it again, so this is the steady-state path.
        //   - Key absent   → legacy migration. Seed to max(shortsInItemIDMap ∪
        //                     shortsInItemIDAliasMap) + 1 so allocation cannot re-issue any
        //                     short that is currently referenced. One-shot: the next save
        //                     writes the key and this branch is never taken again on the
        //                     same world.
        if (tag.contains("nextShortCounter"))
        {
            nextShortCounter = tag.getInt("nextShortCounter");
            if (nextShortCounter < 1)
                nextShortCounter = 1;
        }
        else
        {
            nextShortCounter = computeMigrationSeed(itemIDMap.keySet(), itemIDAliasMap.keySet());
            if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null)
                BACKEND_INSTANCES.LOGGER.info("[ItemIDManager] Legacy world: seeded monotonic "
                        + "nextShortCounter to " + nextShortCounter + " (max used short + 1). "
                        + "Subsequent saves persist the counter under NBT key 'nextShortCounter'.");
        }

        // Merge entries that collapse to the same normalized identity, guarded against
        // silent collapses caused by a CHANGED volatile/deposit-gated component set.
        // May throw ItemIDMergeAbortedException to abort server startup (nothing has been
        // mutated on disk at that point).
        applyStartupMergeGuard();

        // Task #13 Fix D: heal the alias table after every other load-time pass. Repairs
        // pre-existing corrupt states (source present in BOTH itemIDMap and itemIDAliasMap,
        // orphan aliases whose chain terminates outside the registry, cycles). The count is
        // stashed for BankSystemDataHandler.load_itemIDs() to trigger a one-shot save when
        // any repair happened, so the cleaned-up state is durable on the very next tick.
        lastLoadRepairCount = repairCorruptAliasEntries();

        singlePlayerServerBackupOnPlayerLeave = null;
        singlePlayerServerAliasBackupOnPlayerLeave = null;
        singlePlayerServerBackupCounterOnPlayerLeave = -1;
        return true;
    }

    /**
     * Reads and clears the repair count recorded by the most recent {@link #load(CompoundTag)}.
     * Intended for {@code BankSystemDataHandler.load_itemIDs()} to decide whether the
     * post-load {@code save_itemIDs()} is needed (only when {@code &gt; 0}).
     *
     * @return the number of alias entries repaired by the last load, or {@code 0} if none
     */
    public static int consumeLastLoadRepairCount() {
        int v = lastLoadRepairCount;
        lastLoadRepairCount = 0;
        return v;
    }

    /**
     * Read-only peek at the repair count recorded by the most recent {@link #load(CompoundTag)}.
     * Consumes nothing — for tests that want to inspect the count without clearing it.
     */
    public static int getLastLoadRepairCount_forTesting() {
        return lastLoadRepairCount;
    }

    /**
     * Heals corrupt entries in {@code itemIDAliasMap} (Task #13 Fix D).
     * <p>
     * Runs at the end of {@link #load(CompoundTag)}, after every other pass (Task #8 guard,
     * Task #10 consolidation, Task #11 counter migration). Idempotent — a second call on the
     * same world is a no-op because run 1 already dropped everything that would be dropped.
     * <p>
     * <b>Detection criteria</b> (per {@code source -> target} entry, snapshotted before the
     * loop starts so map mutation inside the loop is safe):
     * <ul>
     *   <li><b>invariant violation</b> — {@code source} is <b>also</b> present in
     *       {@code itemIDMap}. Historically this state (both maps hold the same short)
     *       broke {@link #resolveAlias(ItemID)}'s one-hop pre-Fix-C behavior and produced
     *       misleading downstream reads. The alias entry is removed; the map entry is
     *       preserved (the ID becomes a distinct canonical ID going forward).</li>
     *   <li><b>orphan</b> — the alias chain from {@code source} terminates at a short that
     *       is <b>not</b> in {@code itemIDMap} (target item never existed, or was purged).
     *       The alias entry is removed — resolving through it would return a dangling ID.</li>
     *   <li><b>cycle</b> — the chain from {@code source} enters a loop (detected via visited
     *       set / bounded iterations). The <i>current source entry</i> is removed to break
     *       the cycle; other members of the cycle are handled on their own snapshot passes.
     *       Logged at ERROR because cycles indicate a serious past corruption event.</li>
     * </ul>
     * <b>Not touched:</b> alias entries whose chain reaches a canonical ID present in
     * {@code itemIDMap} in {@code &lt;=} {@link #MAX_ALIAS_CHAIN_HOPS} hops with no cycle
     * — those are healthy.
     * <p>
     * <b>Balance data is NEVER touched</b> — this pass only mutates the alias table.
     * Rekeying live bank balances is out of scope (would risk further data loss under a
     * misdiagnosed corrupt state).
     *
     * @return the total count of removed alias entries (sum of invariant violations,
     *         orphans, and cycle breaks). {@code 0} on a clean alias table.
     */
    public static int repairCorruptAliasEntries() {
        int invariantViolations = 0;
        int orphans = 0;
        int cycles = 0;

        // Snapshot BEFORE mutating — map mutation during the outer iteration would either
        // throw (fail-fast iterator) or skip entries silently under ConcurrentHashMap's
        // weakly-consistent iterator. The snapshot is small (aliasMap is typically empty or
        // a few dozen entries) so the copy cost is negligible.
        List<Map.Entry<ItemID, ItemID>> snapshot;
        synchronized (itemIDMap) {
            snapshot = new ArrayList<>(itemIDAliasMap.entrySet());
        }

        for (Map.Entry<ItemID, ItemID> entry : snapshot) {
            ItemID source = entry.getKey();
            ItemID target = entry.getValue();

            // The entry may already have been removed by an earlier iteration (e.g. we
            // removed the tail of a cycle in a previous step) — skip such stale entries.
            if (!itemIDAliasMap.containsKey(source))
                continue;

            // Rule 1: source is in BOTH maps → invariant violation. Remove the alias entry;
            // the map entry stays as the canonical (the ID behaves as a distinct canonical
            // ID from this point forward; balances at the ID remain in place).
            if (itemIDMap.containsKey(source)) {
                itemIDAliasMap.remove(source);
                invariantViolations++;
                logRepairDebug("invariant-violation", source, target, null);
                continue;
            }

            // Rule 2 + 3: walk the chain from `target`, collecting hops in a visited set.
            // Terminate on either a hop bound (cycle guard) or a hop that is not in the
            // alias map (terminal reached).
            Set<ItemID> visited = new HashSet<>();
            visited.add(source);
            ItemID current = target;
            boolean cycleDetected = false;
            for (int hop = 0; hop < MAX_ALIAS_CHAIN_HOPS; hop++) {
                if (!visited.add(current)) {
                    cycleDetected = true;
                    break;
                }
                ItemID next = itemIDAliasMap.get(current);
                if (next == null)
                    break; // terminal reached — `current` is the chain's tail
                current = next;
            }
            // If we exit the loop without breaking on a terminal, we exhausted the hop
            // bound — treat as a cycle for reporting purposes.
            if (!cycleDetected && itemIDAliasMap.containsKey(current))
                cycleDetected = true;

            if (cycleDetected) {
                itemIDAliasMap.remove(source);
                cycles++;
                if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
                    BACKEND_INSTANCES.LOGGER.error("[ItemIDManager] Alias-table repair: cycle detected at "
                            + source + " -> " + target + " (visited: " + visited
                            + "). Removed this entry to break the cycle.");
                }
                continue;
            }

            // At this point `current` is a terminal that is NOT in the alias map. If it is
            // also not in the item map, the whole chain is orphaned and unresolvable — drop
            // the source entry so callers stop resolving to a dangling ID.
            if (!itemIDMap.containsKey(current)) {
                itemIDAliasMap.remove(source);
                orphans++;
                logRepairDebug("orphan", source, target, current);
            }
        }

        int total = invariantViolations + orphans + cycles;
        if (total > 0 && BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
            BACKEND_INSTANCES.LOGGER.warn("[ItemIDManager] Alias-table repair: " + total
                    + " total repaired (" + invariantViolations + " invariant violations, "
                    + orphans + " orphans, " + cycles + " cycles). Details in DEBUG.");
        }
        return total;
    }

    /**
     * DEBUG-level per-repair log line used by {@link #repairCorruptAliasEntries()}.
     * Skips silently when no logger is available (tests without a backend).
     *
     * @param kind    short repair category tag: {@code invariant-violation}, {@code orphan}, {@code cycle}
     * @param source  the alias source that was removed
     * @param target  the alias's immediate target (as recorded before repair)
     * @param terminal the terminal short the chain resolved to (orphan case only, {@code null} otherwise)
     */
    private static void logRepairDebug(String kind, ItemID source, ItemID target, ItemID terminal) {
        if (BACKEND_INSTANCES == null || BACKEND_INSTANCES.LOGGER == null)
            return;
        StringBuilder sb = new StringBuilder("[ItemIDManager] Alias-table repair (").append(kind)
                .append("): removed ").append(source).append(" -> ").append(target);
        if (terminal != null)
            sb.append(" (terminal ").append(terminal).append(" not in itemIDMap)");
        // Try to resolve a human-readable name for the source if the map entry survives.
        ItemStack template = itemIDMap.get(source);
        if (template != null && !template.isEmpty())
            sb.append(" [source template: ").append(ItemUtilities.getItemIDStr(template.getItem())).append(']');
        BACKEND_INSTANCES.LOGGER.debug(sb.toString());
    }

    /**
     * Replaces the ItemIDManager's static state with the provided snapshots. Test-only —
     * used by the upgrade-safety test in {@code ItemIDMergeGuardTests} to install a
     * fixture registry before invoking {@link #load(CompoundTag)} on a v2.0.2-shaped tag.
     * <p>
     * Never call in production — bypasses every sync/broadcast path.
     *
     * @param newItemMap   snapshot to install as {@code itemIDMap} (may be empty)
     * @param newAliasMap  snapshot to install as {@code itemIDAliasMap} (may be empty)
     * @param counter      counter value to install as {@code nextShortCounter}
     */
    public static void replaceState_forTesting(Map<ItemID, ItemStack> newItemMap,
                                                Map<ItemID, ItemID> newAliasMap,
                                                int counter) {
        synchronized (itemIDMap) {
            itemIDMap.clear();
            if (newItemMap != null)
                itemIDMap.putAll(newItemMap);
            itemIDAliasMap.clear();
            if (newAliasMap != null)
                itemIDAliasMap.putAll(newAliasMap);
            nextShortCounter = Math.max(counter, 1);
        }
    }

    /**
     * Removes the given alias-map entries. Test-only cleanup hook used by tests that seed
     * synthetic alias entries (including intentional cycles) and need to drop them before
     * teardown so they don't leak into the live production alias table.
     *
     * @param keys alias-map keys to remove (missing keys are ignored)
     */
    public static void removeAliasEntries_forTesting(Collection<ItemID> keys) {
        if (keys == null)
            return;
        for (ItemID key : keys)
            itemIDAliasMap.remove(key);
    }

    /**
     * Re-normalizes every registered template against the <b>current</b> volatile and
     * deposit-gated component sets and merges IDs whose templates collapse to the same
     * normalized identity.
     * <p>
     * For each group of colliding IDs the lowest short value is kept as the canonical ID; all
     * other IDs are removed from the registry and recorded in the alias table
     * (old ID → canonical ID). Lookups through {@link #getItemStack(ItemID)} /
     * {@link #getItemStackTemplate(ItemID)} transparently resolve aliases, so previously saved
     * references (bank balances, StockMarket markets, orders, ...) remain valid.
     * <p>
     * Called after loading a world (migration of pre-fix data) and whenever the config-sourced
     * volatile component list changes (settings load on the server, sync packet on
     * clients/slaves). Safe to call repeatedly — a fully normalized registry is a no-op.
     * <p>
     * The effective component set is snapshotted <b>once</b> at entry. {@code normalize()}
     * reads live volatile fields, so a concurrent set change (e.g. a sync packet handled on
     * the client/render thread while this pass runs on the server thread — both sides share
     * the static state in singleplayer) could otherwise tear the pass: half the registry
     * normalized under the old set, half under the new one. A concurrent change is instead
     * applied wholly by its own follow-up renormalize call.
     */
    public static void renormalizeAndMerge()
    {
        renormalizeAndMerge(VolatileItemComponents.getEffectiveComponentIds());
    }

    /**
     * Variant of {@link #renormalizeAndMerge()} that normalizes and merges under an
     * <b>explicit</b> component set instead of the globally applied effective set.
     * <p>
     * The no-arg overload delegates here with the applied effective set (snapshotted once,
     * see above). The explicit form additionally allows the in-game test suite to apply a
     * grown set atomically without mutating the globally shared
     * {@link VolatileItemComponents} configuration (which in singleplayer is shared between
     * client and server and must not be flipped around mid-session).
     * <b>Caution:</b> normalizing the registry under a set that is not the applied effective
     * set desyncs template identity from query-side normalization — production code should
     * always use the no-arg overload.
     *
     * @param componentIds component type ids to strip while normalizing
     *                     (unparsable entries are skipped)
     */
    public static void renormalizeAndMerge(Collection<String> componentIds)
    {
        Set<net.minecraft.resources.ResourceLocation> strippedIds = parseComponentIdStrings(componentIds);
        int mergedCount = 0;
        // Alias→canonical pairs created by THIS pass — consumed below to consolidate
        // per-ItemID state (bank balances, allowed items, ...) and notify dependent mods.
        Map<ItemID, ItemID> newAliases = new HashMap<>();
        synchronized (itemIDMap) {
            // Pass 1: re-normalize all templates in place (cheap no-op when already normalized).
            // stripComponentsByIds == normalize() when strippedIds is the applied effective set,
            // but reads no shared mutable state — the whole pass sees ONE consistent set.
            for (Map.Entry<ItemID, ItemStack> entry : itemIDMap.entrySet()) {
                ItemStack normalized = VolatileItemComponents.stripComponentsByIds(entry.getValue(), strippedIds);
                normalized.setCount(1);
                entry.setValue(normalized);
            }

            // Pass 2: bucket by item+components hash, then group buckets by real component
            // equality and merge every group down to its lowest (canonical) ID.
            Map<Integer, List<ItemID>> buckets = new HashMap<>();
            for (Map.Entry<ItemID, ItemStack> entry : itemIDMap.entrySet()) {
                int hash = ItemStack.hashItemAndComponents(entry.getValue());
                buckets.computeIfAbsent(hash, k -> new ArrayList<>()).add(entry.getKey());
            }
            for (List<ItemID> bucket : buckets.values()) {
                if (bucket.size() < 2)
                    continue;
                // Split the hash bucket into groups of genuinely identical templates.
                List<List<ItemID>> groups = new ArrayList<>();
                for (ItemID id : bucket) {
                    ItemStack stack = itemIDMap.get(id);
                    List<ItemID> match = null;
                    for (List<ItemID> group : groups) {
                        if (ItemStack.isSameItemSameComponents(stack, itemIDMap.get(group.getFirst()))) {
                            match = group;
                            break;
                        }
                    }
                    if (match == null) {
                        match = new ArrayList<>();
                        groups.add(match);
                    }
                    match.add(id);
                }
                for (List<ItemID> group : groups) {
                    if (group.size() < 2)
                        continue;
                    // Canonical = lowest short value; alias all others to it.
                    ItemID canonical = group.getFirst();
                    for (ItemID id : group)
                        if (id.getShort() < canonical.getShort())
                            canonical = id;
                    for (ItemID id : group) {
                        if (id.equals(canonical))
                            continue;
                        itemIDMap.remove(id);
                        itemIDAliasMap.put(id, canonical);
                        newAliases.put(id, canonical);
                        mergedCount++;
                    }
                }
            }

            // Pass 3: flatten alias chains — every alias must point at an ID that is
            // actually present in the registry (its canonical may itself have been merged).
            for (Map.Entry<ItemID, ItemID> entry : itemIDAliasMap.entrySet()) {
                ItemID target = entry.getValue();
                int guard = 0;
                while (!itemIDMap.containsKey(target) && itemIDAliasMap.containsKey(target) && guard++ < 1000) {
                    target = itemIDAliasMap.get(target);
                }
                entry.setValue(target);
            }

            // The newly created pairs must reflect the flattened chains as well, so that
            // consumers (bank consolidation, dependent mods) always see a canonical ID
            // that is actually present in the registry.
            for (Map.Entry<ItemID, ItemID> entry : newAliases.entrySet()) {
                ItemID flattened = itemIDAliasMap.get(entry.getKey());
                if (flattened != null)
                    entry.setValue(flattened);
            }
        }
        if (mergedCount > 0 && BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
            BACKEND_INSTANCES.LOGGER.info("[ItemIDManager] Merged " + mergedCount +
                    " duplicate ItemID(s) that collapsed to the same identity after removing volatile item components. " +
                    "Old IDs remain valid as aliases of their canonical ID.");
        }
        if (!newAliases.isEmpty()) {
            // Queue consolidation of per-ItemID state and the dependent-mod notification.
            // Runs immediately when bank data is already loaded (runtime merges); during
            // world load the pairs stay queued until BankSystemDataHandler.loadAll() has
            // finished load_bank() and drains them.
            pendingMergeConsolidation.putAll(newAliases);
            consolidatePendingMerges();
        }
    }

    /**
     * Drains the alias→canonical pairs recorded by {@link #renormalizeAndMerge(Collection)}
     * and consolidates all per-ItemID state under the canonical IDs:
     * <ul>
     *   <li>bank balances and locked balances of every bank account (merged into the
     *       canonical bank, see
     *       {@link net.kroia.banksystem.banking.bankmanager.ServerBankManager#consolidateMergedItemIDs}),</li>
     *   <li>the allowed-item set (aliased entries replaced by their canonical ID),</li>
     *   <li>account icons referencing an aliased ID.</li>
     * </ul>
     * Afterwards the {@code ITEM_IDS_MERGED} event
     * ({@link net.kroia.banksystem.api.IBankSystemEvents#getItemIDsMergedEvent()}) is fired
     * with the drained (unmodifiable) map so dependent mods (e.g. StockMarket) can
     * consolidate their own ItemID-keyed state. The event fires on the calling (server)
     * thread, master side only, and always AFTER BankSystem's own consolidation completed.
     * <p>
     * Master server only: clients and slave servers hold no authoritative per-ItemID bank
     * state — their pending pairs are discarded (they follow the master's registry and
     * resolve aliases through the accessors). When the bank data has not been loaded yet
     * (merge during world load), the pairs stay pending and
     * {@code BankSystemDataHandler.loadAll()} calls this again right after the bank data is
     * loaded. Safe to call at any time; a no-op when nothing is pending.
     */
    public static void consolidatePendingMerges() {
        if (pendingMergeConsolidation.isEmpty() || BACKEND_INSTANCES == null)
            return;
        if (BACKEND_INSTANCES.isSlaveServer || BACKEND_INSTANCES.SERVER_BANK_MANAGER == null) {
            // Not the master side (slave server or pure client) — no server bank state here.
            pendingMergeConsolidation.clear();
            return;
        }
        if (!BankSystemDataHandler.isBankDataLoaded())
            return; // world load in progress — loadAll() drains the queue after load_bank()

        Map<ItemID, ItemID> aliases = new HashMap<>(pendingMergeConsolidation);
        pendingMergeConsolidation.clear();
        Object syncManager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
        if (syncManager instanceof net.kroia.banksystem.banking.bankmanager.ServerBankManager bankManager)
            bankManager.consolidateMergedItemIDs(aliases);
        // Purge balance-history rows keyed to the merged-away alias shorts. These rows would
        // otherwise persist under IDs that no longer resolve to a live identity, and the
        // BalanceHistoryScreen would still render a chart for each dropped alias (user report
        // 2026-07-08, Task #12). Canonical rows are intentionally left untouched — the
        // canonical ID's history continues fresh from the merge point.
        // Master-only path: BALANCE_HISTORY_MANAGER is only constructed on the master server
        // (see BankSystemModBackend#onServerStart), so a null check is enough — no extra
        // isSlaveServer guard needed (already handled above).
        if (BACKEND_INSTANCES.BALANCE_HISTORY_MANAGER != null && !aliases.isEmpty()) {
            List<Short> aliasShorts = new ArrayList<>(aliases.size());
            for (ItemID aliasId : aliases.keySet())
                aliasShorts.add(aliasId.getShort());
            // Fire-and-forget: the delete runs async on the DB executor, matching the pattern
            // used by takeBalanceSnapshot() (BankSystemModBackend). The listener dispatch below
            // does not depend on the deletion completing.
            BACKEND_INSTANCES.BALANCE_HISTORY_MANAGER.deleteAllRowsForItemIDs(aliasShorts);
        }
        // Notify dependent mods AFTER BankSystem's own state is consistent again.
        if (BACKEND_INSTANCES.SERVER_EVENTS != null)
            BACKEND_INSTANCES.SERVER_EVENTS.ITEM_IDS_MERGED.notifyListeners(Collections.unmodifiableMap(aliases));
    }

    // ========================================================================================
    // Startup merge guard (CONFIRM_ITEMID_MERGE)
    // ========================================================================================

    /**
     * One group of ItemIDs that would be merged by {@link #renormalizeAndMerge()} <b>and</b>
     * whose members are genuinely distinct under the previously applied component set
     * (a <i>collapse</i> merge, as opposed to a harmless <i>healing</i> merge of duplicates).
     * Produced by {@link #detectCollapseCollisions(Collection)} — a pure dry run, nothing
     * is mutated.
     *
     * @param canonicalId      the ID that would survive the merge (lowest short in the group)
     * @param mergedIds        the IDs that would be removed and become aliases of the canonical ID
     * @param currentTemplates the registry templates (defensive copies) of every group member,
     *                         keyed by ID — used to report the components that distinguish them
     */
    public record MergeCollisionGroup(ItemID canonicalId,
                                      List<ItemID> mergedIds,
                                      Map<ItemID, ItemStack> currentTemplates) {
    }

    /**
     * <b>Dry run</b> of {@link #renormalizeAndMerge()}'s grouping pass: computes which
     * registered ItemIDs would collapse to the same normalized identity under the
     * <b>current</b> effective component set, then keeps only the groups that represent a
     * dangerous <i>collapse</i> (as opposed to a harmless <i>healing</i> merge).
     * Neither {@code itemIDMap} nor {@code itemIDAliasMap} is mutated.
     * <p>
     * <b>Healing vs. collapse:</b> a group is a <i>healing</i> merge when all of its members
     * are already identical after stripping only the <b>previously applied</b> component set
     * ({@code previousComponentIds}) — i.e. they are duplicates that should always have been
     * one ID (e.g. spurious IDs minted by decaying food before the volatile-component fix, or
     * registry-order duplicates). Those merge automatically, exactly like before this guard
     * existed. A group is a <i>collapse</i> when at least two members remain distinct under
     * the previous set and only become equal under the current set — meaning the admin's
     * component-list change is what fuses genuinely different items (e.g. two markets/bank
     * entries becoming the same item). Only collapse groups are returned.
     *
     * @param previousComponentIds the effective component set that was applied when the world
     *                             was last normalized (id strings; unparsable entries are skipped)
     * @return every collapse group the current set would create; empty if applying the current
     *         set is safe (only healing merges, or no merges at all)
     */
    public static List<MergeCollisionGroup> detectCollapseCollisions(Collection<String> previousComponentIds) {
        return detectCollapseCollisions(previousComponentIds, VolatileItemComponents.getEffectiveComponentIds());
    }

    /**
     * Variant of {@link #detectCollapseCollisions(Collection)} with an <b>explicit</b>
     * "current" component set instead of the globally applied effective set.
     * <p>
     * The startup merge guard uses the single-argument overload (current = the applied
     * effective set). This overload exists so callers (in particular the in-game test suite)
     * can evaluate a hypothetical set change as a pure function — without mutating the
     * globally shared {@link VolatileItemComponents} configuration, which on a singleplayer
     * server is shared between the client and server side and must not be flipped around
     * while play continues.
     *
     * @param previousComponentIds the set the registry was last normalized with
     * @param currentComponentIds  the (hypothetical or applied) new set to evaluate
     * @return every collapse group the given current set would create
     */
    public static List<MergeCollisionGroup> detectCollapseCollisions(Collection<String> previousComponentIds,
                                                                     Collection<String> currentComponentIds) {
        // Parse both sets into resource locations (unknown/absent mods are fine —
        // stripComponentsByIds skips unresolvable ids).
        Set<net.minecraft.resources.ResourceLocation> previousIds = parseComponentIdStrings(previousComponentIds);
        Set<net.minecraft.resources.ResourceLocation> currentIds = parseComponentIdStrings(currentComponentIds);

        List<MergeCollisionGroup> collisions = new ArrayList<>();
        synchronized (itemIDMap) {
            // Normalize COPIES under the given current set — the live templates stay untouched.
            // (stripComponentsByIds == normalize() when currentIds is the applied effective set.)
            Map<ItemID, ItemStack> normalized = new HashMap<>();
            for (Map.Entry<ItemID, ItemStack> entry : itemIDMap.entrySet()) {
                ItemStack n = VolatileItemComponents.stripComponentsByIds(entry.getValue(), currentIds);
                n.setCount(1);
                normalized.put(entry.getKey(), n);
            }

            // Same bucketing/grouping as renormalizeAndMerge() pass 2, on the copies.
            Map<Integer, List<ItemID>> buckets = new HashMap<>();
            for (Map.Entry<ItemID, ItemStack> entry : normalized.entrySet()) {
                int hash = ItemStack.hashItemAndComponents(entry.getValue());
                buckets.computeIfAbsent(hash, k -> new ArrayList<>()).add(entry.getKey());
            }
            for (List<ItemID> bucket : buckets.values()) {
                if (bucket.size() < 2)
                    continue;
                List<List<ItemID>> groups = new ArrayList<>();
                for (ItemID id : bucket) {
                    ItemStack stack = normalized.get(id);
                    List<ItemID> match = null;
                    for (List<ItemID> group : groups) {
                        if (ItemStack.isSameItemSameComponents(stack, normalized.get(group.getFirst()))) {
                            match = group;
                            break;
                        }
                    }
                    if (match == null) {
                        match = new ArrayList<>();
                        groups.add(match);
                    }
                    match.add(id);
                }
                for (List<ItemID> group : groups) {
                    if (group.size() < 2)
                        continue;

                    // Classify: how many distinct identities does this group have under the
                    // PREVIOUS set? 1 → healing (duplicates, merge silently). >1 → collapse.
                    List<ItemStack> distinctUnderPreviousSet = new ArrayList<>();
                    for (ItemID id : group) {
                        ItemStack old = VolatileItemComponents.stripComponentsByIds(itemIDMap.get(id), previousIds);
                        old.setCount(1);
                        boolean found = false;
                        for (ItemStack existing : distinctUnderPreviousSet) {
                            if (ItemStack.isSameItemSameComponents(old, existing)) {
                                found = true;
                                break;
                            }
                        }
                        if (!found)
                            distinctUnderPreviousSet.add(old);
                    }
                    if (distinctUnderPreviousSet.size() < 2)
                        continue; // healing merge — stays automatic

                    // Collapse group: canonical = lowest short (matches renormalizeAndMerge).
                    ItemID canonical = group.getFirst();
                    for (ItemID id : group)
                        if (id.getShort() < canonical.getShort())
                            canonical = id;
                    List<ItemID> mergedIds = new ArrayList<>();
                    Map<ItemID, ItemStack> templates = new HashMap<>();
                    for (ItemID id : group) {
                        templates.put(id, itemIDMap.get(id).copy());
                        if (!id.equals(canonical))
                            mergedIds.add(id);
                    }
                    mergedIds.sort(Comparator.comparingInt(ItemID::getShort));
                    collisions.add(new MergeCollisionGroup(canonical, mergedIds, templates));
                }
            }
        }
        return collisions;
    }

    /** Parses component id strings into resource locations, silently skipping unparsable entries. */
    private static Set<net.minecraft.resources.ResourceLocation> parseComponentIdStrings(Collection<String> idStrings) {
        Set<net.minecraft.resources.ResourceLocation> ids = new HashSet<>();
        if (idStrings != null) {
            for (String idStr : idStrings) {
                net.minecraft.resources.ResourceLocation id =
                        idStr == null ? null : net.minecraft.resources.ResourceLocation.tryParse(idStr.trim());
                if (id != null)
                    ids.add(id);
            }
        }
        return ids;
    }

    /**
     * Builds the human-readable merge report shown to the admin — either as the message of
     * the startup-aborting {@link ItemIDMergeAbortedException} (flag off) or as an INFO log
     * before the confirmed merge is applied (flag on).
     *
     * @param collisions  collapse groups from {@link #detectCollapseCollisions(Collection)}
     * @param previousSet the effective component set persisted at the last normalization
     * @param currentSet  the effective component set of this startup
     * @return multi-line report listing every merge group, the affected items, the
     *         components that distinguish them today, and the admin's two options
     */
    public static String buildMergeReport(List<MergeCollisionGroup> collisions,
                                          List<String> previousSet,
                                          List<String> currentSet) {
        Set<String> previous = previousSet == null ? Set.of() : new HashSet<>(previousSet);
        Set<String> current = currentSet == null ? Set.of() : new HashSet<>(currentSet);
        StringBuilder sb = new StringBuilder();
        sb.append("\n==================== BankSystem ItemID merge guard ====================\n");
        sb.append("The effective volatile/deposit-gated component set changed since this world\n");
        sb.append("was last normalized (config lists ADDITIONAL_VOLATILE_COMPONENTS /\n");
        sb.append("ADDITIONAL_DEPOSIT_GATED_COMPONENTS in settings.json, or the datapack tags\n");
        sb.append("banksystem:volatile_item_components / banksystem:deposit_gated_components).\n");
        sb.append("  Previously applied set: ").append(previousSet).append('\n');
        sb.append("  Current set:            ").append(currentSet).append('\n');
        sb.append("Applying the current set would IRREVERSIBLY merge the following genuinely\n");
        sb.append("distinct items (bank balances and market listings keyed by a merged ID\n");
        sb.append("would all resolve to the canonical item afterwards):\n");
        int groupNr = 0;
        for (MergeCollisionGroup group : collisions) {
            groupNr++;
            sb.append("\nMerge group ").append(groupNr).append(" — canonical: ")
                    .append(describeMember(group.canonicalId(), group.currentTemplates().get(group.canonicalId()), previous, current))
                    .append('\n');
            for (ItemID merged : group.mergedIds()) {
                sb.append("  would become an alias: ")
                        .append(describeMember(merged, group.currentTemplates().get(merged), previous, current))
                        .append('\n');
            }
        }
        sb.append("\nYour options:\n");
        sb.append("  1) Keep the items distinct: revert the component-list change (settings.json\n");
        sb.append("     config lists and/or datapack tags) and restart the server.\n");
        sb.append("  2) Approve this merge once: set \"CONFIRM_ITEMID_MERGE\": true in the\n");
        sb.append("     \"ServerBank\" section of <world>/data/BankSystem/settings.json and restart.\n");
        sb.append("     The flag resets itself to false after the merge is applied.\n");
        sb.append("Nothing has been changed or saved: BankSystem suppresses all of its data\n");
        sb.append("saves for the remainder of this aborted session, so the world data on disk\n");
        sb.append("stays exactly as it was. Back up your world before confirming.\n");
        sb.append("========================================================================");
        return sb.toString();
    }

    /**
     * One report line for a merge-group member: ID, item registry name and the components
     * that are stripped by the current set (the data that distinguishes the member today).
     * Components that were NOT stripped by the previous set — i.e. the ones whose stripping
     * is new and causes the collapse — are marked with {@code (NEW)}.
     * <p>
     * The current set is passed in (instead of re-reading the applied effective set) so the
     * report always describes exactly the set the collision detection was run against.
     */
    private static String describeMember(ItemID id, ItemStack template, Set<String> previousSet, Set<String> currentSet) {
        StringBuilder sb = new StringBuilder();
        sb.append('#').append(id.getShort()).append(" (");
        sb.append(template == null || template.isEmpty() ? "?" : ItemUtilities.getItemIDStr(template.getItem()));
        sb.append(')');
        if (template == null || template.isEmpty())
            return sb.toString();
        // List the components on this template that the CURRENT set strips.
        List<String> stripped = new ArrayList<>();
        for (net.minecraft.core.component.TypedDataComponent<?> typed : template.getComponents()) {
            net.minecraft.resources.ResourceLocation typeId =
                    net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(typed.type());
            if (typeId == null || !currentSet.contains(typeId.toString()))
                continue;
            String value = String.valueOf(typed.value());
            if (value.length() > 120)
                value = value.substring(0, 117) + "...";
            stripped.add(typeId + (previousSet.contains(typeId.toString()) ? "" : " (NEW)") + " = " + value);
        }
        if (!stripped.isEmpty())
            sb.append(" stripped components: ").append(stripped);
        return sb.toString();
    }

    /**
     * Startup merge guard, run at the end of {@link #load(CompoundTag)} on the master server
     * (slave servers never load ItemIDs from disk; clients never call load()).
     * <p>
     * Distinguishes two situations:
     * <ul>
     *   <li><b>Healing</b> (stored set equal to the current set, or no stored set yet →
     *       first load after the mod update / migration): merges are duplicates collapsing
     *       under an unchanged set — the intended self-repair. Merge silently, exactly like
     *       before this guard existed.</li>
     *   <li><b>Potential collapse</b> (stored set differs): dry-run the merge grouping.
     *       Groups whose members are distinct under the OLD set would be fused by the
     *       admin's component-list change — that is irreversible, so it requires an explicit
     *       one-shot confirmation via the {@code CONFIRM_ITEMID_MERGE} setting; without it,
     *       server startup is aborted with a full report
     *       ({@link ItemIDMergeAbortedException}).</li>
     * </ul>
     * After a successful merge the applied effective set is persisted (via the data handler)
     * so the next startup compares against it, and a consumed {@code CONFIRM_ITEMID_MERGE}
     * flag is reset to {@code false} and saved (one-shot confirmation, never a standing
     * bypass).
     *
     * @throws ItemIDMergeAbortedException if the set changed, distinct items would merge,
     *                                     and the admin has not confirmed
     */
    private static void applyStartupMergeGuard() {
        BankSystemDataHandler dataHandler = BACKEND_INSTANCES != null ? BACKEND_INSTANCES.SERVER_DATA_HANDLER : null;
        List<String> currentSet = VolatileItemComponents.getEffectiveComponentIds();
        List<String> storedSet = dataHandler != null ? dataHandler.getStoredEffectiveComponentSet() : null;

        if (storedSet == null || storedSet.equals(currentSet)) {
            // Unchanged set (or no record yet → migration): every merge is a healing merge.
            renormalizeAndMerge();
        } else {
            List<MergeCollisionGroup> collisions = detectCollapseCollisions(storedSet);
            if (collisions.isEmpty()) {
                if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null)
                    BACKEND_INSTANCES.LOGGER.info("[ItemIDManager] The effective volatile/deposit-gated component set changed ("
                            + storedSet + " -> " + currentSet + "), but no distinct ItemIDs collapse — applying the new set.");
                renormalizeAndMerge();
            } else {
                String report = buildMergeReport(collisions, storedSet, currentSet);
                boolean confirmed = BACKEND_INSTANCES != null
                        && BACKEND_INSTANCES.SERVER_SETTINGS != null
                        && Boolean.TRUE.equals(BACKEND_INSTANCES.SERVER_SETTINGS.BANK.CONFIRM_ITEMID_MERGE.get());
                if (!confirmed) {
                    // Abort startup. Nothing has been mutated on disk; the exception message
                    // carries the full report into the log / crash report on both loaders.
                    // BankSystemDataHandler.load_itemIDs() catches this exception in transit
                    // to mark the whole session save-prohibited (the ItemID registry is only
                    // half-initialized here and the bank manager is still empty), so neither
                    // the vanilla crash-save nor the shutdown save can overwrite world data —
                    // that is what makes the report's "nothing saved" guarantee true.
                    throw new ItemIDMergeAbortedException(report
                            + "\nServer startup was aborted by the BankSystem ItemID merge guard (CONFIRM_ITEMID_MERGE is false).");
                }
                if (BACKEND_INSTANCES.LOGGER != null)
                    BACKEND_INSTANCES.LOGGER.info("[ItemIDManager] CONFIRM_ITEMID_MERGE is true — applying the confirmed merge."
                            + report);
                renormalizeAndMerge();
                // One-shot confirmation: consume the flag and persist the reset immediately,
                // so a later (unrelated) set change can never slip through unconfirmed.
                BACKEND_INSTANCES.SERVER_SETTINGS.BANK.CONFIRM_ITEMID_MERGE.set(false);
                if (dataHandler != null)
                    dataHandler.save_globalSettings();
            }
        }

        // Record the set that is now in effect so the next startup can detect changes.
        if (dataHandler != null) {
            dataHandler.setAppliedEffectiveComponentSet(currentSet);
            dataHandler.save_metadata();
        }
    }


    /**
     * Computes the migration seed for {@link #nextShortCounter} on a legacy world (a world
     * saved before the counter existed): {@code max(shortsInItemIDMap ∪ shortsInItemIDAliasMap) + 1}.
     * <p>
     * Returned as an {@code int} (not {@code short}) so a fully-saturated legacy world (max
     * short == {@link Short#MAX_VALUE}) seeds to {@code Short.MAX_VALUE + 1 = 32768}, at which
     * point {@link #registerItemStackServerSide_direct(List)} returns {@link ItemID#INVALID_ID}
     * on the next registration attempt — the exhaustion is reported cleanly instead of
     * silently wrapping.
     * <p>
     * Callable from tests via {@link #computeMigrationSeed_forTesting} which delegates here.
     *
     * @param mapShorts   ItemIDs currently in {@code itemIDMap}
     * @param aliasShorts ItemIDs currently referenced by {@code itemIDAliasMap}
     *                    (the {@code from} side — canonical shorts are already in {@code mapShorts})
     * @return one past the largest short present in either collection, or {@code 1} if both are empty
     */
    static int computeMigrationSeed(Collection<ItemID> mapShorts, Collection<ItemID> aliasShorts) {
        int maxShort = 0;
        if (mapShorts != null)
            for (ItemID id : mapShorts)
                if ((id.getShort() & 0xFFFF) > maxShort)
                    maxShort = id.getShort() & 0xFFFF;
        if (aliasShorts != null)
            for (ItemID id : aliasShorts)
                if ((id.getShort() & 0xFFFF) > maxShort)
                    maxShort = id.getShort() & 0xFFFF;
        return maxShort + 1;
    }

    /**
     * Public delegate to {@link #computeMigrationSeed(Collection, Collection)} for the
     * in-game test suite (Task #11 test 1). Not intended for production use.
     */
    public static int computeMigrationSeed_forTesting(Collection<ItemID> mapShorts, Collection<ItemID> aliasShorts) {
        return computeMigrationSeed(mapShorts, aliasShorts);
    }

    /**
     * Emits the ERROR log required by Task #11 criterion 5 when the allocator refuses to
     * hand out a new short because {@link #nextShortCounter} has passed
     * {@link Short#MAX_VALUE}. Kept as a helper so the counter allocator stays compact and
     * both overflow branches (the initial check and the belt-and-suspenders loop) use the
     * same wording.
     *
     * @param stack the stack whose registration is being aborted — its registry name is
     *              included in the log line so admins can trace the failing operation.
     */
    private static void logSpaceExhausted(ItemStack stack) {
        if (BACKEND_INSTANCES == null || BACKEND_INSTANCES.LOGGER == null)
            return;
        String name = stack != null && !stack.isEmpty() ? ItemUtilities.getItemIDStr(stack.getItem()) : "<empty>";
        BACKEND_INSTANCES.LOGGER.error("[ItemIDManager] BankSystem ItemID space exhausted — "
                + "cannot register new item " + name + ". The monotonic short counter has "
                + "passed Short.MAX_VALUE (" + Short.MAX_VALUE + "); no fresh shorts remain. "
                + "This affects registration only — existing ItemIDs and their bank balances "
                + "are unaffected. Contact the mod maintainer.");
    }

    /** Read-only accessor to {@link #nextShortCounter} for the in-game test suite. */
    public static int getNextShortCounter_forTesting() {
        return nextShortCounter;
    }

    /**
     * Explicit setter for {@link #nextShortCounter}, exclusively for the in-game test suite
     * (Task #11 tests 5 and 6 need to force the counter to overflow). Never call in
     * production code — the counter is otherwise only mutated by the allocator itself and
     * by {@link #load(CompoundTag)}.
     */
    public static void setNextShortCounter_forTesting(int value) {
        nextShortCounter = value;
    }

    public void createDefaultItemIDs(MinecraftServer server)
    {
        RegistryAccess access = server.registryAccess();
        if(access == null)
            return;

        ArrayList<ItemStack> moneyItems = BankSystemItems.getMoneyItems();
        for(ItemStack itemStack : moneyItems)
        {
            registerItemStackServerSide_direct(itemStack);
        }
        MoneyItem.resetItemID();

        access.lookupOrThrow(Registries.ITEM).listElements().forEach(listElement -> {
            registerItemStackServerSide_direct(listElement.value().getDefaultInstance());
        });
    }

    /*@Override
    public boolean save(Map<String, ListTag> listTags) {
        ListTag listTag = new ListTag();
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if(access == null)
            return false;

        ConcurrentHashMap<ItemID, ItemStack> usedMap = itemIDMap;
        if(usedMap.isEmpty())
        {
            if(singlePlayerServerBackupOnPlayerLeave != null)
                usedMap = singlePlayerServerBackupOnPlayerLeave;
        }
        for(Map.Entry<ItemID, ItemStack> entry : usedMap.entrySet())
        {
            ItemStack itemStack = entry.getValue();
            ItemID itemID = entry.getKey();
            CompoundTag tag = new CompoundTag();
            CompoundTag idTag = new CompoundTag();
            itemID.save(idTag);
            tag.put("itemID", idTag);
            CompoundTag itemStackTag = new CompoundTag();
            Tag stackTag = itemStack.save(access, itemStackTag);
            tag.put("itemStack", stackTag);

            listTag.add(tag);
        }
        listTags.put("items", listTag);

        return true;
    }

    @Override
    public boolean load(Map<String, ListTag> listTags) {
        if(!listTags.containsKey("items"))
            return false;

        ListTag listTag = listTags.get("items");
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if(access == null)
            return false;

        //itemIDMap.clear();
        for(Tag tag : listTag)
        {
            CompoundTag compoundTag = (CompoundTag)tag;
            if(compoundTag==null || !compoundTag.contains("itemID") || !compoundTag.contains("itemStack"))
                continue;

            CompoundTag itemStackTag = compoundTag.getCompound("itemStack");
            CompoundTag itemIDTag = compoundTag.getCompound("itemID");

            ItemID id = new ItemID(ItemID.INVALID_ID);
            if(!id.load(itemIDTag))
                continue;

            Optional<ItemStack> itemStackOptional = ItemStack.parse(access, itemStackTag);
            if(itemStackOptional.isEmpty())
                continue;

            ItemStack itemStack = itemStackOptional.get();
            String name = ItemUtilities.getItemIDStr(itemStack.getItem());
            id.setNameCache_internal(name);
            itemIDMap.put(id, itemStack);
        }
        singlePlayerServerBackupOnPlayerLeave = null;
        return true;
    }*/


}
