package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bank.IServerBank;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.IBankManager;
import net.kroia.banksystem.api.bankmanager.IServerBankManager;
import net.kroia.banksystem.banking.bankaccount.ServerBankAccount;
import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.data.filter.EqualityFilter;
import net.kroia.banksystem.data.table.BalanceHistoryManager;
import net.kroia.banksystem.data.table.record.BalanceHistoryRecord;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.banksystem.util.VolatileItemComponents;
import net.kroia.modutilities.event.DataEvent;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * In-game tests for the startup ItemID merge guard ({@code CONFIRM_ITEMID_MERGE}):
 * the dry-run collision detection ({@link ItemIDManager#detectCollapseCollisions}),
 * its healing-vs-collapse classification, the effective-set computation and the
 * merge report content.
 * <p>
 * The actual startup-abort path (throwing {@code ItemIDMergeAbortedException} from
 * world load) cannot be exercised without restarting the server, so it is verified
 * manually — these tests cover everything that can run against the live registry
 * of the server without mutating it (the detection is a pure dry run).
 * <p>
 * <b>Robustness rules</b> (the suite runs against the LIVE registry of a real server —
 * possibly an existing production world, singleplayer or master):
 * <ul>
 *   <li>The dry-run tests never touch the globally applied component set. They evaluate the
 *       hypothetical "grown" set through the explicit-set overload
 *       {@link ItemIDManager#detectCollapseCollisions(java.util.Collection, java.util.Collection)}.
 *       On a singleplayer server the applied set is shared static state between client and
 *       server; flipping it mid-session triggers real renormalize/merge passes from queued
 *       sync packets and corrupts the registry (this happened on a production world).</li>
 *   <li>{@code minecraft:repair_cost} serves as the stand-in for a newly-volatile component.
 *       If the server genuinely treats it as volatile already, the collapse scenario cannot
 *       be constructed and the affected tests skip themselves.</li>
 *   <li>The synthetic paper templates registered here (tagged with a random UUID in
 *       {@code custom_data}) remain in the ItemID table afterwards — harmless, tests are
 *       only registered when dev features are enabled.</li>
 * </ul>
 */
public class ItemIDMergeGuardTests extends TestSuite {

    /** Config-sourced volatile ids captured in setup() and restored in teardown(). */
    private List<String> savedConfigIds = null;

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.ITEM_ID;
    }

    @Override
    public void registerTests() {
        addTest("effective_set_contains_config_ids_and_is_sorted", this::testEffectiveSetContainsConfigIdsAndIsSorted);
        addTest("dry_run_clean_registry_has_no_collisions", this::testDryRunCleanRegistryHasNoCollisions);
        addTest("dry_run_detects_collapse_collision", this::testDryRunDetectsCollapseCollision);
        addTest("dry_run_does_not_mutate_registry", this::testDryRunDoesNotMutateRegistry);
        addTest("dry_run_classifies_healing_merge_as_safe", this::testDryRunClassifiesHealingMergeAsSafe);
        addTest("merge_report_lists_ids_and_components", this::testMergeReportListsIdsAndComponents);
        addTest("confirmed_merge_consolidates_bank_state", this::testConfirmedMergeConsolidatesBankState);
        addTest("confirmed_merge_purges_alias_balance_history", this::testConfirmedMergePurgesAliasBalanceHistory);
    }

    @Override
    public void setup() {
        savedConfigIds = VolatileItemComponents.getConfigComponentIdStrings();
    }

    @Override
    public void teardown() {
        // Safety net only — the only test that changes the config set restores it itself.
        if (savedConfigIds != null && VolatileItemComponents.setConfigComponentIds(savedConfigIds)) {
            ItemIDManager.renormalizeAndMerge();
        }
        savedConfigIds = null;
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /** Creates a paper stack carrying the given string inside minecraft:custom_data. */
    private static ItemStack paperWithCustomData(String value) {
        ItemStack stack = new ItemStack(Items.PAPER);
        CompoundTag nbt = new CompoundTag();
        nbt.putString("banksystem_merge_guard_test", value);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(nbt));
        return stack;
    }

    /**
     * Registers two synthetic paper templates (unique UUID marker) that differ ONLY in
     * {@code minecraft:repair_cost} — distinct IDs under the server's real set, colliding as
     * soon as repair_cost is treated as volatile. Registration happens under the REAL set, so
     * the broadcast sync packets carry the server's real component lists.
     *
     * @return the two registered IDs, index 0 = plain, index 1 = with repair_cost
     */
    private ItemID[] registerRepairCostPair() {
        String marker = UUID.randomUUID().toString();
        ItemStack a = paperWithCustomData(marker);
        ItemStack b = a.copy();
        b.set(DataComponents.REPAIR_COST, 7);
        ItemID idA = ItemIDManager.registerItemStackServerSide_direct(a);
        ItemID idB = ItemIDManager.registerItemStackServerSide_direct(b);
        return new ItemID[]{idA, idB};
    }

    /**
     * Returns a copy of the given set with {@code minecraft:repair_cost} added (sorted, like
     * {@link VolatileItemComponents#getEffectiveComponentIds()}), i.e. the hypothetical
     * "grown" effective set the dry-run tests evaluate WITHOUT applying it globally.
     */
    private static List<String> withRepairCost(List<String> base) {
        List<String> grown = new ArrayList<>(base);
        if (!grown.contains("minecraft:repair_cost"))
            grown.add("minecraft:repair_cost");
        grown.sort(String::compareTo);
        return grown;
    }

    /** Finds the collapse group containing both given IDs, or null. */
    private static ItemIDManager.MergeCollisionGroup findGroupWith(
            List<ItemIDManager.MergeCollisionGroup> groups, ItemID a, ItemID b) {
        for (ItemIDManager.MergeCollisionGroup group : groups) {
            List<ItemID> members = new ArrayList<>(group.mergedIds());
            members.add(group.canonicalId());
            if (members.contains(a) && members.contains(b))
                return group;
        }
        return null;
    }

    // ========================================================================
    // Tests
    // ========================================================================

    /**
     * The persisted/compared effective set must contain config-sourced ids and be sorted
     * (a stable representation is what makes the stored-vs-current comparison meaningful).
     * This is the only test that touches the global config set; it registers nothing while
     * the set is mutated and restores it in a finally block.
     */
    private TestResult testEffectiveSetContainsConfigIdsAndIsSorted() {
        List<String> before = VolatileItemComponents.getEffectiveComponentIds();
        List<String> effective;
        VolatileItemComponents.setConfigComponentIds(List.of("minecraft:repair_cost"));
        try {
            effective = VolatileItemComponents.getEffectiveComponentIds();
        } finally {
            VolatileItemComponents.setConfigComponentIds(savedConfigIds);
        }
        TestResult r = assertTrue("effective set contains the config-added id",
                effective.contains("minecraft:repair_cost"));
        if (!r.passed()) return r;
        List<String> sorted = new ArrayList<>(effective);
        sorted.sort(String::compareTo);
        r = assertEquals("effective set is sorted", sorted, effective);
        if (!r.passed()) return r;
        r = assertEquals("restored effective set equals the pre-test set",
                before, VolatileItemComponents.getEffectiveComponentIds());
        if (!r.passed()) return r;
        return pass("getEffectiveComponentIds() reflects config ids and is sorted");
    }

    /**
     * Acceptance criterion 3 (dry-run half): with an unchanged component set and a fully
     * normalized registry, the dry run must report no collapse collisions.
     */
    private TestResult testDryRunCleanRegistryHasNoCollisions() {
        // Ensure the registry is fully normalized under the current set.
        ItemIDManager.renormalizeAndMerge();
        List<ItemIDManager.MergeCollisionGroup> collisions =
                ItemIDManager.detectCollapseCollisions(VolatileItemComponents.getEffectiveComponentIds());
        return assertEquals("no collapse collisions on a normalized registry with an unchanged set",
                0, collisions.size());
    }

    /**
     * Acceptance criterion 1 (dry-run half): two IDs that are distinct under the previous
     * set but identical under the new (grown) set must be reported as ONE collapse group
     * with the lowest short as canonical. The grown set is evaluated as an explicit
     * hypothetical set — the globally applied set stays untouched.
     */
    private TestResult testDryRunDetectsCollapseCollision() {
        List<String> previousSet = VolatileItemComponents.getEffectiveComponentIds();
        if (previousSet.contains("minecraft:repair_cost"))
            return pass("skipped: minecraft:repair_cost is already volatile on this server — "
                    + "the collapse scenario cannot be constructed");
        ItemID[] pair = registerRepairCostPair();
        TestResult r = assertTrue("both synthetic templates registered",
                pair[0].isValid() && pair[1].isValid() && !pair[0].equals(pair[1]));
        if (!r.passed()) return r;

        // Grow the set: repair_cost becomes volatile — hypothetically, dry run only.
        List<ItemIDManager.MergeCollisionGroup> collisions =
                ItemIDManager.detectCollapseCollisions(previousSet, withRepairCost(previousSet));
        ItemIDManager.MergeCollisionGroup group = findGroupWith(collisions, pair[0], pair[1]);

        if (group == null)
            return fail("dry run did not report the collapse of the two repair_cost variants");
        ItemID expectedCanonical = pair[0].getShort() < pair[1].getShort() ? pair[0] : pair[1];
        ItemID expectedMerged = pair[0].getShort() < pair[1].getShort() ? pair[1] : pair[0];
        TestResult r2 = assertEquals("canonical is the lowest short of the group",
                expectedCanonical, group.canonicalId());
        if (!r2.passed()) return r2;
        r2 = assertTrue("the other ID is listed as merged", group.mergedIds().contains(expectedMerged));
        if (!r2.passed()) return r2;
        return pass("dry run detects the collapse group with correct canonical/merged split");
    }

    /** The dry run must be side-effect free: no merge applied, no aliases created. */
    private TestResult testDryRunDoesNotMutateRegistry() {
        List<String> previousSet = VolatileItemComponents.getEffectiveComponentIds();
        if (previousSet.contains("minecraft:repair_cost"))
            return pass("skipped: minecraft:repair_cost is already volatile on this server — "
                    + "the collapse scenario cannot be constructed");
        ItemID[] pair = registerRepairCostPair();
        ItemIDManager.detectCollapseCollisions(previousSet, withRepairCost(previousSet));

        TestResult r = assertTrue("both IDs still registered after the dry run",
                !ItemIDManager.getItemStack(pair[0]).isEmpty() && !ItemIDManager.getItemStack(pair[1]).isEmpty());
        if (!r.passed()) return r;
        r = assertEquals("no alias created for the would-be merged ID",
                pair[1], ItemIDManager.resolveAlias(pair[1]));
        if (!r.passed()) return r;
        r = assertTrue("the two IDs are still distinct entries in the registry",
                ItemIDManager.getItemIDMap().containsKey(pair[0]) && ItemIDManager.getItemIDMap().containsKey(pair[1]));
        if (!r.passed()) return r;
        return pass("detectCollapseCollisions() is a pure dry run");
    }

    /**
     * Acceptance criterion 4 (classification): a merge whose members were ALREADY identical
     * under the previous set (healing merge — e.g. pre-fix drifted duplicates) must NOT be
     * reported as a collapse, even while the set is different. Simulated by passing a
     * previous set that already contained the differing component.
     */
    private TestResult testDryRunClassifiesHealingMergeAsSafe() {
        List<String> realSet = VolatileItemComponents.getEffectiveComponentIds();
        if (realSet.contains("minecraft:repair_cost"))
            return pass("skipped: minecraft:repair_cost is already volatile on this server — "
                    + "the scenario cannot be constructed");
        // "Previously" repair_cost was already stripped; the current set still strips it —
        // members of the group are identical under BOTH sets → healing, not collapse.
        List<String> previousSet = withRepairCost(realSet);
        ItemID[] pair = registerRepairCostPair();

        List<ItemIDManager.MergeCollisionGroup> collisions =
                ItemIDManager.detectCollapseCollisions(previousSet, withRepairCost(realSet));
        ItemIDManager.MergeCollisionGroup group = findGroupWith(collisions, pair[0], pair[1]);

        if (group != null)
            return fail("healing merge (members identical under the previous set) was reported as a collapse");
        return pass("healing merges are classified as safe and stay automatic");
    }

    /** The admin-facing report must name the involved IDs, the item and the flag. */
    private TestResult testMergeReportListsIdsAndComponents() {
        List<String> previousSet = VolatileItemComponents.getEffectiveComponentIds();
        if (previousSet.contains("minecraft:repair_cost"))
            return pass("skipped: minecraft:repair_cost is already volatile on this server — "
                    + "the collapse scenario cannot be constructed");
        ItemID[] pair = registerRepairCostPair();
        List<String> currentSet = withRepairCost(previousSet);
        List<ItemIDManager.MergeCollisionGroup> collisions =
                ItemIDManager.detectCollapseCollisions(previousSet, currentSet);
        String report = ItemIDManager.buildMergeReport(collisions, previousSet, currentSet);

        ItemIDManager.MergeCollisionGroup group = findGroupWith(collisions, pair[0], pair[1]);
        if (group == null)
            return fail("expected collapse group missing — cannot validate the report");
        TestResult r = assertTrue("report names the canonical ID",
                report.contains("#" + group.canonicalId().getShort()));
        if (!r.passed()) return r;
        r = assertTrue("report names the merged ID",
                report.contains("#" + group.mergedIds().getFirst().getShort()));
        if (!r.passed()) return r;
        r = assertTrue("report names the item", report.contains("minecraft:paper"));
        if (!r.passed()) return r;
        r = assertTrue("report shows the newly stripped component", report.contains("minecraft:repair_cost"));
        if (!r.passed()) return r;
        r = assertTrue("report tells the admin about CONFIRM_ITEMID_MERGE",
                report.contains("CONFIRM_ITEMID_MERGE"));
        if (!r.passed()) return r;
        return pass("merge report contains IDs, item names, components and instructions");
    }

    /**
     * Task #10 regression test: an <b>applied</b> merge must consolidate per-ItemID bank
     * state under the canonical ID — free balance and locked balance become the sum over
     * the merge group, the allowed-item set is rewritten to the canonical ID, and the
     * {@code ITEM_IDS_MERGED} event fires with the alias→canonical map.
     * <p>
     * Unlike the dry-run tests above, this test intentionally APPLIES a real merge to the
     * live registry via the explicit-set overload of
     * {@link ItemIDManager#renormalizeAndMerge(java.util.Collection)} (same approach as
     * {@code ItemIDIdentityTests}). The synthetic paper IDs and the created alias remain in
     * the registry afterwards — harmless residue, consistent with the suite's other tests;
     * the suite is only registered when dev features are enabled. The test bank account and
     * the allowed-set entries are cleaned up in a finally block.
     */
    private TestResult testConfirmedMergeConsolidatesBankState() {
        List<String> realSet = VolatileItemComponents.getEffectiveComponentIds();
        if (realSet.contains("minecraft:repair_cost"))
            return pass("skipped: minecraft:repair_cost is already volatile on this server — "
                    + "the collapse scenario cannot be constructed");

        IBankManager bankManagerApi = BankSystemMod.getAPI().getServerBankManager();
        IServerBankManager manager = bankManagerApi != null ? bankManagerApi.getSync() : null;
        if (manager == null)
            return fail("ServerBankManager is null -- cannot run on slave server");

        ItemID[] pair = registerRepairCostPair();
        if (!pair[0].isValid() || !pair[1].isValid() || pair[0].equals(pair[1]))
            return fail("failed to register the two synthetic templates as distinct IDs");
        // renormalizeAndMerge keeps the lowest short as canonical.
        ItemID canonical = pair[0].getShort() < pair[1].getShort() ? pair[0] : pair[1];
        ItemID alias = canonical.equals(pair[0]) ? pair[1] : pair[0];

        int accountNr = ServerBankAccount.INVALID_ACCOUNT_NUMBER;
        AtomicReference<Map<ItemID, ItemID>> firedMap = new AtomicReference<>();
        Consumer<Map<ItemID, ItemID>> listener = firedMap::set;
        DataEvent<Map<ItemID, ItemID>> mergedEvent = BankSystemMod.getAPI().getEvents().getItemIDsMergedEvent();
        try {
            if (!manager.allowItemID(pair[0]) || !manager.allowItemID(pair[1]))
                return fail("could not allow the synthetic test items for banking");

            IServerBankAccount account = manager.createBankAccount("ItemIDMergeConsolidationTestAccount");
            if (account == null)
                return fail("failed to create the test bank account");
            accountNr = account.getAccountNumber();

            // Canonical-to-be: 1000 free. Alias-to-be: 500, of which 200 get locked.
            IServerBank canonicalBank = account.createBank(canonical, 1000);
            IServerBank aliasBank = account.createBank(alias, 500);
            if (canonicalBank == null || aliasBank == null)
                return fail("failed to create the test banks");
            if (aliasBank.lockAmount(200) != BankStatus.SUCCESS)
                return fail("failed to lock part of the alias bank's balance");

            mergedEvent.addListener(listener);

            // APPLY the merge under the explicitly grown set (repair_cost volatile):
            // the two paper variants collapse; the alias bank must be consolidated.
            ItemIDManager.renormalizeAndMerge(withRepairCost(realSet));

            TestResult r = assertEquals("alias resolves to the canonical ID after the merge",
                    canonical, ItemIDManager.resolveAlias(alias));
            if (!r.passed()) return r;

            // Exactly one bank, keyed by the canonical ID (raw map check, no alias resolution).
            r = assertFalse("no bank is left under the merged alias ID",
                    account.getAllBanks().containsKey(alias));
            if (!r.passed()) return r;
            IServerBank merged = account.getBank(canonical);
            if (merged == null)
                return fail("no bank exists under the canonical ID after the merge");
            r = assertEquals("free balance is the sum of both banks", 1000L + 300L, merged.getBalance());
            if (!r.passed()) return r;
            r = assertEquals("locked balance is preserved through the merge", 200L, merged.getLockedBalance());
            if (!r.passed()) return r;

            // Allowed set: canonical stays, the alias entry is gone (raw list check).
            List<ItemID> allowed = manager.getAllowedItems();
            r = assertTrue("allowed set contains the canonical ID", allowed.contains(canonical));
            if (!r.passed()) return r;
            r = assertFalse("allowed set no longer contains the alias ID", allowed.contains(alias));
            if (!r.passed()) return r;

            // Post-merge event: fired after consolidation, with the correct pair.
            Map<ItemID, ItemID> fired = firedMap.get();
            r = assertNotNull("ITEM_IDS_MERGED event fired", fired);
            if (!r.passed()) return r;
            r = assertEquals("event maps the alias to its canonical ID", canonical, fired.get(alias));
            if (!r.passed()) return r;

            return pass("applied merge consolidates balances, locked balances and the allowed set, "
                    + "and fires ITEM_IDS_MERGED");
        } finally {
            mergedEvent.removeListener(listener);
            if (accountNr != ServerBankAccount.INVALID_ACCOUNT_NUMBER)
                manager.deleteBankAccount(accountNr);
            // Remove the synthetic item from the allowed set again (also removes any
            // leftover banks referencing it on other accounts — there are none).
            manager.disallowItemID(canonical);
        }
    }

    /**
     * Task #12 regression test: an <b>applied</b> merge must purge the balance-history rows
     * keyed to the alias short. Canonical-keyed rows must stay intact (canonical history
     * continues fresh from the merge point; the alias identity is discarded, so its old chart
     * data would refer to an ID that no longer resolves to a live item).
     * <p>
     * Follows the same production-safe explicit-set merge pattern as
     * {@link #testConfirmedMergeConsolidatesBankState()} — the globally applied component set is
     * never mutated.
     * <p>
     * The DB executor is a single-threaded {@link java.util.concurrent.ExecutorService}, so
     * awaiting a fresh no-op runnable submitted after the merge flushes every async DB
     * operation queued by {@code consolidatePendingMerges()} (including our fire-and-forget
     * {@link BalanceHistoryManager#deleteAllRowsForItemIDs deleteAllRowsForItemIDs}).
     */
    private TestResult testConfirmedMergePurgesAliasBalanceHistory() {
        List<String> realSet = VolatileItemComponents.getEffectiveComponentIds();
        if (realSet.contains("minecraft:repair_cost"))
            return pass("skipped: minecraft:repair_cost is already volatile on this server — "
                    + "the collapse scenario cannot be constructed");

        BalanceHistoryManager historyManager = BankSystemModBackend.getBalanceHistoryManager();
        DatabaseManager dbManager = BankSystemModBackend.getDatabaseManager();
        if (historyManager == null || dbManager == null)
            return pass("skipped: no balance-history DB present (slave server or DB not initialized)");

        ItemID[] pair = registerRepairCostPair();
        if (!pair[0].isValid() || !pair[1].isValid() || pair[0].equals(pair[1]))
            return fail("failed to register the two synthetic templates as distinct IDs");
        // Same canonical-choice rule as renormalizeAndMerge: lowest short wins.
        ItemID canonical = pair[0].getShort() < pair[1].getShort() ? pair[0] : pair[1];
        ItemID alias = canonical.equals(pair[0]) ? pair[1] : pair[0];
        // Use a synthetic account number well outside the id range typical worlds allocate,
        // so the test rows are trivially identifiable and cleanup by (account, item) is safe.
        final int syntheticAccount = 987654;
        final long now = System.currentTimeMillis();

        try {
            // Populate history rows for both IDs directly (bypasses the tick-driven snapshotter,
            // which requires a live bank account and would introduce a lot of noise). The point
            // is only to prove that alias rows are purged and canonical rows are not.
            List<BalanceHistoryRecord> seed = List.of(
                    new BalanceHistoryRecord(syntheticAccount, canonical.getShort(), 100L, 0L, now),
                    new BalanceHistoryRecord(syntheticAccount, canonical.getShort(), 150L, 0L, now + 1000L),
                    new BalanceHistoryRecord(syntheticAccount, alias.getShort(), 200L, 0L, now),
                    new BalanceHistoryRecord(syntheticAccount, alias.getShort(), 250L, 0L, now + 1000L)
            );
            historyManager.save(seed).get(10, TimeUnit.SECONDS);

            int canonicalBefore = historyManager.getRecordCount(
                    Optional.empty(), Optional.empty(),
                    Optional.of(new EqualityFilter(canonical.getShort()))
            ).get(10, TimeUnit.SECONDS);
            int aliasBefore = historyManager.getRecordCount(
                    Optional.empty(), Optional.empty(),
                    Optional.of(new EqualityFilter(alias.getShort()))
            ).get(10, TimeUnit.SECONDS);
            TestResult r = assertTrue("seeded canonical rows exist", canonicalBefore >= 2);
            if (!r.passed()) return r;
            r = assertTrue("seeded alias rows exist", aliasBefore >= 2);
            if (!r.passed()) return r;

            // APPLY the merge under the explicitly grown set — same production-safe pattern as
            // the bank-consolidation test above. The delete of alias-keyed history rows is
            // queued on the DB executor from ItemIDManager#consolidatePendingMerges().
            ItemIDManager.renormalizeAndMerge(withRepairCost(realSet));

            // Flush the DB executor: submitting an empty runnable and awaiting it guarantees
            // every earlier async DB op (including the queued delete) has completed.
            dbManager.getDatabaseThread().submit(() -> {}).get(10, TimeUnit.SECONDS);

            int aliasAfter = historyManager.getRecordCount(
                    Optional.empty(), Optional.empty(),
                    Optional.of(new EqualityFilter(alias.getShort()))
            ).get(10, TimeUnit.SECONDS);
            r = assertEquals("alias-keyed history rows are purged after merge", 0, aliasAfter);
            if (!r.passed()) return r;

            int canonicalAfter = historyManager.getRecordCount(
                    Optional.empty(), Optional.empty(),
                    Optional.of(new EqualityFilter(canonical.getShort()))
            ).get(10, TimeUnit.SECONDS);
            r = assertEquals("canonical-keyed history rows are untouched by the merge",
                    canonicalBefore, canonicalAfter);
            if (!r.passed()) return r;

            return pass("applied merge purges alias balance-history rows and preserves canonical rows");
        } catch (Exception e) {
            return fail("balance-history purge test threw: " + e.getMessage());
        } finally {
            // Cleanup: remove any residual history rows for the synthetic account (belt-and-
            // suspenders — the merge path should already have cleaned alias-keyed rows, but
            // the seeded canonical rows are ours to remove). Fire-and-forget: an executor
            // shutdown between here and the next test would flush anyway.
            try {
                historyManager.removeHistory(
                        Optional.empty(),
                        Optional.of(new EqualityFilter(syntheticAccount)),
                        Optional.empty()
                ).get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        }
    }
}
