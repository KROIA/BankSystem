package net.kroia.banksystem.testing;

import com.mojang.logging.LogUtils;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.banking.bankmanager.ServerBankManager;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Test support: snapshot the live, world-persisted server state that a test suite mutates
 * (item allow-list, runtime item blacklist, trusted-slave set, ItemID registry) and hand it
 * back untouched in {@code teardown()}.
 *
 * <p>One save+restore pair per suite replaces per-mutation undo, which cannot work through the
 * public API anyway — see {@link #restore()} for why {@code disallowItemID()} is not an undo.
 *
 * <p>Every method is null-safe and swallows {@link Throwable} — teardown runs even when setup
 * bailed early, and a restore failure must never mask the suite's own cleanup or escape a
 * {@code finally} block.
 *
 * <h2>Why the ItemID registry is guarded too</h2>
 * The suites mint synthetic ItemIDs (paper stacks carrying a {@code minecraft:custom_data}
 * marker) so they can exercise bank/alias logic without touching a real player item. The
 * ItemID table has no deregister, so a naive suite leaves one stray "Paper" row in the bank
 * manage screen per synthetic item, <b>per run, forever</b>. Reinstalling the registry
 * snapshot at the end of {@link #restore()} discards exactly the IDs minted in between.
 *
 * <p>This does <b>not</b> violate the "persisted ItemIDs are immutable / never renumber" rule:
 * the restore puts every pre-existing entry back at its original short and only drops entries
 * that were added after the snapshot. Nothing that existed before the suite ran is renumbered
 * or removed.
 *
 * <h2>Self-healing on an already-dirty world</h2>
 * Register synthetic stacks through {@link #register(ItemStack)} rather than
 * {@link ItemIDManager#registerItemStackServerSide_direct(ItemStack)} directly. With a
 * <b>deterministic</b> marker, a run that was killed before its teardown left residue under
 * the very same ItemID that this run re-resolves, so {@link #purge(ItemID)} can scrub it from
 * the snapshot and let the restore discard it. Residue minted by an older build under a random
 * marker is never re-requested and therefore cannot be healed this way.
 *
 * <p>Usage:
 * <pre>
 * private LiveStateGuard stateGuard;
 *
 * public void setup()    { stateGuard = LiveStateGuard.capture(manager); ... }
 * public void teardown() { ... LiveStateGuard.restore(stateGuard); stateGuard = null; }
 * </pre>
 */
public final class LiveStateGuard {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ISyncServerBankManager manager;
    private final ServerBankManager.ItemFilterSnapshot itemFilters;
    private final Set<String> trustedSlaves;

    private final Map<ItemID, ItemStack> itemIDMap;
    private final Map<ItemID, ItemID> aliasMap;
    private final Map<ItemID, ItemID> quarantinedAliases;
    private final int shortCounter;

    /**
     * {@code minecraft:custom_data} key identifying this suite's scratch stacks, or {@code null}
     * when the suite registers real items only. Gates the live-registry eviction in
     * {@link #register(ItemStack)}.
     */
    private String syntheticMarkerKey;

    private LiveStateGuard(ISyncServerBankManager manager,
                           ServerBankManager.ItemFilterSnapshot itemFilters,
                           Set<String> trustedSlaves,
                           Map<ItemID, ItemStack> itemIDMap,
                           Map<ItemID, ItemID> aliasMap,
                           Map<ItemID, ItemID> quarantinedAliases,
                           int shortCounter) {
        this.manager = manager;
        this.itemFilters = itemFilters;
        this.trustedSlaves = trustedSlaves;
        this.itemIDMap = itemIDMap;
        this.aliasMap = aliasMap;
        this.quarantinedAliases = quarantinedAliases;
        this.shortCounter = shortCounter;
    }

    /**
     * Captures the ItemID registry plus — when {@code manager} is the concrete master-side
     * {@link ServerBankManager} — the item filters and trusted-slave set.
     *
     * <p>Never returns {@code null}: a slave-side or absent manager still gets the registry
     * guarded, which is where the "growing list of Paper entries" residue comes from.
     * The filter/trust halves simply stay unguarded in that case.
     *
     * <p>Must be called <b>before</b> the suite mints its first synthetic ItemID or touches
     * the allow-list; a snapshot taken afterwards would preserve the residue instead of
     * discarding it.
     *
     * @param manager the server bank manager, may be {@code null}
     * @return a guard for {@code teardown()} (never {@code null})
     */
    public static LiveStateGuard capture(ISyncServerBankManager manager) {
        ServerBankManager.ItemFilterSnapshot filters = null;
        Set<String> slaves = null;
        if (manager instanceof ServerBankManager serverManager) {
            try {
                filters = serverManager.snapshotItemFilters();
                slaves = new HashSet<>(serverManager.getTrustedSlaveServers());
            } catch (Throwable ignored) {
                filters = null;
                slaves = null;
            }
        }
        Map<ItemID, ItemStack> items = null;
        Map<ItemID, ItemID> aliases = null;
        Map<ItemID, ItemID> quarantine = null;
        int counter = 0;
        try {
            items = new HashMap<>(ItemIDManager.getItemIDMap());
            aliases = new HashMap<>(ItemIDManager.getItemIDAliasMap());
            quarantine = ItemIDManager.getQuarantinedAliases_forTesting();
            counter = ItemIDManager.getNextShortCounter_forTesting();
        } catch (Throwable ignored) {
            items = null;
        }
        return new LiveStateGuard(manager, filters, slaves, items, aliases, quarantine, counter);
    }

    /** Registry-only guard, for suites that have no {@link ISyncServerBankManager} to hand. */
    public static LiveStateGuard capture() {
        return capture((ISyncServerBankManager) null);
    }

    /**
     * Registry-only guard that additionally <b>evicts residue</b> left in the live registry by
     * an earlier aborted run.
     *
     * @param syntheticMarkerKey the {@code minecraft:custom_data} key the suite stamps onto its
     *                           scratch stacks (e.g. {@code "banksystem_identity_test"}). A
     *                           stack carrying this key cannot exist in a real player's
     *                           inventory, which is what makes live eviction safe — see
     *                           {@link #register(ItemStack)}.
     */
    public static LiveStateGuard captureSynthetic(String syntheticMarkerKey) {
        return capture((ISyncServerBankManager) null).withSyntheticMarker(syntheticMarkerKey);
    }

    /**
     * Declares the {@code minecraft:custom_data} key this suite stamps onto its scratch stacks,
     * enabling the live-residue eviction described in {@link #register(ItemStack)}. Chainable
     * onto {@link #capture(ISyncServerBankManager)} for suites that guard the filters too.
     *
     * @param syntheticMarkerKey the suite's marker key (e.g. {@code "banksystem_identity_test"})
     * @return {@code this}
     */
    public LiveStateGuard withSyntheticMarker(String syntheticMarkerKey) {
        this.syntheticMarkerKey = syntheticMarkerKey;
        return this;
    }

    /**
     * Registers a test stack, first clearing out anything an earlier aborted run left behind
     * for it. Use this instead of
     * {@link ItemIDManager#registerItemStackServerSide_direct(ItemStack)} in test suites.
     *
     * <p>Two levels of cleanup, depending on whether the stack is <b>provably synthetic</b> —
     * that is, whether it carries the {@code syntheticMarkerKey} passed to
     * {@link #captureSynthetic(String)} inside {@code minecraft:custom_data}:
     * <ul>
     *   <li><b>Provably synthetic</b> (a marked paper stack): a pre-existing registry entry for
     *       it can only be residue from an earlier run of this very suite, because no player
     *       can hold such a stack and therefore no balance can be keyed on it. It is evicted
     *       from the <i>live</i> registry before registering, so the caller gets a genuinely
     *       fresh ItemID and the stale row disappears from the bank manage screen for good.
     *       This is what heals an already-dirty world.</li>
     *   <li><b>Everything else</b> (plain vanilla items, enchanted books, stamped shares): the
     *       live registry is <b>never</b> touched. A pre-existing entry for a real item may
     *       well carry real player balances, and evicting it would destroy them. Only the
     *       ID minted by this call is scrubbed from the snapshot, so {@link #restore()} drops
     *       it if it was new and keeps it if it already existed.</li>
     * </ul>
     *
     * @param stack the stack to register
     * @return the minted (or re-resolved) ItemID
     */
    public ItemID register(ItemStack stack) {
        if (isProvablySynthetic(stack))
            evictResidue(stack);
        ItemID itemID = ItemIDManager.registerItemStackServerSide_direct(stack);
        // Purge ONLY genuinely new IDs. An ID that was already in the snapshot belongs to a
        // real item (money, paper, iron ingot) that was merely re-resolved here — dropping it
        // would make restore() delete it from the live, world-persisted registry.
        if (itemIDMap == null || !itemIDMap.containsKey(itemID))
            purge(itemID);
        return itemID;
    }

    /**
     * @return {@code true} if {@code stack} carries this guard's synthetic marker key inside
     *         {@code minecraft:custom_data}, i.e. it is a test-only stack no player can hold.
     */
    private boolean isProvablySynthetic(ItemStack stack) {
        if (syntheticMarkerKey == null || stack == null || stack.isEmpty())
            return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().contains(syntheticMarkerKey);
    }

    /**
     * Drops a stale registry entry for {@code stack} from the <b>live</b> ItemID table, so the
     * subsequent registration mints a fresh short instead of re-resolving the old one.
     *
     * <p>Only ever reached for {@linkplain #isProvablySynthetic provably synthetic} stacks.
     * The alias map and the short counter are carried over unchanged, so this removes exactly
     * one map entry: surviving IDs keep their shorts (no renumbering — persisted ItemIDs stay
     * an immutable source of truth) and the counter's monotonic advance is preserved, which
     * means the evicted short is never re-issued either.
     *
     * <p><b>The incoming stack's marker is not proof.</b> {@link ItemIDManager#getItemID} resolves
     * through normalization and {@link ItemID} equality is short-only, so the row the lookup lands
     * on can be a real vanilla item (plain paper!) or another suite's fixture. The row is only
     * deleted when the <i>stored template</i> itself carries this guard's marker.
     */
    private void evictResidue(ItemStack stack) {
        try {
            ItemID stale = ItemIDManager.getItemID(stack); // non-minting lookup
            if (!stale.isValid())
                return;
            Map<ItemID, ItemStack> live = new HashMap<>(ItemIDManager.getItemIDMap());
            // Prove the RESOLVED row is test-owned before deleting it.
            if (!isProvablySynthetic(live.get(stale)))
                return;
            if (live.remove(stale) == null)
                return;
            ItemIDManager.replaceState_forTesting(live,
                    ItemIDManager.getItemIDAliasMap(),
                    ItemIDManager.getNextShortCounter_forTesting());
            if (itemIDMap != null)
                itemIDMap.remove(stale);
            purge(stale);
            LOGGER.info("LiveStateGuard: evicted stale test ItemID {} left by an earlier run", stale);
        } catch (Throwable t) {
            LOGGER.error("LiveStateGuard: could not evict stale test residue: {}", t.toString());
        }
    }

    /**
     * Removes every trace of {@code itemID} left behind by an earlier aborted run so the
     * {@link #restore()} at the end of this run discards it instead of putting it back.
     *
     * <p>Only ever call this for a <b>synthetic, test-owned</b> ItemID. Such an ID can never
     * legitimately appear in the player's filters or need to survive in the registry, which is
     * what makes all three scrubs unconditionally safe:
     * <ul>
     *   <li>drop the live runtime-blacklist entry, otherwise {@code allowItemID} refuses the
     *       ID and the suite's setup fails (and a failed setup skips teardown, stranding the
     *       rest of the residue too);</li>
     *   <li>drop it from both snapshot sets, so the filter restore cannot reinstate a stale
     *       allowed/blacklisted entry;</li>
     *   <li>drop it from the snapshot registry, so the registry restore discards the stale
     *       ItemID — this is what clears the leftover "Paper" rows from the manage screen.</li>
     * </ul>
     *
     * @param itemID the synthetic ID just minted or re-resolved
     */
    public void purge(ItemID itemID) {
        if (itemID == null)
            return;
        if (itemFilters != null) {
            itemFilters.allowed().remove(itemID);
            itemFilters.runtimeBlacklist().remove(itemID);
        }
        if (itemIDMap != null)
            itemIDMap.remove(itemID);
        try {
            if (manager != null && manager.isItemIDBlacklisted(itemID)) {
                // allowItemID() is the documented way to clear a runtime-blacklist entry.
                // Its allowed-set side effect is reverted wholesale by restore() below.
                manager.allowItemID(itemID);
            }
        } catch (Throwable t) {
            LOGGER.error("LiveStateGuard: could not clear stale blacklist entry for {}: {}",
                    itemID, t.toString());
        }
    }

    /**
     * Puts the captured state back. Safe to call more than once; never throws.
     *
     * <p><b>Order matters.</b> Filters first, registry last: a restored filter entry must never
     * reference an ItemID the registry restore has already dropped — an unresolvable filter
     * entry renders as a stray "air" row in the bank manage screen.
     *
     * <p><b>Never undo an {@code allowItemID} with {@code disallowItemID}.</b> They are not
     * inverses. {@code disallowItemID} delegates to {@code disallowItemIDAndReport}, which
     * adds the ID to the runtime blacklist <i>and</i> removes that item's bank from every
     * holder account with <b>no refund</b>. A blacklisted synthetic ID also makes the next
     * run's {@code allowItemID} fail outright. This snapshot/restore pair is the only route
     * back to the "neither allowed nor blacklisted" state; {@code ServerBankManager}'s own
     * {@code snapshotItemFilters} javadoc says the same.
     */
    public void restore() {
        try {
            if (itemFilters != null && manager instanceof ServerBankManager serverManager) {
                // The registry restore below drops every ID minted after the snapshot. A filter
                // entry left pointing at such an ID resolves to nothing and renders as a stray
                // "air" row, so drop those first. Also self-heals rows an earlier run stranded.
                if (itemIDMap != null) {
                    itemFilters.allowed().removeIf(this::isUnresolvableAfterRestore);
                    itemFilters.runtimeBlacklist().removeIf(this::isUnresolvableAfterRestore);
                }
                serverManager.restoreItemFilters(itemFilters);
            }
        } catch (Throwable ignored) {
            // teardown must continue with the suite's own cleanup
        }
        try {
            if (trustedSlaves != null && manager != null) {
                for (String slaveID : new HashSet<>(manager.getTrustedSlaveServers())) {
                    if (!trustedSlaves.contains(slaveID))
                        manager.untrustSlaveServer(slaveID);
                }
                for (String slaveID : trustedSlaves) {
                    if (!manager.isSlaveServerTrusted(slaveID))
                        manager.trustSlaveServer(slaveID);
                }
            }
        } catch (Throwable ignored) {
            // same
        }
        try {
            if (itemIDMap != null) {
                ItemIDManager.replaceState_forTesting(itemIDMap, aliasMap, shortCounter);
                ItemIDManager.restoreQuarantinedAliases_forTesting(quarantinedAliases);
            }
        } catch (Throwable t) {
            LOGGER.error("LiveStateGuard: could not restore the ItemID registry: {}",
                    t.toString());
        }
    }

    /** @return {@code true} if {@code itemID} will not resolve once the registry snapshot is back. */
    private boolean isUnresolvableAfterRestore(ItemID itemID) {
        return itemID != null
                && !itemIDMap.containsKey(itemID)
                && (aliasMap == null || !aliasMap.containsKey(itemID));
    }

    /** Convenience for {@code teardown()}: null-safe restore of a possibly-uncaptured guard. */
    public static void restore(LiveStateGuard guard) {
        if (guard != null)
            guard.restore();
    }
}
