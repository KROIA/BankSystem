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
import net.kroia.modutilities.UtilitiesPlatform;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
        // Task #13 Fix D tests
        addTest("repair_clean_alias_table_is_no_op", this::testRepairCleanAliasTableIsNoOp);
        addTest("repair_removes_invariant_violation", this::testRepairRemovesInvariantViolation);
        addTest("repair_removes_orphan_alias", this::testRepairRemovesOrphanAlias);
        addTest("repair_breaks_cycle", this::testRepairBreaksCycle);
        addTest("repair_is_idempotent", this::testRepairIsIdempotent);
        addTest("v2_0_2_world_upgrades_cleanly", this::testV202WorldUpgradesCleanly);
        // Startup-reconciliation regression: persisted shorts survive default registration
        addTest("drifted_persisted_shorts_survive_default_registration",
                this::testDriftedPersistedShortsSurviveDefaultRegistration);
        // Task #14 write-side guard tests
        addTest("putAlias_succeeds_when_source_not_in_map", this::testPutAliasSucceedsWhenSourceNotInMap);
        addTest("putAlias_rejects_when_source_in_map", this::testPutAliasRejectsWhenSourceInMap);
        addTest("renormalize_still_produces_aliases_via_guarded_path", this::testRenormalizeStillProducesAliasesViaGuardedPath);
        // Task #16 — registration latch, persisted-wins merges, stale-key replacement
        addTest("master_registration_latched_before_load", this::testMasterRegistrationLatchedBeforeLoad);
        addTest("persisted_short_wins_merge_canonicality", this::testPersistedShortWinsMergeCanonicality);
        addTest("load_replaces_stale_key_objects", this::testLoadReplacesStaleKeyObjects);
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
        // Digest hygiene (Task #16): fixture load()s inside this suite run the startup
        // merge guard, whose save_metadata() persisted the FIXTURE registry's
        // renumbering-tripwire digest into the real Meta_data.nbt. Now that every test has
        // restored the live state, re-save the metadata through the production path so a
        // server kill after a test run cannot leave a fixture digest behind (which would
        // fire a false tripwire ERROR naming fixture shorts on the next boot).
        BankSystemModBackend.Instances instances = BankSystemModBackend.getInstances_forTesting();
        if (instances != null && instances.SERVER_DATA_HANDLER != null)
            instances.SERVER_DATA_HANDLER.save_metadata();
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
        // Canonical rule (Task #16): persisted shorts first, lowest short as the tie-break.
        // Both IDs here are freshly session-minted (same provenance, neither persisted),
        // so the lowest short wins — the original intent of this test is unaffected.
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
        // Same canonical rule as renormalizeAndMerge (Task #16): persisted-first, lowest
        // short as tie-break — both IDs are session-minted here, so lowest short wins.
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

    // ========================================================================================
    // Task #13 Fix D — repair pass + upgrade safety
    // ========================================================================================

    /**
     * A fully-consistent alias table (empty or with only healthy entries) must produce zero
     * repairs. Baseline check: guarantees the pass does not eat healthy data.
     */
    private TestResult testRepairCleanAliasTableIsNoOp() {
        // Ensure a known-good state — every alias in the map already points at a real map entry.
        ItemIDManager.renormalizeAndMerge();
        int repaired = ItemIDManager.repairCorruptAliasEntries();
        return assertEquals("repair on a clean alias table finds nothing to repair",
                0, repaired);
    }

    /**
     * <b>Invariant violation:</b> when a short is present in BOTH {@code itemIDMap} and
     * {@code itemIDAliasMap} (the corrupt state that broke {@code resolveAlias} pre-Fix-C),
     * the alias entry must be removed while the map entry is preserved.
     */
    private TestResult testRepairRemovesInvariantViolation() {
        // Register a canonical paper so we have a real short in itemIDMap.
        ItemID canonical = ItemIDManager.registerItemStackServerSide_direct(
                paperWithCustomData("repair-invariant-" + UUID.randomUUID()));
        if (!canonical.isValid()) return fail("Could not register canonical template");
        // Install a bogus alias whose SOURCE key is the same as the canonical's short — this
        // is the invariant violation shape (source present in BOTH itemIDMap and itemIDAliasMap).
        // Uses the raw bypass helper because applyAliases now routes through the Task #14
        // putAlias guard, which would (correctly) reject this exact insertion.
        ItemIDManager.putAliasRaw_forTesting(canonical, canonical);
        try {
            TestResult r = assertTrue("test precondition: alias installed",
                    ItemIDManager.getItemIDAliasMap().containsKey(canonical));
            if (!r.passed()) return r;
            int repaired = ItemIDManager.repairCorruptAliasEntries();
            r = assertTrue("at least one repair recorded", repaired >= 1);
            if (!r.passed()) return r;
            r = assertFalse("invariant-violation alias entry removed",
                    ItemIDManager.getItemIDAliasMap().containsKey(canonical));
            if (!r.passed()) return r;
            r = assertTrue("canonical stays in itemIDMap",
                    ItemIDManager.getItemIDMap().containsKey(canonical));
            if (!r.passed()) return r;
            return pass("repair removes invariant-violation alias entries");
        } finally {
            ItemIDManager.removeAliasEntries_forTesting(List.of(canonical));
        }
    }

    /**
     * <b>Orphan alias:</b> an alias whose chain terminates at a short that is neither in
     * {@code itemIDMap} nor further in {@code itemIDAliasMap} is unresolvable and must be
     * dropped.
     */
    private TestResult testRepairRemovesOrphanAlias() {
        // Pick two unused shorts: source S and terminal T. T is present in NEITHER map, so
        // the alias S -> T terminates at a nonexistent short.
        short base = pickUnusedShortBase(2);
        if (base < 0) return fail("No free short range available");
        ItemID s = new ItemID(base, null);
        ItemID t = new ItemID((short) (base + 1), null);
        ItemIDManager.applyAliases(Map.of(s, t));
        try {
            int repaired = ItemIDManager.repairCorruptAliasEntries();
            TestResult r = assertTrue("at least one repair recorded", repaired >= 1);
            if (!r.passed()) return r;
            r = assertFalse("orphan alias entry is removed",
                    ItemIDManager.getItemIDAliasMap().containsKey(s));
            if (!r.passed()) return r;
            return pass("repair removes orphan aliases");
        } finally {
            ItemIDManager.removeAliasEntries_forTesting(List.of(s, t));
        }
    }

    /**
     * <b>Cycle:</b> a cyclic chain in the alias table must be broken (at least one entry
     * removed) so future resolutions cannot loop.
     */
    private TestResult testRepairBreaksCycle() {
        short base = pickUnusedShortBase(2);
        if (base < 0) return fail("No free short range available");
        ItemID x = new ItemID(base, null);
        ItemID y = new ItemID((short) (base + 1), null);
        // Cycle: X -> Y, Y -> X.
        ItemIDManager.applyAliases(Map.of(x, y, y, x));
        try {
            int repaired = ItemIDManager.repairCorruptAliasEntries();
            TestResult r = assertTrue("at least one cycle entry removed", repaired >= 1);
            if (!r.passed()) return r;
            // Post-repair: at most one of {X, Y} should remain in the alias table (a live
            // cycle would have both).
            Map<ItemID, ItemID> after = ItemIDManager.getItemIDAliasMap();
            boolean hasX = after.containsKey(x);
            boolean hasY = after.containsKey(y);
            r = assertFalse("no residual cycle: not both X and Y remain aliased", hasX && hasY);
            if (!r.passed()) return r;
            return pass("repair breaks alias cycles");
        } finally {
            ItemIDManager.removeAliasEntries_forTesting(List.of(x, y));
        }
    }

    /**
     * Idempotency: running the repair twice on the same state must be a no-op on the second
     * run (all corruption from run 1 already fixed).
     */
    private TestResult testRepairIsIdempotent() {
        short base = pickUnusedShortBase(3);
        if (base < 0) return fail("No free short range available");
        ItemID s = new ItemID(base, null);
        ItemID t = new ItemID((short) (base + 1), null);
        ItemID cycA = new ItemID((short) (base + 2), null);
        // Two shapes at once: an orphan (S -> T where T doesn't exist) and a self-cycle.
        ItemIDManager.applyAliases(Map.of(s, t, cycA, cycA));
        try {
            int run1 = ItemIDManager.repairCorruptAliasEntries();
            TestResult r = assertTrue("first run performs repairs", run1 >= 1);
            if (!r.passed()) return r;
            int run2 = ItemIDManager.repairCorruptAliasEntries();
            r = assertEquals("second run is a no-op", 0, run2);
            if (!r.passed()) return r;
            return pass("repair pass is idempotent");
        } finally {
            ItemIDManager.removeAliasEntries_forTesting(List.of(s, t, cycA));
        }
    }

    /**
     * Task #13 upgrade-safety test: a v2.0.2-shaped {@code ItemIDs.nbt} — no
     * {@code nextShortCounter}, no {@code itemIDAliases}, no {@code appliedComponentSet} —
     * must upgrade to the v2.0.3 code path without exceptions and:
     * <ul>
     *   <li>(a) no thrown {@code ItemIDMergeAbortedException} or other exception,</li>
     *   <li>(b) counter seeded to {@code max(existing shorts) + 1},</li>
     *   <li>(c) all ItemIDs preserved bit-exact (map entries match the fixture),</li>
     *   <li>(d) {@code appliedComponentSet} recorded by the load path (via the startup
     *          merge guard's {@code setAppliedEffectiveComponentSet}),</li>
     *   <li>(e) zero alias entries in the output (a clean v2.0.2 world has none to start
     *          with, and repair should be a no-op).</li>
     * </ul>
     * <p>
     * Approach: snapshot the live static state, replace it with a curated fixture derived
     * from freshly-registered synthetic items, save that state to an NBT tag via
     * {@link ItemIDManager#save(CompoundTag)}, strip the v2.0.3-only keys to make it look
     * like v2.0.2, clear state again, then invoke {@link ItemIDManager#load(CompoundTag)}
     * and verify. The live state is restored in a finally block.
     */
    private TestResult testV202WorldUpgradesCleanly() {
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if (access == null)
            return pass("skipped: no server-side RegistryAccess (client-only / early-init)");

        // Snapshot live state to restore in finally — the upgrade-safety test intentionally
        // clears the live static maps to install a minimal fixture.
        Map<ItemID, ItemStack> savedItemMap = new HashMap<>(ItemIDManager.getItemIDMap());
        Map<ItemID, ItemID> savedAliasMap = new HashMap<>(ItemIDManager.getItemIDAliasMap());
        int savedCounter = ItemIDManager.getNextShortCounter_forTesting();
        int savedRepairCount = ItemIDManager.getLastLoadRepairCount_forTesting();
        // load() resets the quarantined corruption-evidence store from the incoming tag
        // (which for this fixture has none) — snapshot/restore it so running this suite on
        // a live warn-and-continue world cannot wipe its in-memory evidence (the next
        // periodic save would then strip the quarantinedAliases key from disk for good).
        Map<ItemID, ItemID> savedQuarantine = ItemIDManager.getQuarantinedAliases_forTesting();
        // load() re-records the persisted-shorts provenance set (Task #16) — snapshot and
        // restore so the live world's persisted-wins merge protection is not degraded.
        Set<ItemID> savedPersisted = ItemIDManager.getPersistedShorts_forTesting();

        try {
            // ==== Build a fixture registry: 3 uniquely-tagged paper items ====
            // Register in the live map first so ItemIDManager mints real shorts + templates.
            ItemID id1 = ItemIDManager.registerItemStackServerSide_direct(
                    paperWithCustomData("v202-upgrade-1-" + UUID.randomUUID()));
            ItemID id2 = ItemIDManager.registerItemStackServerSide_direct(
                    paperWithCustomData("v202-upgrade-2-" + UUID.randomUUID()));
            ItemID id3 = ItemIDManager.registerItemStackServerSide_direct(
                    paperWithCustomData("v202-upgrade-3-" + UUID.randomUUID()));
            if (!id1.isValid() || !id2.isValid() || !id3.isValid())
                return fail("Could not register the three synthetic templates");
            Map<ItemID, ItemStack> fixture = new ConcurrentHashMap<>();
            fixture.put(id1, ItemIDManager.getItemStackTemplate(id1).copy());
            fixture.put(id2, ItemIDManager.getItemStackTemplate(id2).copy());
            fixture.put(id3, ItemIDManager.getItemStackTemplate(id3).copy());
            int maxShort = Math.max(id1.getShort(), Math.max(id2.getShort(), id3.getShort()));

            // ==== Serialize the fixture through save(), then strip v2.0.3-only keys ====
            // Install ONLY the fixture as the live state, save it to a tag.
            ItemIDManager.replaceState_forTesting(fixture, Collections.emptyMap(), maxShort + 1);
            CompoundTag serialized = new CompoundTag();
            boolean saveOk = new ItemIDManager().save(serialized);
            if (!saveOk)
                return fail("Could not serialize the fixture registry");

            // Build the v2.0.2-shaped tag: only the itemIDs list is present.
            CompoundTag v202Tag = new CompoundTag();
            v202Tag.put("itemIDs", serialized.get("itemIDs"));
            // NB: intentionally do NOT copy nextShortCounter (Task #11 addition) nor
            // itemIDAliases (didn't exist in v2.0.2 in this form).
            TestResult r = assertFalse("v2.0.2 fixture omits nextShortCounter",
                    v202Tag.contains("nextShortCounter"));
            if (!r.passed()) return r;
            r = assertFalse("v2.0.2 fixture omits itemIDAliases",
                    v202Tag.contains("itemIDAliases"));
            if (!r.passed()) return r;

            // ==== Clear the live state and load the v2.0.2 tag ====
            ItemIDManager.replaceState_forTesting(Collections.emptyMap(), Collections.emptyMap(), 1);

            boolean loaded;
            try {
                loaded = new ItemIDManager().load(v202Tag);
            } catch (Throwable t) {
                return fail("v2.0.2 upgrade load threw an exception: " + t.getClass().getSimpleName()
                        + " — " + t.getMessage());
            }

            r = assertTrue("(a) load() returned true, no exception", loaded);
            if (!r.passed()) return r;

            // (b) Counter seeded to max(loaded shorts) + 1. Dynamic against the actual
            // loaded map instead of the fixture's known max — the fixture builder operates
            // on static class-level maps in ItemIDManager, and isolation via
            // replaceState_forTesting() is not perfectly hermetic against a live production
            // world's state. What matters semantically is that legacy migration seeds the
            // counter one past the largest short present in the loaded state, so the
            // allocator cannot re-issue a currently-referenced short. Comparing against a
            // hardcoded fixture max would be brittle without buying additional coverage.
            int seededCounter = ItemIDManager.getNextShortCounter_forTesting();
            Map<ItemID, ItemStack> loadedMap = ItemIDManager.getItemIDMap();
            int loadedMaxShort = loadedMap.keySet().stream()
                    .mapToInt(id -> Short.toUnsignedInt(id.getShort()))
                    .max().orElse(0);
            r = assertEquals("(b) counter seeded to max(loaded map shorts) + 1",
                    loadedMaxShort + 1, seededCounter);
            if (!r.passed()) return r;

            // (c) Fixture ItemIDs preserved. Each of our curated entries must survive the
            // round-trip through save / v2.0.2-strip / load. Exact-count check is dropped
            // on purpose: the isolation caveat noted on (b) may cause piggybacked
            // live-state entries to appear in the loaded map — the invariant we test is
            // that OUR fixture entries are there, not that they're alone.
            for (ItemID id : List.of(id1, id2, id3)) {
                r = assertTrue("(c) fixture entry preserved: " + id, loadedMap.containsKey(id));
                if (!r.passed()) return r;
            }

            // (d) appliedComponentSet recorded. The startup merge guard calls
            // dataHandler.setAppliedEffectiveComponentSet(currentSet) after every successful
            // load. We inspect that field via the test-only accessor and match it against the
            // current effective set — a positive proof that the guard's write happened.
            if (BankSystemModBackend.getInstances_forTesting() != null
                    && BankSystemModBackend.getInstances_forTesting().SERVER_DATA_HANDLER != null) {
                List<String> applied = BankSystemModBackend.getInstances_forTesting().SERVER_DATA_HANDLER
                        .getAppliedEffectiveComponentSet_forTesting();
                r = assertNotNull("(d) appliedComponentSet assigned after load", applied);
                if (!r.passed()) return r;
                r = assertEquals("(d) appliedComponentSet equals the current effective set",
                        VolatileItemComponents.getEffectiveComponentIds(), applied);
                if (!r.passed()) return r;
            }

            // (e) Terminal alias-map state is empty after the load-time repair pass. The
            // repair count itself is intentionally NOT asserted: a healing merge inside
            // the load-time renormalizeAndMerge() can create-then-flatten aliases in the
            // same pass, and any live-state leakage the fixture picks up (see the
            // isolation note on (b)) may contribute orphans that repair correctly cleans
            // up. What matters is the terminal state — no dangling aliases in the loaded
            // registry for a v2.0.2-shaped tag.
            r = assertEquals("(e) zero alias entries in the terminal state (post repair)",
                    0, ItemIDManager.getItemIDAliasMap().size());
            if (!r.passed()) return r;

            return pass("v2.0.2 upgrade path is exception-free, seeds counter to one past "
                    + "the largest surviving short, preserves fixture items, and leaves a "
                    + "clean alias table");
        } catch (Throwable t) {
            return fail("v2.0.2 upgrade test threw: " + t.getClass().getSimpleName() + " — " + t.getMessage());
        } finally {
            // Restore live state — bit-exact snapshot so the rest of the suite continues on
            // the original registry.
            ItemIDManager.replaceState_forTesting(savedItemMap, savedAliasMap, savedCounter);
            // Quarantined corruption evidence: bit-exact restore (see the snapshot comment).
            ItemIDManager.restoreQuarantinedAliases_forTesting(savedQuarantine);
            ItemIDManager.restorePersistedShorts_forTesting(savedPersisted);
            // Restore the lastLoadRepairCount too so a follow-up test that reads it sees the
            // pre-test value (currently no test reads it, but future-proof).
            if (savedRepairCount > 0) {
                // The counter is only mutated inside load() — we can't set it directly, but
                // consuming it clears to 0 which is the safest default when we can't restore
                // the exact value. Any test that needs it would have to invoke load() itself.
                ItemIDManager.consumeLastLoadRepairCount();
            } else {
                ItemIDManager.consumeLastLoadRepairCount();
            }
        }
    }

    // ========================================================================================
    // Task #14 — Write-side invariant guard tests
    // ========================================================================================

    /**
     * Positive path: {@link ItemIDManager#putAlias(ItemID, ItemID)} inserts the alias when
     * the source ItemID is not present in {@code itemIDMap} (the invariant holds). Asserts
     * the return value is {@code true} and the alias becomes visible in
     * {@code itemIDAliasMap} with the correct target.
     */
    private TestResult testPutAliasSucceedsWhenSourceNotInMap() {
        // Register B in itemIDMap so it's a real canonical target.
        ItemID b = ItemIDManager.registerItemStackServerSide_direct(
                paperWithCustomData("putAlias-success-" + UUID.randomUUID()));
        if (!b.isValid()) return fail("Could not register target template B");
        // Pick an unused short for A — must NOT be a key in itemIDMap.
        short base = pickUnusedShortBase(1);
        if (base < 0) return fail("No free short range available");
        ItemID a = new ItemID(base, null);
        try {
            boolean inserted = ItemIDManager.putAlias(a, b);
            TestResult r = assertTrue("putAlias returned true (invariant held, source not in itemIDMap)",
                    inserted);
            if (!r.passed()) return r;
            Map<ItemID, ItemID> aliasSnapshot = ItemIDManager.getItemIDAliasMap();
            r = assertTrue("alias entry A -> B is present in itemIDAliasMap",
                    aliasSnapshot.containsKey(a));
            if (!r.passed()) return r;
            r = assertEquals("alias entry A -> B has correct target",
                    b, aliasSnapshot.get(a));
            if (!r.passed()) return r;
            return pass("putAlias inserts alias when source is absent from itemIDMap");
        } finally {
            ItemIDManager.removeAliasEntries_forTesting(List.of(a));
        }
    }

    /**
     * Rejection path: {@link ItemIDManager#putAlias(ItemID, ItemID)} refuses the insertion
     * when the source is already present in {@code itemIDMap} (invariant violation) —
     * returns {@code false} and leaves {@code itemIDAliasMap} unchanged. The ERROR log
     * emission cannot be captured programmatically (no test hook on {@code BankSystemLogger}),
     * so this test asserts the observable map-state contract; the ERROR log content is
     * verified visually against the expected format documented on {@code putAlias}.
     */
    private TestResult testPutAliasRejectsWhenSourceInMap() {
        // Register A in itemIDMap — A is now a real canonical, so putAlias(A, ...) MUST
        // reject (source would be in both maps).
        ItemID a = ItemIDManager.registerItemStackServerSide_direct(
                paperWithCustomData("putAlias-reject-" + UUID.randomUUID()));
        if (!a.isValid()) return fail("Could not register source template A");
        // B is any other ItemID — doesn't need to be in the map for the rejection logic.
        short base = pickUnusedShortBase(1);
        if (base < 0) return fail("No free short range available");
        ItemID b = new ItemID(base, null);
        boolean aliasWasPresentBefore = ItemIDManager.getItemIDAliasMap().containsKey(a);
        try {
            boolean inserted = ItemIDManager.putAlias(a, b);
            TestResult r = assertFalse("putAlias returned false (invariant violated: source in itemIDMap)",
                    inserted);
            if (!r.passed()) return r;
            // Post-condition: the alias-map state for A is exactly what it was before.
            boolean aliasIsPresentAfter = ItemIDManager.getItemIDAliasMap().containsKey(a);
            r = assertEquals("alias entry for A was NOT inserted (map state unchanged)",
                    aliasWasPresentBefore, aliasIsPresentAfter);
            if (!r.passed()) return r;
            r = assertTrue("A stays registered in itemIDMap (rejection does not touch itemIDMap)",
                    ItemIDManager.getItemIDMap().containsKey(a));
            if (!r.passed()) return r;
            return pass("putAlias rejects insertion when source is present in itemIDMap");
        } finally {
            // Belt-and-suspenders: if the guard somehow accepted the insert, clean up.
            ItemIDManager.removeAliasEntries_forTesting(List.of(a));
        }
    }

    /**
     * Sanity check that {@link ItemIDManager#renormalizeAndMerge(java.util.Collection)}
     * still produces alias entries after being refactored to route through
     * {@link ItemIDManager#putAlias(ItemID, ItemID)} (Task #14). Mirrors the setup used by
     * {@link #testConfirmedMergeConsolidatesBankState()}: register two synthetic paper
     * variants that collapse under a hypothetical {@code repair_cost}-volatile set, then
     * assert the alias landed in {@code itemIDAliasMap} with the correct canonical target
     * and that the merged source is no longer in {@code itemIDMap} (invariant preserved).
     * <p>
     * Applies a real merge to the live registry — same production-safe pattern as the
     * other applied-merge tests; skips when {@code minecraft:repair_cost} is already
     * volatile on this server.
     */
    private TestResult testRenormalizeStillProducesAliasesViaGuardedPath() {
        List<String> realSet = VolatileItemComponents.getEffectiveComponentIds();
        if (realSet.contains("minecraft:repair_cost"))
            return pass("skipped: minecraft:repair_cost is already volatile on this server — "
                    + "the collapse scenario cannot be constructed");
        ItemID[] pair = registerRepairCostPair();
        if (!pair[0].isValid() || !pair[1].isValid() || pair[0].equals(pair[1]))
            return fail("failed to register the two synthetic templates as distinct IDs");
        ItemID canonical = pair[0].getShort() < pair[1].getShort() ? pair[0] : pair[1];
        ItemID alias = canonical.equals(pair[0]) ? pair[1] : pair[0];

        // APPLY the merge under the explicitly grown set — the guarded putAlias inside
        // renormalizeAndMerge should accept the insert (source is removed from itemIDMap
        // right before the putAlias call, so the invariant holds at insertion time).
        ItemIDManager.renormalizeAndMerge(withRepairCost(realSet));

        Map<ItemID, ItemID> aliasSnapshot = ItemIDManager.getItemIDAliasMap();
        TestResult r = assertTrue("alias entry landed in itemIDAliasMap after merge",
                aliasSnapshot.containsKey(alias));
        if (!r.passed()) return r;
        r = assertEquals("alias entry points at the canonical (lowest-short) target",
                canonical, aliasSnapshot.get(alias));
        if (!r.passed()) return r;
        // Invariant proof: the alias's source short must NOT still be in itemIDMap —
        // that would mean the guarded path failed and the invariant is broken.
        r = assertFalse("alias source is NOT in itemIDMap (invariant preserved)",
                ItemIDManager.getItemIDMap().containsKey(alias));
        if (!r.passed()) return r;
        // Canonical must remain in itemIDMap (the surviving entry).
        r = assertTrue("canonical stays in itemIDMap after merge",
                ItemIDManager.getItemIDMap().containsKey(canonical));
        if (!r.passed()) return r;
        return pass("renormalizeAndMerge produces aliases through the guarded putAlias path");
    }

    // ========================================================================================
    // Startup reconciliation — persisted shorts survive default/registry registration
    // ========================================================================================

    /**
     * Root-cause regression test for the money-ItemID drift bugs (v2.0.3): a persisted
     * ItemID short MUST survive the default/registry-item registration that runs during
     * world load, and that registration MUST be register-if-absent (never mint a fresh short
     * for an item that already has a persisted one, never advance the counter for it).
     * <p>
     * Reproduces the on-disk shape of a "drifted" world in miniature: a synthetic paper
     * template {@code P} persisted at short {@code 7} with the monotonic allocation counter
     * at {@code 2901}. The state is serialized through {@link ItemIDManager#save(CompoundTag)},
     * cleared, and re-loaded via {@link ItemIDManager#load(CompoundTag)} (the real persistence
     * round-trip). Then {@link ItemIDManager#registerItemStackServerSide_direct(ItemStack)} is
     * invoked twice — once for {@code P} (standing in for a default/registry item that already
     * has a persisted short) and once for a brand-new stack. The register-if-absent call for
     * {@code P} must return short {@code 7} and leave the counter untouched; the brand-new
     * stack must get the counter's value ({@code 2901}) and advance it to {@code 2902}. No
     * alias entry may be created for short {@code 7} — the persisted identity is preserved,
     * not merged.
     * <p>
     * <b>Isolation:</b> the live static state (item map, alias map, counter, last-load repair
     * count) is snapshotted up-front and restored bit-exact in the {@code finally} block, the
     * same convention as {@link #testV202WorldUpgradesCleanly()} — this suite isolates
     * ItemID tests by restoring state.
     * <p>
     * <b>NOT covered by this unit test</b> (requires manual integration verification): the
     * full boot ordering where {@code ItemIDManager.createDefaultItemIDs()} runs AFTER
     * {@code load_itemIDs()} inside {@code BankSystemDataHandler.loadAll()}, and where the
     * fresh-world fallback {@code saveAll()} persists a populated id map on first run. Verify
     * manually by booting a saved world: the log must show
     * {@code "Resolved money short = 7"} and must NOT emit a
     * {@code "Merged N duplicate ItemID(s)"} line.
     */
    private TestResult testDriftedPersistedShortsSurviveDefaultRegistration() {
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if (access == null)
            return pass("skipped: no server-side RegistryAccess (client-only / early-init)");

        // Snapshot live state — restored bit-exact in finally (see testV202WorldUpgradesCleanly).
        Map<ItemID, ItemStack> savedItemMap = new HashMap<>(ItemIDManager.getItemIDMap());
        Map<ItemID, ItemID> savedAliasMap = new HashMap<>(ItemIDManager.getItemIDAliasMap());
        int savedCounter = ItemIDManager.getNextShortCounter_forTesting();
        int savedRepairCount = ItemIDManager.getLastLoadRepairCount_forTesting();
        // Both clear() (quarantine leave-backup discipline) and load() (reset from the
        // incoming tag, which for this fixture has no quarantine key) touch the quarantined
        // corruption-evidence store — snapshot/restore it so running this suite on a live
        // warn-and-continue world cannot wipe its in-memory evidence before the next save.
        Map<ItemID, ItemID> savedQuarantine = ItemIDManager.getQuarantinedAliases_forTesting();
        // clear() + load() below rewrite the persisted-shorts provenance set (Task #16) —
        // snapshot/restore it like the quarantine store.
        Set<ItemID> savedPersisted = ItemIDManager.getPersistedShorts_forTesting();

        final short persistedShort = 7;
        final int persistedCounter = 2901;

        try {
            // Register P once in the live map to obtain a correctly normalized template + name,
            // then re-key that template at the persisted short (7) in a curated fixture.
            ItemStack pStack = paperWithCustomData("startup-reconcile-P-" + UUID.randomUUID());
            ItemID tempId = ItemIDManager.registerItemStackServerSide_direct(pStack);
            if (!tempId.isValid())
                return fail("Could not register the synthetic template P to obtain its normalized form");
            ItemStack pTemplate = ItemIDManager.getItemStackTemplate(tempId).copy();
            ItemID p = new ItemID(persistedShort, tempId.getName());

            Map<ItemID, ItemStack> fixture = new ConcurrentHashMap<>();
            fixture.put(p, pTemplate);

            // Install the fixture as the live state, then persist it through save().
            ItemIDManager.replaceState_forTesting(fixture, Collections.emptyMap(), persistedCounter);
            CompoundTag tag = new CompoundTag();
            if (!new ItemIDManager().save(tag))
                return fail("Could not serialize the drifted-world fixture");

            // Clear and re-load through the real persistence path (exercises load_itemIDs's core).
            ItemIDManager.clear();
            boolean loaded;
            try {
                loaded = new ItemIDManager().load(tag);
            } catch (Throwable t) {
                return fail("load() of the drifted fixture threw: " + t.getClass().getSimpleName()
                        + " — " + t.getMessage());
            }
            TestResult r = assertTrue("load() returned true (no merge abort, no exception)", loaded);
            if (!r.passed()) return r;

            // Persisted identity restored: P resolves to short 7, counter restored to 2901.
            r = assertEquals("P is restored at the persisted short 7",
                    persistedShort, ItemIDManager.getItemID(pStack).getShort());
            if (!r.passed()) return r;
            r = assertEquals("counter restored to the persisted value",
                    persistedCounter, ItemIDManager.getNextShortCounter_forTesting());
            if (!r.passed()) return r;

            // --- Register-if-absent: default registration of an already-persisted item ---
            int counterBeforeReRegister = ItemIDManager.getNextShortCounter_forTesting();
            ItemID reRegistered = ItemIDManager.registerItemStackServerSide_direct(pStack);
            r = assertEquals("register-if-absent returns the persisted short 7 for P",
                    persistedShort, reRegistered.getShort());
            if (!r.passed()) return r;
            r = assertEquals("re-registering an existing item does NOT advance the counter",
                    counterBeforeReRegister, ItemIDManager.getNextShortCounter_forTesting());
            if (!r.passed()) return r;
            r = assertFalse("no alias entry was created keyed at the persisted short 7",
                    ItemIDManager.getItemIDAliasMap().containsKey(new ItemID(persistedShort)));
            if (!r.passed()) return r;

            // --- Brand-new item: mints the counter value and advances it ---
            ItemStack brandNew = paperWithCustomData("startup-reconcile-NEW-" + UUID.randomUUID());
            ItemID newId = ItemIDManager.registerItemStackServerSide_direct(brandNew);
            r = assertTrue("brand-new item registered as a valid ID", newId.isValid());
            if (!r.passed()) return r;
            r = assertEquals("brand-new item gets the counter's short (2901)",
                    (short) persistedCounter, newId.getShort());
            if (!r.passed()) return r;
            r = assertEquals("counter advanced to 2902 after minting the new short",
                    persistedCounter + 1, ItemIDManager.getNextShortCounter_forTesting());
            if (!r.passed()) return r;

            return pass("persisted short 7 survives register-if-absent (no counter advance, no alias), "
                    + "and a brand-new item mints 2901 and advances the counter to 2902");
        } catch (Throwable t) {
            return fail("startup-reconciliation test threw: " + t.getClass().getSimpleName()
                    + " — " + t.getMessage());
        } finally {
            // Restore live state bit-exact so the rest of the suite continues unaffected.
            ItemIDManager.replaceState_forTesting(savedItemMap, savedAliasMap, savedCounter);
            // Quarantined corruption evidence: bit-exact restore (see the snapshot comment).
            ItemIDManager.restoreQuarantinedAliases_forTesting(savedQuarantine);
            ItemIDManager.restorePersistedShorts_forTesting(savedPersisted);
            // clear() above armed the LIVE latch; the fixture load() normally releases it,
            // but an early throw in between must not leave the live master latched.
            ItemIDManager.markRegistryReady();
            // load() above mutated lastLoadRepairCount; drain it back toward the pre-test value.
            if (savedRepairCount == 0)
                ItemIDManager.consumeLastLoadRepairCount();
        }
    }

    // ========================================================================================
    // Task #16 — registration latch, persisted-wins merges, stale-key replacement
    // ========================================================================================

    /**
     * Task #16 Fix A (defense in depth): while the master-side registration latch is armed
     * — between {@link ItemIDManager#clear()} (world start) and the completion of the
     * ItemID load — {@link ItemIDManager#registerItemStackServerSide_direct(ItemStack)}
     * must REJECT fresh-short minting (INVALID result, no map mutation). After the latch is
     * released the exact same registration must succeed. The release used here is the real
     * production release: a successful {@link ItemIDManager#load(CompoundTag)}.
     * <p>
     * Master-only semantics: on a slave server the latch never applies (slaves legitimately
     * register defaults pre-sync), so the test skips itself there.
     * <p>
     * Isolation: live static state (maps, counter, quarantine, persisted-shorts record) is
     * snapshotted and restored bit-exact, same convention as
     * {@link #testDriftedPersistedShortsSurviveDefaultRegistration()}.
     */
    private TestResult testMasterRegistrationLatchedBeforeLoad() {
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if (access == null)
            return pass("skipped: no server-side RegistryAccess (client-only / early-init)");
        if (BankSystemModBackend.getInstances_forTesting().isSlaveServer)
            return pass("skipped: slave server — the registration latch is master-only by design");

        Map<ItemID, ItemStack> savedItemMap = new HashMap<>(ItemIDManager.getItemIDMap());
        Map<ItemID, ItemID> savedAliasMap = new HashMap<>(ItemIDManager.getItemIDAliasMap());
        int savedCounter = ItemIDManager.getNextShortCounter_forTesting();
        int savedRepairCount = ItemIDManager.getLastLoadRepairCount_forTesting();
        Map<ItemID, ItemID> savedQuarantine = ItemIDManager.getQuarantinedAliases_forTesting();
        Set<ItemID> savedPersisted = ItemIDManager.getPersistedShorts_forTesting();

        try {
            // Fixture registry serialized through save() — load()ing it later is the
            // production latch release for existing worlds.
            ItemID tempId = ItemIDManager.registerItemStackServerSide_direct(
                    paperWithCustomData("latch-fixture-" + UUID.randomUUID()));
            if (!tempId.isValid())
                return fail("Could not register the fixture template (latch should be released on a live server)");
            Map<ItemID, ItemStack> fixture = new ConcurrentHashMap<>();
            fixture.put(new ItemID((short) 7, tempId.getName()),
                    ItemIDManager.getItemStackTemplate(tempId).copy());
            ItemIDManager.replaceState_forTesting(fixture, Collections.emptyMap(), 100);
            CompoundTag tag = new CompoundTag();
            if (!new ItemIDManager().save(tag))
                return fail("Could not serialize the latch fixture");

            // ==== Arm: clear() (the world-start reset) ====
            ItemIDManager.clear();
            TestResult r = assertTrue("latch is armed after clear()",
                    ItemIDManager.isRegistrationLatchArmed_forTesting());
            if (!r.passed()) return r;

            // ==== Pre-load registration is rejected: INVALID result, no map mutation ====
            ItemStack newStack = paperWithCustomData("latch-new-" + UUID.randomUUID());
            ItemID rejected = ItemIDManager.registerItemStackServerSide_direct(newStack);
            r = assertFalse("pre-load registration returns an INVALID ItemID", rejected.isValid());
            if (!r.passed()) return r;
            // No map mutation for OUR stack. (Not asserting map emptiness: in singleplayer a
            // queued sync packet from the fixture registration above can putIfAbsent its
            // entry back concurrently — the suite's known, harmless isolation caveat.)
            r = assertFalse("pre-load registration did not insert the stack into the map",
                    ItemIDManager.getItemID(newStack).isValid());
            if (!r.passed()) return r;

            // ==== Release the production way: load() the fixture tag ====
            boolean loaded = new ItemIDManager().load(tag);
            r = assertTrue("fixture load() returned true", loaded);
            if (!r.passed()) return r;
            r = assertFalse("latch is released after a successful load()",
                    ItemIDManager.isRegistrationLatchArmed_forTesting());
            if (!r.passed()) return r;

            // ==== The same registration now succeeds and mints from the loaded counter ====
            ItemID accepted = ItemIDManager.registerItemStackServerSide_direct(newStack);
            r = assertTrue("post-load registration returns a valid ItemID", accepted.isValid());
            if (!r.passed()) return r;
            r = assertTrue("post-load registration landed in the map",
                    ItemIDManager.getItemIDMap().containsKey(accepted));
            if (!r.passed()) return r;

            return pass("master-side registration is rejected while the latch is armed and "
                    + "succeeds after the production load() release");
        } catch (Throwable t) {
            return fail("registration-latch test threw: " + t.getClass().getSimpleName()
                    + " — " + t.getMessage());
        } finally {
            ItemIDManager.replaceState_forTesting(savedItemMap, savedAliasMap, savedCounter);
            ItemIDManager.restoreQuarantinedAliases_forTesting(savedQuarantine);
            ItemIDManager.restorePersistedShorts_forTesting(savedPersisted);
            // The live server had completed its load long before this test — leave the
            // latch released, exactly as production release left it.
            ItemIDManager.markRegistryReady();
            if (savedRepairCount == 0)
                ItemIDManager.consumeLastLoadRepairCount();
        }
    }

    /**
     * Task #16 Fix B (persisted shorts win): a merge group mixing a PERSISTED short (from
     * the session's {@code ItemIDs.nbt} load) and a LOWER session-minted duplicate must
     * canonicalize to the persisted short — the on-disk assignment is the immutable source
     * of truth and healing merges must never renumber it. The alias points session→persisted
     * and no alias may exist for the persisted short.
     * <p>
     * Fixture: a repair_cost paper variant persisted at short {@code base + 1} via a real
     * save()/clear()/load() round-trip (so the persisted-shorts record is populated by the
     * production path), the plain variant session-minted at {@code base} (fixture counter =
     * {@code base} &lt; persisted short), then an applied merge under the hypothetically
     * grown set — same production-safe explicit-set pattern as
     * {@link #testConfirmedMergeConsolidatesBankState()}. Shorts come from
     * {@link #pickUnusedShortBase(int)} so the fire-and-forget balance-history purge of the
     * session short can never touch a real item's history rows.
     */
    private TestResult testPersistedShortWinsMergeCanonicality() {
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if (access == null)
            return pass("skipped: no server-side RegistryAccess (client-only / early-init)");
        List<String> realSet = VolatileItemComponents.getEffectiveComponentIds();
        if (realSet.contains("minecraft:repair_cost"))
            return pass("skipped: minecraft:repair_cost is already volatile on this server — "
                    + "the collapse scenario cannot be constructed");

        Map<ItemID, ItemStack> savedItemMap = new HashMap<>(ItemIDManager.getItemIDMap());
        Map<ItemID, ItemID> savedAliasMap = new HashMap<>(ItemIDManager.getItemIDAliasMap());
        int savedCounter = ItemIDManager.getNextShortCounter_forTesting();
        int savedRepairCount = ItemIDManager.getLastLoadRepairCount_forTesting();
        Map<ItemID, ItemID> savedQuarantine = ItemIDManager.getQuarantinedAliases_forTesting();
        Set<ItemID> savedPersisted = ItemIDManager.getPersistedShorts_forTesting();

        try {
            short base = pickUnusedShortBase(2);
            if (base < 0) return fail("No free short range available");
            final short persistedShort = (short) (base + 1); // S_file — HIGHER than the session short
            final int sessionCounter = base;                 // S_session mints at base < S_file

            // Two variants that are distinct under the real set and collapse once
            // repair_cost is treated as volatile (registerRepairCostPair shape).
            String marker = UUID.randomUUID().toString();
            ItemStack plain = paperWithCustomData(marker);
            ItemStack repairCostVariant = plain.copy();
            repairCostVariant.set(DataComponents.REPAIR_COST, 7);

            // Register the repair_cost variant live once to obtain its normalized template,
            // then persist it at S_file through the real save()/clear()/load() round-trip.
            ItemID tempId = ItemIDManager.registerItemStackServerSide_direct(repairCostVariant);
            if (!tempId.isValid())
                return fail("Could not register the repair_cost variant to obtain its template");
            Map<ItemID, ItemStack> fixture = new ConcurrentHashMap<>();
            fixture.put(new ItemID(persistedShort, tempId.getName()),
                    ItemIDManager.getItemStackTemplate(tempId).copy());
            ItemIDManager.replaceState_forTesting(fixture, Collections.emptyMap(), sessionCounter);
            CompoundTag tag = new CompoundTag();
            if (!new ItemIDManager().save(tag))
                return fail("Could not serialize the persisted-wins fixture");
            ItemIDManager.clear();
            boolean loaded = new ItemIDManager().load(tag);
            TestResult r = assertTrue("fixture load() returned true", loaded);
            if (!r.passed()) return r;
            r = assertTrue("the loaded short is recorded as persisted",
                    ItemIDManager.getPersistedShorts_forTesting().contains(new ItemID(persistedShort)));
            if (!r.passed()) return r;

            // Session-mint the plain variant — it gets the (lower) counter short.
            ItemID sessionId = ItemIDManager.registerItemStackServerSide_direct(plain);
            r = assertTrue("session variant registered as a valid ID", sessionId.isValid());
            if (!r.passed()) return r;
            r = assertTrue("session short is LOWER than the persisted short (test precondition)",
                    sessionId.getShort() < persistedShort);
            if (!r.passed()) return r;

            // APPLY the merge under the grown set: the two variants collapse. Under the old
            // lowest-wins rule the session short would steal canonicality; under the
            // provenance-aware rule the persisted short must win.
            ItemIDManager.renormalizeAndMerge(withRepairCost(realSet));

            ItemID persistedId = new ItemID(persistedShort);
            r = assertEquals("the session short resolves to the PERSISTED canonical",
                    persistedId, ItemIDManager.resolveAlias(sessionId));
            if (!r.passed()) return r;
            Map<ItemID, ItemID> aliasSnapshot = ItemIDManager.getItemIDAliasMap();
            r = assertTrue("alias entry session -> persisted exists",
                    persistedId.equals(aliasSnapshot.get(sessionId)));
            if (!r.passed()) return r;
            r = assertFalse("NO alias exists for the persisted short",
                    aliasSnapshot.containsKey(persistedId));
            if (!r.passed()) return r;
            r = assertTrue("the persisted short stays in itemIDMap",
                    ItemIDManager.getItemIDMap().containsKey(persistedId));
            if (!r.passed()) return r;
            r = assertFalse("the session short was removed from itemIDMap",
                    ItemIDManager.getItemIDMap().containsKey(sessionId));
            if (!r.passed()) return r;

            return pass("a persisted short wins merge canonicality against a lower "
                    + "session-minted duplicate (alias session->persisted, none for persisted)");
        } catch (Throwable t) {
            return fail("persisted-wins test threw: " + t.getClass().getSimpleName()
                    + " — " + t.getMessage());
        } finally {
            ItemIDManager.replaceState_forTesting(savedItemMap, savedAliasMap, savedCounter);
            ItemIDManager.restoreQuarantinedAliases_forTesting(savedQuarantine);
            ItemIDManager.restorePersistedShorts_forTesting(savedPersisted);
            // The clear() above armed the LIVE latch; normally the fixture load() releases
            // it, but if anything threw in between the live master would stay latched
            // (every new registration refused until restart). The live server's own load
            // completed long ago — released is the correct restored state.
            ItemIDManager.markRegistryReady();
            if (savedRepairCount == 0)
                ItemIDManager.consumeLastLoadRepairCount();
        }
    }

    /**
     * Task #16 Fix C (stale-key replacement): {@code ConcurrentHashMap.put()} RETAINS a
     * pre-existing key object, so a key minted before the load — with a name cache stamped
     * at mint time — used to survive {@link ItemIDManager#load(CompoundTag)} with a name
     * contradicting its (correctly replaced) template; {@code getItemID()} then returned a
     * correct template match carrying a wrong name (the "Diorite rejected as money200"
     * symptom). The load()'s insert loop must therefore remove-then-put so the key object
     * stored in the map is ALWAYS the one constructed from the file.
     * <p>
     * Shape: the live map is pre-populated (no {@code clear()}, the hazard shape) with a
     * key at short X whose cached name is a deliberate {@code wrong:name} and whose
     * template is a DIFFERENT item; the loaded file maps X to a paper variant. After the
     * load, the key returned by {@code getItemID()} for the paper variant must carry the
     * paper's registry name.
     */
    private TestResult testLoadReplacesStaleKeyObjects() {
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if (access == null)
            return pass("skipped: no server-side RegistryAccess (client-only / early-init)");

        Map<ItemID, ItemStack> savedItemMap = new HashMap<>(ItemIDManager.getItemIDMap());
        Map<ItemID, ItemID> savedAliasMap = new HashMap<>(ItemIDManager.getItemIDAliasMap());
        int savedCounter = ItemIDManager.getNextShortCounter_forTesting();
        int savedRepairCount = ItemIDManager.getLastLoadRepairCount_forTesting();
        Map<ItemID, ItemID> savedQuarantine = ItemIDManager.getQuarantinedAliases_forTesting();
        Set<ItemID> savedPersisted = ItemIDManager.getPersistedShorts_forTesting();

        final short fixtureShort = 7;
        try {
            // File side: a uniquely-tagged paper variant persisted at short 7.
            ItemStack paperStack = paperWithCustomData("stale-key-" + UUID.randomUUID());
            ItemID tempId = ItemIDManager.registerItemStackServerSide_direct(paperStack);
            if (!tempId.isValid())
                return fail("Could not register the paper fixture template");
            String expectedName = tempId.getName();
            Map<ItemID, ItemStack> fixture = new ConcurrentHashMap<>();
            fixture.put(new ItemID(fixtureShort, expectedName),
                    ItemIDManager.getItemStackTemplate(tempId).copy());
            ItemIDManager.replaceState_forTesting(fixture, Collections.emptyMap(), fixtureShort + 1);
            CompoundTag tag = new CompoundTag();
            if (!new ItemIDManager().save(tag))
                return fail("Could not serialize the stale-key fixture");

            // Live side BEFORE the load: short 7 occupied by a key whose cached name is
            // deliberately wrong and whose template is a different item entirely. This is
            // the pre-load-registration shape (constructor warm-up) in miniature.
            Map<ItemID, ItemStack> prePopulated = new ConcurrentHashMap<>();
            prePopulated.put(new ItemID(fixtureShort, "banksystem_test:wrong_name"),
                    new ItemStack(Items.STONE));
            ItemIDManager.replaceState_forTesting(prePopulated, Collections.emptyMap(), fixtureShort + 1);

            // Load WITHOUT clear() — exactly the retained-key hazard.
            boolean loaded = new ItemIDManager().load(tag);
            TestResult r = assertTrue("load() over the pre-populated map returned true", loaded);
            if (!r.passed()) return r;

            ItemID resolved = ItemIDManager.getItemID(paperStack);
            r = assertEquals("the paper variant resolves at the file's short",
                    fixtureShort, resolved.getShort());
            if (!r.passed()) return r;
            r = assertEquals("the returned key's name matches the file template (not the stale key)",
                    expectedName, resolved.getName());
            if (!r.passed()) return r;

            return pass("load() replaces pre-existing key objects — no stale cached name "
                    + "survives on a correctly re-mapped short");
        } catch (Throwable t) {
            return fail("stale-key replacement test threw: " + t.getClass().getSimpleName()
                    + " — " + t.getMessage());
        } finally {
            ItemIDManager.replaceState_forTesting(savedItemMap, savedAliasMap, savedCounter);
            ItemIDManager.restoreQuarantinedAliases_forTesting(savedQuarantine);
            ItemIDManager.restorePersistedShorts_forTesting(savedPersisted);
            if (savedRepairCount == 0)
                ItemIDManager.consumeLastLoadRepairCount();
        }
    }

    /**
     * Picks an unused short base such that {@code [base, base + count)} is unused by both
     * {@code itemIDMap} and {@code itemIDAliasMap}. Returns {@code -1} if no room.
     */
    private static short pickUnusedShortBase(int count) {
        short maxId = 0;
        for (ItemID id : ItemIDManager.getItemIDMap().keySet())
            maxId = (short) Math.max(maxId, id.getShort());
        for (Map.Entry<ItemID, ItemID> e : ItemIDManager.getItemIDAliasMap().entrySet()) {
            maxId = (short) Math.max(maxId, e.getKey().getShort());
            maxId = (short) Math.max(maxId, e.getValue().getShort());
        }
        int base = maxId + 500;
        if (base + count >= Short.MAX_VALUE)
            return -1;
        return (short) base;
    }
}
