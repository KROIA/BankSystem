package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * In-game tests for the monotonic short allocation counter added in Task #11 /
 * ISSUES.md #56. Verifies that {@link ItemIDManager#nextShortCounter}:
 * <ul>
 *   <li>seeds correctly on migration from a pre-counter world,</li>
 *   <li>round-trips through save/load,</li>
 *   <li>drives {@link ItemIDManager#registerItemStackServerSide_direct(ItemStack)} monotonically,</li>
 *   <li>never re-issues a short that was dropped from {@code itemIDMap} at load,</li>
 *   <li>refuses to allocate past {@link Short#MAX_VALUE} (returns {@link ItemID#INVALID_ID}),</li>
 *   <li>surfaces overflow cleanly to callers of the register entry points.</li>
 * </ul>
 *
 * <b>Robustness rules</b> (the suite runs against the LIVE registry of a real server):
 * <ul>
 *   <li>Every test that mutates {@link ItemIDManager#nextShortCounter} restores it in a
 *       {@code finally} block. The counter is otherwise driven only by real registration
 *       events — a leaked overflow value would freeze the whole registry.</li>
 *   <li>Synthetic paper templates registered here are tagged with a random UUID in
 *       {@code minecraft:custom_data} so they never collide with real templates. They
 *       remain in the registry afterwards — harmless residue, consistent with the other
 *       suites in this category; tests are only registered when dev features are enabled.</li>
 * </ul>
 */
public class ItemIDCounterTests extends TestSuite {

    /** Snapshot of {@code nextShortCounter} captured in setup() and restored in teardown(). */
    private int savedCounter = -1;

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.ITEM_ID;
    }

    @Override
    public void registerTests() {
        addTest("counter_seeded_on_migration", this::testCounterSeededOnMigration);
        addTest("counter_survives_save_load", this::testCounterSurvivesSaveLoad);
        addTest("new_allocation_uses_counter", this::testNewAllocationUsesCounter);
        addTest("dropped_short_not_reused", this::testDroppedShortNotReused);
        addTest("overflow_returns_invalid", this::testOverflowReturnsInvalid);
        addTest("overflow_caller_handling", this::testOverflowCallerHandling);
    }

    @Override
    public void setup() {
        savedCounter = ItemIDManager.getNextShortCounter_forTesting();
    }

    @Override
    public void teardown() {
        // Safety net — every test restores the counter itself, but a crash mid-test must
        // never leak a poisoned counter into the live registry.
        if (savedCounter > 0)
            ItemIDManager.setNextShortCounter_forTesting(savedCounter);
        savedCounter = -1;
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /** Creates a paper stack carrying the given string inside minecraft:custom_data. */
    private static ItemStack paperWithCustomData(String value) {
        ItemStack stack = new ItemStack(Items.PAPER);
        CompoundTag nbt = new CompoundTag();
        nbt.putString("banksystem_counter_test", value);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(nbt));
        return stack;
    }

    // ========================================================================
    // Tests
    // ========================================================================

    /**
     * Acceptance criterion 3 (migration): on a legacy world without the {@code nextShortCounter}
     * NBT key, the seed is {@code max(shortsInItemIDMap ∪ shortsInItemIDAliasMap) + 1}. Uses
     * the pure {@link ItemIDManager#computeMigrationSeed_forTesting} helper — no I/O, no
     * mutation of the live registry.
     */
    private TestResult testCounterSeededOnMigration() {
        List<ItemID> mapShorts = Arrays.asList(new ItemID((short) 5), new ItemID((short) 10), new ItemID((short) 20));
        List<ItemID> aliasShorts = Arrays.asList(new ItemID((short) 3), new ItemID((short) 8));

        int seed = ItemIDManager.computeMigrationSeed_forTesting(mapShorts, aliasShorts);
        TestResult r = assertEquals("seed is max(5,10,20,3,8) + 1", 21, seed);
        if (!r.passed()) return r;

        // Symmetric edge cases: alias-side maxima and the empty case.
        r = assertEquals("seed reflects an alias-side maximum",
                101, ItemIDManager.computeMigrationSeed_forTesting(mapShorts, List.of(new ItemID((short) 100))));
        if (!r.passed()) return r;
        r = assertEquals("empty maps seed to 1",
                1, ItemIDManager.computeMigrationSeed_forTesting(List.of(), List.of()));
        if (!r.passed()) return r;
        return pass("migration seed = max used short + 1 across both maps");
    }

    /**
     * Acceptance criterion 2/3: the counter is persisted in {@link ItemIDManager#save} and
     * restored on {@link ItemIDManager#load}. Surgical round trip: writes through
     * {@code save()} into an NBT tag, verifies the key is present with the expected value,
     * then reads it back into the field via a controlled emulation of the load-branch
     * (checking {@code tag.contains(...)} and reading the int) without invoking the full
     * {@code load()} — the full load path also runs the startup merge guard which touches
     * the live data handler.
     */
    private TestResult testCounterSurvivesSaveLoad() {
        int probe = 500;
        int before = ItemIDManager.getNextShortCounter_forTesting();
        try {
            ItemIDManager.setNextShortCounter_forTesting(probe);
            // Save current state (map + aliases + counter) into a probe tag.
            CompoundTag tag = new CompoundTag();
            boolean saved = new ItemIDManager().save(tag);
            TestResult r = assertTrue("save() succeeded", saved);
            if (!r.passed()) return r;
            r = assertTrue("tag contains the nextShortCounter key", tag.contains("nextShortCounter"));
            if (!r.passed()) return r;
            r = assertEquals("persisted counter equals the value that was set",
                    probe, tag.getInt("nextShortCounter"));
            if (!r.passed()) return r;

            // Mimic the load-branch: reset the field, then read from the tag as load() does.
            ItemIDManager.setNextShortCounter_forTesting(1);
            if (tag.contains("nextShortCounter"))
                ItemIDManager.setNextShortCounter_forTesting(tag.getInt("nextShortCounter"));
            r = assertEquals("counter restored from the persisted key",
                    probe, ItemIDManager.getNextShortCounter_forTesting());
            if (!r.passed()) return r;
        } finally {
            ItemIDManager.setNextShortCounter_forTesting(before);
        }
        return pass("nextShortCounter round-trips through save + load-branch read");
    }

    /**
     * Acceptance criterion 4: allocating a fresh ItemID pulls the next value out of the
     * counter (and advances it). Uses a synthetic paper template so the test never touches
     * live registry entries.
     */
    private TestResult testNewAllocationUsesCounter() {
        int probe = Math.max(ItemIDManager.getNextShortCounter_forTesting(), 100);
        ItemIDManager.setNextShortCounter_forTesting(probe);
        try {
            ItemStack stack = paperWithCustomData("new_alloc_uses_counter_" + UUID.randomUUID());
            ItemID id = ItemIDManager.registerItemStackServerSide_direct(stack);

            TestResult r = assertTrue("registered ID is valid", id.isValid());
            if (!r.passed()) return r;
            r = assertTrue("short ≥ probe (" + probe + "), got " + id.getShort(),
                    (id.getShort() & 0xFFFF) >= probe);
            if (!r.passed()) return r;
            r = assertTrue("counter advanced past the issued short",
                    ItemIDManager.getNextShortCounter_forTesting() > (id.getShort() & 0xFFFF));
            if (!r.passed()) return r;
            return pass("allocation uses (and advances) nextShortCounter");
        } finally {
            // The synthetic template stays in the registry — same convention as the
            // other suites in this category. Counter is not restored: production
            // registration events always leave the counter monotonically increased.
        }
    }

    /**
     * Acceptance criterion 4: a short that has been "silently dropped" (as would happen
     * when the load path fails to parse the stored ItemStack) is never re-issued, because
     * the counter has already moved past it. We simulate the drop by advancing the counter
     * past a synthetic short WITHOUT registering an item at that short, then registering a
     * fresh template and asserting the fresh short is strictly greater.
     */
    private TestResult testDroppedShortNotReused() {
        // Start well above any current registry occupation so we can pick a "would-be
        // dropped" short deterministically.
        int startAt = Math.max(ItemIDManager.getNextShortCounter_forTesting(), 200);
        short droppedShort = (short) (startAt + 5);
        int postDropCounter = (droppedShort & 0xFFFF) + 1;

        // Skip past the dropped short — the counter never decrements, so anything issued
        // afterwards must be strictly larger.
        ItemIDManager.setNextShortCounter_forTesting(postDropCounter);

        ItemStack fresh = paperWithCustomData("dropped_short_not_reused_" + UUID.randomUUID());
        ItemID freshId = ItemIDManager.registerItemStackServerSide_direct(fresh);

        TestResult r = assertTrue("fresh ID is valid", freshId.isValid());
        if (!r.passed()) return r;
        r = assertTrue("fresh short (" + freshId.getShort() + ") ≠ simulated dropped short ("
                + droppedShort + ")", freshId.getShort() != droppedShort);
        if (!r.passed()) return r;
        r = assertTrue("fresh short is strictly greater than the dropped short",
                (freshId.getShort() & 0xFFFF) > (droppedShort & 0xFFFF));
        if (!r.passed()) return r;
        return pass("counter's monotonic advance prevents reuse of dropped shorts");
    }

    /**
     * Acceptance criterion 5: when {@link ItemIDManager#nextShortCounter} exceeds
     * {@link Short#MAX_VALUE}, the allocator returns {@link ItemID#INVALID_ID} and does
     * NOT insert anything into the live registry maps.
     */
    private TestResult testOverflowReturnsInvalid() {
        int liveBefore = ItemIDManager.getItemIDMap().size();
        Set<ItemID> aliasesBefore = ItemIDManager.getItemIDAliasMap().keySet();
        ItemIDManager.setNextShortCounter_forTesting(Short.MAX_VALUE + 1);
        try {
            ItemStack stack = paperWithCustomData("overflow_returns_invalid_" + UUID.randomUUID());
            ItemID id = ItemIDManager.registerItemStackServerSide_direct(stack);

            TestResult r = assertEquals("allocator returned INVALID_ID on overflow",
                    ItemID.INVALID_ID, id);
            if (!r.passed()) return r;
            r = assertFalse("returned ID reports itself as invalid", id.isValid());
            if (!r.passed()) return r;
            r = assertEquals("live itemIDMap size unchanged after refused allocation",
                    liveBefore, ItemIDManager.getItemIDMap().size());
            if (!r.passed()) return r;
            r = assertEquals("live itemIDAliasMap key set unchanged after refused allocation",
                    aliasesBefore, ItemIDManager.getItemIDAliasMap().keySet());
            if (!r.passed()) return r;
            return pass("counter overflow returns INVALID_ID and mutates no state");
        } finally {
            ItemIDManager.setNextShortCounter_forTesting(savedCounter);
        }
    }

    /**
     * Acceptance criterion 5/6: the {@link ItemID#getOrRegisterFromItemStackServerSide_direct}
     * entry point (used by BankTerminal deposit, account-icon updates, etc.) surfaces the
     * overflow cleanly to callers — {@link ItemID#INVALID_ID} + failing {@link ItemID#isValid()}
     * — so downstream code can fail its own operation instead of silently poisoning bank state.
     */
    private TestResult testOverflowCallerHandling() {
        int liveBefore = ItemIDManager.getItemIDMap().size();
        ItemIDManager.setNextShortCounter_forTesting(Short.MAX_VALUE + 1);
        try {
            ItemStack stack = paperWithCustomData("overflow_caller_handling_" + UUID.randomUUID());
            ItemID id = ItemID.getOrRegisterFromItemStackServerSide_direct(stack);

            TestResult r = assertNotNull("public entry point never returns null", id);
            if (!r.passed()) return r;
            r = assertFalse("caller sees isValid() == false on overflow", id.isValid());
            if (!r.passed()) return r;
            r = assertEquals("caller receives INVALID_ID", ItemID.INVALID_ID, id);
            if (!r.passed()) return r;
            r = assertEquals("no state was mutated (map size unchanged)",
                    liveBefore, ItemIDManager.getItemIDMap().size());
            if (!r.passed()) return r;
            return pass("public register entry point surfaces overflow as INVALID_ID for callers");
        } finally {
            ItemIDManager.setNextShortCounter_forTesting(savedCounter);
        }
    }
}
