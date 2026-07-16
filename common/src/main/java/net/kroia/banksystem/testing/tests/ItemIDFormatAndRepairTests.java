package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.minecraft.item.BankSystemItems;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.BankSystemSaveFormat;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.banksystem.util.ItemIDWorldRepair;
import net.kroia.banksystem.util.ItemIDsNewerFormatException;
import net.kroia.modutilities.UtilitiesPlatform;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-game tests for the v2.0.4 save-format versioning of {@code ItemIDs.nbt}
 * ({@link BankSystemSaveFormat}) and the cent-shift world-corruption detection/repair
 * planning ({@link ItemIDWorldRepair}).
 * <p>
 * Conventions follow {@link ItemIDMergeGuardTests} exactly:
 * <ul>
 *   <li>Tests that mutate the live static ItemID state snapshot it up-front and restore it
 *       bit-exact in a {@code finally} block
 *       ({@link ItemIDManager#replaceState_forTesting}).</li>
 *   <li>The detection/planning tests (5–10) never touch the live state at all —
 *       {@link ItemIDWorldRepair} is pure and operates on maps the tests construct
 *       themselves, so they are safe on any production world.</li>
 *   <li>Tests skip themselves when no server-side {@code RegistryAccess} is available
 *       (client-only / early-init).</li>
 * </ul>
 */
public class ItemIDFormatAndRepairTests extends TestSuite {

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.ITEM_ID;
    }

    @Override
    public void teardown() {
        // Digest hygiene (Task #16): fixture load()s inside this suite run the startup
        // merge guard, whose save_metadata() persisted the FIXTURE registry's
        // renumbering-tripwire digest into the real Meta_data.nbt. Now that every test has
        // restored the live state, re-save the metadata through the production path so a
        // server kill after a test run cannot leave a fixture digest behind (which would
        // fire a false tripwire ERROR naming fixture shorts on the next boot).
        net.kroia.banksystem.BankSystemModBackend.Instances instances =
                net.kroia.banksystem.BankSystemModBackend.getInstances_forTesting();
        if (instances != null && instances.SERVER_DATA_HANDLER != null)
            instances.SERVER_DATA_HANDLER.save_metadata();
    }

    @Override
    public void registerTests() {
        // Format versioning (BankSystemSaveFormat)
        addTest("save_stamps_current_format_version", this::testSaveStampsCurrentFormatVersion);
        addTest("format_version_round_trip", this::testFormatVersionRoundTrip);
        addTest("legacy_versionless_tag_loads_and_flags_resave", this::testLegacyVersionlessTagLoadsAndFlagsResave);
        addTest("newer_format_version_is_refused_without_mutation", this::testNewerFormatVersionIsRefusedWithoutMutation);
        // Cent-shift corruption detection (ItemIDWorldRepair.detect — pure)
        addTest("detect_empty_on_healthy_fresh_post_cent_world", this::testDetectEmptyOnHealthyFreshPostCentWorld);
        addTest("detect_empty_on_healthy_upgraded_pre_cent_world", this::testDetectEmptyOnHealthyUpgradedPreCentWorld);
        addTest("detect_fires_on_corrupted_state_and_is_dry_run", this::testDetectFiresOnCorruptedStateAndIsDryRun);
        // Repair planning (ItemIDWorldRepair.buildRepairPlan — pure)
        addTest("repair_plan_restores_old_mapping", this::testRepairPlanRestoresOldMapping);
        addTest("repair_plan_refuses_inconsistent_evidence", this::testRepairPlanRefusesInconsistentEvidence);
        addTest("repair_is_idempotent_detect_empty_after_apply", this::testRepairIsIdempotentDetectEmptyAfterApply);
        // End-to-end guard integration through the real NBT load path
        addTest("corrupted_nbt_load_aborts_and_quarantines_evidence",
                this::testCorruptedNbtLoadAbortsAndQuarantinesEvidence);
        // Realistic multi-pair corpora (direct-pair fingerprint semantics)
        addTest("multi_pair_money_corpus_validates_and_repairs",
                this::testMultiPairMoneyCorpusValidatesAndRepairs);
        addTest("large_plus5_corpus_loses_no_fingerprints",
                this::testLargePlus5CorpusLosesNoFingerprints);
        // Singleplayer cross-world isolation of the quarantine store
        addTest("fresh_world_does_not_inherit_quarantined_evidence",
                this::testFreshWorldDoesNotInheritQuarantinedEvidence);
        // Task #16 — boot-order candidate firewall + renumbering-tripwire digest
        addTest("boot_order_corrupted_shape_passes_extended_firewall",
                this::testBootOrderCorruptedShapePassesExtendedFirewall);
        addTest("digest_tripwire_reports_renumbered_shorts",
                this::testDigestTripwireReportsRenumberedShorts);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /** Creates a paper stack carrying the given string inside minecraft:custom_data. */
    private static ItemStack paperWithCustomData(String value) {
        ItemStack stack = new ItemStack(Items.PAPER);
        CompoundTag nbt = new CompoundTag();
        nbt.putString("banksystem_format_repair_test", value);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(nbt));
        return stack;
    }

    /**
     * Registers two uniquely-tagged paper templates in the LIVE registry (so the templates
     * are correctly normalized), copies them into a fixture map at shorts 7 and 9, and
     * returns the fixture. The live state is expected to be snapshotted/restored by the
     * calling test.
     *
     * @return fixture map with exactly two entries at shorts 7 and 9, or {@code null} when
     *         registration failed
     */
    private static Map<ItemID, ItemStack> buildTwoEntryFixture() {
        ItemID tempA = ItemIDManager.registerItemStackServerSide_direct(
                paperWithCustomData("fixture-A-" + UUID.randomUUID()));
        ItemID tempB = ItemIDManager.registerItemStackServerSide_direct(
                paperWithCustomData("fixture-B-" + UUID.randomUUID()));
        if (!tempA.isValid() || !tempB.isValid())
            return null;
        Map<ItemID, ItemStack> fixture = new ConcurrentHashMap<>();
        fixture.put(new ItemID((short) 7, tempA.getName()), ItemIDManager.getItemStackTemplate(tempA).copy());
        fixture.put(new ItemID((short) 9, tempB.getName()), ItemIDManager.getItemStackTemplate(tempB).copy());
        return fixture;
    }

    /** Finds the short mapped to the given item in a simulated short→template mapping. */
    private static Short shortOf(LinkedHashMap<Short, ItemStack> mapping, Item item) {
        for (Map.Entry<Short, ItemStack> entry : mapping.entrySet())
            if (entry.getValue().getItem() == item)
                return entry.getKey();
        return null;
    }

    /** Converts a simulated short→template mapping into an ItemID-keyed map (deep copies). */
    private static Map<ItemID, ItemStack> toItemIDMap(LinkedHashMap<Short, ItemStack> byShort) {
        Map<ItemID, ItemStack> map = new HashMap<>();
        for (Map.Entry<Short, ItemStack> entry : byShort.entrySet())
            map.put(new ItemID(entry.getKey()), entry.getValue().copy());
        return map;
    }

    /**
     * Builds the canonical synthetic corrupted state used by tests 7, 8 and 10: the item map
     * is a full fresh post-cent default assignment F, and the alias table carries the money
     * fingerprint (old-epoch base-money short → fresh base-money short) the buggy build left
     * behind. Pure — nothing is installed into live state.
     *
     * @return array {corruptedMap (Map&lt;ItemID,ItemStack&gt;), aliases (Map&lt;ItemID,ItemID&gt;),
     *         oldMapping O, freshMapping F} boxed as Object[] for compactness
     */
    private static Object[] buildSyntheticCorruptedState(RegistryAccess access) {
        LinkedHashMap<Short, ItemStack> oldMapping = ItemIDWorldRepair.simulateDefaultAssignment(access, false, 1);
        LinkedHashMap<Short, ItemStack> freshMapping = ItemIDWorldRepair.simulateDefaultAssignment(access, true, 1);
        Item money = BankSystemItems.MONEY.get();
        Short oldMoneyShort = shortOf(oldMapping, money);
        Short freshMoneyShort = shortOf(freshMapping, money);
        if (oldMoneyShort == null || freshMoneyShort == null)
            return null;
        Map<ItemID, ItemStack> corruptedMap = toItemIDMap(freshMapping);
        Map<ItemID, ItemID> aliases = new HashMap<>();
        aliases.put(new ItemID(oldMoneyShort), new ItemID(freshMoneyShort));
        return new Object[]{corruptedMap, aliases, oldMapping, freshMapping};
    }

    /** Compares two ItemID-keyed template maps for same-keys + same item/components templates. */
    private static boolean mapsEquivalent(Map<ItemID, ItemStack> a, Map<ItemID, ItemStack> b) {
        if (a.size() != b.size())
            return false;
        for (Map.Entry<ItemID, ItemStack> entry : a.entrySet()) {
            ItemStack other = b.get(entry.getKey());
            if (other == null || !ItemStack.isSameItemSameComponents(entry.getValue(), other))
                return false;
        }
        return true;
    }

    /** Drains the one-shot load signals so a test observes only its own load's flags. */
    private static void drainLoadSignals() {
        ItemIDManager.consumeLastLoadRepairCount();
        ItemIDManager.consumeLastLoadRequiresResave();
        ItemIDManager.consumeRepairWasApplied();
    }

    // ========================================================================
    // 1–4: format versioning
    // ========================================================================

    /** Test 1: {@code save()} stamps {@code formatVersion == ITEM_IDS_FORMAT_CURRENT}. */
    private TestResult testSaveStampsCurrentFormatVersion() {
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if (access == null)
            return pass("skipped: no server-side RegistryAccess (client-only / early-init)");
        CompoundTag tag = new CompoundTag();
        if (!new ItemIDManager().save(tag))
            return fail("save() returned false on the live registry");
        TestResult r = assertTrue("saved tag contains the format-version key",
                tag.contains(BankSystemSaveFormat.KEY_FORMAT_VERSION));
        if (!r.passed()) return r;
        r = assertEquals("saved format version equals ITEM_IDS_FORMAT_CURRENT",
                BankSystemSaveFormat.ITEM_IDS_FORMAT_CURRENT,
                tag.getInt(BankSystemSaveFormat.KEY_FORMAT_VERSION));
        if (!r.passed()) return r;
        return pass("save() stamps formatVersion = " + BankSystemSaveFormat.ITEM_IDS_FORMAT_CURRENT);
    }

    /**
     * Test 2: save→load round-trip — fixture entries and counter survive bit-exact and the
     * loaded format version is reported as CURRENT.
     */
    private TestResult testFormatVersionRoundTrip() {
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if (access == null)
            return pass("skipped: no server-side RegistryAccess (client-only / early-init)");

        Map<ItemID, ItemStack> savedItemMap = new HashMap<>(ItemIDManager.getItemIDMap());
        Map<ItemID, ItemID> savedAliasMap = new HashMap<>(ItemIDManager.getItemIDAliasMap());
        int savedCounter = ItemIDManager.getNextShortCounter_forTesting();
        // load() resets the quarantined-evidence store from the incoming tag — snapshot and
        // restore it so the test cannot wipe a real world's persisted evidence in memory.
        Map<ItemID, ItemID> savedQuarantine = ItemIDManager.getQuarantinedAliases_forTesting();
        // load() also re-records the persisted-shorts provenance set (Task #16) — restore it
        // so the live world's persisted-wins merge protection is not degraded by the test.
        java.util.Set<ItemID> savedPersisted = ItemIDManager.getPersistedShorts_forTesting();
        final int fixtureCounter = 4242;
        try {
            Map<ItemID, ItemStack> fixture = buildTwoEntryFixture();
            if (fixture == null)
                return fail("could not register the synthetic fixture templates");

            ItemIDManager.replaceState_forTesting(fixture, Collections.emptyMap(), fixtureCounter);
            CompoundTag tag = new CompoundTag();
            if (!new ItemIDManager().save(tag))
                return fail("could not serialize the fixture");

            ItemIDManager.replaceState_forTesting(Collections.emptyMap(), Collections.emptyMap(), 1);
            drainLoadSignals();
            boolean loaded = new ItemIDManager().load(tag);
            TestResult r = assertTrue("load() returned true", loaded);
            if (!r.passed()) return r;

            Map<ItemID, ItemStack> loadedMap = ItemIDManager.getItemIDMap();
            for (Map.Entry<ItemID, ItemStack> entry : fixture.entrySet()) {
                ItemStack loadedTemplate = loadedMap.get(entry.getKey());
                r = assertTrue("fixture entry #" + entry.getKey().getShort() + " survives the round-trip",
                        loadedTemplate != null
                                && ItemStack.isSameItemSameComponents(entry.getValue(), loadedTemplate));
                if (!r.passed()) return r;
            }
            r = assertEquals("counter survives the round-trip",
                    fixtureCounter, ItemIDManager.getNextShortCounter_forTesting());
            if (!r.passed()) return r;
            r = assertEquals("loaded format version is reported as CURRENT",
                    BankSystemSaveFormat.ITEM_IDS_FORMAT_CURRENT,
                    ItemIDManager.getLoadedFormatVersion_forTesting());
            if (!r.passed()) return r;
            return pass("versioned save/load round-trip preserves maps and counter");
        } catch (Throwable t) {
            return fail("round-trip test threw: " + t.getClass().getSimpleName() + " — " + t.getMessage());
        } finally {
            ItemIDManager.replaceState_forTesting(savedItemMap, savedAliasMap, savedCounter);
            ItemIDManager.restoreQuarantinedAliases_forTesting(savedQuarantine);
            ItemIDManager.restorePersistedShorts_forTesting(savedPersisted);
            drainLoadSignals();
        }
    }

    /**
     * Test 3: a legacy version-less tag (pre-v2.0.4 shape) loads through the tolerant path,
     * flags the one-shot conversion re-save, and the subsequent save stamps the version.
     */
    private TestResult testLegacyVersionlessTagLoadsAndFlagsResave() {
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if (access == null)
            return pass("skipped: no server-side RegistryAccess (client-only / early-init)");

        Map<ItemID, ItemStack> savedItemMap = new HashMap<>(ItemIDManager.getItemIDMap());
        Map<ItemID, ItemID> savedAliasMap = new HashMap<>(ItemIDManager.getItemIDAliasMap());
        int savedCounter = ItemIDManager.getNextShortCounter_forTesting();
        Map<ItemID, ItemID> savedQuarantine = ItemIDManager.getQuarantinedAliases_forTesting();
        java.util.Set<ItemID> savedPersisted = ItemIDManager.getPersistedShorts_forTesting();
        try {
            Map<ItemID, ItemStack> fixture = buildTwoEntryFixture();
            if (fixture == null)
                return fail("could not register the synthetic fixture templates");

            ItemIDManager.replaceState_forTesting(fixture, Collections.emptyMap(), 100);
            CompoundTag tag = new CompoundTag();
            if (!new ItemIDManager().save(tag))
                return fail("could not serialize the fixture");
            // Strip the version key — the exact shape every pre-v2.0.4 ItemIDs.nbt has.
            tag.remove(BankSystemSaveFormat.KEY_FORMAT_VERSION);

            ItemIDManager.replaceState_forTesting(Collections.emptyMap(), Collections.emptyMap(), 1);
            drainLoadSignals();
            boolean loaded = new ItemIDManager().load(tag);
            TestResult r = assertTrue("legacy tag load() returned true (tolerant path)", loaded);
            if (!r.passed()) return r;
            r = assertEquals("loaded format version is reported as LEGACY",
                    BankSystemSaveFormat.ITEM_IDS_FORMAT_LEGACY,
                    ItemIDManager.getLoadedFormatVersion_forTesting());
            if (!r.passed()) return r;
            Map<ItemID, ItemStack> loadedMap = ItemIDManager.getItemIDMap();
            for (ItemID id : fixture.keySet()) {
                r = assertTrue("legacy fixture entry #" + id.getShort() + " loaded", loadedMap.containsKey(id));
                if (!r.passed()) return r;
            }
            r = assertTrue("legacy load flags the one-shot conversion re-save",
                    ItemIDManager.consumeLastLoadRequiresResave());
            if (!r.passed()) return r;
            r = assertFalse("the re-save flag is one-shot (second consume returns false)",
                    ItemIDManager.consumeLastLoadRequiresResave());
            if (!r.passed()) return r;

            // The conversion save stamps the current format version.
            CompoundTag resaved = new CompoundTag();
            if (!new ItemIDManager().save(resaved))
                return fail("conversion re-save failed");
            r = assertEquals("conversion re-save stamps the current format version",
                    BankSystemSaveFormat.ITEM_IDS_FORMAT_CURRENT,
                    resaved.getInt(BankSystemSaveFormat.KEY_FORMAT_VERSION));
            if (!r.passed()) return r;
            return pass("legacy version-less files load tolerantly and are converted by the next save");
        } catch (Throwable t) {
            return fail("legacy-load test threw: " + t.getClass().getSimpleName() + " — " + t.getMessage());
        } finally {
            ItemIDManager.replaceState_forTesting(savedItemMap, savedAliasMap, savedCounter);
            ItemIDManager.restoreQuarantinedAliases_forTesting(savedQuarantine);
            ItemIDManager.restorePersistedShorts_forTesting(savedPersisted);
            drainLoadSignals();
        }
    }

    /**
     * Test 4: a tag stamped with CURRENT+1 is refused via {@link ItemIDsNewerFormatException}
     * with ZERO static-state mutation (snapshot compare of both maps and the counter).
     */
    private TestResult testNewerFormatVersionIsRefusedWithoutMutation() {
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if (access == null)
            return pass("skipped: no server-side RegistryAccess (client-only / early-init)");

        Map<ItemID, ItemStack> savedItemMap = new HashMap<>(ItemIDManager.getItemIDMap());
        Map<ItemID, ItemID> savedAliasMap = new HashMap<>(ItemIDManager.getItemIDAliasMap());
        int savedCounter = ItemIDManager.getNextShortCounter_forTesting();
        try {
            Map<ItemID, ItemStack> fixture = buildTwoEntryFixture();
            if (fixture == null)
                return fail("could not register the synthetic fixture templates");

            ItemIDManager.replaceState_forTesting(fixture, Collections.emptyMap(), 100);
            CompoundTag tag = new CompoundTag();
            if (!new ItemIDManager().save(tag))
                return fail("could not serialize the fixture");
            // Fake a file written by a newer BankSystem build.
            tag.putInt(BankSystemSaveFormat.KEY_FORMAT_VERSION,
                    BankSystemSaveFormat.ITEM_IDS_FORMAT_CURRENT + 1);

            // Snapshot the exact pre-load state (the fixture) for the zero-mutation compare.
            Map<ItemID, ItemStack> beforeMap = new HashMap<>(ItemIDManager.getItemIDMap());
            Map<ItemID, ItemID> beforeAliases = new HashMap<>(ItemIDManager.getItemIDAliasMap());
            int beforeCounter = ItemIDManager.getNextShortCounter_forTesting();
            int beforeLoadedVersion = ItemIDManager.getLoadedFormatVersion_forTesting();
            drainLoadSignals();

            boolean threw = false;
            try {
                new ItemIDManager().load(tag);
            } catch (ItemIDsNewerFormatException e) {
                threw = true;
                TestResult msg = assertTrue("refusal message names the file version, the supported "
                                + "version and the update instruction",
                        e.getMessage().contains(String.valueOf(BankSystemSaveFormat.ITEM_IDS_FORMAT_CURRENT + 1))
                                && e.getMessage().contains(String.valueOf(BankSystemSaveFormat.ITEM_IDS_FORMAT_CURRENT))
                                && e.getMessage().contains("update the mod"));
                if (!msg.passed()) return msg;
            }
            TestResult r = assertTrue("load() threw ItemIDsNewerFormatException", threw);
            if (!r.passed()) return r;

            r = assertTrue("itemIDMap is unchanged after the refusal",
                    mapsEquivalent(beforeMap, ItemIDManager.getItemIDMap()));
            if (!r.passed()) return r;
            r = assertEquals("alias map is unchanged after the refusal",
                    beforeAliases, ItemIDManager.getItemIDAliasMap());
            if (!r.passed()) return r;
            r = assertEquals("counter is unchanged after the refusal",
                    beforeCounter, ItemIDManager.getNextShortCounter_forTesting());
            if (!r.passed()) return r;
            r = assertEquals("tracked loaded-format version is unchanged after the refusal",
                    beforeLoadedVersion, ItemIDManager.getLoadedFormatVersion_forTesting());
            if (!r.passed()) return r;
            r = assertFalse("no one-shot re-save was flagged by the refused load",
                    ItemIDManager.consumeLastLoadRequiresResave());
            if (!r.passed()) return r;
            return pass("newer-format files are refused before any static state is touched");
        } catch (Throwable t) {
            return fail("newer-format refusal test threw: " + t.getClass().getSimpleName() + " — " + t.getMessage());
        } finally {
            ItemIDManager.replaceState_forTesting(savedItemMap, savedAliasMap, savedCounter);
            drainLoadSignals();
        }
    }

    // ========================================================================
    // 5–7: detection (pure — never touches live state)
    // ========================================================================

    /**
     * Test 5: a healthy FRESH post-cent world — full fresh default assignment, empty alias
     * table — must not be detected. The contiguous money block alone (signal b) must NOT
     * trigger without a money fingerprint alias (signal a).
     */
    private TestResult testDetectEmptyOnHealthyFreshPostCentWorld() {
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if (access == null)
            return pass("skipped: no server-side RegistryAccess (client-only / early-init)");

        LinkedHashMap<Short, ItemStack> freshMapping = ItemIDWorldRepair.simulateDefaultAssignment(access, true, 1);
        Map<ItemID, ItemStack> map = toItemIDMap(freshMapping);

        // No aliases at all — the canonical fresh-world shape.
        Optional<ItemIDWorldRepair.CorruptionEvidence> evidence =
                ItemIDWorldRepair.detect(map, Collections.emptyMap(), access);
        TestResult r = assertFalse("no detection on a fresh post-cent world without aliases",
                evidence.isPresent());
        if (!r.passed()) return r;

        // A NON-money alias (paper-shaped orphan) must not act as a fingerprint either —
        // the contiguous money block alone stays insufficient.
        int maxShort = freshMapping.keySet().stream().mapToInt(Short::toUnsignedInt).max().orElse(0);
        Map<ItemID, ItemID> nonMoneyAlias = Map.of(
                new ItemID((short) (maxShort + 100)), new ItemID((short) (maxShort + 101)));
        evidence = ItemIDWorldRepair.detect(map, nonMoneyAlias, access);
        r = assertFalse("a non-money alias plus the money block does not trigger detection",
                evidence.isPresent());
        if (!r.passed()) return r;
        return pass("healthy fresh post-cent worlds are never detected (money block alone insufficient)");
    }

    /**
     * Test 6: a healthy UPGRADED pre-cent world — old assignment with the cents appended at
     * the tail by the fixed register-if-absent pass, no aliases — must not be detected.
     */
    private TestResult testDetectEmptyOnHealthyUpgradedPreCentWorld() {
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if (access == null)
            return pass("skipped: no server-side RegistryAccess (client-only / early-init)");

        LinkedHashMap<Short, ItemStack> oldMapping = ItemIDWorldRepair.simulateDefaultAssignment(access, false, 1);
        Map<ItemID, ItemStack> map = toItemIDMap(oldMapping);
        // Fixed builds append the (new) cent items at the tail: money stays at 1..9, cents
        // land above every registry item — the healthy upgraded-world shape.
        int nextShort = oldMapping.keySet().stream().mapToInt(Short::toUnsignedInt).max().orElse(0) + 1;
        for (ItemStack centStack : List.of(
                new ItemStack(BankSystemItems.MONEY_CENT1.get()),
                new ItemStack(BankSystemItems.MONEY_CENT5.get()),
                new ItemStack(BankSystemItems.MONEY_CENT10.get()),
                new ItemStack(BankSystemItems.MONEY_CENT20.get()),
                new ItemStack(BankSystemItems.MONEY_CENT50.get()))) {
            map.put(new ItemID((short) nextShort), centStack);
            nextShort++;
        }

        // The genuinely healthy shape: no aliases.
        Optional<ItemIDWorldRepair.CorruptionEvidence> evidence =
                ItemIDWorldRepair.detect(map, Collections.emptyMap(), access);
        TestResult r = assertFalse("no detection on an upgraded pre-cent world without aliases",
                evidence.isPresent());
        if (!r.passed()) return r;

        // Even IF a money alias existed, the tail-appended cents break the fresh-assignment
        // money-block signature (signal b), so detection must still stay silent.
        Item money = BankSystemItems.MONEY.get();
        Short moneyShort = shortOf(oldMapping, money);
        if (moneyShort == null)
            return fail("base money missing from the simulated old assignment");
        Map<ItemID, ItemID> hypotheticalAlias = Map.of(
                new ItemID((short) (nextShort + 100)), new ItemID(moneyShort));
        evidence = ItemIDWorldRepair.detect(map, hypotheticalAlias, access);
        r = assertFalse("tail-appended cents break the fresh money-block signature",
                evidence.isPresent());
        if (!r.passed()) return r;
        return pass("healthy upgraded pre-cent worlds are never detected");
    }

    /**
     * Test 7: the synthetic corrupted state (fresh cent-shifted mapping + money fingerprint
     * alias) IS detected — and detection is a pure dry run (input maps unchanged).
     */
    private TestResult testDetectFiresOnCorruptedStateAndIsDryRun() {
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if (access == null)
            return pass("skipped: no server-side RegistryAccess (client-only / early-init)");

        Object[] state = buildSyntheticCorruptedState(access);
        if (state == null)
            return fail("could not build the synthetic corrupted state (money item missing from a simulation)");
        @SuppressWarnings("unchecked")
        Map<ItemID, ItemStack> corruptedMap = (Map<ItemID, ItemStack>) state[0];
        @SuppressWarnings("unchecked")
        Map<ItemID, ItemID> aliases = (Map<ItemID, ItemID>) state[1];

        Map<ItemID, ItemStack> mapBefore = new HashMap<>(corruptedMap);
        Map<ItemID, ItemID> aliasesBefore = new HashMap<>(aliases);

        Optional<ItemIDWorldRepair.CorruptionEvidence> evidence =
                ItemIDWorldRepair.detect(corruptedMap, aliases, access);
        TestResult r = assertTrue("corrupted state is detected", evidence.isPresent());
        if (!r.passed()) return r;
        ItemIDWorldRepair.CorruptionEvidence ev = evidence.get();
        r = assertEquals("exactly one money fingerprint alias reported", 1, ev.fingerprints().size());
        if (!r.passed()) return r;
        ItemIDWorldRepair.AliasFingerprint fp = ev.fingerprints().getFirst();
        ItemID aliasSource = aliases.keySet().iterator().next();
        r = assertEquals("fingerprint 'from' matches the alias source",
                aliasSource.getShort(), fp.from());
        if (!r.passed()) return r;
        r = assertEquals("fingerprint 'to' matches the alias target",
                aliases.get(aliasSource).getShort(), fp.to());
        if (!r.passed()) return r;
        r = assertEquals("the fresh money block has all 14 money/cent items",
                14, ev.freshMoneyBlock().size());
        if (!r.passed()) return r;

        // Pure dry run: the passed-in maps are bit-identical afterwards.
        r = assertTrue("detect() did not mutate the item map", mapsEquivalent(mapBefore, corruptedMap));
        if (!r.passed()) return r;
        r = assertEquals("detect() did not mutate the alias map", aliasesBefore, aliases);
        if (!r.passed()) return r;
        return pass("detection fires on the corrupted signature and is a pure dry run");
    }

    // ========================================================================
    // 8–10: repair planning (pure — never touches live state)
    // ========================================================================

    /**
     * Test 8: the repair plan restores the old mapping — the corrupted state is built FROM
     * the known canonical pre-cent mapping O by simulating the bug (fresh assignment F +
     * money fingerprint alias); the plan must then map every old short back to its original
     * item, append the cents above the old maximum, keep the counter monotonic, and report
     * exactly the shift set as changed shorts.
     */
    private TestResult testRepairPlanRestoresOldMapping() {
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if (access == null)
            return pass("skipped: no server-side RegistryAccess (client-only / early-init)");

        Object[] state = buildSyntheticCorruptedState(access);
        if (state == null)
            return fail("could not build the synthetic corrupted state");
        @SuppressWarnings("unchecked")
        Map<ItemID, ItemStack> corruptedMap = (Map<ItemID, ItemStack>) state[0];
        @SuppressWarnings("unchecked")
        Map<ItemID, ItemID> aliases = (Map<ItemID, ItemID>) state[1];
        @SuppressWarnings("unchecked")
        LinkedHashMap<Short, ItemStack> oldMapping = (LinkedHashMap<Short, ItemStack>) state[2];

        int persistedCounter = corruptedMap.size() + 500; // realistic: counter past every used short
        ItemIDWorldRepair.RepairPlan plan =
                ItemIDWorldRepair.buildRepairPlan(corruptedMap, aliases, persistedCounter, access);

        TestResult r = assertEquals("cross-validation passes on consistent evidence",
                List.of(), plan.validationFailures());
        if (!r.passed()) return r;

        // (a) Every old short resolves to its original item again.
        int maxOldShort = 0;
        for (Map.Entry<Short, ItemStack> entry : oldMapping.entrySet()) {
            ItemStack repairedTemplate = plan.repairedMap().get(new ItemID(entry.getKey()));
            r = assertTrue("old short #" + Short.toUnsignedInt(entry.getKey())
                            + " resolves to its original item in the repaired map",
                    repairedTemplate != null
                            && ItemStack.isSameItemSameComponents(entry.getValue(), repairedTemplate));
            if (!r.passed()) return r;
            maxOldShort = Math.max(maxOldShort, Short.toUnsignedInt(entry.getKey()));
        }

        // (b) The five cents are re-appended ABOVE the old maximum.
        for (Item cent : List.of(BankSystemItems.MONEY_CENT1.get(), BankSystemItems.MONEY_CENT5.get(),
                BankSystemItems.MONEY_CENT10.get(), BankSystemItems.MONEY_CENT20.get(),
                BankSystemItems.MONEY_CENT50.get())) {
            Short appendedShort = null;
            for (Map.Entry<ItemID, ItemStack> entry : plan.appended().entrySet())
                if (entry.getValue().getItem() == cent)
                    appendedShort = entry.getKey().getShort();
            r = assertTrue("cent item is re-appended above the old maximum",
                    appendedShort != null && Short.toUnsignedInt(appendedShort) > maxOldShort);
            if (!r.passed()) return r;
        }

        // (c) Counter: max(persistedCounter, max repaired short + 1) — never decreases.
        int maxRepairedShort = plan.repairedMap().keySet().stream()
                .mapToInt(id -> Short.toUnsignedInt(id.getShort())).max().orElse(0);
        r = assertEquals("new counter is max(persisted, max repaired short + 1)",
                Math.max(persistedCounter, maxRepairedShort + 1), plan.newNextShortCounter());
        if (!r.passed()) return r;

        // (d) changedShorts == exactly the set of shorts whose resolved item differs between
        // the corrupted and the repaired map (computed independently here).
        TreeSet<Short> expectedChanged = new TreeSet<>();
        java.util.Set<Short> allShorts = new java.util.HashSet<>();
        for (ItemID id : corruptedMap.keySet()) allShorts.add(id.getShort());
        for (ItemID id : plan.repairedMap().keySet()) allShorts.add(id.getShort());
        for (Short shortValue : allShorts) {
            ItemStack before = corruptedMap.get(new ItemID(shortValue));
            ItemStack after = plan.repairedMap().get(new ItemID(shortValue));
            if ((before == null) != (after == null)
                    || (before != null && !ItemStack.isSameItemSameComponents(before, after)))
                expectedChanged.add(shortValue);
        }
        r = assertEquals("changedShorts equals the independently computed shift set",
                expectedChanged, plan.changedShorts());
        if (!r.passed()) return r;

        // (e) The fingerprint alias is scheduled for removal.
        ItemID aliasSource = aliases.keySet().iterator().next();
        r = assertTrue("the fingerprint alias is in aliasesToDrop",
                plan.aliasesToDrop().contains(aliasSource));
        if (!r.passed()) return r;
        return pass("repair plan restores the old mapping, appends cents above the old max, "
                + "keeps the counter monotonic and reports the exact shift set");
    }

    /**
     * Test 9: inconsistent evidence — a money fingerprint alias whose source contradicts the
     * candidate old mapping — must be refused: non-empty validation failures, no repaired map.
     */
    private TestResult testRepairPlanRefusesInconsistentEvidence() {
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if (access == null)
            return pass("skipped: no server-side RegistryAccess (client-only / early-init)");

        Object[] state = buildSyntheticCorruptedState(access);
        if (state == null)
            return fail("could not build the synthetic corrupted state");
        @SuppressWarnings("unchecked")
        Map<ItemID, ItemStack> corruptedMap = (Map<ItemID, ItemStack>) state[0];
        @SuppressWarnings("unchecked")
        LinkedHashMap<Short, ItemStack> oldMapping = (LinkedHashMap<Short, ItemStack>) state[2];
        @SuppressWarnings("unchecked")
        LinkedHashMap<Short, ItemStack> freshMapping = (LinkedHashMap<Short, ItemStack>) state[3];

        Item money = BankSystemItems.MONEY.get();
        Short oldMoneyShort = shortOf(oldMapping, money);
        Short freshMoneyShort = shortOf(freshMapping, money);
        if (oldMoneyShort == null || freshMoneyShort == null)
            return fail("base money missing from a simulation");
        // CONTRADICTION: the alias source is NOT the old money short (off by one) — the
        // firewall must reject the whole plan (the evidence does not fit the candidates).
        Map<ItemID, ItemID> inconsistentAliases = Map.of(
                new ItemID((short) (oldMoneyShort + 1)), new ItemID(freshMoneyShort));

        ItemIDWorldRepair.RepairPlan plan = ItemIDWorldRepair.buildRepairPlan(
                corruptedMap, inconsistentAliases, corruptedMap.size() + 1, access);

        TestResult r = assertTrue("validation failures are reported",
                !plan.validationFailures().isEmpty());
        if (!r.passed()) return r;
        r = assertTrue("no repaired map is offered on inconsistent evidence",
                plan.repairedMap().isEmpty());
        if (!r.passed()) return r;
        r = assertTrue("no appended entries are offered either", plan.appended().isEmpty());
        if (!r.passed()) return r;
        return pass("cross-validation firewall refuses inconsistent evidence — no repair offered");
    }

    /**
     * Test 10: idempotence — after applying the plan (repaired map installed, dropped
     * aliases removed), detection must return empty: a repaired world is a healthy world.
     */
    private TestResult testRepairIsIdempotentDetectEmptyAfterApply() {
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if (access == null)
            return pass("skipped: no server-side RegistryAccess (client-only / early-init)");

        Object[] state = buildSyntheticCorruptedState(access);
        if (state == null)
            return fail("could not build the synthetic corrupted state");
        @SuppressWarnings("unchecked")
        Map<ItemID, ItemStack> corruptedMap = (Map<ItemID, ItemStack>) state[0];
        @SuppressWarnings("unchecked")
        Map<ItemID, ItemID> aliases = (Map<ItemID, ItemID>) state[1];

        ItemIDWorldRepair.RepairPlan plan = ItemIDWorldRepair.buildRepairPlan(
                corruptedMap, aliases, corruptedMap.size() + 1, access);
        TestResult r = assertEquals("plan is valid", List.of(), plan.validationFailures());
        if (!r.passed()) return r;

        // Apply the plan the same way applyCorruptionRepairGuard() does — on local maps.
        Map<ItemID, ItemStack> repairedState = new HashMap<>(plan.repairedMap());
        Map<ItemID, ItemID> repairedAliases = new HashMap<>(aliases);
        for (ItemID drop : plan.aliasesToDrop())
            repairedAliases.remove(drop);

        Optional<ItemIDWorldRepair.CorruptionEvidence> evidence =
                ItemIDWorldRepair.detect(repairedState, repairedAliases, access);
        r = assertFalse("detection is empty on the repaired state (idempotence)",
                evidence.isPresent());
        if (!r.passed()) return r;
        return pass("applying the plan yields a state that detection considers healthy");
    }

    // ========================================================================
    // 11: end-to-end guard integration through the real NBT load path
    // ========================================================================

    /**
     * Test 11 (guard integration): a synthetic corrupted state serialized through the REAL
     * NBT keys ({@code itemIDs}, {@code itemIDAliases}, {@code nextShortCounter}) and fed
     * into {@link ItemIDManager#load(CompoundTag)} must
     * <ol>
     *   <li>throw {@code ItemIDRepairRequiredException} while {@code CONFIRM_ITEMID_REPAIR}
     *       is {@code false} — proving the guard sees the RAW persisted alias evidence even
     *       though {@code putAlias}'s invariant rejects the fingerprint (its source short is
     *       occupied by the fresh mapping), and</li>
     *   <li>quarantine the fingerprint so a subsequent {@code save()}+{@code load()} cycle
     *       — the exact shape of the one-shot post-load re-save that would otherwise strip
     *       the rejected alias from the file — STILL detects the corruption.</li>
     * </ol>
     * All static state (maps, counter, quarantine, one-shot flags) and the settings flag are
     * snapshotted and restored; the quarantine restore is critical — leaked synthetic
     * evidence would make the guard fire on the next REAL startup of this world.
     */
    private TestResult testCorruptedNbtLoadAbortsAndQuarantinesEvidence() {
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if (access == null)
            return pass("skipped: no server-side RegistryAccess (client-only / early-init)");

        // Settings flag handling: force the unconfirmed path, restore the original value
        // in finally (memory only — never persisted from a test).
        net.kroia.banksystem.BankSystemModBackend.Instances instances =
                net.kroia.banksystem.BankSystemModBackend.getInstances_forTesting();
        Boolean savedConfirmFlag = null;
        if (instances != null && instances.SERVER_SETTINGS != null) {
            savedConfirmFlag = instances.SERVER_SETTINGS.BANK.CONFIRM_ITEMID_REPAIR.get();
            instances.SERVER_SETTINGS.BANK.CONFIRM_ITEMID_REPAIR.set(false);
        }

        Map<ItemID, ItemStack> savedItemMap = new HashMap<>(ItemIDManager.getItemIDMap());
        Map<ItemID, ItemID> savedAliasMap = new HashMap<>(ItemIDManager.getItemIDAliasMap());
        int savedCounter = ItemIDManager.getNextShortCounter_forTesting();
        Map<ItemID, ItemID> savedQuarantine = ItemIDManager.getQuarantinedAliases_forTesting();
        java.util.Set<ItemID> savedPersisted = ItemIDManager.getPersistedShorts_forTesting();
        try {
            Object[] state = buildSyntheticCorruptedState(access);
            if (state == null)
                return fail("could not build the synthetic corrupted state");
            @SuppressWarnings("unchecked")
            Map<ItemID, ItemStack> corruptedMap = (Map<ItemID, ItemStack>) state[0];
            @SuppressWarnings("unchecked")
            Map<ItemID, ItemID> fingerprintAliases = (Map<ItemID, ItemID>) state[1];
            ItemID fingerprintFrom = fingerprintAliases.keySet().iterator().next();
            ItemID fingerprintTo = fingerprintAliases.get(fingerprintFrom);

            // ==== Serialize the corrupted state through the REAL persistence keys ====
            // Install the corrupted map as live state and save() it (writes itemIDs,
            // formatVersion, an empty itemIDAliases list and nextShortCounter)...
            ItemIDManager.clearQuarantinedAliases_forTesting();
            ItemIDManager.replaceState_forTesting(corruptedMap, Collections.emptyMap(),
                    corruptedMap.size() + 500);
            CompoundTag tag = new CompoundTag();
            if (!new ItemIDManager().save(tag))
                return fail("could not serialize the corrupted fixture");
            // ...then append the fingerprint alias into the real itemIDAliases list the way
            // the buggy build persisted it. (It cannot be installed via the live table —
            // the putAlias invariant would reject it, which is exactly the point.)
            net.minecraft.nbt.ListTag aliasList =
                    tag.getList("itemIDAliases", net.minecraft.nbt.Tag.TAG_COMPOUND);
            CompoundTag aliasTag = new CompoundTag();
            aliasTag.putShort("from", fingerprintFrom.getShort());
            aliasTag.putShort("to", fingerprintTo.getShort());
            aliasList.add(aliasTag);
            tag.put("itemIDAliases", aliasList);

            // ==== (a) load() must abort with ItemIDRepairRequiredException ====
            ItemIDManager.replaceState_forTesting(Collections.emptyMap(), Collections.emptyMap(), 1);
            drainLoadSignals();
            boolean threw = false;
            try {
                new ItemIDManager().load(tag);
            } catch (net.kroia.banksystem.util.ItemIDRepairRequiredException e) {
                threw = true;
                TestResult msg = assertTrue("abort report names CONFIRM_ITEMID_REPAIR and the remap",
                        e.getMessage().contains("CONFIRM_ITEMID_REPAIR")
                                && e.getMessage().contains("Proposed repair"));
                if (!msg.passed()) return msg;
            }
            TestResult r = assertTrue("load() of the corrupted NBT threw ItemIDRepairRequiredException",
                    threw);
            if (!r.passed()) return r;
            r = assertFalse("no repair was applied on the unconfirmed path",
                    ItemIDManager.consumeRepairWasApplied());
            if (!r.passed()) return r;

            // ==== (b) the raw fingerprint evidence is quarantined... ====
            Map<ItemID, ItemID> quarantine = ItemIDManager.getQuarantinedAliases_forTesting();
            r = assertTrue("the rejected fingerprint alias is quarantined",
                    fingerprintTo.equals(quarantine.get(fingerprintFrom)));
            if (!r.passed()) return r;

            // ==== ...and survives a save()+load() cycle (the re-save shape) ====
            CompoundTag resaved = new CompoundTag();
            if (!new ItemIDManager().save(resaved))
                return fail("could not re-save the post-abort state");
            r = assertTrue("the re-saved tag carries the quarantined evidence key",
                    resaved.contains(BankSystemSaveFormat.KEY_QUARANTINED_ALIASES));
            if (!r.passed()) return r;

            // Simulate a fresh process: quarantine cleared, then load the re-saved tag
            // (whose itemIDAliases no longer contains the fingerprint) — detection must
            // still fire from the quarantined evidence.
            ItemIDManager.clearQuarantinedAliases_forTesting();
            ItemIDManager.replaceState_forTesting(Collections.emptyMap(), Collections.emptyMap(), 1);
            drainLoadSignals();
            boolean threwAgain = false;
            try {
                new ItemIDManager().load(resaved);
            } catch (net.kroia.banksystem.util.ItemIDRepairRequiredException e) {
                threwAgain = true;
            }
            r = assertTrue("a save()+load() cycle still detects via the quarantined evidence",
                    threwAgain);
            if (!r.passed()) return r;
            return pass("corrupted NBT aborts the load unconfirmed, and the fingerprint evidence "
                    + "survives re-save cycles via the quarantine");
        } catch (Throwable t) {
            return fail("guard integration test threw: " + t.getClass().getSimpleName()
                    + " — " + t.getMessage());
        } finally {
            // Restore EVERYTHING: live maps/counter, quarantine, one-shot flags, settings.
            ItemIDManager.replaceState_forTesting(savedItemMap, savedAliasMap, savedCounter);
            // Quarantine restore: bit-exact (normally empty on a healthy world). Critical —
            // leaked synthetic evidence would abort the next real startup of this world.
            ItemIDManager.restoreQuarantinedAliases_forTesting(savedQuarantine);
            ItemIDManager.restorePersistedShorts_forTesting(savedPersisted);
            drainLoadSignals();
            if (instances != null && instances.SERVER_SETTINGS != null && savedConfirmFlag != null)
                instances.SERVER_SETTINGS.BANK.CONFIRM_ITEMID_REPAIR.set(savedConfirmFlag);
        }
    }

    // ========================================================================
    // 12–13: realistic multi-pair corpora (direct-pair fingerprint semantics)
    // ========================================================================

    /** The nine non-cent money items in {@code getMoneyItems()} order. */
    private static List<Item> nonCentMoneyItems() {
        return List.of(
                BankSystemItems.MONEY.get(), BankSystemItems.MONEY5.get(),
                BankSystemItems.MONEY10.get(), BankSystemItems.MONEY20.get(),
                BankSystemItems.MONEY50.get(), BankSystemItems.MONEY100.get(),
                BankSystemItems.MONEY200.get(), BankSystemItems.MONEY500.get(),
                BankSystemItems.MONEY1000.get());
    }

    /**
     * Test 12: the REALISTIC money-only corruption corpus — nine DIRECT pairs
     * {@code oldMoneyShort → freshMoneyShort} (the +5-shifted, numerically overlapping
     * shape {@code 1→6, 2→7, ..., 9→14} where targets of some pairs are sources of others).
     * Fingerprinting must report exactly the direct pairs (a chain walk would collapse
     * {@code 1→6→11} and mis-attribute MONEY to MONEY100), the cross-validation firewall
     * must PASS, and the plan must restore the old mapping.
     */
    private TestResult testMultiPairMoneyCorpusValidatesAndRepairs() {
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if (access == null)
            return pass("skipped: no server-side RegistryAccess (client-only / early-init)");

        LinkedHashMap<Short, ItemStack> oldMapping = ItemIDWorldRepair.simulateDefaultAssignment(access, false, 1);
        LinkedHashMap<Short, ItemStack> freshMapping = ItemIDWorldRepair.simulateDefaultAssignment(access, true, 1);
        Map<ItemID, ItemStack> corruptedMap = toItemIDMap(freshMapping);

        // Build the full 9-pair money corpus: old short → fresh short for every money item.
        Map<ItemID, ItemID> aliases = new HashMap<>();
        Map<Short, Short> expectedPairs = new HashMap<>();
        for (Item money : nonCentMoneyItems()) {
            Short oldShort = shortOf(oldMapping, money);
            Short freshShort = shortOf(freshMapping, money);
            if (oldShort == null || freshShort == null)
                return fail("money item missing from a simulated assignment");
            aliases.put(new ItemID(oldShort), new ItemID(freshShort));
            expectedPairs.put(oldShort, freshShort);
        }

        Optional<ItemIDWorldRepair.CorruptionEvidence> evidence =
                ItemIDWorldRepair.detect(corruptedMap, aliases, access);
        TestResult r = assertTrue("detection fires on the 9-pair money corpus", evidence.isPresent());
        if (!r.passed()) return r;
        r = assertEquals("all 9 direct pairs are reported as fingerprints",
                9, evidence.get().fingerprints().size());
        if (!r.passed()) return r;
        for (ItemIDWorldRepair.AliasFingerprint fp : evidence.get().fingerprints()) {
            Short expectedTo = expectedPairs.get(fp.from());
            r = assertTrue("fingerprint #" + Short.toUnsignedInt(fp.from())
                            + " is the DIRECT pair (no chain collapse)",
                    expectedTo != null && expectedTo == fp.to());
            if (!r.passed()) return r;
        }

        ItemIDWorldRepair.RepairPlan plan = ItemIDWorldRepair.buildRepairPlan(
                corruptedMap, aliases, corruptedMap.size() + 1, access);
        r = assertEquals("cross-validation PASSES on the overlapping multi-pair corpus",
                List.of(), plan.validationFailures());
        if (!r.passed()) return r;

        // The plan restores the complete old mapping.
        for (Map.Entry<Short, ItemStack> entry : oldMapping.entrySet()) {
            ItemStack repairedTemplate = plan.repairedMap().get(new ItemID(entry.getKey()));
            r = assertTrue("old short #" + Short.toUnsignedInt(entry.getKey())
                            + " resolves to its original item",
                    repairedTemplate != null
                            && ItemStack.isSameItemSameComponents(entry.getValue(), repairedTemplate));
            if (!r.passed()) return r;
        }
        return pass("9-pair money corpus: direct-pair fingerprints, firewall passes, old mapping restored");
    }

    /**
     * Test 13: a large-corpus-like fixture modeled on the documented real-world state
     * (~2,809 entries): the money pairs PLUS hundreds of synthetic non-money {@code s → s+5}
     * pairs forming one long overlapping ladder. Detection must fire and every money
     * fingerprint must survive — no evidence may be lost to chain-walk hop limits (the
     * direct-pair semantics have no walks at all).
     */
    private TestResult testLargePlus5CorpusLosesNoFingerprints() {
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if (access == null)
            return pass("skipped: no server-side RegistryAccess (client-only / early-init)");

        LinkedHashMap<Short, ItemStack> oldMapping = ItemIDWorldRepair.simulateDefaultAssignment(access, false, 1);
        LinkedHashMap<Short, ItemStack> freshMapping = ItemIDWorldRepair.simulateDefaultAssignment(access, true, 1);
        Map<ItemID, ItemStack> corruptedMap = toItemIDMap(freshMapping);

        // The full +5 ladder: every old short s aliased to s + 5, several hundred entries
        // (bounded by the simulated old mapping's size — a vanilla registry gives ~1300).
        int maxOldShort = oldMapping.keySet().stream().mapToInt(Short::toUnsignedInt).max().orElse(0);
        int ladderLength = Math.min(maxOldShort, 400);
        Map<ItemID, ItemID> aliases = new HashMap<>();
        for (int s = 1; s <= ladderLength; s++)
            aliases.put(new ItemID((short) s), new ItemID((short) (s + 5)));

        Optional<ItemIDWorldRepair.CorruptionEvidence> evidence =
                ItemIDWorldRepair.detect(corruptedMap, aliases, access);
        TestResult r = assertTrue("detection fires on the large +5 ladder corpus", evidence.isPresent());
        if (!r.passed()) return r;
        // Exactly the nine money pairs (fresh money block sits at 6..14, so sources 1..9)
        // must be fingerprinted — nothing dropped to hop limits, nothing extra.
        r = assertEquals("all 9 money fingerprints survive the large corpus",
                9, evidence.get().fingerprints().size());
        if (!r.passed()) return r;
        for (ItemIDWorldRepair.AliasFingerprint fp : evidence.get().fingerprints()) {
            r = assertTrue("fingerprint is a direct +5 pair", fp.to() == fp.from() + 5);
            if (!r.passed()) return r;
        }
        ItemIDWorldRepair.RepairPlan plan = ItemIDWorldRepair.buildRepairPlan(
                corruptedMap, aliases, corruptedMap.size() + 1, access);
        r = assertEquals("cross-validation passes on the large corpus",
                List.of(), plan.validationFailures());
        if (!r.passed()) return r;
        return pass("large +5 corpus: detection fires, no fingerprint lost, firewall passes");
    }

    // ========================================================================
    // 14: singleplayer cross-world isolation of the quarantine store
    // ========================================================================

    /**
     * Test 14: a fresh world created in the same JVM after leaving a corrupted world must
     * NOT inherit the corrupted world's quarantined evidence. Simulates the exact
     * singleplayer sequence: evidence in memory (world A) → {@link ItemIDManager#clear()}
     * (leave) → world A's fallback save still carries the evidence (leave-backup) → a fresh
     * world B (no file, {@code load()} never runs, live map re-populated) saves WITHOUT the
     * {@code quarantinedAliases} key.
     */
    private TestResult testFreshWorldDoesNotInheritQuarantinedEvidence() {
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if (access == null)
            return pass("skipped: no server-side RegistryAccess (client-only / early-init)");

        Map<ItemID, ItemStack> savedItemMap = new HashMap<>(ItemIDManager.getItemIDMap());
        Map<ItemID, ItemID> savedAliasMap = new HashMap<>(ItemIDManager.getItemIDAliasMap());
        int savedCounter = ItemIDManager.getNextShortCounter_forTesting();
        Map<ItemID, ItemID> savedQuarantine = ItemIDManager.getQuarantinedAliases_forTesting();
        java.util.Set<ItemID> savedPersisted = ItemIDManager.getPersistedShorts_forTesting();
        try {
            // World A (corrupted): evidence sits in the in-memory quarantine store.
            ItemIDManager.restoreQuarantinedAliases_forTesting(
                    Map.of(new ItemID((short) 1), new ItemID((short) 6)));

            // Player leaves world A: clear() must back the evidence up and empty the store.
            ItemIDManager.clear();
            TestResult r = assertTrue("quarantine store is empty after clear()",
                    ItemIDManager.getQuarantinedAliases_forTesting().isEmpty());
            if (!r.passed()) return r;

            // World A's leave-then-save fallback (live map empty → backups): the evidence
            // must still be persisted for the world it belongs to.
            CompoundTag worldASave = new CompoundTag();
            if (!new ItemIDManager().save(worldASave))
                return fail("world A fallback save failed");
            r = assertTrue("world A's fallback save still carries its quarantined evidence",
                    worldASave.contains(BankSystemSaveFormat.KEY_QUARANTINED_ALIASES));
            if (!r.passed()) return r;

            // Fresh world B: no ItemIDs.nbt exists, so load() never runs; the data handler
            // releases the registration latch via markRegistryReady() (the fresh-world
            // release in load_itemIDs — Task #16), then default registration populates a
            // non-empty live map and the first save fires. The release below is the exact
            // production release, not a test backdoor.
            ItemIDManager.markRegistryReady();
            Map<ItemID, ItemStack> fixture = buildTwoEntryFixture();
            if (fixture == null)
                return fail("could not register the fresh-world fixture templates");
            ItemIDManager.replaceState_forTesting(fixture, Collections.emptyMap(), 100);
            CompoundTag worldBSave = new CompoundTag();
            if (!new ItemIDManager().save(worldBSave))
                return fail("world B save failed");
            r = assertFalse("fresh world B's first save has NO quarantinedAliases key",
                    worldBSave.contains(BankSystemSaveFormat.KEY_QUARANTINED_ALIASES));
            if (!r.passed()) return r;
            return pass("quarantined evidence never leaks from a left world into a fresh world");
        } catch (Throwable t) {
            return fail("quarantine isolation test threw: " + t.getClass().getSimpleName()
                    + " — " + t.getMessage());
        } finally {
            ItemIDManager.replaceState_forTesting(savedItemMap, savedAliasMap, savedCounter);
            ItemIDManager.restoreQuarantinedAliases_forTesting(savedQuarantine);
            ItemIDManager.restorePersistedShorts_forTesting(savedPersisted);
            // clear() above armed the LIVE latch; the in-test markRegistryReady() normally
            // releases it, but an early assert-return in between must not leave the live
            // master latched (every registration refused until restart).
            ItemIDManager.markRegistryReady();
            drainLoadSignals();
        }
    }

    // ========================================================================
    // 15–16: Task #16 — boot-order candidate firewall + renumbering tripwire
    // ========================================================================

    /**
     * Test 15 (Task #16 Fix D, cross-validation): a corrupted state whose fresh assignment
     * has the historical <b>boot-order</b> shape — {@code ServerBankManager} constructor
     * prefix (blacklist, not-removable, allowed items) then {@code createDefaultItemIDs()},
     * see {@link ItemIDWorldRepair#simulateBootOrderAssignment} — must
     * <ol>
     *   <li>be detected (the boot-order money block is a valid signal-b signature),</li>
     *   <li>PASS the extended firewall (validation failures empty), and</li>
     *   <li>yield a plan that restores the boot-order OLD mapping.</li>
     * </ol>
     * The pre-existing createDefaultItemIDs-shape fixture must STILL pass (regression half —
     * also covered by tests 8/12, re-asserted here so this test documents the exact
     * one-candidate-only contract). Pure — never touches live state.
     */
    private TestResult testBootOrderCorruptedShapePassesExtendedFirewall() {
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if (access == null)
            return pass("skipped: no server-side RegistryAccess (client-only / early-init)");

        // ==== Boot-order corrupted fixture: map = fresh boot assignment F_B, alias = ====
        // ==== money fingerprint old(O_B) -> fresh(F_B) for the base money item.      ====
        LinkedHashMap<Short, ItemStack> oldBoot = ItemIDWorldRepair.simulateBootOrderAssignment(access, false, 1);
        LinkedHashMap<Short, ItemStack> freshBoot = ItemIDWorldRepair.simulateBootOrderAssignment(access, true, 1);
        Item money = BankSystemItems.MONEY.get();
        Short oldMoneyShort = shortOf(oldBoot, money);
        Short freshMoneyShort = shortOf(freshBoot, money);
        if (oldMoneyShort == null || freshMoneyShort == null)
            return fail("base money missing from a simulated boot-order assignment");
        Map<ItemID, ItemStack> corruptedMap = toItemIDMap(freshBoot);
        Map<ItemID, ItemID> aliases = new HashMap<>();
        aliases.put(new ItemID(oldMoneyShort), new ItemID(freshMoneyShort));

        // (1) Detection fires on the boot-order money-block shape.
        Optional<ItemIDWorldRepair.CorruptionEvidence> evidence =
                ItemIDWorldRepair.detect(corruptedMap, aliases, access);
        TestResult r = assertTrue("detection fires on the boot-order corrupted shape",
                evidence.isPresent());
        if (!r.passed()) return r;

        // (2) The extended firewall accepts the boot-order candidate.
        ItemIDWorldRepair.RepairPlan plan = ItemIDWorldRepair.buildRepairPlan(
                corruptedMap, aliases, corruptedMap.size() + 1, access);
        r = assertEquals("cross-validation PASSES on the boot-order shape",
                List.of(), plan.validationFailures());
        if (!r.passed()) return r;

        // (3) The plan restores the boot-order OLD mapping (spot-check every old short).
        for (Map.Entry<Short, ItemStack> entry : oldBoot.entrySet()) {
            ItemStack repairedTemplate = plan.repairedMap().get(new ItemID(entry.getKey()));
            r = assertTrue("boot-order old short #" + Short.toUnsignedInt(entry.getKey())
                            + " resolves to its original item in the repaired map",
                    repairedTemplate != null
                            && ItemStack.isSameItemSameComponents(entry.getValue(), repairedTemplate));
            if (!r.passed()) return r;
        }

        // ==== Regression half: the old createDefaultItemIDs-shape fixture still passes ====
        Object[] state = buildSyntheticCorruptedState(access);
        if (state == null)
            return fail("could not build the default-order synthetic corrupted state");
        @SuppressWarnings("unchecked")
        Map<ItemID, ItemStack> defaultShapeMap = (Map<ItemID, ItemStack>) state[0];
        @SuppressWarnings("unchecked")
        Map<ItemID, ItemID> defaultShapeAliases = (Map<ItemID, ItemID>) state[1];
        ItemIDWorldRepair.RepairPlan defaultShapePlan = ItemIDWorldRepair.buildRepairPlan(
                defaultShapeMap, defaultShapeAliases, defaultShapeMap.size() + 1, access);
        r = assertEquals("the createDefaultItemIDs-shape fixture STILL passes the extended firewall",
                List.of(), defaultShapePlan.validationFailures());
        if (!r.passed()) return r;

        return pass("boot-order corrupted shape is detected, passes the extended firewall and "
                + "repairs to the boot-order old mapping; the default-order shape still passes");
    }

    /**
     * Test 16 (Task #16 Fix D, renumbering tripwire): the short→item-name digest must
     * <ul>
     *   <li>be SILENT on a healthy round-trip (digest built, persisted through the real NBT
     *       key shape, re-read, diffed against an unchanged registry → no mismatches),</li>
     *   <li>REPORT a short whose resolved item changed since the digest was taken, and</li>
     *   <li>stay silent for shorts that no longer resolve at all (dropped entries have
     *       their own load-time WARN — "gone" is not "rebound").</li>
     * </ul>
     * Exercises {@link ItemIDManager#buildShortNameDigest()} /
     * {@link ItemIDManager#diffShortNameDigest(ListTag)} — the exact functions
     * {@code save_metadata()} / {@code loadAll()} wire to {@code Meta_data.nbt}.
     */
    private TestResult testDigestTripwireReportsRenumberedShorts() {
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if (access == null)
            return pass("skipped: no server-side RegistryAccess (client-only / early-init)");

        Map<ItemID, ItemStack> savedItemMap = new HashMap<>(ItemIDManager.getItemIDMap());
        Map<ItemID, ItemID> savedAliasMap = new HashMap<>(ItemIDManager.getItemIDAliasMap());
        int savedCounter = ItemIDManager.getNextShortCounter_forTesting();
        try {
            Map<ItemID, ItemStack> fixture = buildTwoEntryFixture(); // shorts 7 and 9
            if (fixture == null)
                return fail("could not register the synthetic fixture templates");
            ItemIDManager.replaceState_forTesting(fixture, Collections.emptyMap(), 100);

            // Build the digest and round-trip it through the real Meta_data.nbt key shape.
            ListTag digest = ItemIDManager.buildShortNameDigest();
            TestResult r = assertEquals("digest has one entry per registry mapping",
                    fixture.size(), digest.size());
            if (!r.passed()) return r;
            CompoundTag metaShaped = new CompoundTag();
            metaShaped.put(BankSystemSaveFormat.KEY_ITEM_ID_DIGEST, digest);
            ListTag reRead = metaShaped.getList(BankSystemSaveFormat.KEY_ITEM_ID_DIGEST,
                    net.minecraft.nbt.Tag.TAG_COMPOUND);

            // Healthy round-trip: registry unchanged → silent.
            r = assertTrue("healthy round-trip yields no mismatches",
                    ItemIDManager.diffShortNameDigest(reRead).isEmpty());
            if (!r.passed()) return r;
            r = assertTrue("null digest (older save) is silent",
                    ItemIDManager.diffShortNameDigest(null).isEmpty());
            if (!r.passed()) return r;

            // Mutate: short 7 now resolves to a DIFFERENT item (stone instead of paper).
            Map<ItemID, ItemStack> mutated = new ConcurrentHashMap<>(fixture);
            mutated.put(new ItemID((short) 7), new ItemStack(Items.STONE));
            ItemIDManager.replaceState_forTesting(mutated, Collections.emptyMap(), 100);
            List<String> mismatches = ItemIDManager.diffShortNameDigest(reRead);
            r = assertEquals("exactly the renumbered short is reported", 1, mismatches.size());
            if (!r.passed()) return r;
            r = assertTrue("the report names the short, the previous and the current item",
                    mismatches.getFirst().contains("#7")
                            && mismatches.getFirst().contains("minecraft:paper")
                            && mismatches.getFirst().contains("minecraft:stone"));
            if (!r.passed()) return r;

            // Dropped short: entry 9 removed entirely → not reported (gone, not rebound).
            Map<ItemID, ItemStack> dropped = new ConcurrentHashMap<>(fixture);
            dropped.entrySet().removeIf(e -> e.getKey().getShort() == 9);
            ItemIDManager.replaceState_forTesting(dropped, Collections.emptyMap(), 100);
            r = assertTrue("a dropped (unresolvable) short is not reported as renumbered",
                    ItemIDManager.diffShortNameDigest(reRead).isEmpty());
            if (!r.passed()) return r;

            return pass("digest tripwire reports renumbered shorts, stays silent on healthy "
                    + "round-trips, absent digests and dropped shorts");
        } catch (Throwable t) {
            return fail("digest tripwire test threw: " + t.getClass().getSimpleName()
                    + " — " + t.getMessage());
        } finally {
            ItemIDManager.replaceState_forTesting(savedItemMap, savedAliasMap, savedCounter);
        }
    }
}
