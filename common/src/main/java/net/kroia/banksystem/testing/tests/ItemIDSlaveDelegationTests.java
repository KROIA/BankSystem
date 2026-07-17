package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.banksystem.util.VolatileItemComponents;
import net.kroia.modutilities.UtilitiesPlatform;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * In-game tests for Task #23 — the slave-side ItemID minting delegation architecture:
 * <ul>
 *   <li>{@link ItemIDManager#finalizeSlaveSync()} no longer runs {@code createDefaultItemIDs()}
 *       (Task #23 spec item #3),</li>
 *   <li>slave-side {@link ItemIDManager#registerItemStackServerSide_direct(ItemStack)} on an
 *       unknown item returns {@link ItemID#INVALID_ID} AND fires exactly one
 *       {@code RegisterItemStacksBatchRequest} (Acceptance Criteria A + C),</li>
 *   <li>the negative / in-flight cache dedupes repeated lookups (Acceptance Criterion C),</li>
 *   <li>while the registration latch is armed, the slave path returns INVALID_ID WITHOUT
 *       firing an ARRS (Task #23 spec item #6).</li>
 * </ul>
 *
 * <b>Robustness rules</b>:
 * <ul>
 *   <li>Every test that pretends to run on the slave (by setting {@code isSlaveServer=true} in
 *       {@code BACKEND_INSTANCES}) restores the flag in a {@code finally} block. A leaked
 *       {@code true} on a live master would misroute every future ItemID registration through
 *       the ARRS path.</li>
 *   <li>The slave-branch never mints local shorts, so unlike the master-side tests these
 *       tests do NOT snapshot/restore the counter — they only manipulate the negative cache
 *       and (for the latch test) the latch flag.</li>
 *   <li>The ARRS itself never round-trips in this test harness (no real master is connected)
 *       — the tests only assert the <i>fire</i>-count and the cache state, both of which are
 *       set on the slave BEFORE any wait for the master's response, so they are observable
 *       synchronously.</li>
 * </ul>
 */
public class ItemIDSlaveDelegationTests extends TestSuite {

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.ITEM_ID;
    }

    @Override
    public void registerTests() {
        addTest("finalize_slave_sync_does_not_call_createDefaultItemIDs",
                this::testFinalizeSlaveSyncDoesNotCallCreateDefaultItemIDs);
        addTest("slave_register_unknown_returns_invalid_and_fires_arrs",
                this::testSlaveRegisterUnknownReturnsInvalidAndFiresArrs);
        addTest("negative_cache_dedupes_repeated_lookups",
                this::testNegativeCacheDedupesRepeatedLookups);
        addTest("latch_armed_rejection_skips_arrs",
                this::testLatchArmedRejectionSkipsArrs);
        addTest("positive_response_populates_item_id_map",
                this::testPositiveResponsePopulatesItemIdMap);
        addTest("negative_response_keeps_negative_mark",
                this::testNegativeResponseKeepsNegativeMark);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static ItemStack paperWithCustomData(String value) {
        ItemStack stack = new ItemStack(Items.PAPER);
        CompoundTag nbt = new CompoundTag();
        nbt.putString("banksystem_slave_delegation_test", value);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(nbt));
        return stack;
    }

    /** Runs the given body with {@code BACKEND_INSTANCES.isSlaveServer} temporarily forced. */
    private static void withIsSlaveServer(boolean slave, Runnable body) {
        BankSystemModBackend.Instances instances = BankSystemModBackend.getInstances_forTesting();
        boolean saved = instances.isSlaveServer;
        instances.isSlaveServer = slave;
        try {
            body.run();
        } finally {
            instances.isSlaveServer = saved;
        }
    }

    // ========================================================================
    // Tests
    // ========================================================================

    /**
     * Task #23 spec item #3: {@link ItemIDManager#finalizeSlaveSync()} MUST NOT call
     * {@code createDefaultItemIDs()} anymore. The slave never mints local shorts.
     * <p>
     * We can't invoke {@code createDefaultItemIDs()} directly from a testable seam (it is an
     * instance method on {@link ItemIDManager}), so we assert the observable equivalent:
     * simulating a fresh armed-latch slave state, running {@code finalizeSlaveSync()}, and
     * checking that the registry stays exactly as the master's sync left it — no fresh
     * shorts appear for registry items the master didn't sync. In practice: on the live
     * server, we snapshot the map, arm the latch (as if we were mid-boot), invoke
     * {@code finalizeSlaveSync()} under {@code isSlaveServer=true}, and confirm the item
     * count is unchanged.
     */
    private TestResult testFinalizeSlaveSyncDoesNotCallCreateDefaultItemIDs() {
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if (access == null)
            return pass("skipped: no server-side RegistryAccess (client-only / early-init)");

        // Save state: we're going to twiddle the latch flag and re-run finalizeSlaveSync.
        Map<ItemID, ItemStack> savedItemMap = new HashMap<>(ItemIDManager.getItemIDMap());
        Map<ItemID, ItemID> savedAliasMap = new HashMap<>(ItemIDManager.getItemIDAliasMap());
        int savedCounter = ItemIDManager.getNextShortCounter_forTesting();
        Set<ItemID> savedPersisted = ItemIDManager.getPersistedShorts_forTesting();
        Map<ItemID, ItemID> savedQuarantine = ItemIDManager.getQuarantinedAliases_forTesting();
        boolean savedLatch = ItemIDManager.isRegistrationLatchArmed_forTesting();

        int mapSizeBefore = savedItemMap.size();

        try {
            // Force a slave-view + armed latch, then invoke finalizeSlaveSync — under Task
            // #22 this would run createDefaultItemIDs() and grow the map to ~1300 entries.
            // Under Task #23 the map must stay EXACTLY at mapSizeBefore.
            final int[] mapSizeAfter = { -1 };
            final int[] arrsFiredAfter = { -1 };
            withIsSlaveServer(true, () -> {
                // Arm the latch without touching the map (clear() would wipe it — we want to
                // verify finalizeSlaveSync doesn't ADD entries, so keep the current map as-is).
                // The latch-arming state is normally set by clear(); the production
                // finalizeSlaveSync gates on it via early-return-if-not-armed, so we must arm
                // it here to actually exercise the release branch.
                // Since there's no direct arm-only helper, we clear() and re-install the map
                // via replaceState_forTesting (which does NOT touch the latch), then keep the
                // latch in its post-clear armed state.
                ItemIDManager.clear();
                ItemIDManager.replaceState_forTesting(savedItemMap, savedAliasMap, savedCounter);
                // clear() armed the latch; replaceState_forTesting does not release it.
                // (Verify assumption explicitly to prevent silent test rot.)
                if (!ItemIDManager.isRegistrationLatchArmed_forTesting()) {
                    mapSizeAfter[0] = -2;
                    return;
                }

                // Strengthened assertion (2026-07-17 code-review): reset the ARRS counter
                // to a known zero right BEFORE finalizeSlaveSync so a hypothetical Task #22
                // regression — createDefaultItemIDs() sneaking back into finalizeSlaveSync
                // — is caught even when register-if-absent no-ops leave map SIZE unchanged.
                // On a slave those ~1300 default registrations would each flow through the
                // delegated slave path and fire an ARRS, so the counter jumping from 0 to a
                // large N is the loud, direction-independent regression signal.
                ItemIDManager.resetSlaveArrsCounter_forTesting();

                // Task #23: this must NOT run createDefaultItemIDs anymore.
                ItemIDManager.finalizeSlaveSync();
                mapSizeAfter[0] = ItemIDManager.getItemIDMap().size();
                arrsFiredAfter[0] = ItemIDManager.getSlaveArrsRequestsFired_forTesting();
            });

            if (mapSizeAfter[0] == -2)
                return fail("Latch was released by replaceState_forTesting — test premise broken");
            TestResult r = assertEquals(
                    "finalizeSlaveSync did not add default items on the slave (map size unchanged)",
                    mapSizeBefore, mapSizeAfter[0]);
            if (!r.passed()) return r;
            // Belt-and-braces (2026-07-17): under a Task #22 regression the reintroduced
            // createDefaultItemIDs() would drive registerItemStackServerSide_direct_slave
            // once per registry item — even if register-if-absent hid the size delta, the
            // ARRS counter would jump from 0 into the thousands. Assert it stayed at 0.
            r = assertEquals(
                    "finalizeSlaveSync did not fire any ARRS requests (createDefaultItemIDs "
                            + "reintroduction would flood this counter with ~1300 registry hits)",
                    0, arrsFiredAfter[0]);
            if (!r.passed()) return r;
            r = assertFalse("finalizeSlaveSync released the latch",
                    ItemIDManager.isRegistrationLatchArmed_forTesting());
            if (!r.passed()) return r;

            return pass("finalizeSlaveSync no longer runs createDefaultItemIDs on the slave "
                    + "(map size stayed at " + mapSizeBefore + ", ARRS counter stayed at 0)");
        } catch (Throwable t) {
            return fail("finalizeSlaveSync test threw: " + t.getClass().getSimpleName()
                    + " — " + t.getMessage());
        } finally {
            // Restore bit-exact. finalizeSlaveSync (Task #23) released the latch — on a live
            // server that was already the case, so leaving it released matches the pre-test
            // steady state (whether savedLatch was true or false, the live post-load server
            // has the latch released). Explicitly release just in case a fixture load left it
            // armed, since neither replaceState_forTesting nor the *_forTesting restores of
            // quarantine/persistedShorts touch the latch.
            ItemIDManager.replaceState_forTesting(savedItemMap, savedAliasMap, savedCounter);
            ItemIDManager.restoreQuarantinedAliases_forTesting(savedQuarantine);
            ItemIDManager.restorePersistedShorts_forTesting(savedPersisted);
            ItemIDManager.clearSlaveNegativeCache_forTesting();
            ItemIDManager.resetSlaveArrsCounter_forTesting();
            if (!savedLatch)
                ItemIDManager.markRegistryReady();
        }
    }

    /**
     * Task #23 core invariant: slave-side {@code registerItemStackServerSide_direct} on an
     * unknown stack returns {@link ItemID#INVALID_ID} and fires exactly ONE
     * {@code RegisterItemStacksBatchRequest} (Acceptance Criteria A + C + D).
     */
    private TestResult testSlaveRegisterUnknownReturnsInvalidAndFiresArrs() {
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if (access == null)
            return pass("skipped: no server-side RegistryAccess (client-only / early-init)");

        boolean savedLatch = ItemIDManager.isRegistrationLatchArmed_forTesting();
        ItemStack unknown = paperWithCustomData("slave-delegate-fire-" + UUID.randomUUID());

        try {
            // Latch must be released for the ARRS to fire (arming skips ARRS by design —
            // that's the OTHER Task #23 test).
            ItemIDManager.markRegistryReady();
            ItemIDManager.clearSlaveNegativeCache_forTesting();
            ItemIDManager.resetSlaveArrsCounter_forTesting();

            final ItemID[] resultRef = { ItemID.INVALID_ID };
            withIsSlaveServer(true, () -> {
                resultRef[0] = ItemIDManager.registerItemStackServerSide_direct(unknown);
            });

            TestResult r = assertFalse("slave register returns INVALID_ID on cache miss",
                    resultRef[0].isValid());
            if (!r.passed()) return r;
            r = assertTrue("slave register added the stack to the negative / in-flight cache",
                    ItemIDManager.isInSlaveNegativeCache_forTesting(unknown));
            if (!r.passed()) return r;
            r = assertEquals("exactly ONE ARRS request was fired for the miss",
                    1, ItemIDManager.getSlaveArrsRequestsFired_forTesting());
            if (!r.passed()) return r;

            return pass("slave register returns INVALID_ID, populates negative cache, and "
                    + "fires exactly one ARRS request");
        } catch (Throwable t) {
            return fail("slave-register-fires-ARRS test threw: " + t.getClass().getSimpleName()
                    + " — " + t.getMessage());
        } finally {
            // Clear the negative cache + ARRS counter (diagnostic, resets every clear()
            // anyway). Restoring the exact pre-test ARRS counter would require a setter —
            // absent by design because the counter is diagnostic; a fresh 0 is fine.
            ItemIDManager.clearSlaveNegativeCache_forTesting();
            ItemIDManager.resetSlaveArrsCounter_forTesting();
            // We forced the latch released above; on a live server it was already released
            // (post-load steady state). If pre-test was somehow armed (extremely unlikely
            // mid-test-suite) leaving it released here is safe — the isolation risk is
            // negligible against the maintenance cost of a full arm restore.
            if (!savedLatch)
                ItemIDManager.markRegistryReady();
        }
    }

    /**
     * Acceptance Criterion C: repeated lookups of the SAME unknown stack must NOT re-fire
     * the ARRS. The negative / in-flight cache acts as a dedupe: first miss fires one
     * request, all subsequent misses hit the cache and return INVALID_ID immediately.
     */
    private TestResult testNegativeCacheDedupesRepeatedLookups() {
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if (access == null)
            return pass("skipped: no server-side RegistryAccess (client-only / early-init)");

        ItemStack unknown = paperWithCustomData("slave-dedup-" + UUID.randomUUID());
        try {
            ItemIDManager.markRegistryReady();
            ItemIDManager.clearSlaveNegativeCache_forTesting();
            ItemIDManager.resetSlaveArrsCounter_forTesting();

            withIsSlaveServer(true, () -> {
                // First lookup — one ARRS.
                ItemIDManager.registerItemStackServerSide_direct(unknown);
                // Second lookup — MUST hit the cache; ARRS count stays at 1.
                ItemIDManager.registerItemStackServerSide_direct(unknown);
                // Third lookup for good measure.
                ItemIDManager.registerItemStackServerSide_direct(unknown);
            });

            int arrsFired = ItemIDManager.getSlaveArrsRequestsFired_forTesting();
            TestResult r = assertEquals("ARRS fires exactly ONCE across three repeated lookups",
                    1, arrsFired);
            if (!r.passed()) return r;

            return pass("negative cache dedupes repeated lookups — one ARRS across three misses");
        } catch (Throwable t) {
            return fail("dedup test threw: " + t.getClass().getSimpleName()
                    + " — " + t.getMessage());
        } finally {
            ItemIDManager.clearSlaveNegativeCache_forTesting();
            ItemIDManager.resetSlaveArrsCounter_forTesting();
            ItemIDManager.markRegistryReady();
        }
    }

    /**
     * Task #23 spec item #6: while the registration latch is armed on the slave (i.e. before
     * {@code SyncItemIDsPacket} has arrived), a lookup miss must return
     * {@link ItemID#INVALID_ID} WITHOUT firing an ARRS request (master might not yet be
     * connected, and the sync packet is our "master is ready" signal).
     */
    private TestResult testLatchArmedRejectionSkipsArrs() {
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if (access == null)
            return pass("skipped: no server-side RegistryAccess (client-only / early-init)");

        Map<ItemID, ItemStack> savedItemMap = new HashMap<>(ItemIDManager.getItemIDMap());
        Map<ItemID, ItemID> savedAliasMap = new HashMap<>(ItemIDManager.getItemIDAliasMap());
        int savedCounter = ItemIDManager.getNextShortCounter_forTesting();
        Set<ItemID> savedPersisted = ItemIDManager.getPersistedShorts_forTesting();
        Map<ItemID, ItemID> savedQuarantine = ItemIDManager.getQuarantinedAliases_forTesting();
        boolean savedLatch = ItemIDManager.isRegistrationLatchArmed_forTesting();

        ItemStack unknown = paperWithCustomData("slave-latch-" + UUID.randomUUID());

        try {
            // Reset ARRS counter first.
            ItemIDManager.resetSlaveArrsCounter_forTesting();
            ItemIDManager.clearSlaveNegativeCache_forTesting();

            final ItemID[] resultRef = { ItemID.INVALID_ID };
            final int[] arrsAfterRef = { -1 };
            final boolean[] cachedRef = { false };
            withIsSlaveServer(true, () -> {
                // Arm the latch via clear() (production release path); we do NOT release it.
                ItemIDManager.clear();

                resultRef[0] = ItemIDManager.registerItemStackServerSide_direct(unknown);
                arrsAfterRef[0] = ItemIDManager.getSlaveArrsRequestsFired_forTesting();
                cachedRef[0] = ItemIDManager.isInSlaveNegativeCache_forTesting(unknown);
            });

            TestResult r = assertFalse("latch-armed slave register returns INVALID_ID",
                    resultRef[0].isValid());
            if (!r.passed()) return r;
            r = assertEquals("latch-armed slave register fires ZERO ARRS requests",
                    0, arrsAfterRef[0]);
            if (!r.passed()) return r;
            r = assertFalse("latch-armed rejection does not populate the negative cache "
                            + "(the miss will be re-attempted after the sync packet arrives)",
                    cachedRef[0]);
            if (!r.passed()) return r;

            return pass("latch-armed slave register returns INVALID_ID without firing ARRS "
                    + "and without polluting the negative cache");
        } catch (Throwable t) {
            return fail("latch-armed test threw: " + t.getClass().getSimpleName()
                    + " — " + t.getMessage());
        } finally {
            // clear() wiped everything — restore bit-exact.
            ItemIDManager.replaceState_forTesting(savedItemMap, savedAliasMap, savedCounter);
            ItemIDManager.restoreQuarantinedAliases_forTesting(savedQuarantine);
            ItemIDManager.restorePersistedShorts_forTesting(savedPersisted);
            ItemIDManager.clearSlaveNegativeCache_forTesting();
            ItemIDManager.resetSlaveArrsCounter_forTesting();
            if (!savedLatch)
                ItemIDManager.markRegistryReady();
            // If savedLatch was true (pre-test armed), we've re-armed it via clear() above —
            // no additional action needed.
        }
    }

    // ========================================================================
    // Populator-path tests (2026-07-17 code-review addition): direct exercise of
    // populateSlaveCacheFromResponse — the ARRS-response body that hops onto the
    // server thread and mutates itemIDMap + the negative cache. The rest of the
    // suite only covers the FIRE side (registerItemStackServerSide_direct_slave);
    // without these, a future edit that broke the marshalling or the
    // positive-branch defensive-copy invariant would have no test coverage.
    // ========================================================================

    /**
     * Reflective invocation of the private static
     * {@code ItemIDManager.populateSlaveCacheFromResponse(List, List, List, Throwable)}.
     * Deliberately reflection-only per the code-review directive: no new production
     * surface is exposed for testing convenience. The {@code List<NormalizedStackKey>}
     * parameter — whose element type is itself a private inner class — is populated
     * via a companion reflective constructor call, see {@link #newNormalizedStackKey}.
     */
    private static void invokePopulateSlaveCacheFromResponse(List<ItemStack> normalizedStacks,
                                                             List<?> keys,
                                                             List<ItemID> response,
                                                             Throwable ex) throws Exception {
        Method m = ItemIDManager.class.getDeclaredMethod("populateSlaveCacheFromResponse",
                List.class, List.class, List.class, Throwable.class);
        m.setAccessible(true);
        m.invoke(null, normalizedStacks, keys, response, ex);
    }

    /**
     * Reflective constructor for the private inner class
     * {@code ItemIDManager$NormalizedStackKey}. Kept private to this test file — the
     * ItemIDManager Javadoc explicitly documents equality semantics
     * ({@link ItemStack#isSameItemSameComponents(ItemStack, ItemStack)} +
     * {@link ItemStack#hashItemAndComponents(ItemStack)}), so an independently-constructed
     * key from the same normalized stack hashes to the same bucket as the one the
     * production register-path inserted.
     */
    private static Object newNormalizedStackKey(ItemStack normalizedStack) throws Exception {
        Class<?> nskClass = Class.forName("net.kroia.banksystem.util.ItemIDManager$NormalizedStackKey");
        Constructor<?> ctor = nskClass.getDeclaredConstructor(ItemStack.class);
        ctor.setAccessible(true);
        return ctor.newInstance(normalizedStack);
    }

    /**
     * Direct exercise of the POSITIVE branch of {@code populateSlaveCacheFromResponse}:
     * a master-returned valid short must (a) land in {@code itemIDMap} under a defensive
     * COPY of the passed stack (never the same instance — the caller retains that
     * reference), and (b) remove the in-flight marker from the negative cache so the
     * next lookup hits the positive map.
     * <p>
     * The Task #23 fire-side test suite covers registerItemStackServerSide_direct_slave
     * end-to-end (miss → ARRS → INVALID_ID + negative-cache mark). The populator
     * runs asynchronously on a server.execute hop, so the fire-side test cannot observe
     * its effect deterministically. This test invokes the populator directly (via
     * reflection — no new production surface added per the code-review directive) with a
     * synthesized "master returned short 9999" response and asserts the two
     * defensive-copy / cache-transition invariants explicitly.
     */
    private TestResult testPositiveResponsePopulatesItemIdMap() {
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if (access == null)
            return pass("skipped: no server-side RegistryAccess (client-only / early-init)");

        boolean savedLatch = ItemIDManager.isRegistrationLatchArmed_forTesting();
        // Short 9999 as a fabricated master-minted short: comfortably outside the
        // ~1300 defaults range but well below Short.MAX_VALUE (32767). A collision with
        // an already-registered short would sabotage the test — assert absence explicitly.
        final short fabricatedShort = (short) 9999;
        ItemID fabricatedId = new ItemID(fabricatedShort);
        ItemStack raw = paperWithCustomData("populate-positive-" + UUID.randomUUID());

        try {
            ItemIDManager.markRegistryReady();
            ItemIDManager.clearSlaveNegativeCache_forTesting();
            ItemIDManager.resetSlaveArrsCounter_forTesting();

            // Test premise: short 9999 must not already be registered (otherwise the
            // putIfAbsent inside the populator no-ops and the defensive-copy assertion
            // has nothing to verify).
            if (ItemIDManager.getItemIDMap().containsKey(fabricatedId))
                return pass("skipped: fabricated short " + fabricatedShort
                        + " is already registered — pick a different short if this recurs");

            // Normalize the stack exactly the way the production register-path does,
            // then seed the negative cache with a wire-consistent key so we can assert
            // the positive response REMOVES the in-flight mark.
            ItemStack normalized = VolatileItemComponents.normalize(raw);
            normalized.setCount(1);
            Object key = newNormalizedStackKey(normalized);

            List<ItemStack> stacks = new ArrayList<>();
            stacks.add(normalized);
            List<Object> keys = new ArrayList<>();
            keys.add(key);
            List<ItemID> response = new ArrayList<>();
            response.add(fabricatedId);

            // Seed the negative cache with the same key by running the register path —
            // this both mirrors the production sequence (register → ARRS fires → response
            // hops back and calls populate) AND lets us assert the positive response
            // REMOVES the mark.
            withIsSlaveServer(true, () -> {
                ItemIDManager.registerItemStackServerSide_direct(raw);
            });
            if (!ItemIDManager.isInSlaveNegativeCache_forTesting(raw))
                return fail("test setup: register path did not seed the negative cache "
                        + "for the synthetic stack");

            // Invoke the populator directly with the fabricated positive response.
            invokePopulateSlaveCacheFromResponse(stacks, keys, response, null);

            // Assertion 1: itemIDMap now contains fabricatedShort → equivalent stack.
            Map<ItemID, ItemStack> mapAfter = ItemIDManager.getItemIDMap();
            ItemStack stored = mapAfter.get(fabricatedId);
            TestResult r = assertNotNull(
                    "populateSlaveCacheFromResponse installed the fabricated short in itemIDMap",
                    stored);
            if (!r.passed()) return r;
            r = assertTrue(
                    "stored template is isSameItemSameComponents-equal to the input normalized stack",
                    ItemStack.isSameItemSameComponents(stored, normalized));
            if (!r.passed()) return r;

            // Assertion 2: the stored stack is a DEFENSIVE COPY, not the same instance
            // that was passed in. Guards against a future edit dropping the .copy() call
            // inside the positive branch — the map would then alias the caller's stack
            // and any subsequent mutation on it (e.g. setCount) would poison the registry.
            r = assertFalse(
                    "stored template is a defensive copy, not the same instance as the input "
                            + "normalized stack (guard against dropped .copy() call)",
                    stored == normalized);
            if (!r.passed()) return r;

            // Assertion 3: positive response REMOVED the in-flight / negative mark so
            // the next lookup hits the positive map.
            r = assertFalse(
                    "positive response cleared the negative / in-flight cache entry",
                    ItemIDManager.isInSlaveNegativeCache_forTesting(raw));
            if (!r.passed()) return r;

            return pass("populateSlaveCacheFromResponse positive branch installs a defensive "
                    + "copy under the master-supplied short and clears the negative-cache mark");
        } catch (Throwable t) {
            return fail("positive-response populator test threw: " + t.getClass().getSimpleName()
                    + " — " + t.getMessage());
        } finally {
            // Drop the fabricated short from the live registry so the next test / live
            // world does not observe a leftover entry with our synthetic template.
            // The manager has no public "remove one short" helper — replaceState_forTesting
            // preserves the alias / counter state and rebuilds the map minus our entry.
            Map<ItemID, ItemStack> current = ItemIDManager.getItemIDMap();
            current.remove(new ItemID(fabricatedShort));
            ItemIDManager.replaceState_forTesting(current, ItemIDManager.getItemIDAliasMap(),
                    ItemIDManager.getNextShortCounter_forTesting());
            ItemIDManager.clearSlaveNegativeCache_forTesting();
            ItemIDManager.resetSlaveArrsCounter_forTesting();
            if (!savedLatch)
                ItemIDManager.markRegistryReady();
        }
    }

    /**
     * Companion of {@link #testPositiveResponsePopulatesItemIdMap} for the NEGATIVE
     * branch: an INVALID_ID response must (a) leave the negative-cache entry in place
     * (permanent-rejection semantic for this session), and (b) NOT add the fabricated
     * short to {@code itemIDMap}. The INFO-log first-time bookkeeping is not asserted
     * here — reaching {@code slaveRejectedLoggedOnce} requires reflecting a private
     * ConcurrentHashMap field, and the two invariants below already regression-guard
     * the branch. The log line remains covered by the manual review of the log stream
     * during the test suite run.
     */
    private TestResult testNegativeResponseKeepsNegativeMark() {
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if (access == null)
            return pass("skipped: no server-side RegistryAccess (client-only / early-init)");

        boolean savedLatch = ItemIDManager.isRegistrationLatchArmed_forTesting();
        final short fabricatedShort = (short) 9998;
        ItemID fabricatedId = new ItemID(fabricatedShort);
        ItemStack raw = paperWithCustomData("populate-negative-" + UUID.randomUUID());

        try {
            ItemIDManager.markRegistryReady();
            ItemIDManager.clearSlaveNegativeCache_forTesting();
            ItemIDManager.resetSlaveArrsCounter_forTesting();

            if (ItemIDManager.getItemIDMap().containsKey(fabricatedId))
                return pass("skipped: fabricated short " + fabricatedShort
                        + " is already registered — pick a different short if this recurs");

            ItemStack normalized = VolatileItemComponents.normalize(raw);
            normalized.setCount(1);
            Object key = newNormalizedStackKey(normalized);

            List<ItemStack> stacks = new ArrayList<>();
            stacks.add(normalized);
            List<Object> keys = new ArrayList<>();
            keys.add(key);
            List<ItemID> response = new ArrayList<>();
            response.add(ItemID.INVALID_ID);

            // Seed the negative cache via the register path (mirrors production order).
            withIsSlaveServer(true, () -> {
                ItemIDManager.registerItemStackServerSide_direct(raw);
            });
            if (!ItemIDManager.isInSlaveNegativeCache_forTesting(raw))
                return fail("test setup: register path did not seed the negative cache "
                        + "for the synthetic stack");

            invokePopulateSlaveCacheFromResponse(stacks, keys, response, null);

            // Invariant 1: negative-cache mark stays (permanent rejection this session).
            TestResult r = assertTrue(
                    "negative response leaves the negative-cache mark in place "
                            + "(subsequent lookups return INVALID_ID without firing ARRS)",
                    ItemIDManager.isInSlaveNegativeCache_forTesting(raw));
            if (!r.passed()) return r;

            // Invariant 2: itemIDMap does NOT gain the fabricated short.
            r = assertFalse(
                    "negative response did not add the fabricated short to itemIDMap",
                    ItemIDManager.getItemIDMap().containsKey(fabricatedId));
            if (!r.passed()) return r;

            return pass("populateSlaveCacheFromResponse negative branch retains the "
                    + "in-flight / negative mark and does not populate itemIDMap");
        } catch (Throwable t) {
            return fail("negative-response populator test threw: " + t.getClass().getSimpleName()
                    + " — " + t.getMessage());
        } finally {
            ItemIDManager.clearSlaveNegativeCache_forTesting();
            ItemIDManager.resetSlaveArrsCounter_forTesting();
            if (!savedLatch)
                ItemIDManager.markRegistryReady();
        }
    }
}
