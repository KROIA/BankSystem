package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.banksystem.util.VolatileItemComponents;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.util.List;

/**
 * In-game tests for the deposit-side equivalence gate
 * ({@link VolatileItemComponents#isDepositEquivalent}).
 * <p>
 * The gate blocks the state-laundering exploit that volatile-component normalization opened:
 * because ItemID identity ignores gated components (e.g. {@code tfc:food}), a spoiled item
 * resolves to the same ItemID as a fresh one — without the gate it could be deposited and
 * withdrawn fresh. These tests use {@code minecraft:custom_data} / {@code minecraft:repair_cost}
 * as stand-ins for third-party gated/volatile components by temporarily adding them to the
 * config-sourced sets. Both original config sets are restored in {@code teardown()}.
 * <p>
 * Note on the "reject" expectation: registry templates are normalized (gated components
 * stripped) and vanilla components have no constructor hook that re-stamps fresh state onto
 * copies, so a reconstruction never carries the synthetic gated component. Any incoming stack
 * that does carry it is therefore not equivalent — exactly the mismatch case the gate must
 * reject. The "accept" case for mods like TFC (fresh food equals a fresh reconstruction under
 * the mod's own {@code isSameItemSameComponents} semantics) requires the mod's mixin and is
 * covered by manual TFC verification.
 */
public class DepositGateTests extends TestSuite {

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
        addTest("gate_accepts_when_gated_set_empty", this::testAcceptsWhenGatedSetEmpty);
        addTest("gate_accepts_items_without_gated_components", this::testAcceptsItemsWithoutGatedComponents);
        addTest("gate_rejects_mismatched_gated_component", this::testRejectsMismatchedGatedComponent);
        addTest("gate_ignores_nongated_volatile_differences", this::testIgnoresNonGatedVolatileDifferences);
        addTest("gated_component_ignored_for_identity", this::testGatedComponentIgnoredForIdentity);
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
    private static ItemStack paperWithCustomData(String value) {
        ItemStack stack = new ItemStack(Items.PAPER);
        CompoundTag nbt = new CompoundTag();
        nbt.putString("banksystem_deposit_gate_test", value);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(nbt));
        return stack;
    }

    // ========================================================================
    // Tests
    // ========================================================================

    /**
     * Acceptance criterion 2: with an empty gated set the gate is disabled — even a stack
     * carrying a (merely volatile) state component is accepted unchanged.
     */
    private TestResult testAcceptsWhenGatedSetEmpty() {
        VolatileItemComponents.setConfigComponentIds(List.of("minecraft:custom_data"));
        VolatileItemComponents.setGatedConfigComponentIds(List.of());
        ItemStack stack = paperWithCustomData("volatile-but-not-gated");
        TestResult r = assertTrue("stack accepted when no components are gated",
                VolatileItemComponents.isDepositEquivalent(stack));
        if (!r.passed()) return r;
        return pass("empty gated set leaves deposits unaffected");
    }

    /**
     * Acceptance criterion 2: items that carry none of the gated component types are
     * completely unaffected (short-circuit accept).
     */
    private TestResult testAcceptsItemsWithoutGatedComponents() {
        VolatileItemComponents.setConfigComponentIds(List.of());
        VolatileItemComponents.setGatedConfigComponentIds(List.of("minecraft:custom_data"));
        ItemStack plain = new ItemStack(Items.PAPER);
        TestResult r = assertTrue("plain paper (no gated component) accepted",
                VolatileItemComponents.isDepositEquivalent(plain));
        if (!r.passed()) return r;
        ItemStack iron = new ItemStack(Items.IRON_INGOT, 12);
        r = assertTrue("iron ingots (no gated component) accepted",
                VolatileItemComponents.isDepositEquivalent(iron));
        if (!r.passed()) return r;
        return pass("items without gated components pass the gate untouched");
    }

    /**
     * Acceptance criterion 1: a stack whose gated component does not match the
     * withdrawal-equivalent reconstruction must be rejected (this is the laundering case:
     * the registry template — and thus everything the bank hands back — has no such state).
     */
    private TestResult testRejectsMismatchedGatedComponent() {
        VolatileItemComponents.setConfigComponentIds(List.of());
        VolatileItemComponents.setGatedConfigComponentIds(List.of("minecraft:custom_data"));
        ItemStack tainted = paperWithCustomData("gated-mismatch");
        // Identity ignores the gated component, so the stack resolves to the plain paper ID...
        ItemID id = ItemIDManager.getItemID(tainted);
        if (!id.isValid())
            return fail("Plain paper is not registered — default ItemIDs missing on this server?");
        // ...but the gate must refuse it: the reconstruction the bank would hand back is plain.
        TestResult r = assertFalse("stack with mismatched gated component is rejected",
                VolatileItemComponents.isDepositEquivalent(tainted, id));
        if (!r.passed()) return r;
        return pass("mismatched gated state cannot be deposited (no laundering)");
    }

    /**
     * The gate must strip non-gated volatile components from both comparison sides:
     * a difference in a merely volatile component (e.g. tfc:heat) must not cause a false
     * rejection, while a difference in the gated component still must.
     */
    private TestResult testIgnoresNonGatedVolatileDifferences() {
        VolatileItemComponents.setConfigComponentIds(List.of("minecraft:repair_cost"));
        VolatileItemComponents.setGatedConfigComponentIds(List.of("minecraft:custom_data"));

        // Volatile-only difference: accepted (repair_cost is stripped on both sides).
        ItemStack volatileOnly = new ItemStack(Items.PAPER);
        volatileOnly.set(DataComponents.REPAIR_COST, 5);
        TestResult r = assertTrue("volatile-only difference does not trip the gate",
                VolatileItemComponents.isDepositEquivalent(volatileOnly));
        if (!r.passed()) return r;

        // Same stack plus a mismatched gated component: rejected (gated state is kept).
        ItemStack gatedAndVolatile = paperWithCustomData("gated-with-volatile");
        gatedAndVolatile.set(DataComponents.REPAIR_COST, 5);
        r = assertFalse("gated mismatch still rejected when volatile noise is present",
                VolatileItemComponents.isDepositEquivalent(gatedAndVolatile));
        if (!r.passed()) return r;
        return pass("gate keeps gated components and ignores non-gated volatile ones");
    }

    /**
     * Gated components must be invisible to ItemID identity (like volatile ones) — a spoiled
     * and a fresh item must share one ItemID; only the deposit gate tells them apart.
     */
    private TestResult testGatedComponentIgnoredForIdentity() {
        VolatileItemComponents.setConfigComponentIds(List.of());
        VolatileItemComponents.setGatedConfigComponentIds(List.of("minecraft:custom_data"));
        ItemID plainId = ItemIDManager.getItemID(new ItemStack(Items.PAPER));
        if (!plainId.isValid())
            return fail("Plain paper is not registered — default ItemIDs missing on this server?");
        ItemID gatedId = ItemIDManager.getItemID(paperWithCustomData("identity-check"));
        return assertEquals("stack with gated component resolves to the plain ItemID",
                plainId, gatedId);
    }
}
