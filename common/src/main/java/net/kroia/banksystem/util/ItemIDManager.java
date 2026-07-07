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

    private static ConcurrentHashMap<ItemID, ItemStack> singlePlayerServerBackupOnPlayerLeave = null;
    private static ConcurrentHashMap<ItemID, ItemID> singlePlayerServerAliasBackupOnPlayerLeave = null;
    private static Map<ItemStack, CompletableFuture<ItemID>> pendingItemIDs = new ConcurrentHashMap<>();
    private static List<Pair<List<ItemStack>, CompletableFuture<List<ItemID>>>> pendingItemIDGroups = new CopyOnWriteArrayList<>();

    public static void clear()
    {
        singlePlayerServerBackupOnPlayerLeave = new ConcurrentHashMap<>(itemIDMap);
        singlePlayerServerAliasBackupOnPlayerLeave = new ConcurrentHashMap<>(itemIDAliasMap);
        itemIDMap.clear();
        itemIDAliasMap.clear();
    }

    /**
     * Resolves an ItemID through the alias table to its canonical ID.
     * IDs that were never merged resolve to themselves.
     *
     * @param itemID the (possibly aliased) ItemID
     * @return the canonical ItemID
     */
    public static @NotNull ItemID resolveAlias(@NotNull ItemID itemID) {
        ItemID canonical = itemIDAliasMap.get(itemID);
        return canonical != null ? canonical : itemID;
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

                short newID = (short)(itemIDMap.size() + 1);
                do{
                    newID++;
                    // Never reuse a short that is still referenced as an alias of a merged ID —
                    // old saved data may still address it.
                }while(itemIDMap.containsKey(new ItemID(newID)) || itemIDAliasMap.containsKey(new ItemID(newID)));

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
        if(usedMap.isEmpty())
        {
            if(singlePlayerServerBackupOnPlayerLeave != null)
                usedMap = singlePlayerServerBackupOnPlayerLeave;
            if(singlePlayerServerAliasBackupOnPlayerLeave != null)
                usedAliasMap = singlePlayerServerAliasBackupOnPlayerLeave;
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
            if(itemStackOptional.isEmpty())
                continue;

            // Migration for worlds saved before volatile-component normalization existed:
            // templates saved with volatile data (e.g. a rotten tfc:food state) become clean here.
            ItemStack itemStack = VolatileItemComponents.normalize(itemStackOptional.get());
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

        // Merge entries that collapse to the same normalized identity (e.g. spurious IDs
        // minted by decaying food before this fix). Keeps the lowest ID as canonical and
        // aliases the rest, so bank balances / markets keyed by old IDs keep resolving.
        renormalizeAndMerge();

        singlePlayerServerBackupOnPlayerLeave = null;
        singlePlayerServerAliasBackupOnPlayerLeave = null;
        return true;
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
     */
    public static void renormalizeAndMerge()
    {
        int mergedCount = 0;
        synchronized (itemIDMap) {
            // Pass 1: re-normalize all templates in place (cheap no-op when already normalized).
            for (Map.Entry<ItemID, ItemStack> entry : itemIDMap.entrySet()) {
                ItemStack normalized = VolatileItemComponents.normalize(entry.getValue());
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
        }
        if (mergedCount > 0 && BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
            BACKEND_INSTANCES.LOGGER.info("[ItemIDManager] Merged " + mergedCount +
                    " duplicate ItemID(s) that collapsed to the same identity after removing volatile item components. " +
                    "Old IDs remain valid as aliases of their canonical ID.");
        }
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
