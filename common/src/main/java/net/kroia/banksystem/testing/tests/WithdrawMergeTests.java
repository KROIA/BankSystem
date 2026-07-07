package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.minecraft.block.BankSystemBlocks;
import net.kroia.banksystem.minecraft.entity.custom.BankTerminalBlockEntity;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.ContainerItemInsertion;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.banksystem.util.VolatileItemComponents;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.util.List;

/**
 * In-game tests for component-aware physical stack placement when bank credits materialize
 * into a container ({@link ContainerItemInsertion}, used by the bank terminal block
 * inventory and the bank download block).
 * <p>
 * The bug these tests guard against: ItemID identity deliberately ignores volatile and
 * deposit-gated components, so a "spoiled" and a "fresh" stack share one ItemID. Any
 * insertion that picks its merge slot by ItemID equality merges withdrawn (fresh) items
 * onto the spoiled stack, silently rewriting their state. Physical merging must instead use
 * the game's own {@code ItemStack.isSameItemSameComponents} (which mods like TFC hook so
 * rotten never equals fresh).
 * <p>
 * Like {@link DepositGateTests}, these tests use {@code minecraft:custom_data} as a
 * stand-in for a third-party gated component by temporarily adding it to the config-sourced
 * gated set (making it invisible to identity, exactly like {@code tfc:food}); both config
 * sets are restored in {@code teardown()}.
 */
public class WithdrawMergeTests extends TestSuite {

    /** Config-sourced volatile ids captured in setup() and restored in teardown(). */
    private List<String> savedVolatileConfigIds = null;
    /** Config-sourced deposit-gated ids captured in setup() and restored in teardown(). */
    private List<String> savedGatedConfigIds = null;

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.ITEM_ID;
    }

    @Override
    public void registerTests() {
        addTest("withdrawn_items_do_not_merge_into_mismatched_stack", this::testNoMergeIntoMismatchedStack);
        addTest("withdrawn_items_merge_into_component_equal_stack", this::testMergeIntoComponentEqualStack);
        addTest("merge_preferred_over_empty_slot", this::testMergePreferredOverEmptySlot);
        addTest("full_container_inserts_nothing_and_keeps_stacks", this::testFullContainerNoSilentLoss);
        addTest("terminal_inventory_keeps_mismatched_stack_untouched", this::testTerminalInventoryKeepsMismatchedStack);
    }

    @Override
    public void setup() {
        savedVolatileConfigIds = VolatileItemComponents.getConfigComponentIdStrings();
        savedGatedConfigIds = VolatileItemComponents.getGatedConfigComponentIdStrings();
    }

    @Override
    public void teardown() {
        // Restore the server's real config-sourced component sets. Both sets affect
        // ItemID identity, so a change requires re-normalizing the registry.
        boolean changed = false;
        if (savedVolatileConfigIds != null)
            changed = VolatileItemComponents.setConfigComponentIds(savedVolatileConfigIds);
        if (savedGatedConfigIds != null)
            changed |= VolatileItemComponents.setGatedConfigComponentIds(savedGatedConfigIds);
        if (changed)
            ItemIDManager.renormalizeAndMerge();
        savedVolatileConfigIds = null;
        savedGatedConfigIds = null;
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /** Creates a paper stack carrying the given string inside minecraft:custom_data. */
    private static ItemStack paperWithCustomData(String value, int count) {
        ItemStack stack = new ItemStack(Items.PAPER, count);
        CompoundTag nbt = new CompoundTag();
        nbt.putString("banksystem_withdraw_merge_test", value);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(nbt));
        return stack;
    }

    /**
     * Makes custom_data a deposit-gated component (invisible to identity, like tfc:food)
     * and resolves the plain-paper ItemID both the "fresh" and "spoiled" variants share.
     */
    private ItemID gateCustomDataAndGetPaperId() {
        VolatileItemComponents.setConfigComponentIds(List.of());
        VolatileItemComponents.setGatedConfigComponentIds(List.of("minecraft:custom_data"));
        return ItemIDManager.getItemID(new ItemStack(Items.PAPER));
    }

    // ========================================================================
    // Tests
    // ========================================================================

    /**
     * Acceptance criterion 1: withdrawing into a container holding a component-mismatched
     * stack of the same ItemID must use a separate slot and leave the existing stack
     * untouched — never merge onto it.
     */
    private TestResult testNoMergeIntoMismatchedStack() {
        ItemID paperId = gateCustomDataAndGetPaperId();
        if (!paperId.isValid())
            return fail("Plain paper is not registered — default ItemIDs missing on this server?");

        // Premise of the bug: the "spoiled" variant resolves to the same ItemID.
        TestResult r = assertEquals("spoiled variant shares the plain paper ItemID",
                paperId, ItemIDManager.getItemID(paperWithCustomData("spoiled", 1)));
        if (!r.passed()) return r;

        SimpleContainer container = new SimpleContainer(9);
        container.setItem(0, paperWithCustomData("spoiled", 10));

        long inserted = ContainerItemInsertion.insertWithdrawnItems(container, paperId, 10);
        r = assertEquals("all 10 items inserted", 10L, inserted);
        if (!r.passed()) return r;

        ItemStack slot0 = container.getItem(0);
        r = assertEquals("mismatched stack count untouched", 10, slot0.getCount());
        if (!r.passed()) return r;
        r = assertTrue("mismatched stack kept its gated component",
                slot0.has(DataComponents.CUSTOM_DATA));
        if (!r.passed()) return r;

        ItemStack slot1 = container.getItem(1);
        r = assertEquals("withdrawn items placed in a separate slot", 10, slot1.getCount());
        if (!r.passed()) return r;
        r = assertFalse("withdrawn items did not inherit the mismatched component",
                slot1.has(DataComponents.CUSTOM_DATA));
        if (!r.passed()) return r;
        return pass("withdrawn items never merge onto a component-mismatched stack");
    }

    /** Component-equal stacks must still merge (normal stacking is unchanged). */
    private TestResult testMergeIntoComponentEqualStack() {
        ItemID paperId = ItemIDManager.getItemID(new ItemStack(Items.PAPER));
        if (!paperId.isValid())
            return fail("Plain paper is not registered — default ItemIDs missing on this server?");

        SimpleContainer container = new SimpleContainer(9);
        container.setItem(0, new ItemStack(Items.PAPER, 5));

        long inserted = ContainerItemInsertion.insertWithdrawnItems(container, paperId, 10);
        TestResult r = assertEquals("all 10 items inserted", 10L, inserted);
        if (!r.passed()) return r;
        r = assertEquals("items merged into the component-equal stack", 15, container.getItem(0).getCount());
        if (!r.passed()) return r;
        r = assertTrue("no second slot used", container.getItem(1).isEmpty());
        if (!r.passed()) return r;
        return pass("component-equal stacks merge as before");
    }

    /** Vanilla semantics: top up existing component-equal stacks before opening empty slots. */
    private TestResult testMergePreferredOverEmptySlot() {
        ItemID paperId = ItemIDManager.getItemID(new ItemStack(Items.PAPER));
        if (!paperId.isValid())
            return fail("Plain paper is not registered — default ItemIDs missing on this server?");

        SimpleContainer container = new SimpleContainer(9);
        // Slot 0 empty, slot 1 partially filled (max stack size of paper is 64).
        container.setItem(1, new ItemStack(Items.PAPER, 60));

        long inserted = ContainerItemInsertion.insertWithdrawnItems(container, paperId, 10);
        TestResult r = assertEquals("all 10 items inserted", 10L, inserted);
        if (!r.passed()) return r;
        r = assertEquals("existing stack topped up first", 64, container.getItem(1).getCount());
        if (!r.passed()) return r;
        r = assertEquals("remainder placed into the empty slot", 6, container.getItem(0).getCount());
        if (!r.passed()) return r;
        return pass("merging is preferred over opening a new slot");
    }

    /**
     * Acceptance criterion 3: when only component-mismatched stacks occupy the container and
     * no slot is free, nothing is inserted (return 0) and every stack stays untouched — the
     * un-inserted remainder is reported to the caller, which keeps it in the bank.
     */
    private TestResult testFullContainerNoSilentLoss() {
        ItemID paperId = gateCustomDataAndGetPaperId();
        if (!paperId.isValid())
            return fail("Plain paper is not registered — default ItemIDs missing on this server?");

        SimpleContainer container = new SimpleContainer(3);
        for (int i = 0; i < 3; i++)
            container.setItem(i, paperWithCustomData("spoiled", 64));

        long inserted = ContainerItemInsertion.insertWithdrawnItems(container, paperId, 10);
        TestResult r = assertEquals("nothing inserted into the full container", 0L, inserted);
        if (!r.passed()) return r;
        for (int i = 0; i < 3; i++) {
            ItemStack slot = container.getItem(i);
            r = assertEquals("slot " + i + " count untouched", 64, slot.getCount());
            if (!r.passed()) return r;
            r = assertTrue("slot " + i + " kept its gated component",
                    slot.has(DataComponents.CUSTOM_DATA));
            if (!r.passed()) return r;
        }
        return pass("full container: nothing merged, nothing voided, remainder reported");
    }

    /**
     * Same guarantee through the real bank terminal inventory (the container the user's
     * repro used): withdrawn items land in a separate slot, the spoiled stack is untouched.
     */
    private TestResult testTerminalInventoryKeepsMismatchedStack() {
        ItemID paperId = gateCustomDataAndGetPaperId();
        if (!paperId.isValid())
            return fail("Plain paper is not registered — default ItemIDs missing on this server?");

        // Detached block entity (no level): TerminalInventory only needs it for setChanged(),
        // which is a no-op without a level.
        BankTerminalBlockEntity blockEntity = new BankTerminalBlockEntity(
                BlockPos.ZERO, BankSystemBlocks.BANK_TERMINAL_BLOCK.get().defaultBlockState());
        BankTerminalBlockEntity.TerminalInventory inventory =
                new BankTerminalBlockEntity.TerminalInventory(blockEntity, 9);
        inventory.setStackInSlot(0, paperWithCustomData("spoiled", 10));

        long added = inventory.addItem(paperId, 10);
        TestResult r = assertEquals("all 10 items added", 10L, added);
        if (!r.passed()) return r;

        ItemStack slot0 = inventory.getItem(0);
        r = assertEquals("spoiled stack count untouched", 10, slot0.getCount());
        if (!r.passed()) return r;
        r = assertTrue("spoiled stack kept its gated component", slot0.has(DataComponents.CUSTOM_DATA));
        if (!r.passed()) return r;

        ItemStack slot1 = inventory.getItem(1);
        r = assertEquals("withdrawn items placed in a separate slot", 10, slot1.getCount());
        if (!r.passed()) return r;
        r = assertFalse("withdrawn items are clean", slot1.has(DataComponents.CUSTOM_DATA));
        if (!r.passed()) return r;
        return pass("terminal inventory withdrawal keeps component-mismatched stacks intact");
    }
}
