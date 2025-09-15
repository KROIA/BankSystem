package net.kroia.banksystem.util;

import com.google.gson.JsonElement;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.JsonUtilities;
import net.kroia.modutilities.networking.INetworkPayloadConverter;
import net.kroia.modutilities.persistence.ServerSaveable;
import net.kroia.modutilities.setting.parser.ItemStackJsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class ItemID implements ServerSaveable, INetworkPayloadConverter {

    public static final ItemID EMPTY = new ItemID(Items.AIR.getDefaultInstance());

    private ItemStack stack;


    public ItemID(String itemID) {
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
    public ItemID(FriendlyByteBuf buf) {
        decode(buf);
    }

    public static ItemID createFromTag(CompoundTag tag) {
        ItemID itemID = new ItemID();
        if (!itemID.load(tag)) {
            return null; // Invalid data
        }
        return itemID;
    }
    public static ItemID createFomBytes(FriendlyByteBuf buf) {
        ItemID itemID = new ItemID();
        itemID.decode(buf);
        return itemID;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ItemID other)) return false;
        return ItemStack.isSameItemSameTags(this.stack, other.stack);
    }

    @Override
    public int hashCode() {
        if(stack == null || stack.isEmpty()) {
            return Objects.hash(Items.AIR.getDefaultInstance(), null); // Handle empty stack
        }
        return Objects.hash(stack.getItem(), stack.getTag()); // Consider NBT
    }

    public ItemStack getStack() {
        return stack;
    }
    public String getName() {
        return ItemUtilities.getItemIDStr(stack.getItem());
    }
    public boolean isAir() {
        return stack == null || stack.isEmpty() || stack.is(Items.AIR);
    }

    @Override
    public boolean save(CompoundTag tag) {
        tag.put("item", stack.save(new CompoundTag()));
        return true;
    }

    @Override
    public boolean load(CompoundTag tag) {
        if(!tag.contains("item"))
            return false;
        stack = ItemStack.of(tag.getCompound("item"));
        return true;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeItem(stack); // Encode the ItemID as an ItemStack
    }

    @Override
    public void decode(FriendlyByteBuf buf) {
        stack = buf.readItem(); // Decode the ItemID from an ItemStack
    }

    public JsonElement toJson() {
        ItemStackJsonParser parser = new ItemStackJsonParser();
        return parser.toJson(stack);
    }

    public boolean fromJson(JsonElement json) {
        ItemStackJsonParser parser = new ItemStackJsonParser();
        this.stack = parser.fromJson(json);
        if (this.stack == null || this.stack.isEmpty()) {
            this.stack = Items.AIR.getDefaultInstance();
            return false;
        }
        this.stack.setCount(1); // Ensure count is 1
        return true;
    }

    public String toJsonString()
    {
        return JsonUtilities.toPrettyString(toJson());
    }


    public boolean isValid() {
        return !stack.isEmpty() && stack != null && !isAir();
    }

    @Override
    public String toString() {
        return toJsonString();
    }

    public UUID getUUID() {
        return UUID.nameUUIDFromBytes(toString().getBytes());
    }

    public int compareTo(ItemID other) {
        Item item1 = stack.getItem();
        Item item2 = other.stack.getItem();

        // Get the registry keys for both items
        ResourceKey<Item> key1 = BuiltInRegistries.ITEM.getResourceKey(item1).orElseThrow();
        ResourceKey<Item> key2 = BuiltInRegistries.ITEM.getResourceKey(item2).orElseThrow();

        // Get all tags for both items
        Set<TagKey<Item>> tags1 = BuiltInRegistries.ITEM.getHolderOrThrow(key1).tags().collect(Collectors.toSet());
        Set<TagKey<Item>> tags2 = BuiltInRegistries.ITEM.getHolderOrThrow(key2).tags().collect(Collectors.toSet());

        // Convert tags to sorted string representation for comparison
        String tagString1 = tags1.stream()
                .map(tag -> tag.location().toString())
                .sorted()
                .collect(Collectors.joining(","));

        String tagString2 = tags2.stream()
                .map(tag -> tag.location().toString())
                .sorted()
                .collect(Collectors.joining(","));

        // First compare by tags
        int tagComparison = tagString1.compareTo(tagString2);
        if (tagComparison != 0) {
            return -tagComparison;
        }

        // If tags are the same, compare by item name
        String name1 = item1.getDescriptionId();
        String name2 = item2.getDescriptionId();

        return name1.compareTo(name2);
    }


}
