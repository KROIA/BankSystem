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

    //public static final ItemID EMPTY = new ItemID(Items.AIR.getDefaultInstance());

    //private ItemStack stack;
    //private UUID uuid;
    private short id;

    /*public ItemID(UUID uuid) {
        this.uuid = uuid;
    }*/
    public ItemID(short id)
    {
        this.id = id;
    }
    public ItemID(ItemID other)
    {
        this.id = other.id;
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
    public static @NotNull ItemID getOrRegisterFromItemStack(ItemStack itemStack)
    {
        return ItemIDManager.registerItemStack(itemStack);
    }

    public static ItemID of(ItemStack defaultInstance) {
        ItemID id = ItemIDManager.getItemID(defaultInstance);
        if(id == null)
        {
            warn("Item not registered: "+ defaultInstance);
        }
        return id;
    }


   /* public ItemID(String itemID) {
        this(ItemUtilities.createItemStackFromId(itemID));
    }
    public ItemID(ItemStack stack) {
        if(stack == null)
        {
            this.stack = Items.AIR.getDefaultInstance();
            return;
        }
        this.stack = stack.copy(); // Ensure immutability
        this.stack.setCount(1); // Ensure count is 1
    }
    private ItemID()
    {

    }

    public static ItemID of(ItemStack stack)
    {
        return new ItemID(stack);
    }
    public ItemID(CompoundTag tag) {
        load(tag);
    }
    public ItemID(JsonElement jsonElement) {
        fromJson(jsonElement);
    }
*/



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
        ItemStack stack = getStack();
        if(stack == null) {
            return "?";
        }
        String name = ItemUtilities.getItemIDStr(stack.getItem());
        if(name == null) {
            return "?";
        }
        return name;
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
        //return toJsonString();
        return "ID = '"+id+"'";
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
