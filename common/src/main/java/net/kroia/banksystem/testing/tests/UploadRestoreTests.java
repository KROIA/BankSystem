package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.minecraft.entity.custom.BankUploadBlockEntity;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Issue #82: a bank upload block puts a stack back after a failed deposit. The slot was cleared
 * when the deposit started, so whatever arrived there meanwhile must never be overwritten.
 * Tests the placement seam {@link BankUploadBlockEntity#restoreInto}; the returned remainder is
 * what the block entity drops at its position.
 */
public class UploadRestoreTests extends TestSuite {

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.ITEM_BANK;
    }

    @Override
    public void registerTests() {
        addTest("restore_into_empty_slot", this::testEmptySlot);
        addTest("restore_merges_into_compatible_stack", this::testCompatibleMerge);
        addTest("restore_moves_past_incompatible_stack", this::testIncompatibleUsesOtherSlot);
        addTest("restore_full_inventory_returns_rest_to_drop", this::testFullInventoryDrops);
    }

    private TestResult testEmptySlot() {
        SimpleContainer container = new SimpleContainer(3);
        ItemStack rest = BankUploadBlockEntity.restoreInto(container, 1, new ItemStack(Items.DIRT, 10));
        TestResult r = assertTrue("nothing left to drop", rest.isEmpty());
        if (!r.passed()) return r;
        r = assertTrue("stack back in its own slot", container.getItem(1).is(Items.DIRT));
        if (!r.passed()) return r;
        return assertEquals("count restored", 10, container.getItem(1).getCount());
    }

    private TestResult testCompatibleMerge() {
        SimpleContainer container = new SimpleContainer(3);
        container.setItem(1, new ItemStack(Items.DIRT, 20));
        ItemStack rest = BankUploadBlockEntity.restoreInto(container, 1, new ItemStack(Items.DIRT, 10));
        TestResult r = assertTrue("nothing left to drop", rest.isEmpty());
        if (!r.passed()) return r;
        r = assertEquals("merged into the newer stack", 30, container.getItem(1).getCount());
        if (!r.passed()) return r;
        r = assertTrue("no other slot used (0)", container.getItem(0).isEmpty());
        if (!r.passed()) return r;
        return assertTrue("no other slot used (2)", container.getItem(2).isEmpty());
    }

    private TestResult testIncompatibleUsesOtherSlot() {
        SimpleContainer container = new SimpleContainer(3);
        container.setItem(1, new ItemStack(Items.STONE, 5));
        ItemStack rest = BankUploadBlockEntity.restoreInto(container, 1, new ItemStack(Items.DIRT, 10));
        TestResult r = assertTrue("nothing left to drop", rest.isEmpty());
        if (!r.passed()) return r;
        r = assertTrue("newer stack not overwritten", container.getItem(1).is(Items.STONE));
        if (!r.passed()) return r;
        r = assertEquals("newer stack count untouched", 5, container.getItem(1).getCount());
        if (!r.passed()) return r;
        int dirt = 0;
        for (int i = 0; i < container.getContainerSize(); i++)
            if (container.getItem(i).is(Items.DIRT))
                dirt += container.getItem(i).getCount();
        return assertEquals("restored stack placed in another slot", 10, dirt);
    }

    private TestResult testFullInventoryDrops() {
        SimpleContainer container = new SimpleContainer(2);
        container.setItem(0, new ItemStack(Items.STONE, 64));
        container.setItem(1, new ItemStack(Items.DIRT, 60));
        ItemStack rest = BankUploadBlockEntity.restoreInto(container, 0, new ItemStack(Items.DIRT, 10));
        TestResult r = assertEquals("4 merged into the dirt stack", 64, container.getItem(1).getCount());
        if (!r.passed()) return r;
        r = assertEquals("stone stack untouched", 64, container.getItem(0).getCount());
        if (!r.passed()) return r;
        r = assertTrue("remainder is dirt", rest.is(Items.DIRT));
        if (!r.passed()) return r;
        return assertEquals("remainder returned for dropping", 6, rest.getCount());
    }
}
