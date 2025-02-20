package net.kroia.banksystem.util;

import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.ServerSaveable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

public class ItemID implements ServerSaveable {
    private ItemStack stack;

    public ItemID(String itemID) {
        this(ItemUtilities.createItemStackFromId(itemID));
    }
    public ItemID(ItemStack stack) {
        this.stack = stack.copy(); // Ensure immutability
        this.stack.setCount(1); // Ensure count is 1
    }
    public ItemID(CompoundTag tag) {
        load(tag);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ItemID other)) return false;
        return ItemStack.isSameItemSameTags(this.stack, other.stack);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stack.getItem(), stack.getTag()); // Consider NBT
    }

    public ItemStack getStack() {
        return stack;
    }
    public String getName() {
        return ItemUtilities.getItemName(stack.getItem());
    }

    @Override
    public boolean save(CompoundTag tag) {
        tag.put("item", stack.save(new CompoundTag()));
        return false;
    }

    @Override
    public boolean load(CompoundTag tag) {
        stack = ItemStack.of(tag.getCompound("item"));
        return false;
    }
}
