package net.kroia.banksystem.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.JsonUtilities;
import net.kroia.modutilities.persistence.ServerSaveable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

public class ItemID implements ServerSaveable {

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, ItemID> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.SHORT, p -> p.id,
            ItemID::new
    );

    private static final String compoundTagKey_ID = "id";
    public static final ItemID INVALID_ID = new ItemID((short)0);

    private short id;
    private @Nullable String name_cache = null;


    public ItemID(short id)
    {
        this.id = id;
        //this.name_cache = String.valueOf(id);
        tryUpdateNameCache();
    }
    public ItemID(short id, @Nullable String name_cache)
    {
        this.id = id;
        this.name_cache = name_cache;
    }
    public ItemID(ItemID other)
    {
        this.id = other.id;
        this.name_cache = other.name_cache;
    }
    public void setNameCache_internal(String name_cache)
    {
        this.name_cache = name_cache;
    }
    public void tryUpdateNameCache()
    {
        ItemStack stack = getStack();
        if(stack == null) {
            name_cache = String.valueOf(id);
            return;
        }
        name_cache = ItemUtilities.getItemIDStr(stack.getItem());
    }
    public static @Nullable ItemID fromJson(JsonElement jsonElement)
    {
        if(!jsonElement.isJsonObject())
            return null;

        JsonObject jsonObject = jsonElement.getAsJsonObject();
        if(!jsonObject.has(compoundTagKey_ID))
            return null;
        short id = jsonObject.get(compoundTagKey_ID).getAsShort();
        return new ItemID(id);
    }

    public static @Nullable ItemID createFromTag(CompoundTag tag) {
        ItemID itemID = new ItemID((short) 0);
        if (!itemID.load(tag)) {
            return null; // Invalid data
        }
        return itemID;
    }

    public static @Nullable ItemID getFromItemStack(ItemStack itemStack)
    {
        return ItemIDManager.getItemID(itemStack);
    }

    /**
     * Do not call this from the client side!
     * @param itemStack
     * @return the itemID associated with the itemStack
     */
    public static @NotNull CompletableFuture<ItemID> getOrRegisterFromItemStack(@NotNull ItemStack itemStack)
    {
        return ItemIDManager.registerItemStack(itemStack);
    }

    /**
     * This function is only save to call from the master server
     * @param itemStack
     * @return the itemID associated with the itemStack
     */
    public static @NotNull ItemID getOrRegisterFromItemStack_direct(@NotNull ItemStack itemStack)
    {
        @Nullable ItemID id = getFromItemStack(itemStack);
        if(id == null)
        {
            id = ItemIDManager.registerItemStack_direct(itemStack);
        }
        return id;
    }

    public static ItemID of(ItemStack defaultInstance) {
        ItemID id = ItemIDManager.getItemID(defaultInstance);
        if(id == null)
        {
            warn("Item not registered: "+ defaultInstance);
        }
        return id;
    }




    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ItemID other)) return false;
        return id == other.id;
    }

    @Override
    public int hashCode() {
        return id;
    }

    public @Nullable ItemStack getStack() {
        return ItemIDManager.getItemStack(this);
    }
    public @NotNull String getName() {
        if(name_cache != null)
            return name_cache;

        ItemStack stack = getStack();
        if(stack == null) {
            name_cache = String.valueOf(id);
            return name_cache;
        }
        String name = ItemUtilities.getItemIDStr(stack.getItem());
        if(name == null) {
            name_cache = String.valueOf(id);
            return name_cache;
        }
        name_cache = name;
        return name_cache;
    }
    public boolean isAir() {
        ItemStack stack = getStack();
        return stack == null || stack.isEmpty() || stack.is(Items.AIR);
    }

    @Override
    public boolean save(CompoundTag tag) {
        tag.putShort(compoundTagKey_ID, id);
        return true;
    }

    @Override
    public boolean load(CompoundTag tag) {
        if(!tag.contains(compoundTagKey_ID))
            return false;
        id = tag.getShort(compoundTagKey_ID);
        name_cache = String.valueOf(id);
        return true;
    }

    public JsonElement toJson() {
        JsonObject jsonElement = new JsonObject();
        jsonElement.addProperty(compoundTagKey_ID, id);
        return jsonElement;
    }

    public String toJsonString()
    {
        return JsonUtilities.toPrettyString(toJson());
    }


    public boolean isValid() {
        if(id == INVALID_ID.id)
            return false;
        ItemStack stack = getStack();
        return stack != null;
    }

    @Override
    public String toString() {
        return getName();
    }

    public short getShort() {
        return id;
    }

    private static void info(String msg)
    {
        BACKEND_INSTANCES.LOGGER.info("[ItemID] " + msg);
    }
    private static void error(String msg)
    {
        BACKEND_INSTANCES.LOGGER.error("[ItemID] " + msg);
    }
    private static void error(String msg, Throwable e)
    {
        BACKEND_INSTANCES.LOGGER.error("[ItemID] " + msg, e);
    }
    private static void warn(String msg)
    {
        BACKEND_INSTANCES.LOGGER.warn("[ItemID] " + msg);
    }
    private static void debug(String msg)
    {
        BACKEND_INSTANCES.LOGGER.debug("[ItemID] " + msg);
    }
}
