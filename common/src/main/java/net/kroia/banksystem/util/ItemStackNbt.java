package net.kroia.banksystem.util;

import net.kroia.modutilities.NbtCompat;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * Version-neutral ItemStack / container NBT (de)serialization. Produces exactly the format of
 * the 1.21.1 vanilla helpers (ItemStack.save/parse/parseOptional, SimpleContainer.createTag/fromTag,
 * ContainerHelper.saveAllItems/loadAllItems), which MC 1.21.6 removed. All of them are thin wrappers
 * over {@link ItemStack#CODEC} with the registries' NBT serialization context, as vanilla was.
 */
public final class ItemStackNbt {

    private ItemStackNbt() {
    }

    private static RegistryOps<Tag> ops(HolderLookup.Provider registries) {
        return registries.createSerializationContext(NbtOps.INSTANCE);
    }

    /** Like 1.21.1 {@code stack.save(registries)}; the stack must not be empty. */
    public static Tag save(ItemStack stack, HolderLookup.Provider registries) {
        if (stack.isEmpty())
            throw new IllegalStateException("Cannot encode empty ItemStack");
        return ItemStack.CODEC.encodeStart(ops(registries), stack).getOrThrow();
    }

    /** Like 1.21.1 {@code stack.save(registries, prefix)}: merges the stack's fields into {@code prefix}. */
    public static Tag save(ItemStack stack, HolderLookup.Provider registries, Tag prefix) {
        if (stack.isEmpty())
            throw new IllegalStateException("Cannot encode empty ItemStack");
        return ItemStack.CODEC.encode(stack, ops(registries), prefix).getOrThrow();
    }

    /** Like 1.21.1 {@code ItemStack.parse(registries, tag)}. */
    public static Optional<ItemStack> parse(HolderLookup.Provider registries, Tag tag) {
        return ItemStack.CODEC.parse(ops(registries), tag).resultOrPartial(error -> { });
    }

    /**
     * Like {@link #parse} but refuses a partial decode: empty when any part of the tag failed
     * (e.g. an enchantment or component from a removed mod), instead of silently dropping it.
     */
    public static Optional<ItemStack> parseStrict(HolderLookup.Provider registries, Tag tag) {
        return ItemStack.CODEC.parse(ops(registries), tag).result();
    }

    /** Like 1.21.1 {@code ItemStack.parseOptional(registries, tag)}. */
    public static ItemStack parseOptional(HolderLookup.Provider registries, CompoundTag tag) {
        return tag.isEmpty() ? ItemStack.EMPTY : parse(registries, tag).orElse(ItemStack.EMPTY);
    }

    /** Like 1.21.1 {@code SimpleContainer.createTag(registries)}: the non-empty stacks, without slots. */
    public static ListTag createTag(Container container, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty())
                list.add(save(stack, registries));
        }
        return list;
    }

    /** Like 1.21.1 {@code SimpleContainer.fromTag(list, registries)}: clears, then adds each stack. */
    public static void fromTag(SimpleContainer container, ListTag list, HolderLookup.Provider registries) {
        container.clearContent();
        for (int i = 0; i < list.size(); i++)
            parse(registries, NbtCompat.getCompound(list, i)).ifPresent(container::addItem);
    }

    /** Like 1.21.1 {@code ContainerHelper.saveAllItems(tag, items, registries)} ("Items" list with "Slot" bytes). */
    public static CompoundTag saveAllItems(CompoundTag tag, NonNullList<ItemStack> items, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty()) {
                CompoundTag slot = new CompoundTag();
                slot.putByte("Slot", (byte) i);
                list.add(save(stack, registries, slot));
            }
        }
        tag.put("Items", list);
        return tag;
    }

    /** Like 1.21.1 {@code ContainerHelper.loadAllItems(tag, items, registries)}. */
    public static void loadAllItems(CompoundTag tag, NonNullList<ItemStack> items, HolderLookup.Provider registries) {
        ListTag list = NbtCompat.getList(tag, "Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag slotTag = NbtCompat.getCompound(list, i);
            int slot = NbtCompat.getByte(slotTag, "Slot") & 255;
            if (slot < items.size())
                items.set(slot, parse(registries, slotTag).orElse(ItemStack.EMPTY));
        }
    }
}
