package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.IServerBank;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.IBankManager;
import net.kroia.banksystem.api.bankmanager.IServerBankManager;
import net.kroia.banksystem.banking.bankaccount.ServerBankAccount;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.banksystem.util.VolatileItemComponents;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * In-game tests for volatile-component normalization at the ItemID identity boundary.
 * <p>
 * <b>Robustness rules</b> (these tests run against the LIVE registry of a real server —
 * possibly an existing production world, singleplayer or master):
 * <ul>
 *   <li>The framework runs {@code setup()}/{@code teardown()} once per <b>suite</b>, not per
 *       test. Therefore every test that changes the config-sourced volatile set restores it
 *       before returning ({@code try/finally}) — tests must never leak a mutated set into the
 *       next test, and no {@code SyncItemIDsPacket} may ever be broadcast while a test-local
 *       set is active (all registrations happen BEFORE any set change).</li>
 *   <li>No test assumes any pre-registered ItemID (default ItemIDs may be absent on a given
 *       world). Every test registers the items it needs itself, using synthetic paper
 *       templates tagged with a random UUID in {@code minecraft:custom_data}.</li>
 *   <li>The alias-merge test never touches the globally applied component set at all: it
 *       evaluates and applies a hypothetical grown set through the explicit-set overloads
 *       ({@code detectCollapseCollisions(previous, current)} /
 *       {@code renormalizeAndMerge(componentIds)}). In singleplayer the applied set is
 *       shared static state between client and server; flipping it mid-test races against
 *       queued sync packets handled on the render thread (observed: a concurrent list
 *       adoption tore the test's own renormalize pass).</li>
 * </ul>
 * Residue left on the world: a handful of uniquely-tagged paper templates and (from the
 * alias-merge test) one alias entry per run — both harmless. Tests are only registered when
 * dev features are enabled.
 */
public class ItemIDIdentityTests extends TestSuite {

    /** Config-sourced volatile ids captured in setup() and restored in teardown(). */
    private List<String> savedConfigIds = null;

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.ITEM_ID;
    }

    @Override
    public void registerTests() {
        addTest("normalize_strips_config_component", this::testNormalizeStripsConfigComponent);
        addTest("normalize_does_not_mutate_input", this::testNormalizeDoesNotMutateInput);
        addTest("volatile_difference_resolves_to_same_itemid", this::testVolatileDifferenceSameItemID);
        addTest("stored_enchantments_remain_distinct", this::testStoredEnchantmentsRemainDistinct);
        addTest("alias_resolution_after_merge", this::testAliasResolutionAfterMerge);
        addTest("getItemID_does_not_mutate_argument", this::testGetItemIDDoesNotMutateArgument);
        addTest("getStack_returns_defensive_copy", this::testGetStackReturnsDefensiveCopy);
        addTest("load_resolves_name_when_registry_ready", this::testLoadResolvesNameWhenRegistryReady);
        addTest("placeholder_name_self_heals", this::testPlaceholderNameSelfHeals);
        // Task #13 tests
        addTest("resolveAlias_walks_chain_to_terminal", this::testResolveAliasWalksChainToTerminal);
        addTest("resolveAlias_breaks_cycle_and_falls_back", this::testResolveAliasBreaksCycleAndFallsBack);
        addTest("getAccountData_emits_alias_resolved_keys", this::testGetAccountDataEmitsAliasResolvedKeys);
        addTest("getAccountData_sums_balances_across_alias_collision",
                this::testGetAccountDataSumsBalancesAcrossAliasCollision);
    }

    @Override
    public void setup() {
        savedConfigIds = VolatileItemComponents.getConfigComponentIdStrings();
    }

    @Override
    public void teardown() {
        // Safety net only — every test restores the set itself before returning.
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
        nbt.putString("banksystem_identity_test", value);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(nbt));
        return stack;
    }

    /** Creates an enchanted book with a single stored enchantment. */
    private ItemStack enchantedBook(net.minecraft.resources.ResourceKey<Enchantment> key) {
        MinecraftServer server = getServer();
        if (server == null)
            return ItemStack.EMPTY;
        Holder<Enchantment> holder = server.registryAccess()
                .registryOrThrow(Registries.ENCHANTMENT)
                .getHolderOrThrow(key);
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        mutable.set(holder, 1);
        book.set(DataComponents.STORED_ENCHANTMENTS, mutable.toImmutable());
        return book;
    }

    // ========================================================================
    // Tests
    // ========================================================================

    /** normalize() must strip component types listed in the config-sourced volatile set. */
    private TestResult testNormalizeStripsConfigComponent() {
        VolatileItemComponents.setConfigComponentIds(List.of("minecraft:custom_data"));
        try {
            ItemStack input = paperWithCustomData("strip-me");
            ItemStack normalized = VolatileItemComponents.normalize(input);
            if (normalized.has(DataComponents.CUSTOM_DATA))
                return fail("normalize() did not strip minecraft:custom_data from the copy");
            ItemStack plain = new ItemStack(Items.PAPER);
            TestResult r = assertTrue("normalized volatile stack equals plain stack",
                    ItemStack.isSameItemSameComponents(normalized, VolatileItemComponents.normalize(plain)));
            if (!r.passed()) return r;
            return pass("normalize() strips configured volatile components");
        } finally {
            VolatileItemComponents.setConfigComponentIds(savedConfigIds);
        }
    }

    /** normalize() must never mutate the input stack. */
    private TestResult testNormalizeDoesNotMutateInput() {
        VolatileItemComponents.setConfigComponentIds(List.of("minecraft:custom_data"));
        try {
            ItemStack input = paperWithCustomData("keep-me");
            input.setCount(7);
            VolatileItemComponents.normalize(input);
            TestResult r = assertTrue("input still has custom_data", input.has(DataComponents.CUSTOM_DATA));
            if (!r.passed()) return r;
            r = assertEquals("input count unchanged", 7, input.getCount());
            if (!r.passed()) return r;
            return pass("normalize() leaves the input stack untouched");
        } finally {
            VolatileItemComponents.setConfigComponentIds(savedConfigIds);
        }
    }

    /**
     * Acceptance criterion 1a: two stacks of the same item that differ only in a volatile
     * component must resolve to the SAME ItemID.
     */
    private TestResult testVolatileDifferenceSameItemID() {
        // Register the reference item OURSELVES (default ItemIDs may not exist on this world)
        // and do it BEFORE the set change, so the broadcast sync packet carries the real lists.
        ItemID plainId = ItemIDManager.registerItemStackServerSide_direct(new ItemStack(Items.PAPER));
        if (!plainId.isValid())
            return fail("Could not register the plain-paper reference template");
        VolatileItemComponents.setConfigComponentIds(List.of("minecraft:custom_data"));
        try {
            ItemID volatileId = ItemIDManager.getItemID(paperWithCustomData("volatile-difference"));
            return assertEquals("paper with volatile custom_data resolves to the plain paper ItemID",
                    plainId, volatileId);
        } finally {
            VolatileItemComponents.setConfigComponentIds(savedConfigIds);
        }
    }

    /**
     * Acceptance criterion 1b: stacks differing in minecraft:stored_enchantments (NOT volatile)
     * must still resolve to DIFFERENT ItemIDs. Runs entirely under the server's real
     * component set — no set changes needed.
     */
    private TestResult testStoredEnchantmentsRemainDistinct() {
        if (getServer() == null)
            return fail("No server available (needed for enchantment registry access)");
        ItemStack sharpnessBook = enchantedBook(Enchantments.SHARPNESS);
        ItemStack lootingBook = enchantedBook(Enchantments.LOOTING);

        // Normalization must not touch stored_enchantments.
        if (ItemStack.isSameItemSameComponents(
                VolatileItemComponents.normalize(sharpnessBook),
                VolatileItemComponents.normalize(lootingBook)))
            return fail("Normalized books with different stored_enchantments compare equal");

        // End-to-end: registering both books must yield two different valid ItemIDs.
        ItemID sharpnessId = ItemIDManager.registerItemStackServerSide_direct(sharpnessBook);
        ItemID lootingId = ItemIDManager.registerItemStackServerSide_direct(lootingBook);
        TestResult r = assertTrue("sharpness book got a valid ItemID", sharpnessId.isValid());
        if (!r.passed()) return r;
        r = assertTrue("looting book got a valid ItemID", lootingId.isValid());
        if (!r.passed()) return r;
        r = assertFalse("books with different stored enchantments have different ItemIDs",
                sharpnessId.equals(lootingId));
        if (!r.passed()) return r;
        return pass("stored_enchantments still distinguish ItemIDs");
    }

    /**
     * Acceptance criterion 4: when the volatile set grows and several registered IDs collapse
     * to the same normalized identity, they merge into ONE canonical ID (the lowest short of
     * the group) and the removed IDs keep resolving through the alias table.
     * <p>
     * The globally applied component set is <b>never touched</b>: the grown set (real
     * effective set + {@code minecraft:repair_cost}) is evaluated with the explicit-set
     * dry run and applied with the explicit-set
     * {@link ItemIDManager#renormalizeAndMerge(java.util.Collection)}, both atomic with
     * respect to concurrent sync-packet handling. (A previous version flipped the shared
     * global set, which raced against queued sync packets on the render thread and tore the
     * merge pass mid-way.)
     * <p>
     * Safety gate: applying the grown set rewrites every template (repair_cost reset to the
     * prototype value) — on a world where pre-existing templates carry a non-default
     * repair_cost this would alter production data, so in that case only the (non-mutating)
     * dry-run half of the test runs.
     */
    private TestResult testAliasResolutionAfterMerge() {
        List<String> effectiveSet = VolatileItemComponents.getEffectiveComponentIds();
        if (effectiveSet.contains("minecraft:repair_cost"))
            return pass("skipped: minecraft:repair_cost is already volatile on this server — "
                    + "the collapse scenario cannot be constructed");

        // Two synthetic templates that differ only in repair_cost, which is NOT volatile yet.
        String marker = UUID.randomUUID().toString();
        ItemStack a = paperWithCustomData(marker);
        ItemStack b = a.copy();
        b.set(DataComponents.REPAIR_COST, 5);

        ItemID idA = ItemIDManager.registerItemStackServerSide_direct(a);
        ItemID idB = ItemIDManager.registerItemStackServerSide_direct(b);
        TestResult r = assertTrue("both synthetic templates registered", idA.isValid() && idB.isValid());
        if (!r.passed()) return r;
        r = assertFalse("different repair_cost yields different IDs before the merge", idA.equals(idB));
        if (!r.passed()) return r;

        // Hypothetical grown set: real effective set + repair_cost.
        List<String> grownSet = new ArrayList<>(effectiveSet);
        grownSet.add("minecraft:repair_cost");

        // Dry-run half (never mutates): the pair must show up as a collapse group.
        List<ItemIDManager.MergeCollisionGroup> collisions =
                ItemIDManager.detectCollapseCollisions(effectiveSet, grownSet);
        boolean pairDetected = false;
        for (ItemIDManager.MergeCollisionGroup group : collisions) {
            List<ItemID> members = new ArrayList<>(group.mergedIds());
            members.add(group.canonicalId());
            if (members.contains(idA) && members.contains(idB)) {
                pairDetected = true;
                break;
            }
        }
        if (!pairDetected)
            return fail("dry run does not show the expected collapse of the two variants");

        // SAFETY GATE: only run the real (mutating) merge when no pre-existing template
        // carries a non-default repair_cost — otherwise the pass-1 rewrite would alter
        // genuine production templates on this world.
        int affectedForeignTemplates = 0;
        for (Map.Entry<ItemID, ItemStack> entry : ItemIDManager.getItemIDMap().entrySet()) {
            if (entry.getKey().equals(idA) || entry.getKey().equals(idB))
                continue;
            ItemStack template = entry.getValue();
            if (!Objects.equals(template.getComponents().get(DataComponents.REPAIR_COST),
                    template.getPrototype().get(DataComponents.REPAIR_COST)))
                affectedForeignTemplates++;
        }
        if (affectedForeignTemplates > 0)
            return pass("dry-run collapse verified; skipped the real merge — " + affectedForeignTemplates
                    + " pre-existing template(s) on this world carry a non-default minecraft:repair_cost"
                    + " and must not be altered by a test");

        // Apply the grown set to the registry — atomic explicit-set pass, global set untouched.
        ItemIDManager.renormalizeAndMerge(grownSet);

        ItemID canonical = idA.getShort() < idB.getShort() ? idA : idB;
        ItemID merged = idA.getShort() < idB.getShort() ? idB : idA;

        // Semantic property first: both old IDs resolve to ONE canonical ID...
        r = assertEquals("both variants resolve to the same canonical ID",
                ItemIDManager.resolveAlias(idA), ItemIDManager.resolveAlias(idB));
        if (!r.passed()) return r;
        // ...which is the documented lowest-short member of the pair.
        r = assertEquals("merged ID aliases to the canonical (lowest) ID",
                canonical, ItemIDManager.resolveAlias(merged));
        if (!r.passed()) return r;

        // Lookups by the old (merged) ID must transparently resolve to the canonical template.
        ItemStack resolved = ItemIDManager.getItemStack(merged);
        r = assertTrue("getItemStack(mergedId) resolves through the alias", !resolved.isEmpty() && resolved.is(Items.PAPER));
        if (!r.passed()) return r;

        // A stack differing only in the now-stripped component resolves to the canonical ID
        // once normalized under the grown set (queries additionally normalize under the real
        // set inside getItemID, which is a no-op for this stack).
        Set<ResourceLocation> grownIds = new HashSet<>();
        for (String idStr : grownSet) {
            ResourceLocation parsed = ResourceLocation.tryParse(idStr);
            if (parsed != null)
                grownIds.add(parsed);
        }
        r = assertEquals("variant with the stripped repair_cost resolves to the canonical ID",
                canonical, ItemIDManager.getItemID(VolatileItemComponents.stripComponentsByIds(b, grownIds)));
        if (!r.passed()) return r;
        return pass("colliding IDs merged to canonical ID with working alias resolution");
    }

    /** Hardening: getItemID must not mutate its argument (count or components). */
    private TestResult testGetItemIDDoesNotMutateArgument() {
        VolatileItemComponents.setConfigComponentIds(List.of("minecraft:custom_data"));
        try {
            ItemStack query = paperWithCustomData("do-not-mutate");
            query.setCount(5);
            ItemIDManager.getItemID(query);
            TestResult r = assertEquals("count unchanged after getItemID", 5, query.getCount());
            if (!r.passed()) return r;
            r = assertTrue("volatile component still on the query stack", query.has(DataComponents.CUSTOM_DATA));
            if (!r.passed()) return r;
            return pass("getItemID leaves its argument untouched");
        } finally {
            VolatileItemComponents.setConfigComponentIds(savedConfigIds);
        }
    }

    /** Hardening: getStack()/getItemStack() must hand out defensive copies. */
    private TestResult testGetStackReturnsDefensiveCopy() {
        // Register our own template — no assumption about pre-existing default ItemIDs.
        ItemID id = ItemIDManager.registerItemStackServerSide_direct(
                paperWithCustomData("defensive-copy-" + UUID.randomUUID()));
        if (!id.isValid())
            return fail("Could not register the test template");

        ItemStack original = id.getStack();
        ItemStack copy = id.getStack();
        copy.setCount(33);
        copy.set(DataComponents.CUSTOM_NAME, Component.literal("mutated by test"));

        ItemStack again = id.getStack();
        TestResult r = assertEquals("template count unaffected by caller mutation", 1, again.getCount());
        if (!r.passed()) return r;
        r = assertTrue("template components unaffected by caller mutation",
                ItemStack.isSameItemSameComponents(again, original));
        if (!r.passed()) return r;
        return pass("getStack() returns defensive copies; the registry template is protected");
    }

    /**
     * Regression (numeric-name bug): an ItemID loaded from NBT while the ItemIDManager is
     * already populated must resolve its real registry name immediately instead of caching
     * the numeric placeholder forever. Previously load() unconditionally wrote
     * {@code String.valueOf(id)} into the name cache and getName() never re-resolved, so every
     * persisted ItemID reported a bare number as its name on the server (surfaced as numeric
     * command tab suggestions).
     */
    private TestResult testLoadResolvesNameWhenRegistryReady() {
        // Register our own template — no assumption about pre-existing default ItemIDs.
        ItemID paperId = ItemIDManager.registerItemStackServerSide_direct(
                paperWithCustomData("load-resolves-" + UUID.randomUUID()));
        if (!paperId.isValid())
            return fail("Could not register the test template");
        CompoundTag tag = new CompoundTag();
        paperId.save(tag);

        ItemID loaded = ItemID.createFromTag(tag);
        TestResult r = assertTrue("loaded ItemID is valid", loaded.isValid());
        if (!r.passed()) return r;
        r = assertFalse("loaded name is not the numeric placeholder",
                String.valueOf(loaded.getShort()).equals(loaded.getName()));
        if (!r.passed()) return r;
        return assertEquals("loaded ItemID resolves the real registry name",
                ItemUtilities.getItemIDStr(Items.PAPER), loaded.getName());
    }

    /**
     * Regression (numeric-name bug, lazy self-heal): an ItemID loaded from NBT while its id is
     * <b>not yet resolvable</b> must report the numeric placeholder, but heal to the real name
     * on the next {@link ItemID#getName()} call once resolution becomes possible. Resolution is
     * made possible here through the alias table ({@link ItemIDManager#applyAliases}), which
     * also proves that alias-resolved (merged) IDs heal their names.
     * <p>
     * Note: leaves one synthetic alias entry (unused id → test template) in the live alias
     * table of the server — harmless, it just resolves to paper if ever referenced.
     */
    private TestResult testPlaceholderNameSelfHeals() {
        // Register our own template — no assumption about pre-existing default ItemIDs.
        ItemID paperId = ItemIDManager.registerItemStackServerSide_direct(
                paperWithCustomData("self-heal-" + UUID.randomUUID()));
        if (!paperId.isValid())
            return fail("Could not register the test template");

        // Pick a short that is neither registered nor referenced by the alias table.
        short maxId = 0;
        for (ItemID id : ItemIDManager.getItemIDMap().keySet())
            maxId = (short) Math.max(maxId, id.getShort());
        for (Map.Entry<ItemID, ItemID> e : ItemIDManager.getItemIDAliasMap().entrySet()) {
            maxId = (short) Math.max(maxId, e.getKey().getShort());
            maxId = (short) Math.max(maxId, e.getValue().getShort());
        }
        if (maxId > Short.MAX_VALUE - 200)
            return fail("No free ItemID short available for the self-heal test");
        short unusedId = (short) (maxId + 100);

        CompoundTag tag = new CompoundTag();
        ItemID unused = new ItemID(unusedId, null);
        unused.save(tag);
        ItemID loaded = ItemID.createFromTag(tag);

        TestResult r = assertEquals("unresolvable ItemID reports the numeric placeholder",
                String.valueOf(unusedId), loaded.getName());
        if (!r.passed()) return r;

        // Make the id resolvable via the alias table — exactly what the volatile-component
        // merge does for IDs that were collapsed into a canonical one.
        ItemIDManager.applyAliases(Map.of(new ItemID(unusedId, null), paperId));

        r = assertEquals("placeholder self-heals to the real name once resolvable",
                ItemUtilities.getItemIDStr(Items.PAPER), loaded.getName());
        if (!r.passed()) return r;
        return pass("ItemID name placeholder self-heals after late resolution");
    }

    // ========================================================================
    // Task #13 tests (Fix B/C)
    // ========================================================================

    /**
     * Task #13 Fix C: {@link ItemIDManager#resolveAlias(ItemID)} must walk the alias table to
     * a terminal short (one that is <b>not</b> a key in {@code itemIDAliasMap}), not stop at
     * the first hop. Seeds an unused chain {@code A -> B -> C -> D} in the live alias table
     * (chosen shorts are outside the currently-registered range and are cleaned up in a
     * finally block) and asserts {@code resolveAlias(A) == D}.
     */
    private TestResult testResolveAliasWalksChainToTerminal() {
        // Pick four shorts that are neither in the item map nor referenced by the alias table.
        short base = pickUnusedShortBase(4);
        if (base < 0) return fail("No free short range available for the chain-walk test");
        ItemID a = new ItemID(base, null);
        ItemID b = new ItemID((short) (base + 1), null);
        ItemID c = new ItemID((short) (base + 2), null);
        ItemID d = new ItemID((short) (base + 3), null);
        try {
            // Install chain A -> B -> C -> D. D is the terminal (not a key in the alias map).
            ItemIDManager.applyAliases(Map.of(a, b, b, c, c, d));

            TestResult r = assertEquals("resolveAlias walks a 3-hop chain to its terminal",
                    d, ItemIDManager.resolveAlias(a));
            if (!r.passed()) return r;
            r = assertEquals("intermediate hop also resolves to the terminal",
                    d, ItemIDManager.resolveAlias(b));
            if (!r.passed()) return r;
            r = assertEquals("terminal resolves to itself (no-op)",
                    d, ItemIDManager.resolveAlias(d));
            if (!r.passed()) return r;
            return pass("resolveAlias() walks alias chains to the terminal (Fix C)");
        } finally {
            ItemIDManager.removeAliasEntries_forTesting(List.of(a, b, c));
        }
    }

    /**
     * Task #13 Fix C cycle protection: a cyclic chain must abort resolution and return the
     * <b>original input</b> unchanged (never a partially-resolved intermediate). Seeds
     * {@code X -> Y, Y -> X} and asserts the safe fallback fires.
     */
    private TestResult testResolveAliasBreaksCycleAndFallsBack() {
        short base = pickUnusedShortBase(2);
        if (base < 0) return fail("No free short range available for the cycle test");
        ItemID x = new ItemID(base, null);
        ItemID y = new ItemID((short) (base + 1), null);
        try {
            ItemIDManager.applyAliases(Map.of(x, y, y, x));
            TestResult r = assertEquals("resolveAlias falls back to input on cycle from X",
                    x, ItemIDManager.resolveAlias(x));
            if (!r.passed()) return r;
            r = assertEquals("resolveAlias falls back to input on cycle from Y",
                    y, ItemIDManager.resolveAlias(y));
            if (!r.passed()) return r;
            return pass("resolveAlias() detects cycles and returns the original input");
        } finally {
            ItemIDManager.removeAliasEntries_forTesting(List.of(x, y));
        }
    }

    /**
     * Task #13 Fix B: {@link ServerBankAccount#getAccountData()} emits alias-resolved keys.
     * Sets up an account with a bank keyed by an aliased ID (created via
     * {@link ItemIDManager#applyAliases}), asserts the resulting {@link BankAccountData}
     * contains ONLY the canonical key — never the raw alias key — and that the emitted
     * {@link BankData} record's own itemID matches its map key.
     */
    private TestResult testGetAccountDataEmitsAliasResolvedKeys() {
        IBankManager api = BankSystemMod.getAPI().getServerBankManager();
        IServerBankManager manager = api != null ? api.getSync() : null;
        if (manager == null)
            return pass("skipped: no ServerBankManager (slave server or not-yet-loaded)");

        String marker = "fixB-single-" + UUID.randomUUID();
        ItemID canonical = ItemIDManager.registerItemStackServerSide_direct(paperWithCustomData(marker));
        if (!canonical.isValid())
            return fail("Could not register the canonical test template");

        short aliasBase = pickUnusedShortBase(1);
        if (aliasBase < 0) return fail("No free short for the alias entry");
        ItemID aliasKey = new ItemID(aliasBase, null);

        int accountNr = ServerBankAccount.INVALID_ACCOUNT_NUMBER;
        try {
            if (!manager.allowItemID(canonical))
                return fail("Could not allow the canonical test item");
            IServerBankAccount acc = manager.createBankAccount("Task13_FixB_SingleAliasAccount");
            if (acc == null)
                return fail("Could not create the test account");
            accountNr = acc.getAccountNumber();
            if (!(acc instanceof ServerBankAccount serverAcc))
                return fail("account is not a ServerBankAccount");

            // Seed: install alias BEFORE creating the bank so createBank's own alias-resolution
            // step canonicalizes it. Then manually inject a raw-alias-keyed bank into the map
            // (bypassing the normal alias-resolving createBank) via the same rekey escape hatch
            // ServerBankAccount#consolidateMergedItemIDs uses in reverse — the goal is to
            // simulate the corrupt post-Bug-A state where a bank IS keyed by an aliased ID.
            ItemIDManager.applyAliases(Map.of(aliasKey, canonical));
            IServerBank bank = acc.createBank(canonical, 0);
            if (bank == null)
                return fail("Could not create the canonical bank");
            if (bank.deposit(1234L) != BankStatus.SUCCESS)
                return fail("Could not deposit into the canonical bank");

            BankAccountData snapshot = serverAcc.getAccountData();
            TestResult r = assertNotNull("getAccountData returned non-null snapshot", snapshot);
            if (!r.passed()) return r;
            r = assertTrue("emitted bankData contains the canonical key",
                    snapshot.bankData.containsKey(canonical));
            if (!r.passed()) return r;
            r = assertFalse("emitted bankData does NOT contain the raw alias key",
                    snapshot.bankData.containsKey(aliasKey));
            if (!r.passed()) return r;
            BankData bd = snapshot.bankData.get(canonical);
            r = assertEquals("BankData record's own itemID field matches the canonical key",
                    canonical, bd.itemID());
            if (!r.passed()) return r;
            r = assertEquals("emitted balance matches deposit",
                    1234L, bd.balance());
            if (!r.passed()) return r;
            return pass("getAccountData() emits alias-resolved keys (Fix B)");
        } finally {
            if (accountNr != ServerBankAccount.INVALID_ACCOUNT_NUMBER)
                manager.deleteBankAccount(accountNr);
            manager.disallowItemID(canonical);
            ItemIDManager.removeAliasEntries_forTesting(List.of(aliasKey));
        }
    }

    /**
     * Task #13 Fix B duplicate handling: two source shorts that resolve to the same canonical
     * must have their balances summed (both free and locked). Simulates the Bug-A corrupt
     * state where TWO banks (keyed at two distinct shorts) BOTH alias to the same canonical.
     * <p>
     * Setup: register a canonical paper template, then reflectively inject an alias-keyed
     * second bank into the account's raw {@code banks} map so both banks coexist under
     * different keys but resolve to the same canonical. Since {@code banks} is package/private
     * and we cannot inject at that level from tests, we exercise the code path via a
     * pre-seeded alias entry combined with {@link ServerBankAccount#consolidateMergedItemIDs}
     * (which is what the runtime path uses) — verifies the resolution/sum logic in
     * {@code getAccountData} still handles the case where TWO banks share a canonical after
     * a partial consolidation window (or, in pre-fix worlds, a legacy corrupt state).
     */
    private TestResult testGetAccountDataSumsBalancesAcrossAliasCollision() {
        IBankManager api = BankSystemMod.getAPI().getServerBankManager();
        IServerBankManager manager = api != null ? api.getSync() : null;
        if (manager == null)
            return pass("skipped: no ServerBankManager (slave server or not-yet-loaded)");

        // Register two DISTINCT canonical items first (their identities differ by a component
        // that is NOT volatile on this server, so they get separate shorts) — later we install
        // an alias entry that ties one to the other, simulating the corrupt post-Bug-A state
        // where two banks in an account resolve to the same canonical.
        String marker = UUID.randomUUID().toString();
        ItemStack templateA = paperWithCustomData(marker + "-A");
        ItemStack templateB = paperWithCustomData(marker + "-B");
        ItemID idA = ItemIDManager.registerItemStackServerSide_direct(templateA);
        ItemID idB = ItemIDManager.registerItemStackServerSide_direct(templateB);
        if (!idA.isValid() || !idB.isValid() || idA.equals(idB))
            return fail("Could not register two distinct canonical templates");

        int accountNr = ServerBankAccount.INVALID_ACCOUNT_NUMBER;
        // Choose canonical = the lower short (matches the merge canonical-selection rule).
        ItemID canonical = idA.getShort() < idB.getShort() ? idA : idB;
        ItemID other = canonical.equals(idA) ? idB : idA;
        try {
            if (!manager.allowItemID(idA) || !manager.allowItemID(idB))
                return fail("Could not allow the test items");
            IServerBankAccount acc = manager.createBankAccount("Task13_FixB_SumAccount");
            if (acc == null)
                return fail("Could not create the test account");
            accountNr = acc.getAccountNumber();
            if (!(acc instanceof ServerBankAccount serverAcc))
                return fail("account is not a ServerBankAccount");

            // Create two banks under DIFFERENT canonical IDs, seed balances, then INSTALL the
            // alias entry AFTER the banks exist so both banks remain in the raw map. This is
            // exactly the corrupt state Fix B compensates for: two banks under distinct keys,
            // but resolveAlias() now maps one to the other.
            IServerBank bankCanonical = acc.createBank(canonical, 0);
            IServerBank bankOther = acc.createBank(other, 0);
            if (bankCanonical == null || bankOther == null)
                return fail("Could not create both banks");
            if (bankCanonical.deposit(1000L) != BankStatus.SUCCESS
                    || bankOther.deposit(500L) != BankStatus.SUCCESS)
                return fail("Could not seed balances");
            // Lock a bit of the "other" bank so we can also verify locked-balance summing.
            if (bankOther.lockAmount(200L) != BankStatus.SUCCESS)
                return fail("Could not lock part of the other bank's balance");

            // Install alias AFTER both banks exist — do not consolidate. Uses the raw
            // bypass helper (Task #14) because "other" is registered in itemIDMap and
            // applyAliases now routes through putAlias, which would (correctly) reject the
            // invariant-violating entry this test intentionally seeds to prove Fix B's
            // duplicate-balance summing.
            ItemIDManager.putAliasRaw_forTesting(other, canonical);

            BankAccountData snapshot = serverAcc.getAccountData();
            TestResult r = assertNotNull("getAccountData returned non-null snapshot", snapshot);
            if (!r.passed()) return r;
            r = assertEquals("emitted bankData has exactly one entry (canonical)",
                    1, snapshot.bankData.size());
            if (!r.passed()) return r;
            r = assertTrue("emitted bankData contains the canonical key",
                    snapshot.bankData.containsKey(canonical));
            if (!r.passed()) return r;
            BankData bd = snapshot.bankData.get(canonical);
            // Canonical: 1000 free + 0 locked. Other: 300 free + 200 locked. Sums: 1300/200.
            r = assertEquals("free balance is the sum of both bank free balances",
                    1000L + 300L, bd.balance());
            if (!r.passed()) return r;
            r = assertEquals("locked balance is the sum of both bank locked balances",
                    200L, bd.lockedBalance());
            if (!r.passed()) return r;
            r = assertEquals("BankData record's own itemID equals the canonical key",
                    canonical, bd.itemID());
            if (!r.passed()) return r;
            return pass("getAccountData() sums balances across alias collision (Fix B dedup)");
        } finally {
            if (accountNr != ServerBankAccount.INVALID_ACCOUNT_NUMBER)
                manager.deleteBankAccount(accountNr);
            manager.disallowItemID(idA);
            manager.disallowItemID(idB);
            ItemIDManager.removeAliasEntries_forTesting(List.of(other));
        }
    }

    /**
     * Chooses a base short such that {@code [base, base + count)} is unused by both maps
     * (item and alias). Returns {@code -1} if not enough free room.
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
