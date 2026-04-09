package net.kroia.banksystem.util;

import com.ibm.icu.impl.Pair;
import net.kroia.banksystem.item.BankSystemItems;
import net.kroia.banksystem.networking.packet.general.RegisterItemIDPacket;
import net.kroia.banksystem.networking.packet.general.SyncItemIDsPacket;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.UtilitiesPlatform;
import net.kroia.modutilities.networking.server_server.ServerServerManager;
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
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class ItemIDManager implements ServerSaveable {

    private static final ConcurrentHashMap<ItemID, ItemStack> itemIDMap = new ConcurrentHashMap<>();

    private static ConcurrentHashMap<ItemID, ItemStack> singlePlayerServerBackupOnPlayerLeave = null;
    private static Map<ItemStack, CompletableFuture<ItemID>> pendingItemIDs = new ConcurrentHashMap<>();
    private static List<Pair<List<ItemStack>, CompletableFuture<List<ItemID>>>> pendingItemIDGroups = new ArrayList<>();

    public static void clear()
    {
        singlePlayerServerBackupOnPlayerLeave = new ConcurrentHashMap<>(itemIDMap);
        itemIDMap.clear();
    }

    public static @NotNull ItemStack getItemStack(@NotNull ItemID itemID) {
        ItemStack item = itemIDMap.get(itemID);
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

    public static CompletableFuture<ItemID> registerItemStackServerSide(@NotNull ItemStack itemStack)
    {
        ItemStack cpy =  itemStack.copy();
        cpy.setCount(1);
        CompletableFuture<ItemID>  future = new CompletableFuture<>();
        if(ServerServerManager.isRunning() && ServerServerManager.isMaster())
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
    public static CompletableFuture<List<ItemID>> registerItemStackServerSide(List<ItemStack> itemStacks) {
        List<ItemStack> cpyStacks = new ArrayList<>();
        CompletableFuture<List<ItemID>> groupFuture = new CompletableFuture<>();
        List<ItemID> foundItemIDs = new ArrayList<>();
        for (ItemStack itemStack : itemStacks)
        {
            ItemID itemID = getItemID(itemStack);
            if(itemID == null) {
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
            pendingItemIDGroups.add(Pair.of(cpyStacks, groupFuture));
            RegisterItemIDPacket.sendRegisterItemIDPacketToMaster(cpyStacks);
        }
        return groupFuture;
    }


    public static CompletableFuture<ItemID> registerItemStackClientSide(@NotNull ItemStack itemStack)
    {
        ItemStack cpy =  itemStack.copy();
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
            if(itemID == null) {
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
        for(ItemStack stack : itemStacks)
        {
            // Search the entire list to check if the same item stack already is registered
            ItemID id = getItemID(stack);
            if(id != null)
            {
                ids.add(id);
                continue;
            }

            if(stack.isEmpty())
            {
                ids.add(ItemID.INVALID_ID);
                continue;
            }

            short newID = (short)(itemIDMap.size()+1);

            ItemStack cpy = stack.copy();
            String name = ItemUtilities.getItemIDStr(cpy.getItem());
            id = new ItemID(newID, name);
            cpy.setCount(1);
            newItemIDMap.put(id, cpy);
            itemIDMap.put(id, cpy);
        }
        if(!newItemIDMap.isEmpty())
            onNewItemAdded(newItemIDMap);
        return ids;
    }

    public static @Nullable ItemID getItemID(@NotNull ItemStack itemStack)
    {
        int oldAmount = itemStack.getCount();
        itemStack.setCount(1);
        for(Map.Entry<ItemID, ItemStack> entry : itemIDMap.entrySet())
        {
            ItemID itemID = entry.getKey();
            ItemStack item = entry.getValue();
            if(ItemStack.isSameItemSameComponents(itemStack, item))
            {
                itemStack.setCount(oldAmount);
                return itemID;
            }
        }
        itemStack.setCount(oldAmount);
        return null;
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
        if(ServerServerManager.isRunning() && ServerServerManager.isMaster())
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
        Map<ItemID, ItemStack> items = packet.getItems();
        for(Map.Entry<ItemID, ItemStack> entry : items.entrySet())
        {
            // Search the entire list to check if the same item stack already is registered
            ItemStack itemStack = entry.getValue();

            //ItemID id = getItemID(itemStack);
            //if(id != null)
            //    continue;
            ItemID id = entry.getKey();
            ItemStack cpy = itemStack.copy();
            cpy.setCount(1);
            String name = ItemUtilities.getItemIDStr(cpy.getItem());
            entry.getKey().setNameCache_internal(name);

            CompletableFuture<ItemID> pendingItemID = pendingItemIDs.get(itemStack);
            if(pendingItemID != null)
            {
                pendingItemID.complete(id);
                pendingItemIDs.remove(itemStack);
            }


            itemIDMap.put(id, cpy);
            id.tryUpdateNameCache();
        }
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
        registerItemStackServerSide_direct(packet.getItems());
    }

    @Override
    public boolean save(CompoundTag tag) {
        ConcurrentHashMap<ItemID, ItemStack> usedMap = itemIDMap;
        if(usedMap.isEmpty())
        {
            if(singlePlayerServerBackupOnPlayerLeave != null)
                usedMap = singlePlayerServerBackupOnPlayerLeave;
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
            Tag stackTag = itemStack.save(access, itemStackTag);
            pairTag.put("itemStack", stackTag);

            listTag.add(pairTag);
        }
        tag.put("itemIDs", listTag);
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

            ItemStack itemStack = itemStackOptional.get();

            itemIDMap.put(id, itemStack);
            id.tryUpdateNameCache();
        }
        singlePlayerServerBackupOnPlayerLeave = null;
        return true;
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
