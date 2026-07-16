package net.kroia.banksystem.util;

import net.kroia.banksystem.minecraft.item.BankSystemItems;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.modutilities.ItemUtilities;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure, side-effect-free detection and repair planning for the <b>cent-shift world
 * corruption</b> (pre-v2.0.3 load-order bug).
 * <p>
 * <b>What happened:</b> before the load-order fix (v2.0.3, "load persisted item ids before
 * registering defaults"), {@code createDefaultItemIDs()} ran BEFORE {@code load_itemIDs()},
 * minting fresh shorts from 1 on every startup. On worlds that ran such a buggy build after
 * the five cent coins were added, {@code ItemIDs.nbt} was overwritten with a fresh
 * <i>cent-shifted</i> default mapping (the cent coins are prepended before base money in
 * {@link BankSystemItems#getMoneyItems()}, shifting everything after them by +5) while all
 * other world data — bank balances, chunk block entities, StockMarket saves, the
 * balance-history DB — still references the <i>old</i> shorts. Real-world symptom: an item's
 * stored short resolving to a wrong item five positions away (e.g. Diorite showing up as
 * {@code banksystem:money200}). The bad merge additionally left <b>fingerprint aliases</b>
 * in the file: the persisted money shorts aliased to the fresh money shorts.
 * <p>
 * <b>Why repairing the mapping is enough:</b> {@code itemIDMap} is the single source of
 * truth for short→item resolution. Restoring the correct mapping inside {@code ItemIDs.nbt}
 * makes every ItemID-keyed reference in the world resolve correctly again — no chunk, DB or
 * StockMarket rewrite is needed.
 * <p>
 * <b>Design contract:</b> every method in this class is {@code static}, <b>pure</b> and
 * operates exclusively on the snapshot maps passed in — never on
 * {@link ItemIDManager}'s live maps. Nothing here mutates anything; the caller
 * ({@code ItemIDManager.applyCorruptionRepairGuard()}) decides whether to apply a plan.
 * This is what makes the detection a provable dry run and the whole class unit-testable
 * without touching live state.
 */
public final class ItemIDWorldRepair {

    /** Static utility — never instantiated. */
    private ItemIDWorldRepair() {
    }

    // ========================================================================================
    // Evidence records
    // ========================================================================================

    /**
     * One <b>fingerprint alias</b> left behind by the buggy merge: a persisted alias pair
     * whose <b>direct target</b> short maps to a BankSystem money/cent item. Healthy worlds
     * can never contain such an entry — fresh worlds have no aliases at all, and pre-cent
     * worlds upgraded through fixed builds create none because default registration is
     * register-if-absent (persisted money shorts are simply kept).
     * <p>
     * <b>Direct pair, deliberately NOT chain-resolved:</b> chain-walking semantics belong to
     * the <i>runtime</i> alias table (flattened by {@code renormalizeAndMerge}), not to raw
     * persisted evidence. The buggy merge wrote DIRECT old→fresh pairs whose targets are
     * numerically also sources of other pairs (old money {@code 1..9} → fresh {@code 6..14},
     * and on badly hit worlds every old short {@code s → s+5} across thousands of entries).
     * Chain-walking such a corpus either lands on the wrong terminal (1→6→11 reports
     * MONEY100 instead of MONEY, breaking the firewall's O/F line-up check) or exceeds any
     * hop bound on the long +5 ladders and silently drops every fingerprint. The direct
     * pair is exactly what the buggy build recorded, and exactly what the cross-validation
     * firewall checks against the simulated old/fresh assignments.
     *
     * @param from              the alias's source short (the money short of the OLD epoch)
     * @param to                the alias's direct target short (the money short of the
     *                          FRESH cent-shifted epoch)
     * @param canonicalTemplate defensive copy of the direct target's template (the money
     *                          item the pair points at)
     */
    public record AliasFingerprint(short from, short to, ItemStack canonicalTemplate) {
    }

    /**
     * The full evidence bundle assembled by {@link #detect}: both corruption signals plus
     * provenance context for the report.
     *
     * @param fingerprints          every money fingerprint alias found (signal a; never empty)
     * @param freshMoneyBlock       the contiguous ascending money/cent block found in the
     *                              corrupted map, keyed short → template in
     *                              {@link BankSystemItems#getMoneyItems()} order (signal b)
     * @param lastSavedByModVersion the mod version string read from {@code Meta_data.nbt}
     *                              ("world last saved by"), or {@code null} if unknown —
     *                              report context only, not used for decisions
     */
    public record CorruptionEvidence(List<AliasFingerprint> fingerprints,
                                     LinkedHashMap<Short, ItemStack> freshMoneyBlock,
                                     @Nullable String lastSavedByModVersion) {
    }

    /**
     * The complete, pre-computed repair proposal produced by {@link #buildRepairPlan}.
     * When {@link #validationFailures} is non-empty the cross-validation firewall rejected
     * the evidence — <b>no repair is offered</b> and {@link #repairedMap} is empty; the
     * report then instructs the admin to restore {@code ItemIDs.nbt} from a world backup.
     *
     * @param repairedMap         the full proposed replacement for {@code itemIDMap}:
     *                            the reconstructed old-epoch mapping plus {@link #appended};
     *                            insertion-ordered (old shorts ascending, then appended);
     *                            EMPTY when validation failed
     * @param aliasesToDrop       alias-table source IDs that must be removed when the plan is
     *                            applied: the fingerprint aliases plus every alias whose
     *                            source short is re-occupied by {@link #repairedMap}
     *                            (the {@code putAlias} source-not-in-map invariant)
     * @param appended            the subset of {@link #repairedMap} that had no old-epoch
     *                            short (cent coins, post-corruption registrations) and was
     *                            re-appended at fresh shorts above the old maximum,
     *                            preserving its relative order in the corrupted map
     * @param changedShorts       every short whose resolved item differs between the
     *                            corrupted and the repaired map — the mixed-epoch audit
     *                            list (data written since the corruption under these shorts
     *                            changes meaning when the repair is applied)
     * @param newNextShortCounter the monotonic counter value to install with the plan:
     *                            {@code max(persistedCounter, max repaired short + 1)} —
     *                            never lower than the persisted counter
     * @param validationFailures  human-readable cross-validation failures; non-empty means
     *                            the evidence is inconsistent and no repair may be applied
     */
    public record RepairPlan(LinkedHashMap<ItemID, ItemStack> repairedMap,
                             Set<ItemID> aliasesToDrop,
                             LinkedHashMap<ItemID, ItemStack> appended,
                             TreeSet<Short> changedShorts,
                             int newNextShortCounter,
                             List<String> validationFailures) {
    }

    // ========================================================================================
    // Detection
    // ========================================================================================

    /**
     * Convenience overload of {@link #detect(Map, Map, RegistryAccess, String)} without
     * provenance context (evidence carries {@code null} as the last-saved mod version).
     */
    public static Optional<CorruptionEvidence> detect(Map<ItemID, ItemStack> map,
                                                      Map<ItemID, ItemID> aliases,
                                                      RegistryAccess access) {
        return detect(map, aliases, access, null);
    }

    /**
     * <b>Pure dry-run detection</b> of the cent-shift corruption on the given snapshot maps.
     * Requires BOTH signals to be present — either one alone is not sufficient evidence:
     * <ol>
     *   <li><b>Money fingerprint alias</b> (signal a): at least one raw alias pair whose
     *       DIRECT target template is a BankSystem money/cent item
     *       ({@code instanceof MoneyItem}) — see {@link AliasFingerprint} for why the direct
     *       pair (never a chain terminal) is the correct unit of evidence. Impossible on
     *       healthy worlds: fresh worlds have no aliases, and pre-cent worlds upgraded
     *       through fixed builds create none (registration is register-if-absent, so
     *       persisted money shorts are kept and never merged).</li>
     *   <li><b>Fresh cent-shifted money block</b> (signal b): all 14 money/cent items occupy
     *       a contiguous ascending short block in one of the two historical fresh-assignment
     *       orders — {@link BankSystemItems#getMoneyItems()} order
     *       ({@code CENT1..CENT50, MONEY..MONEY1000} at {@code k..k+13}) or the boot order
     *       ({@code CENT1..CENT50, MONEY5..MONEY1000, MONEY}; see
     *       {@link #simulateBootOrderAssignment}) — the signature of a fresh post-cent
     *       default assignment. A healthy upgraded pre-cent world has its cents appended at
     *       the tail instead, far away from base money.</li>
     * </ol>
     * Detection <b>never mutates anything</b> and never blocks startup by itself — the
     * caller decides what to do with the evidence (see
     * {@code ItemIDManager.applyCorruptionRepairGuard()}).
     *
     * @param map                   snapshot of the (possibly corrupted) short→template map
     * @param aliases               snapshot of the alias table (source → canonical)
     * @param access                server registry access (unused by detection itself; kept
     *                              in the signature so detection and planning share one call
     *                              shape and future signals can consult the registry)
     * @param lastSavedByModVersion provenance context for the report ({@code null} if unknown)
     * @return the evidence bundle when BOTH signals fire; {@link Optional#empty()} otherwise
     */
    public static Optional<CorruptionEvidence> detect(Map<ItemID, ItemStack> map,
                                                      Map<ItemID, ItemID> aliases,
                                                      RegistryAccess access,
                                                      @Nullable String lastSavedByModVersion) {
        if (map == null || map.isEmpty() || aliases == null || aliases.isEmpty())
            return Optional.empty(); // signal (a) needs at least one alias — cheap healthy-path exit

        // Signal (a): money fingerprint aliases.
        List<AliasFingerprint> fingerprints = collectMoneyFingerprints(map, aliases);
        if (fingerprints.isEmpty())
            return Optional.empty();

        // Signal (b): contiguous ascending money/cent block in one of the two historical
        // fresh-assignment orders — getMoneyItems() order (createDefaultItemIDs-first
        // builds) or the boot order (constructor-warm-up builds, see
        // simulateBootOrderAssignment). Either shape is the signature of a fresh default
        // assignment; the cross-validation firewall later pins down which one.
        LinkedHashMap<Short, ItemStack> moneyBlock = findContiguousMoneyBlock(map, BankSystemItems.getMoneyItems());
        if (moneyBlock == null)
            moneyBlock = findContiguousMoneyBlock(map, bootOrderMoneyItems());
        if (moneyBlock == null)
            return Optional.empty();

        return Optional.of(new CorruptionEvidence(List.copyOf(fingerprints), moneyBlock,
                lastSavedByModVersion));
    }

    /**
     * Collects every raw alias pair whose <b>direct target</b> template is a BankSystem
     * money/cent item (see {@link AliasFingerprint}). Pure — reads only the snapshot maps.
     * <p>
     * <b>No chain resolution here — by design.</b> The input is raw persisted evidence
     * (direct old→fresh pairs as the buggy build wrote them), not the flattened runtime
     * alias table. On the realistic corpora the pairs overlap numerically (targets of some
     * pairs are sources of others: money-only {@code 1→6..9→14}, or the full {@code s→s+5}
     * ladder across thousands of entries), so a chain walk would either report the wrong
     * item (failing the O/F firewall line-up forever) or blow any hop bound and drop every
     * fingerprint — destroying detection exactly on the worlds this guard exists for.
     * Pairs whose direct target is absent from the map (orphans) or not a money item are
     * simply not fingerprints.
     */
    private static List<AliasFingerprint> collectMoneyFingerprints(Map<ItemID, ItemStack> map,
                                                                   Map<ItemID, ItemID> aliases) {
        List<AliasFingerprint> fingerprints = new ArrayList<>();
        for (Map.Entry<ItemID, ItemID> entry : aliases.entrySet()) {
            ItemID directTarget = entry.getValue();
            if (directTarget == null)
                continue;
            ItemStack template = map.get(directTarget);
            if (template == null || template.isEmpty())
                continue; // orphan pair — not a fingerprint
            if (template.getItem() instanceof MoneyItem)
                fingerprints.add(new AliasFingerprint(entry.getKey().getShort(),
                        directTarget.getShort(), template.copy()));
        }
        // Deterministic order (by source short) for stable reports and tests.
        fingerprints.sort(Comparator.comparingInt(fp -> Short.toUnsignedInt(fp.from())));
        return fingerprints;
    }

    /**
     * Looks for the 14 money/cent items at a contiguous ascending short block in the given
     * order ({@link BankSystemItems#getMoneyItems()} order or the boot order from
     * {@link #bootOrderMoneyItems()}).
     *
     * @param map        the snapshot map to search
     * @param moneyItems the expected money-item order of the candidate fresh assignment
     * @return the block as an insertion-ordered short→template map, or {@code null} when any
     *         money item is missing, occurs at more than one short, or the shorts are not
     *         exactly {@code k, k+1, ..., k+13} in list order
     */
    private static @Nullable LinkedHashMap<Short, ItemStack> findContiguousMoneyBlock(Map<ItemID, ItemStack> map,
                                                                                      List<ItemStack> moneyItems) {
        LinkedHashMap<Short, ItemStack> block = new LinkedHashMap<>();
        Integer expectedNext = null;
        for (ItemStack moneyStack : moneyItems) {
            Short found = findUniqueShortByItem(map, moneyStack.getItem());
            if (found == null)
                return null; // missing or ambiguous — not the fresh-assignment signature
            int shortValue = Short.toUnsignedInt(found);
            if (expectedNext != null && shortValue != expectedNext)
                return null; // gap or reordering — not contiguous ascending
            expectedNext = shortValue + 1;
            block.put(found, map.get(new ItemID(found)).copy());
        }
        return block;
    }

    /**
     * Finds the single short mapped to the given item in the snapshot map.
     *
     * @return the short, or {@code null} when the item is absent or present at more than one
     *         short (ambiguous — treated as "signature not present" by the callers)
     */
    private static @Nullable Short findUniqueShortByItem(Map<ItemID, ItemStack> map, Item item) {
        Short found = null;
        for (Map.Entry<ItemID, ItemStack> entry : map.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()
                    && entry.getValue().getItem() == item) {
                if (found != null)
                    return null; // ambiguous
                found = entry.getKey().getShort();
            }
        }
        return found;
    }

    // ========================================================================================
    // Simulation of the default assignment
    // ========================================================================================

    /**
     * <b>Pure reimplementation</b> of {@code ItemIDManager.createDefaultItemIDs()}'s
     * allocation order, producing the short→template mapping a fresh default assignment
     * would create — without touching any live state.
     * <p>
     * Order (identical to the production pass): first the money list from
     * {@link BankSystemItems#getMoneyItems()}, then every item of the game registry in
     * {@code Registries.ITEM} iteration order. Each candidate is normalized
     * ({@link VolatileItemComponents#normalize}) and skipped when its normalized identity
     * was already assigned (register-if-absent, exactly like
     * {@code registerItemStackServerSide_direct}); empty stacks (e.g. {@code minecraft:air})
     * are skipped without consuming a short, mirroring the allocator's empty-stack refusal.
     * <p>
     * <b>Pre-cent mode</b> ({@code includeCents == false}) simulates the OLD epoch: the five
     * cent coins are excluded from the money list AND from the registry iteration — they did
     * not exist in the pre-cent build's registry at all, so the old assignment never gave
     * them shorts.
     *
     * @param access       server registry access for the registry iteration
     * @param includeCents {@code true} = fresh post-cent assignment; {@code false} = old
     *                     pre-cent epoch assignment
     * @param startShort   first short to hand out (production uses {@code 1};
     *                     short {@code 0} is reserved for {@link ItemID#INVALID_ID})
     * @return insertion-ordered short→normalized-template mapping of the simulated assignment
     */
    public static LinkedHashMap<Short, ItemStack> simulateDefaultAssignment(@NotNull RegistryAccess access,
                                                                            boolean includeCents,
                                                                            int startShort) {
        LinkedHashMap<Short, ItemStack> result = new LinkedHashMap<>();
        List<ItemStack> assignedTemplates = new ArrayList<>(); // normalized identities already assigned
        Set<Item> centItems = centItemSet();
        int counter = Math.max(startShort, 1);

        // Phase 1: the money list (cent coins first, then base money — see getMoneyItems()).
        for (ItemStack moneyStack : BankSystemItems.getMoneyItems()) {
            if (!includeCents && centItems.contains(moneyStack.getItem()))
                continue; // pre-cent epoch: cent coins did not exist
            counter = simulateRegister(result, assignedTemplates, moneyStack, counter);
        }

        // Phase 2: the full item registry in registry iteration order.
        for (var holder : access.lookupOrThrow(Registries.ITEM).listElements().toList()) {
            ItemStack stack = holder.value().getDefaultInstance();
            if (!includeCents && centItems.contains(stack.getItem()))
                continue; // pre-cent epoch: cent coins absent from the registry too
            counter = simulateRegister(result, assignedTemplates, stack, counter);
        }
        return result;
    }

    /**
     * Simulates one register-if-absent allocation step: normalizes the candidate, skips it
     * when empty or when its normalized identity is already assigned, otherwise assigns the
     * next short.
     *
     * @return the counter after the step (advanced only when a short was actually assigned)
     */
    private static int simulateRegister(LinkedHashMap<Short, ItemStack> out,
                                        List<ItemStack> assignedTemplates,
                                        ItemStack candidate,
                                        int counter) {
        if (candidate == null || candidate.isEmpty())
            return counter; // empty stacks never mint a short (mirrors the live allocator)
        ItemStack normalized = VolatileItemComponents.normalize(candidate);
        normalized.setCount(1);
        for (ItemStack existing : assignedTemplates) {
            if (ItemStack.isSameItemSameComponents(normalized, existing))
                return counter; // register-if-absent: identity already has a short
        }
        if (counter > Short.MAX_VALUE)
            return counter; // exhausted — mirrors the live allocator's refusal
        out.put((short) counter, normalized);
        assignedTemplates.add(normalized);
        return counter + 1;
    }

    /** The five cent-coin items (prepended to {@code getMoneyItems()} in the post-cent build). */
    private static Set<Item> centItemSet() {
        Set<Item> cents = new HashSet<>();
        cents.add(BankSystemItems.MONEY_CENT1.get());
        cents.add(BankSystemItems.MONEY_CENT5.get());
        cents.add(BankSystemItems.MONEY_CENT10.get());
        cents.add(BankSystemItems.MONEY_CENT20.get());
        cents.add(BankSystemItems.MONEY_CENT50.get());
        return cents;
    }

    /**
     * <b>Pure reimplementation of the historical BOOT registration order</b> of builds
     * {@code >= 7265ef15} whose {@code ServerBankManager} constructor registered ItemIDs
     * <i>before</i> {@code ItemIDs.nbt} was loaded (verified against the current constructor
     * code and {@code git show 7265ef15}): the constructor prefix —
     * {@code INITIAL_BLACKLIST_ITEMS} in list order ({@code getBlacklistedItems()}), then
     * {@code INITIAL_NOT_REMOVABLE_ITEMS} ({@code getNotRemovableItems()}), then
     * {@code INITIAL_ALLOWED_ITEMS} ({@code setupDefaultItems()}) — followed by
     * {@code createDefaultItemIDs()}'s money list + full registry iteration. This yields
     * bedrock=1, ..., money200=19, ..., {@code banksystem:money}=22, iron=23, ..., coal=27,
     * then the remaining registry items.
     * <p>
     * This is the fresh-assignment shape of every world whose first ItemID registration ran
     * through the constructor warm-up (in particular fresh worlds created by v2.0.3 /
     * early-v2.0.4 builds, where {@code createDefaultItemIDs()} had already moved post-load
     * but the constructor still minted pre-load — the Task #16 root cause). It is the
     * <b>second candidate mapping</b> of {@link #buildRepairPlan}'s cross-validation
     * firewall, alongside {@link #simulateDefaultAssignment}'s pure
     * {@code createDefaultItemIDs()} order.
     * <p>
     * Same register-if-absent / empty-skip / pre-cent semantics as
     * {@link #simulateDefaultAssignment(RegistryAccess, boolean, int)}.
     *
     * @param access       server registry access for the registry iteration
     * @param includeCents {@code true} = post-cent epoch; {@code false} = pre-cent epoch
     *                     (cent coins absent from the settings lists and the registry)
     * @param startShort   first short to hand out (production uses {@code 1})
     * @return insertion-ordered short→normalized-template mapping of the simulated boot order
     */
    public static LinkedHashMap<Short, ItemStack> simulateBootOrderAssignment(@NotNull RegistryAccess access,
                                                                              boolean includeCents,
                                                                              int startShort) {
        LinkedHashMap<Short, ItemStack> result = new LinkedHashMap<>();
        List<ItemStack> assignedTemplates = new ArrayList<>();
        Set<Item> centItems = centItemSet();
        int counter = Math.max(startShort, 1);

        // Phase 1: the constructor prefix + createDefaultItemIDs' money list, in exact
        // historical call order (see the class Javadoc of ServerBankManager's constructor).
        for (ItemStack stack : bootPrefixItems()) {
            if (!includeCents && centItems.contains(stack.getItem()))
                continue; // pre-cent epoch: cent coins did not exist
            counter = simulateRegister(result, assignedTemplates, stack, counter);
        }

        // Phase 2: the full item registry in registry iteration order (createDefaultItemIDs).
        for (var holder : access.lookupOrThrow(Registries.ITEM).listElements().toList()) {
            ItemStack stack = holder.value().getDefaultInstance();
            if (!includeCents && centItems.contains(stack.getItem()))
                continue; // pre-cent epoch: cent coins absent from the registry too
            counter = simulateRegister(result, assignedTemplates, stack, counter);
        }
        return result;
    }

    /**
     * The historical boot-order registration prefix (before the registry iteration):
     * blacklist, not-removable, allowed items, then {@code getMoneyItems()}. Sourced from
     * the static settings-list factories so the simulation needs no live settings instance.
     */
    private static List<ItemStack> bootPrefixItems() {
        List<ItemStack> prefix = new ArrayList<>();
        prefix.addAll(net.kroia.banksystem.BankSystemModSettings.Bank.createInitialBlacklistItems());
        prefix.addAll(net.kroia.banksystem.BankSystemModSettings.Bank.createInitialNotRemovableItems());
        prefix.addAll(net.kroia.banksystem.BankSystemModSettings.Bank.createInitialAllowedItems());
        prefix.addAll(BankSystemItems.getMoneyItems());
        return prefix;
    }

    /**
     * The money/cent items in the order the BOOT registration sequence first encounters
     * them: the 13 money items of {@code INITIAL_BLACKLIST_ITEMS} (cent1..cent50,
     * money5..money1000), then {@code banksystem:money} from
     * {@code INITIAL_NOT_REMOVABLE_ITEMS}. Derived from the boot prefix (not hardcoded) so
     * a settings-list change cannot silently desync the two. Counterpart of
     * {@link BankSystemItems#getMoneyItems()} for the boot-order money-block signature.
     */
    private static List<ItemStack> bootOrderMoneyItems() {
        List<ItemStack> order = new ArrayList<>();
        Set<Item> seen = new HashSet<>();
        for (ItemStack stack : bootPrefixItems()) {
            if (stack.getItem() instanceof MoneyItem && seen.add(stack.getItem()))
                order.add(stack);
        }
        return order;
    }

    // ========================================================================================
    // Repair planning
    // ========================================================================================

    /**
     * Builds the complete repair proposal for a detected cent-shift corruption. Pure —
     * consumes only the snapshot maps and the registry; mutates nothing.
     * <p>
     * <b>Candidate epochs</b> (Task #16 Fix D — two historical registration orders):
     * <ul>
     *   <li><b>Candidate A (default order):</b> {@code O = simulateDefaultAssignment(false)}
     *       / {@code F = simulateDefaultAssignment(true)} — builds where
     *       {@code createDefaultItemIDs()} ran first pre-load.</li>
     *   <li><b>Candidate B (boot order):</b> {@code O = simulateBootOrderAssignment(false)}
     *       / {@code F = simulateBootOrderAssignment(true)} — builds whose
     *       {@code ServerBankManager} constructor registered first (blacklist prefix, see
     *       {@link #simulateBootOrderAssignment}).</li>
     * </ul>
     * Each candidate pair runs the full <b>cross-validation firewall</b>
     * ({@link #validateCandidate}: fresh money block line-up + fingerprint old/fresh
     * consistency). The firewall stays strict: a repair is offered only when the evidence
     * matches exactly <b>one</b> candidate consistently — no candidate, or (theoretically)
     * both, records {@link RepairPlan#validationFailures} and <b>no repair is offered</b>
     * (empty repaired map); the report then points the admin at restoring
     * {@code ItemIDs.nbt} from a world backup instead. The validated candidate's OLD
     * mapping is the one the repair restores.
     * <p>
     * <b>Repaired map:</b> all of {@code O} whose templates resolve, plus every corrupted-map
     * entry whose normalized identity is NOT in {@code O} (the cent coins and any
     * post-corruption registrations) re-appended at fresh shorts starting at
     * {@code max(O) + 1}, preserving their relative order in the corrupted map.
     *
     * @param currentMap      snapshot of the corrupted short→template map
     * @param aliases         snapshot of the alias table
     * @param persistedCounter the {@code nextShortCounter} restored from disk — the plan's
     *                        counter never goes below it (monotonic guarantee)
     * @param access          server registry access for the simulations
     * @return the full plan; check {@link RepairPlan#validationFailures} before applying
     */
    public static RepairPlan buildRepairPlan(Map<ItemID, ItemStack> currentMap,
                                             Map<ItemID, ItemID> aliases,
                                             int persistedCounter,
                                             @NotNull RegistryAccess access) {
        // ---- Common precondition: without money fingerprints nothing ties this map to ----
        // ---- the corruption at all — no candidate can validate.                       ----
        List<AliasFingerprint> fingerprints = collectMoneyFingerprints(currentMap, aliases);
        if (fingerprints.isEmpty()) {
            return new RepairPlan(new LinkedHashMap<>(), Set.of(), new LinkedHashMap<>(),
                    new TreeSet<>(), Math.max(persistedCounter, 1),
                    List.of("no money fingerprint aliases present — nothing ties this map to the cent-shift bug"));
        }

        // ---- Candidate epochs (Task #16 Fix D): two historical fresh-assignment orders. ----
        // Candidate A — pure createDefaultItemIDs() order (builds where the default pass
        // ran first pre-load); candidate B — the constructor boot order (builds whose
        // ServerBankManager constructor was the first registrar, see
        // simulateBootOrderAssignment). The firewall stays strict: a plan is offered only
        // when the evidence matches exactly ONE candidate consistently.
        LinkedHashMap<Short, ItemStack> oldA = simulateDefaultAssignment(access, false, 1);
        LinkedHashMap<Short, ItemStack> freshA = simulateDefaultAssignment(access, true, 1);
        List<String> failuresA = validateCandidate(currentMap, fingerprints, oldA, freshA);

        LinkedHashMap<Short, ItemStack> oldB = simulateBootOrderAssignment(access, false, 1);
        LinkedHashMap<Short, ItemStack> freshB = simulateBootOrderAssignment(access, true, 1);
        List<String> failuresB = validateCandidate(currentMap, fingerprints, oldB, freshB);

        boolean candidateAValid = failuresA.isEmpty();
        boolean candidateBValid = failuresB.isEmpty();

        if (candidateAValid && candidateBValid) {
            // Should be impossible (the two fresh money blocks occupy different shorts, so
            // firewall check 1 cannot pass for both) — refuse rather than guess an epoch.
            return new RepairPlan(new LinkedHashMap<>(), Set.of(), new LinkedHashMap<>(),
                    new TreeSet<>(), Math.max(persistedCounter, 1),
                    List.of("evidence matches BOTH candidate boot orders (default order and "
                            + "constructor boot order) — ambiguous, no repair can be chosen safely"));
        }
        if (!candidateAValid && !candidateBValid) {
            // Neither epoch fits → NO repair is offered. Report both candidates' failures,
            // prefixed, so the admin/report can see how close each candidate came.
            List<String> combined = new ArrayList<>();
            for (String failure : failuresA)
                combined.add("[default-order candidate] " + failure);
            for (String failure : failuresB)
                combined.add("[boot-order candidate] " + failure);
            return new RepairPlan(new LinkedHashMap<>(), Set.of(), new LinkedHashMap<>(),
                    new TreeSet<>(), Math.max(persistedCounter, 1), List.copyOf(combined));
        }

        // Exactly one candidate validated — its OLD mapping is the epoch the world data
        // still references and therefore the mapping the repair restores.
        LinkedHashMap<Short, ItemStack> oldMapping = candidateAValid ? oldA : oldB;

        // ---- Repaired map: all of O (resolvable templates), then the append set. ----
        LinkedHashMap<ItemID, ItemStack> repaired = new LinkedHashMap<>();
        List<ItemStack> repairedTemplates = new ArrayList<>(); // normalized identity index
        int maxRepairedShort = 0;
        for (Map.Entry<Short, ItemStack> entry : oldMapping.entrySet()) {
            ItemStack template = entry.getValue();
            if (template == null || template.isEmpty())
                continue; // template does not resolve — its short stays burned, never re-bound
            repaired.put(makeItemID(entry.getKey(), template), template.copy());
            repairedTemplates.add(template);
            maxRepairedShort = Math.max(maxRepairedShort, Short.toUnsignedInt(entry.getKey()));
        }

        // Append every corrupted-map entry whose normalized identity is NOT in O — the cent
        // coins and anything registered after the corruption — at fresh shorts above the old
        // maximum, preserving their relative (short-ascending) order in the corrupted map.
        LinkedHashMap<ItemID, ItemStack> appended = new LinkedHashMap<>();
        List<Map.Entry<ItemID, ItemStack>> currentSorted = new ArrayList<>(currentMap.entrySet());
        currentSorted.sort(Comparator.comparingInt(e -> Short.toUnsignedInt(e.getKey().getShort())));
        int nextAppendShort = maxRepairedShort + 1;
        for (Map.Entry<ItemID, ItemStack> entry : currentSorted) {
            ItemStack raw = entry.getValue();
            if (raw == null || raw.isEmpty())
                continue;
            ItemStack normalized = VolatileItemComponents.normalize(raw);
            normalized.setCount(1);
            if (containsIdentity(repairedTemplates, normalized))
                continue; // identity already carried over from O (or already appended)
            if (nextAppendShort > Short.MAX_VALUE) {
                // ItemID space exhausted while appending — cannot build a complete plan.
                return new RepairPlan(new LinkedHashMap<>(), Set.of(), new LinkedHashMap<>(),
                        new TreeSet<>(), Math.max(persistedCounter, 1),
                        List.of("ItemID short space exhausted while re-appending post-corruption entries — no repair possible"));
            }
            ItemID appendedId = makeItemID((short) nextAppendShort, normalized);
            repaired.put(appendedId, normalized.copy());
            appended.put(appendedId, normalized.copy());
            repairedTemplates.add(normalized);
            maxRepairedShort = nextAppendShort;
            nextAppendShort++;
        }

        // ---- Aliases to drop: the fingerprints + every alias re-occupied by the repaired map. ----
        Set<ItemID> aliasesToDrop = new HashSet<>();
        for (AliasFingerprint fp : fingerprints)
            aliasesToDrop.add(new ItemID(fp.from()));
        for (ItemID source : aliases.keySet()) {
            // putAlias invariant: an alias source short must never also be a live map key.
            if (repaired.containsKey(source))
                aliasesToDrop.add(source);
        }

        // ---- Changed shorts: every short whose resolved item differs corrupted vs repaired. ----
        TreeSet<Short> changedShorts = new TreeSet<>();
        Set<Short> allShorts = new HashSet<>();
        for (ItemID id : currentMap.keySet())
            allShorts.add(id.getShort());
        for (ItemID id : repaired.keySet())
            allShorts.add(id.getShort());
        for (Short shortValue : allShorts) {
            ItemStack before = currentMap.get(new ItemID(shortValue));
            ItemStack after = repaired.get(new ItemID(shortValue));
            if ((before == null) != (after == null)) {
                changedShorts.add(shortValue);
            } else if (before != null) {
                // Compare on normalized identity — repaired templates are already normalized.
                ItemStack beforeNormalized = VolatileItemComponents.normalize(before);
                beforeNormalized.setCount(1);
                if (!ItemStack.isSameItemSameComponents(beforeNormalized, after))
                    changedShorts.add(shortValue);
            }
        }

        // ---- Counter: never decreases below the persisted value (monotonic guarantee). ----
        int newNextShortCounter = Math.max(persistedCounter, maxRepairedShort + 1);

        return new RepairPlan(repaired, Set.copyOf(aliasesToDrop), appended, changedShorts,
                newNextShortCounter, List.of());
    }

    /**
     * Runs the cross-validation firewall checks for ONE candidate epoch pair
     * ({@code oldMapping} = the assignment the world data still references,
     * {@code freshMapping} = the fresh assignment the buggy boot wrote):
     * <ul>
     *   <li><b>Check 1:</b> the corrupted map's money/cent block must match
     *       {@code freshMapping}'s money block exactly (same shorts for the same items);</li>
     *   <li><b>Check 2:</b> every money fingerprint alias {@code from → to} must be
     *       consistent with both candidates: the canonical money item's old-short must equal
     *       {@code from} and its fresh-short must equal {@code to}.</li>
     * </ul>
     * Pure; returns the failures instead of mutating shared state so
     * {@link #buildRepairPlan} can evaluate several candidates independently.
     *
     * @return human-readable failures; empty = the candidate validates
     */
    private static List<String> validateCandidate(Map<ItemID, ItemStack> currentMap,
                                                  List<AliasFingerprint> fingerprints,
                                                  LinkedHashMap<Short, ItemStack> oldMapping,
                                                  LinkedHashMap<Short, ItemStack> freshMapping) {
        List<String> failures = new ArrayList<>();

        // ---- Firewall check 1: corrupted money block == F's money block, item for item. ----
        for (ItemStack moneyStack : BankSystemItems.getMoneyItems()) {
            Item item = moneyStack.getItem();
            String name = ItemUtilities.getItemIDStr(item);
            Short freshShort = findUniqueShortByItem(shortKeyedView(freshMapping), item);
            Short currentShort = findUniqueShortByItem(currentMap, item);
            if (freshShort == null) {
                failures.add("money item " + name + " missing/ambiguous in the simulated fresh assignment");
            } else if (currentShort == null) {
                failures.add("money item " + name + " missing/ambiguous in the corrupted map");
            } else if (!freshShort.equals(currentShort)) {
                failures.add("money item " + name + " sits at short #" + Short.toUnsignedInt(currentShort)
                        + " in the corrupted map but the simulated fresh assignment places it at #"
                        + Short.toUnsignedInt(freshShort) + " — the map does not match a fresh default assignment");
            }
        }

        // ---- Firewall check 2: every fingerprint alias must line up with O and F. ----
        for (AliasFingerprint fp : fingerprints) {
            Item item = fp.canonicalTemplate().getItem();
            String name = ItemUtilities.getItemIDStr(item);
            Short oShort = findUniqueShortByItem(shortKeyedView(oldMapping), item);
            Short fShort = findUniqueShortByItem(shortKeyedView(freshMapping), item);
            if (oShort == null || oShort != fp.from()) {
                failures.add("fingerprint alias #" + Short.toUnsignedInt(fp.from()) + " -> #"
                        + Short.toUnsignedInt(fp.to()) + " (" + name + "): the simulated OLD assignment places "
                        + name + " at " + (oShort == null ? "<absent>" : "#" + Short.toUnsignedInt(oShort))
                        + ", not at the alias source — evidence inconsistent with the candidate old mapping");
            }
            if (fShort == null || fShort != fp.to()) {
                failures.add("fingerprint alias #" + Short.toUnsignedInt(fp.from()) + " -> #"
                        + Short.toUnsignedInt(fp.to()) + " (" + name + "): the simulated FRESH assignment places "
                        + name + " at " + (fShort == null ? "<absent>" : "#" + Short.toUnsignedInt(fShort))
                        + ", not at the alias target — evidence inconsistent with the fresh mapping");
            }
        }
        return failures;
    }

    /** True when any template in the list has the same normalized item+components identity. */
    private static boolean containsIdentity(List<ItemStack> templates, ItemStack normalized) {
        for (ItemStack existing : templates) {
            if (ItemStack.isSameItemSameComponents(normalized, existing))
                return true;
        }
        return false;
    }

    /**
     * Builds an {@link ItemID} with a correctly pre-filled name cache (the item's registry
     * name), so a swapped-in repaired map never exposes stale numeric placeholder names.
     */
    private static ItemID makeItemID(short shortValue, ItemStack template) {
        return new ItemID(shortValue, ItemUtilities.getItemIDStr(template.getItem()));
    }

    /** Adapts a short→template map to the ItemID-keyed shape {@link #findUniqueShortByItem} expects. */
    private static Map<ItemID, ItemStack> shortKeyedView(Map<Short, ItemStack> byShort) {
        Map<ItemID, ItemStack> view = new LinkedHashMap<>();
        for (Map.Entry<Short, ItemStack> entry : byShort.entrySet())
            view.put(new ItemID(entry.getKey()), entry.getValue());
        return view;
    }

    // ========================================================================================
    // Report
    // ========================================================================================

    /**
     * Builds the human-readable repair report shown to the admin — either as the message of
     * the startup-aborting {@link ItemIDRepairRequiredException} (flag off), as an INFO log
     * before a confirmed repair is applied (flag on), or as an ERROR log when the
     * cross-validation firewall rejected the evidence (warn-and-continue). Modeled on
     * {@code ItemIDManager.buildMergeReport()}.
     *
     * @param evidence     the detection evidence (fingerprints, money block, provenance)
     * @param plan         the repair plan (or the validation failures when rejected)
     * @param corruptedMap snapshot of the corrupted map — used to render the "was" column of
     *                     the remap table (the plan itself only carries the "becomes" side)
     * @return multi-line report: detection summary, remap table or validation failures,
     *         mixed-epoch caveat, and the admin's options
     */
    public static String buildRepairReport(CorruptionEvidence evidence,
                                           RepairPlan plan,
                                           Map<ItemID, ItemStack> corruptedMap) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n================== BankSystem ItemID world-repair guard ==================\n");
        sb.append("This world's ItemIDs.nbt matches the signature of the pre-v2.0.3 load-order\n");
        sb.append("bug: a buggy build overwrote the persisted short->item mapping with a fresh\n");
        sb.append("cent-shifted default assignment, while all other world data (bank balances,\n");
        sb.append("placed blocks, StockMarket data, balance history) still references the OLD\n");
        sb.append("shorts — items therefore resolve to wrong items (typically shifted by 5).\n");
        if (evidence.lastSavedByModVersion() != null)
            sb.append("World last saved by BankSystem ").append(evidence.lastSavedByModVersion()).append(".\n");

        sb.append("\nEvidence:\n");
        sb.append("  1) Money fingerprint aliases left behind by the bad merge:\n");
        for (AliasFingerprint fp : evidence.fingerprints()) {
            sb.append("       #").append(Short.toUnsignedInt(fp.from()))
                    .append(" -> #").append(Short.toUnsignedInt(fp.to()))
                    .append(" (").append(describeStack(fp.canonicalTemplate())).append(")\n");
        }
        sb.append("  2) All ").append(evidence.freshMoneyBlock().size())
                .append(" money/cent items sit in one contiguous ascending block (shorts ");
        List<Short> blockShorts = new ArrayList<>(evidence.freshMoneyBlock().keySet());
        if (!blockShorts.isEmpty()) {
            sb.append('#').append(Short.toUnsignedInt(blockShorts.getFirst()))
                    .append("..#").append(Short.toUnsignedInt(blockShorts.getLast()));
        }
        sb.append(") — the signature of a fresh post-cent default assignment.\n");

        if (!plan.validationFailures().isEmpty()) {
            sb.append("\nHOWEVER, the evidence FAILED cross-validation against the simulated old and\n");
            sb.append("fresh default assignments — an automatic repair can NOT be offered:\n");
            for (String failure : plan.validationFailures())
                sb.append("  - ").append(failure).append('\n');
            sb.append("\nIMPORTANT — this may be a FALSE ALARM: a healthy world can match parts of\n");
            sb.append("this signature (e.g. a legitimate component-set merge whose canonical entry\n");
            sb.append("is a money item). If this world has NEVER shown wrong-item symptoms — items\n");
            sb.append("resolving to entirely different items, money deposits rejected as a wrong or\n");
            sb.append("blacklisted denomination — do NOT restore anything; no action is needed and\n");
            sb.append("restoring an old ItemIDs.nbt over a healthy world would CORRUPT it.\n");
            sb.append("\nOnly if those symptoms ARE present:\n");
            sb.append("  Restore data/BankSystem/ItemIDs.nbt from a world backup taken BEFORE the\n");
            sb.append("  symptoms started, then restart the server. The server continues running\n");
            sb.append("  for now (nothing is repaired automatically).\n");
            sb.append("===========================================================================");
            return sb.toString();
        }

        sb.append("\nProposed repair — restore the old short->item mapping (")
                .append(plan.changedShorts().size()).append(" shorts change meaning):\n");
        for (Short shortValue : plan.changedShorts()) {
            ItemStack before = corruptedMap == null ? null : corruptedMap.get(new ItemID(shortValue));
            ItemStack after = plan.repairedMap().get(new ItemID(shortValue));
            sb.append("  #").append(Short.toUnsignedInt(shortValue)).append(": ")
                    .append(before == null ? "<unassigned>" : describeStack(before))
                    .append(" -> ")
                    .append(after == null ? "<unassigned>" : describeStack(after))
                    .append('\n');
        }
        if (!plan.appended().isEmpty()) {
            sb.append("Entries re-appended at fresh shorts (no old-epoch short exists for them):\n");
            for (Map.Entry<ItemID, ItemStack> entry : plan.appended().entrySet()) {
                sb.append("  #").append(Short.toUnsignedInt(entry.getKey().getShort()))
                        .append(" = ").append(describeStack(entry.getValue())).append('\n');
            }
        }
        if (!plan.aliasesToDrop().isEmpty()) {
            sb.append("Alias entries dropped by the repair: ");
            List<Integer> dropShorts = new ArrayList<>();
            for (ItemID id : plan.aliasesToDrop())
                dropShorts.add(Short.toUnsignedInt(id.getShort()));
            dropShorts.sort(Integer::compareTo);
            for (int i = 0; i < dropShorts.size(); i++)
                sb.append(i == 0 ? "#" : ", #").append(dropShorts.get(i));
            sb.append('\n');
        }
        sb.append("nextShortCounter after the repair: ").append(plan.newNextShortCounter()).append('\n');

        sb.append("\nMIXED-EPOCH CAVEAT: any world data written SINCE the buggy build under one of\n");
        sb.append("the changed shorts above was recorded against the WRONG item and changes its\n");
        sb.append("meaning when the repair is applied. In particular, balance-history rows from\n");
        sb.append("that window stay keyed to the wrong item — they are reported here but are NOT\n");
        sb.append("deleted (review and clean them manually if they matter).\n");

        sb.append("\nYour options:\n");
        sb.append("  1) Restore data/BankSystem/ItemIDs.nbt from a world backup taken before the\n");
        sb.append("     corruption, then restart (preferred when a good backup exists).\n");
        sb.append("  2) Approve this repair once: set \"CONFIRM_ITEMID_REPAIR\": true in the\n");
        sb.append("     \"ServerBank\" section of <world>/data/BankSystem/settings.json and restart.\n");
        sb.append("     The flag resets itself to false after the repair is applied; the previous\n");
        sb.append("     ItemIDs.nbt is copied aside as ItemIDs.nbt.pre-repair-<timestamp> first.\n");
        sb.append("Nothing has been changed or saved: BankSystem suppresses all of its data saves\n");
        sb.append("for the remainder of an aborted session, so the world data on disk stays\n");
        sb.append("exactly as it was. Back up your world before confirming.\n");
        sb.append("===========================================================================");
        return sb.toString();
    }

    /** One report token for a template: its item registry name (or {@code ?} when empty). */
    private static String describeStack(ItemStack stack) {
        if (stack == null || stack.isEmpty())
            return "?";
        return ItemUtilities.getItemIDStr(stack.getItem());
    }

    /**
     * Stable, deterministic hash of an evidence state, used as the log-acknowledgment key
     * for the warn-and-continue path: the full validation-failure report is logged at ERROR
     * only the first time a given evidence state is seen; once its hash is recorded in
     * {@code Meta_data.nbt}, later startups with the SAME evidence emit a single WARN line
     * instead. Any change to the evidence (new fingerprint, moved money block) produces a
     * different hash and re-triggers the full report.
     * <p>
     * Built from the sorted fingerprint tuples (from, to, canonical item name) and the money
     * block (short → item name) via {@link String#hashCode()} — whose algorithm is fixed by
     * the Java spec, so the hash is stable across JVMs and restarts. Collisions are
     * inconsequential (worst case: a changed evidence state logs a WARN instead of the full
     * report once).
     *
     * @param evidence the evidence bundle to fingerprint
     * @return a short stable hex hash of the evidence state
     */
    public static String evidenceFingerprint(CorruptionEvidence evidence) {
        StringBuilder sb = new StringBuilder();
        for (AliasFingerprint fp : evidence.fingerprints()) {
            sb.append(Short.toUnsignedInt(fp.from())).append('>')
                    .append(Short.toUnsignedInt(fp.to())).append(':')
                    .append(describeStack(fp.canonicalTemplate())).append(';');
        }
        sb.append("|block:");
        for (Map.Entry<Short, ItemStack> entry : evidence.freshMoneyBlock().entrySet()) {
            sb.append(Short.toUnsignedInt(entry.getKey())).append('=')
                    .append(describeStack(entry.getValue())).append(';');
        }
        return Integer.toHexString(sb.toString().hashCode());
    }
}
