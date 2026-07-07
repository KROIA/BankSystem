package net.kroia.banksystem.testing.tests;

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
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-game tests for volatile-component normalization at the ItemID identity boundary.
 * <p>
 * These tests use {@code minecraft:custom_data} / {@code minecraft:repair_cost} as stand-ins
 * for third-party volatile components (e.g. {@code tfc:food}) by temporarily adding them to
 * the config-sourced volatile set. The original config set is restored in {@code teardown()}.
 * <p>
 * Note: they operate on the live {@link ItemIDManager} of the running (dev/test) server —
 * the alias-merge test registers two synthetic paper templates (tagged with a random UUID in
 * {@code custom_data}) that remain in the ItemID table afterwards. Tests are only registered
 * when dev features are enabled, so production worlds are unaffected.
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
    }

    @Override
    public void setup() {
        savedConfigIds = VolatileItemComponents.getConfigComponentIdStrings();
    }

    @Override
    public void teardown() {
        // Restore the server's real config-sourced volatile set.
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
        ItemStack input = paperWithCustomData("strip-me");
        ItemStack normalized = VolatileItemComponents.normalize(input);
        if (normalized.has(DataComponents.CUSTOM_DATA))
            return fail("normalize() did not strip minecraft:custom_data from the copy");
        ItemStack plain = new ItemStack(Items.PAPER);
        TestResult r = assertTrue("normalized volatile stack equals plain stack",
                ItemStack.isSameItemSameComponents(normalized, VolatileItemComponents.normalize(plain)));
        if (!r.passed()) return r;
        return pass("normalize() strips configured volatile components");
    }

    /** normalize() must never mutate the input stack. */
    private TestResult testNormalizeDoesNotMutateInput() {
        VolatileItemComponents.setConfigComponentIds(List.of("minecraft:custom_data"));
        ItemStack input = paperWithCustomData("keep-me");
        input.setCount(7);
        VolatileItemComponents.normalize(input);
        TestResult r = assertTrue("input still has custom_data", input.has(DataComponents.CUSTOM_DATA));
        if (!r.passed()) return r;
        r = assertEquals("input count unchanged", 7, input.getCount());
        if (!r.passed()) return r;
        return pass("normalize() leaves the input stack untouched");
    }

    /**
     * Acceptance criterion 1a: two stacks of the same item that differ only in a volatile
     * component must resolve to the SAME ItemID.
     */
    private TestResult testVolatileDifferenceSameItemID() {
        VolatileItemComponents.setConfigComponentIds(List.of("minecraft:custom_data"));
        ItemID plainId = ItemIDManager.getItemID(new ItemStack(Items.PAPER));
        if (!plainId.isValid())
            return fail("Plain paper is not registered — default ItemIDs missing on this server?");
        ItemID volatileId = ItemIDManager.getItemID(paperWithCustomData("volatile-difference"));
        return assertEquals("paper with volatile custom_data resolves to the plain paper ItemID",
                plainId, volatileId);
    }

    /**
     * Acceptance criterion 1b: stacks differing in minecraft:stored_enchantments (NOT volatile)
     * must still resolve to DIFFERENT ItemIDs.
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
     * to the same normalized identity, the lowest ID becomes canonical and the other IDs keep
     * resolving through the alias table.
     */
    private TestResult testAliasResolutionAfterMerge() {
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

        // Now declare repair_cost volatile — the two IDs must collapse into one.
        VolatileItemComponents.setConfigComponentIds(List.of("minecraft:repair_cost"));
        ItemIDManager.renormalizeAndMerge();

        ItemID canonical = idA.getShort() < idB.getShort() ? idA : idB;
        ItemID merged = idA.getShort() < idB.getShort() ? idB : idA;

        r = assertEquals("merged ID aliases to the canonical (lowest) ID",
                canonical, ItemIDManager.resolveAlias(merged));
        if (!r.passed()) return r;

        // Lookups by the old (merged) ID must transparently resolve to the canonical template.
        ItemStack resolved = ItemIDManager.getItemStack(merged);
        r = assertTrue("getItemStack(mergedId) resolves through the alias", !resolved.isEmpty() && resolved.is(Items.PAPER));
        if (!r.passed()) return r;

        // Stack lookups of either variant must resolve to the canonical ID.
        r = assertEquals("getItemID(variant with volatile repair_cost) returns the canonical ID",
                canonical, ItemIDManager.getItemID(b));
        if (!r.passed()) return r;
        return pass("colliding IDs merged to canonical ID with working alias resolution");
    }

    /** Hardening: getItemID must not mutate its argument (count or components). */
    private TestResult testGetItemIDDoesNotMutateArgument() {
        VolatileItemComponents.setConfigComponentIds(List.of("minecraft:custom_data"));
        ItemStack query = paperWithCustomData("do-not-mutate");
        query.setCount(5);
        ItemIDManager.getItemID(query);
        TestResult r = assertEquals("count unchanged after getItemID", 5, query.getCount());
        if (!r.passed()) return r;
        r = assertTrue("volatile component still on the query stack", query.has(DataComponents.CUSTOM_DATA));
        if (!r.passed()) return r;
        return pass("getItemID leaves its argument untouched");
    }

    /** Hardening: getStack()/getItemStack() must hand out defensive copies. */
    private TestResult testGetStackReturnsDefensiveCopy() {
        ItemID paperId = ItemIDManager.getItemID(new ItemStack(Items.PAPER));
        if (!paperId.isValid())
            return fail("Plain paper is not registered — default ItemIDs missing on this server?");
        ItemStack copy = paperId.getStack();
        copy.setCount(33);
        CompoundTag nbt = new CompoundTag();
        nbt.putString("banksystem_identity_test", "template-mutation");
        copy.set(DataComponents.CUSTOM_DATA, CustomData.of(nbt));

        ItemStack again = paperId.getStack();
        TestResult r = assertEquals("template count unaffected by caller mutation", 1, again.getCount());
        if (!r.passed()) return r;
        r = assertFalse("template components unaffected by caller mutation", again.has(DataComponents.CUSTOM_DATA));
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
        ItemID paperId = ItemIDManager.getItemID(new ItemStack(Items.PAPER));
        if (!paperId.isValid())
            return fail("Plain paper is not registered — default ItemIDs missing on this server?");
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
     * Note: leaves one synthetic alias entry (unused id → paper) in the live alias table of the
     * dev/test server — harmless, it just resolves to paper if ever referenced.
     */
    private TestResult testPlaceholderNameSelfHeals() {
        ItemID paperId = ItemIDManager.getItemID(new ItemStack(Items.PAPER));
        if (!paperId.isValid())
            return fail("Plain paper is not registered — default ItemIDs missing on this server?");

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
}
