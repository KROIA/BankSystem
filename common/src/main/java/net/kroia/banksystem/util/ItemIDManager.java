package net.kroia.banksystem.util;

import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ItemIDManager {

    private static final ConcurrentHashMap<ItemID, ItemStack> itemIDMap = new ConcurrentHashMap<>();


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

        id = generateIdFromItemStack(itemStack);
        itemIDMap.put(id, itemStack);
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


    private static @NotNull ItemID generateIdFromItemStack(@NotNull ItemStack itemStack)
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
    }
}
