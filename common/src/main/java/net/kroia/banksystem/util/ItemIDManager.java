package net.kroia.banksystem.util;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncItemIDsPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ItemIDManager {

    private static final ConcurrentHashMap<ItemID, ItemStack> itemIDMap = new ConcurrentHashMap<>();

    public static void clear()
    {
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

    public static @NotNull ItemID registerItemStack(@NotNull ItemStack itemStack)
    {
        // Search the entire list to check if the same item stack already is registered
        ItemID id = getItemID(itemStack);
        if(id != null)
            return id; // Already registered

        if(itemStack.isEmpty())
        {
            return ItemID.INVALID_ID;
        }

        short newID = (short)(itemIDMap.size()+1);
        id = new ItemID(newID);
        ItemStack cpy = itemStack.copy();
        cpy.setCount(1);
        itemIDMap.put(id, cpy);
        onNewItemAdded(id, cpy);
        return id;
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



    private static void onNewItemAdded(@NotNull ItemID itemID, ItemStack stack)
    {
        // Broadcast update to players
        Map<ItemID, ItemStack> items = new HashMap<>();
        items.put(itemID, stack);
        SyncItemIDsPacket packet = new SyncItemIDsPacket(items);
        packet.broadcastToClients();
    }
    public static void onPlayerJoined(@NotNull ServerPlayer player)
    {
        Map<ItemID, ItemStack> items = new HashMap<>(itemIDMap);
        SyncItemIDsPacket packet = new SyncItemIDsPacket(items);
        packet.sendToClient(player);
    }
    public static void receiveSyncPacket(SyncItemIDsPacket packet, NetworkManager.PacketContext context)
    {
        Map<ItemID, ItemStack> items = packet.getItems();
        for(Map.Entry<ItemID, ItemStack> entry : items.entrySet())
        {
            // Search the entire list to check if the same item stack already is registered
            ItemStack itemStack = entry.getValue();
            ItemID id = getItemID(itemStack);
            if(id != null)
                continue;

            ItemStack cpy = itemStack.copy();
            cpy.setCount(1);
            itemIDMap.put(entry.getKey(), cpy);
        }
    }

}
