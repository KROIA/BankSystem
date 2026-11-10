package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.minecraft.block.BankSystemBlocks;
import net.kroia.banksystem.minecraft.entity.custom.BankTerminalBlockEntity;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.ItemStackNbt;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Issue #81: one unreadable entry in a bank terminal's per-player "Slots" list must not stop
 * the load. Every later slot used to be skipped and was then lost on the next save.
 */
public class TerminalInventoryLoadTests extends TestSuite {

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.DATA_PERSISTENCE;
    }

    @Override
    public void registerTests() {
        addTest("bad_middle_entries_keep_other_slots", this::testBadMiddleEntriesKeepOtherSlots);
        addTest("out_of_range_slot_does_not_throw", this::testOutOfRangeSlotDoesNotThrow);
    }

    private static CompoundTag entry(int slot, Tag stackTag) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Slot", slot);
        tag.put("ItemStack", stackTag);
        return tag;
    }

    private static BankTerminalBlockEntity.TerminalInventory inventory(HolderLookup.Provider registries) {
        // Detached block entity (no level), as in WithdrawMergeTests.
        BankTerminalBlockEntity blockEntity = new BankTerminalBlockEntity(
                BlockPos.ZERO, BankSystemBlocks.BANK_TERMINAL_BLOCK.get().defaultBlockState());
        BankTerminalBlockEntity.TerminalInventory inventory =
                new BankTerminalBlockEntity.TerminalInventory(blockEntity, 9);
        inventory.setRegistryProvider(registries);
        return inventory;
    }

    private TestResult testBadMiddleEntriesKeepOtherSlots() {
        MinecraftServer server = getServer();
        if (server == null)
            return skip("needs a running server for the item registries");
        HolderLookup.Provider registries = server.registryAccess();

        ListTag slots = new ListTag();
        slots.add(entry(0, ItemStackNbt.save(new ItemStack(Items.DIRT, 5), registries)));
        CompoundTag noStack = new CompoundTag();
        noStack.putInt("Slot", 1);
        slots.add(noStack);
        CompoundTag removedModItem = new CompoundTag();
        removedModItem.putString("id", "banksystem_test_removed_mod:gone");
        removedModItem.putInt("count", 3);
        slots.add(entry(2, removedModItem));
        slots.add(entry(4, ItemStackNbt.save(new ItemStack(Items.STONE, 7), registries)));
        CompoundTag tag = new CompoundTag();
        tag.put("Slots", slots);

        BankTerminalBlockEntity.TerminalInventory inventory = inventory(registries);
        TestResult r = assertTrue("load must succeed despite bad entries", inventory.load(tag));
        if (!r.passed()) return r;
        r = assertEquals("slot before the bad entries", 5, inventory.getItem(0).getCount());
        if (!r.passed()) return r;
        r = assertTrue("slot before the bad entries is dirt", inventory.getItem(0).is(Items.DIRT));
        if (!r.passed()) return r;
        r = assertTrue("entry without ItemStack stays empty", inventory.getItem(1).isEmpty());
        if (!r.passed()) return r;
        r = assertTrue("unknown item stays empty", inventory.getItem(2).isEmpty());
        if (!r.passed()) return r;
        r = assertEquals("slot after the bad entries", 7, inventory.getItem(4).getCount());
        if (!r.passed()) return r;
        return assertTrue("slot after the bad entries is stone", inventory.getItem(4).is(Items.STONE));
    }

    private TestResult testOutOfRangeSlotDoesNotThrow() {
        MinecraftServer server = getServer();
        if (server == null)
            return skip("needs a running server for the item registries");
        HolderLookup.Provider registries = server.registryAccess();

        ListTag slots = new ListTag();
        slots.add(entry(99, ItemStackNbt.save(new ItemStack(Items.DIRT, 1), registries)));
        slots.add(entry(-1, ItemStackNbt.save(new ItemStack(Items.DIRT, 1), registries)));
        slots.add(entry(3, ItemStackNbt.save(new ItemStack(Items.STONE, 2), registries)));
        CompoundTag tag = new CompoundTag();
        tag.put("Slots", slots);

        BankTerminalBlockEntity.TerminalInventory inventory = inventory(registries);
        try {
            inventory.load(tag);
        } catch (RuntimeException e) {
            return fail("load threw on an out-of-range slot: " + e);
        }
        return assertEquals("slot after the out-of-range entries", 2, inventory.getItem(3).getCount());
    }
}
