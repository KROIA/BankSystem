package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.minecraft.item.BankSystemItems;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.BankCraftingMatcher;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * In-game tests for {@link BankCraftingMatcher} — the side-agnostic, pure match
 * engine behind the Bank Terminal's bank-assisted 3x3 crafting grid.
 * <p>
 * The matcher is static and stateless: every test calls
 * {@link BankCraftingMatcher#findMatch(Level, List, boolean, BankAccountData)}
 * (or the 5-arg ghost-recipe overload) directly with a hand-built grid and a
 * synthetic {@link BankAccountData} snapshot, using the overworld of the live
 * server for recipe-manager access. The ghost-path tests additionally use the
 * vanilla bucket and ladder recipes via explicit recipe-id lookup.
 * <p>
 * <b>Vanilla-recipe assumption:</b> the scenarios are constructed so that, with
 * the given physical seed items and the given (tightly budgeted) bank balances,
 * exactly ONE vanilla recipe is completable — the matcher scans all crafting
 * recipes in registry order and returns the first hit, so uniqueness is what
 * makes the assertions deterministic. Recipes used: oak planks (from log),
 * bucket (3 iron), bow (3 sticks + 3 string, mirrored), book (3 paper +
 * 1 leather, shapeless), torch (coal/charcoal + stick). A heavily modded test
 * world that adds competing crafting recipes over these exact ingredient sets
 * could change which recipe matches first; the suite targets dev/vanilla-ish
 * servers, consistent with the other suites' vanilla assumptions.
 * <p>
 * <b>MASTER_ONLY:</b> the fixtures register vanilla item templates through
 * {@link ItemIDManager#registerItemStackServerSide_direct(ItemStack)}, which is
 * only safe on the master server. Residue: plain vanilla-item ItemIDs in the
 * registry (normally present anyway as defaults) — harmless.
 * <p>
 * <b>Deliberately not covered</b> (no production hooks were added):
 * <ul>
 *   <li><i>recipe.matches() re-verification rejecting a per-slot-passing fill</i> —
 *       for vanilla shaped/shapeless recipes the per-slot ingredient logic is
 *       equivalent to {@code matches()} (including mirroring), so a fill that
 *       passes per-slot tests but fails {@code matches()} can only be built with
 *       a custom recipe class, which would require registering a test recipe
 *       (a production/datapack hook). Verified indirectly: every positive match
 *       in this suite went through the {@code matches()} gate.</li>
 *   <li><i>a genuine shapeless backtracking dead-end</i> (physical item matching
 *       two DIFFERENT overlapping ingredient sets where greedy assignment fails)
 *       — vanilla has no shapeless recipe with two distinct overlapping
 *       ingredient sets. The book test still exercises the backtracking
 *       assignment across duplicate ingredients.</li>
 * </ul>
 */
public class BankCraftingMatcherTests extends TestSuite {

    private static final int ONE_ITEM = BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.ITEM_BANK;
    }

    @Override
    public void registerTests() {
        addTest("wrong_grid_size_returns_null", this::testWrongGridSizeReturnsNull);
        addTest("physical_only_match_vanilla_recipe", this::testPhysicalOnlyMatch);
        addTest("physical_match_wins_over_bank", this::testPhysicalMatchWinsOverBank);
        addTest("empty_grid_with_full_bank_never_matches", this::testEmptyGridNeverMatches);
        addTest("bank_fill_requires_flag_and_bank_data", this::testBankFillRequiresFlagAndData);
        addTest("shaped_bank_fill_completes_bucket", this::testShapedBankFillCompletesBucket);
        addTest("shaped_offset_placement_matches", this::testShapedOffsetPlacementMatches);
        addTest("shaped_mirrored_placement_matches", this::testShapedMirroredPlacementMatches);
        addTest("shapeless_bank_fill_completes_book", this::testShapelessBankFillCompletesBook);
        addTest("insufficient_cumulative_balance_rejects", this::testInsufficientCumulativeBalanceRejects);
        addTest("balance_below_one_item_excluded", this::testBalanceBelowOneItemExcluded);
        addTest("money_never_sourced_from_bank", this::testMoneyNeverSourcedFromBank);
        addTest("deterministic_lowest_itemid_pick", this::testDeterministicLowestItemIdPick);
        addTest("ghost_recipe_empty_grid_completes_from_bank", this::testGhostRecipeEmptyGridCompletesFromBank);
        addTest("ghost_recipe_requires_bank_data", this::testGhostRecipeRequiresBankData);
        addTest("ghost_recipe_insufficient_bank_rejects", this::testGhostRecipeInsufficientBankRejects);
        addTest("ghost_recipe_physical_first", this::testGhostRecipePhysicalFirst);
        addTest("ghost_recipe_no_scan_fallback", this::testGhostRecipeNoScanFallback);
        addTest("ghost_layout_representative_cells", this::testGhostLayoutRepresentativeCells);
        addTest("recipes_for_product_lookup", this::testRecipesForProductLookup);
        addTest("physical_satisfiability_check", this::testPhysicalSatisfiabilityCheck);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private Level level() {
        MinecraftServer server = getServer();
        return server != null ? server.overworld() : null;
    }

    /** Returns a 9-slot grid of EMPTY stacks. */
    private static List<ItemStack> emptyGrid() {
        List<ItemStack> grid = new ArrayList<>(BankCraftingMatcher.GRID_SIZE);
        for (int i = 0; i < BankCraftingMatcher.GRID_SIZE; i++)
            grid.add(ItemStack.EMPTY);
        return grid;
    }

    /** Places single-count stacks of the given item into the given slots of a fresh grid. */
    private static List<ItemStack> gridWith(net.minecraft.world.item.Item item, int... slots) {
        List<ItemStack> grid = emptyGrid();
        for (int slot : slots)
            grid.set(slot, new ItemStack(item));
        return grid;
    }

    /** Registers (or resolves) the ItemID for a plain vanilla item template. */
    private static ItemID id(net.minecraft.world.item.Item item) {
        return ItemIDManager.registerItemStackServerSide_direct(new ItemStack(item));
    }

    /**
     * Builds a synthetic bank snapshot. Varargs are (ItemID, freeBalance) pairs;
     * balances are raw units ({@link #ONE_ITEM} per whole item), locked = 0.
     */
    private static BankAccountData bank(Object... idBalancePairs) {
        Map<ItemID, BankData> bankData = new java.util.HashMap<>();
        for (int i = 0; i < idBalancePairs.length; i += 2) {
            ItemID itemID = (ItemID) idBalancePairs[i];
            long balance = ((Number) idBalancePairs[i + 1]).longValue();
            bankData.put(itemID, new BankData(itemID, balance, 0));
        }
        return new BankAccountData(999999, "BankCraftingMatcherTestAccount",
                null, null, Collections.emptyMap(), bankData);
    }

    /** Counts how many bankPerSlot entries equal the given ItemID. */
    private static int countBankSlots(BankCraftingMatcher.Match match, ItemID itemID) {
        int count = 0;
        for (ItemID slot : match.bankPerSlot())
            if (itemID.equals(slot))
                count++;
        return count;
    }

    /** Resolves a vanilla crafting-recipe holder by path (e.g. "bucket"), or null. */
    @SuppressWarnings("unchecked")
    private static RecipeHolder<CraftingRecipe> craftingRecipe(Level level, String path) {
        return (RecipeHolder<CraftingRecipe>) level.getRecipeManager()
                .byKey(net.minecraft.resources.ResourceLocation.withDefaultNamespace(path))
                .filter(holder -> holder.value() instanceof CraftingRecipe)
                .orElse(null);
    }

    // ========================================================================
    // Guards & stage-1 (physical) tests
    // ========================================================================

    /** A grid list that is not exactly 9 entries must never match. */
    private TestResult testWrongGridSizeReturnsNull() {
        Level level = level();
        if (level == null) return fail("No server level available");
        List<ItemStack> shortGrid = new ArrayList<>();
        for (int i = 0; i < 8; i++) shortGrid.add(ItemStack.EMPTY);
        BankCraftingMatcher.Match match =
                BankCraftingMatcher.findMatch(level, shortGrid, true, bank(id(Items.IRON_INGOT), 64L * ONE_ITEM));
        return assertNull("8-slot grid returns null", match);
    }

    /**
     * Stage 1: a physically complete vanilla recipe (1 oak log -> 4 oak planks)
     * matches with bank mode off and no bank snapshot; the match reports no bank
     * usage and copies the physical grid into the virtual grid.
     */
    private TestResult testPhysicalOnlyMatch() {
        Level level = level();
        if (level == null) return fail("No server level available");
        List<ItemStack> grid = gridWith(Items.OAK_LOG, 0);
        BankCraftingMatcher.Match match = BankCraftingMatcher.findMatch(level, grid, false, null);
        if (match == null) return fail("physical oak log did not match the planks recipe");
        TestResult r = assertTrue("result is oak planks", match.result().is(Items.OAK_PLANKS));
        if (!r.passed()) return r;
        r = assertEquals("planks recipe yields 4 per craft", 4, match.result().getCount());
        if (!r.passed()) return r;
        r = assertFalse("physical match uses no bank items", match.usesBankItems());
        if (!r.passed()) return r;
        r = assertTrue("bankCountsPerCraft is empty", match.bankCountsPerCraft().isEmpty());
        if (!r.passed()) return r;
        for (int i = 0; i < BankCraftingMatcher.GRID_SIZE; i++) {
            if (match.bankPerSlot()[i] != null)
                return fail("bankPerSlot[" + i + "] is set on a purely physical match");
        }
        r = assertTrue("virtualGrid[0] mirrors the physical log", match.virtualGrid()[0].is(Items.OAK_LOG));
        if (!r.passed()) return r;
        return pass("stage-1 physical matching works without bank data");
    }

    /**
     * Stage priority: when the grid already matches physically, the physical
     * match wins even with bank mode ON and a bank that could also supply the
     * ingredient — no bank items are reserved.
     */
    private TestResult testPhysicalMatchWinsOverBank() {
        Level level = level();
        if (level == null) return fail("No server level available");
        List<ItemStack> grid = gridWith(Items.OAK_LOG, 0);
        BankAccountData bankData = bank(id(Items.OAK_LOG), 64L * ONE_ITEM);
        BankCraftingMatcher.Match match = BankCraftingMatcher.findMatch(level, grid, true, bankData);
        if (match == null) return fail("physically complete grid did not match with bank mode on");
        TestResult r = assertTrue("result is oak planks", match.result().is(Items.OAK_PLANKS));
        if (!r.passed()) return r;
        r = assertFalse("physical match reserves nothing from the bank", match.usesBankItems());
        if (!r.passed()) return r;
        return pass("stage 1 (physical) has priority over bank-assisted completion");
    }

    /**
     * Seed rule: an entirely empty grid must never match, no matter how full the
     * bank is — otherwise every recipe in the game would be bank-completable.
     */
    private TestResult testEmptyGridNeverMatches() {
        Level level = level();
        if (level == null) return fail("No server level available");
        BankAccountData bankData = bank(
                id(Items.IRON_INGOT), 64L * ONE_ITEM,
                id(Items.OAK_PLANKS), 64L * ONE_ITEM,
                id(Items.STICK), 64L * ONE_ITEM);
        BankCraftingMatcher.Match match = BankCraftingMatcher.findMatch(level, emptyGrid(), true, bankData);
        return assertNull("empty grid + full bank yields no match", match);
    }

    /**
     * Stage-2 gating: the bucket seed layout (iron at slots 0 and 2, missing the
     * center-bottom iron) must NOT match with bank mode off or without a bank
     * snapshot, and MUST match once both are provided (positive control).
     */
    private TestResult testBankFillRequiresFlagAndData() {
        Level level = level();
        if (level == null) return fail("No server level available");
        List<ItemStack> grid = gridWith(Items.IRON_INGOT, 0, 2);
        BankAccountData bankData = bank(id(Items.IRON_INGOT), ONE_ITEM);

        TestResult r = assertNull("useBank=false blocks bank-assisted completion",
                BankCraftingMatcher.findMatch(level, grid, false, bankData));
        if (!r.passed()) return r;
        r = assertNull("null bank snapshot blocks bank-assisted completion",
                BankCraftingMatcher.findMatch(level, grid, true, null));
        if (!r.passed()) return r;
        r = assertNotNull("positive control: flag + bank snapshot yields the match",
                BankCraftingMatcher.findMatch(level, grid, true, bankData));
        if (!r.passed()) return r;
        return pass("bank-assisted completion requires the flag AND a bank snapshot");
    }

    // ========================================================================
    // Stage-2 (bank-assisted) tests
    // ========================================================================

    /**
     * Shaped bank fill: iron seeds at slots 0 and 2 plus exactly ONE bank iron
     * complete the bucket recipe ("# #" / " # "). The bank balance of exactly one
     * whole iron makes the bucket the only completable vanilla recipe. Asserts
     * the exact fill slot (4), the reservation map and the virtual grid stack.
     */
    private TestResult testShapedBankFillCompletesBucket() {
        Level level = level();
        if (level == null) return fail("No server level available");
        ItemID iron = id(Items.IRON_INGOT);
        List<ItemStack> grid = gridWith(Items.IRON_INGOT, 0, 2);
        BankCraftingMatcher.Match match =
                BankCraftingMatcher.findMatch(level, grid, true, bank(iron, ONE_ITEM));
        if (match == null) return fail("bucket seed layout + 1 bank iron produced no match");
        TestResult r = assertTrue("result is a bucket", match.result().is(Items.BUCKET));
        if (!r.passed()) return r;
        r = assertTrue("match is flagged as using bank items", match.usesBankItems());
        if (!r.passed()) return r;
        r = assertEquals("exactly the center slot (4) is bank-filled", iron, match.bankPerSlot()[4]);
        if (!r.passed()) return r;
        r = assertEquals("exactly one slot is bank-filled in total", 1, countBankSlots(match, iron));
        if (!r.passed()) return r;
        r = assertEquals("one whole iron is reserved per craft",
                Integer.valueOf(1), match.bankCountsPerCraft().get(iron));
        if (!r.passed()) return r;
        r = assertEquals("no other item is reserved", 1, match.bankCountsPerCraft().size());
        if (!r.passed()) return r;
        r = assertTrue("virtualGrid[4] holds a single bank iron",
                match.virtualGrid()[4].is(Items.IRON_INGOT) && match.virtualGrid()[4].getCount() == 1);
        if (!r.passed()) return r;
        r = assertTrue("physical seeds are kept in the virtual grid",
                match.virtualGrid()[0].is(Items.IRON_INGOT) && match.virtualGrid()[2].is(Items.IRON_INGOT));
        if (!r.passed()) return r;
        return pass("shaped bank fill completes the bucket with correct slot, counts and grid");
    }

    /**
     * Shaped placement: the same bucket seeds shifted one row down (slots 3 and 5)
     * still match — the matcher must try the offsetY=1 placement — and the bank
     * fill lands on slot 7 accordingly.
     */
    private TestResult testShapedOffsetPlacementMatches() {
        Level level = level();
        if (level == null) return fail("No server level available");
        ItemID iron = id(Items.IRON_INGOT);
        List<ItemStack> grid = gridWith(Items.IRON_INGOT, 3, 5);
        BankCraftingMatcher.Match match =
                BankCraftingMatcher.findMatch(level, grid, true, bank(iron, ONE_ITEM));
        if (match == null) return fail("offset bucket seed layout produced no match");
        TestResult r = assertTrue("result is a bucket", match.result().is(Items.BUCKET));
        if (!r.passed()) return r;
        r = assertEquals("bank fill follows the placement offset (slot 7)", iron, match.bankPerSlot()[7]);
        if (!r.passed()) return r;
        r = assertEquals("exactly one slot is bank-filled", 1, countBankSlots(match, iron));
        if (!r.passed()) return r;
        return pass("shaped placement offsets are searched; the fill slot follows the offset");
    }

    /**
     * Mirrored placement: the bow pattern (" #X" / "# X" / " #X") holds its
     * strings in column 2 — placing the three physical strings in column 0
     * (slots 0, 3, 6) only fits the MIRRORED layout. The bank supplies exactly
     * the three sticks (slots 1, 5, 7 in mirrored orientation).
     */
    private TestResult testShapedMirroredPlacementMatches() {
        Level level = level();
        if (level == null) return fail("No server level available");
        ItemID stick = id(Items.STICK);
        List<ItemStack> grid = gridWith(Items.STRING, 0, 3, 6);
        BankCraftingMatcher.Match match =
                BankCraftingMatcher.findMatch(level, grid, true, bank(stick, 3L * ONE_ITEM));
        if (match == null) return fail("mirrored bow seed layout produced no match");
        TestResult r = assertTrue("result is a bow", match.result().is(Items.BOW));
        if (!r.passed()) return r;
        r = assertEquals("three slots are bank-filled with sticks", 3, countBankSlots(match, stick));
        if (!r.passed()) return r;
        r = assertEquals("three whole sticks are reserved per craft",
                Integer.valueOf(3), match.bankCountsPerCraft().get(stick));
        if (!r.passed()) return r;
        r = assertTrue("mirrored stick slots are 1, 5 and 7",
                stick.equals(match.bankPerSlot()[1])
                        && stick.equals(match.bankPerSlot()[5])
                        && stick.equals(match.bankPerSlot()[7]));
        if (!r.passed()) return r;
        return pass("mirrored shaped placements are searched and bank-filled correctly");
    }

    /**
     * Shapeless bank fill with duplicate ingredients: book = 3 paper + 1 leather.
     * Physical paper (slot 0) and leather (slot 3) seed the grid; the bank
     * supplies the remaining TWO paper. Exercises the backtracking assignment of
     * physical items to ingredient indices and the empty-slot fill order.
     */
    private TestResult testShapelessBankFillCompletesBook() {
        Level level = level();
        if (level == null) return fail("No server level available");
        ItemID paper = id(Items.PAPER);
        List<ItemStack> grid = emptyGrid();
        grid.set(0, new ItemStack(Items.PAPER));
        grid.set(3, new ItemStack(Items.LEATHER));
        BankCraftingMatcher.Match match =
                BankCraftingMatcher.findMatch(level, grid, true, bank(paper, 2L * ONE_ITEM));
        if (match == null) return fail("book seed layout (paper + leather) produced no match");
        TestResult r = assertTrue("result is a book", match.result().is(Items.BOOK));
        if (!r.passed()) return r;
        r = assertEquals("exactly two slots are bank-filled with paper", 2, countBankSlots(match, paper));
        if (!r.passed()) return r;
        r = assertEquals("two whole paper are reserved per craft",
                Integer.valueOf(2), match.bankCountsPerCraft().get(paper));
        if (!r.passed()) return r;
        r = assertTrue("physical seeds keep their slots in the virtual grid",
                match.virtualGrid()[0].is(Items.PAPER) && match.virtualGrid()[3].is(Items.LEATHER));
        if (!r.passed()) return r;
        return pass("shapeless recipes are bank-completed via the assignment/backtracking path");
    }

    /**
     * Cumulative reservation: book needs THREE paper from the bank when only the
     * leather is physical. With a free balance of (3 items - 1 raw unit) the
     * third pick must fail (needs are summed against the same balance) — no
     * match. With exactly 3 items it must succeed (boundary control).
     */
    private TestResult testInsufficientCumulativeBalanceRejects() {
        Level level = level();
        if (level == null) return fail("No server level available");
        ItemID paper = id(Items.PAPER);
        List<ItemStack> grid = gridWith(Items.LEATHER, 0);

        BankCraftingMatcher.Match tooFew =
                BankCraftingMatcher.findMatch(level, grid, true, bank(paper, 3L * ONE_ITEM - 1));
        TestResult r = assertNull("2.99 paper cannot cover a 3-paper need", tooFew);
        if (!r.passed()) return r;

        BankCraftingMatcher.Match exact =
                BankCraftingMatcher.findMatch(level, grid, true, bank(paper, 3L * ONE_ITEM));
        if (exact == null) return fail("exactly 3 bank paper did not complete the book");
        r = assertTrue("boundary control result is a book", exact.result().is(Items.BOOK));
        if (!r.passed()) return r;
        r = assertEquals("all three paper are reserved cumulatively",
                Integer.valueOf(3), exact.bankCountsPerCraft().get(paper));
        if (!r.passed()) return r;
        return pass("cumulative needs are reserved against the free balance");
    }

    /**
     * Candidate cut-off: a bank item with less than ONE whole free item (or with
     * only a locked balance) is never offered as a crafting candidate.
     */
    private TestResult testBalanceBelowOneItemExcluded() {
        Level level = level();
        if (level == null) return fail("No server level available");
        ItemID iron = id(Items.IRON_INGOT);
        List<ItemStack> grid = gridWith(Items.IRON_INGOT, 0, 2);

        BankCraftingMatcher.Match fractional =
                BankCraftingMatcher.findMatch(level, grid, true, bank(iron, ONE_ITEM - 1));
        TestResult r = assertNull("0.99 free iron is not a usable candidate", fractional);
        if (!r.passed()) return r;

        // Locked balance does not count as free balance.
        Map<ItemID, BankData> lockedOnly = Map.of(iron, new BankData(iron, 0, 64L * ONE_ITEM));
        BankAccountData lockedBank = new BankAccountData(999999, "BankCraftingMatcherTestAccount",
                null, null, Collections.emptyMap(), lockedOnly);
        r = assertNull("locked-only balance is not a usable candidate",
                BankCraftingMatcher.findMatch(level, grid, true, lockedBank));
        if (!r.passed()) return r;

        BankCraftingMatcher.Match whole =
                BankCraftingMatcher.findMatch(level, grid, true, bank(iron, ONE_ITEM));
        r = assertNotNull("boundary control: exactly 1.00 free iron matches", whole);
        if (!r.passed()) return r;
        return pass("balances below one whole free item are excluded from bank sourcing");
    }

    /**
     * Money exclusion: money items are never sourced from the bank. A bank
     * holding money alongside the needed iron must complete the bucket without
     * ever touching the money entry; a bank holding ONLY money completes nothing.
     * <p>
     * Note: on a vanilla server no crafting ingredient accepts the money item, so
     * the exclusion is additionally vacuous at the ingredient level — the test
     * scans the recipes and reports whether the guard is load-bearing here.
     */
    private TestResult testMoneyNeverSourcedFromBank() {
        Level level = level();
        if (level == null) return fail("No server level available");
        ItemID money = ItemIDManager.registerItemStackServerSide_direct(
                BankSystemItems.MONEY.get().getDefaultInstance());
        if (!money.isValid()) return fail("could not register the money ItemID");
        TestResult r = assertTrue("precondition: registered ItemID is detected as money",
                MoneyItem.isMoney(money));
        if (!r.passed()) return r;

        ItemID iron = id(Items.IRON_INGOT);
        List<ItemStack> grid = gridWith(Items.IRON_INGOT, 0, 2);

        // Only money in the bank: nothing to source, no match.
        r = assertNull("a bank holding only money completes nothing",
                BankCraftingMatcher.findMatch(level, grid, true, bank(money, 100_000L * ONE_ITEM)));
        if (!r.passed()) return r;

        // Money + iron: the match must reserve iron only, never money.
        BankCraftingMatcher.Match match = BankCraftingMatcher.findMatch(level, grid, true,
                bank(money, 100_000L * ONE_ITEM, iron, ONE_ITEM));
        if (match == null) return fail("bucket did not complete from the mixed money+iron bank");
        r = assertFalse("money is never reserved", match.bankCountsPerCraft().containsKey(money));
        if (!r.passed()) return r;
        r = assertEquals("no grid slot is filled with money", 0, countBankSlots(match, money));
        if (!r.passed()) return r;

        // Informational: is the exclusion load-bearing on this server, or vacuous?
        ItemStack moneyStack = money.getStack();
        boolean anyIngredientAcceptsMoney = false;
        for (RecipeHolder<CraftingRecipe> holder : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = holder.value();
            if (!(recipe instanceof ShapedRecipe) && !(recipe instanceof ShapelessRecipe))
                continue;
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (!ingredient.isEmpty() && ingredient.test(moneyStack)) {
                    anyIngredientAcceptsMoney = true;
                    break;
                }
            }
            if (anyIngredientAcceptsMoney) break;
        }
        return pass("money items are never sourced from the bank"
                + (anyIngredientAcceptsMoney
                ? " (load-bearing: at least one crafting ingredient accepts money on this server)"
                : " (vacuous at ingredient level on this server: no crafting ingredient accepts money)"));
    }

    /**
     * Determinism: the torch's fuel ingredient accepts coal AND charcoal. With
     * both in the bank, the candidate with the LOWEST ItemID short must be picked
     * — and the pick must be identical across repeated calls, so the client-side
     * ghost preview and the server-side authoritative match always agree.
     */
    private TestResult testDeterministicLowestItemIdPick() {
        Level level = level();
        if (level == null) return fail("No server level available");
        ItemID coal = id(Items.COAL);
        ItemID charcoal = id(Items.CHARCOAL);
        if (!coal.isValid() || !charcoal.isValid())
            return fail("could not register coal/charcoal templates");
        // Mirrors the matcher's comparator: ascending signed short.
        ItemID expected = coal.getShort() < charcoal.getShort() ? coal : charcoal;
        ItemID other = expected.equals(coal) ? charcoal : coal;

        List<ItemStack> grid = gridWith(Items.STICK, 4);
        BankAccountData bankData = bank(coal, 64L * ONE_ITEM, charcoal, 64L * ONE_ITEM);

        BankCraftingMatcher.Match first = BankCraftingMatcher.findMatch(level, grid, true, bankData);
        if (first == null) return fail("torch seed layout (stick + bank coals) produced no match");
        TestResult r = assertTrue("result is a torch", first.result().is(Items.TORCH));
        if (!r.passed()) return r;
        r = assertEquals("fuel slot (1) is filled with the lowest-short candidate",
                expected, first.bankPerSlot()[1]);
        if (!r.passed()) return r;
        r = assertFalse("the higher-short candidate is not reserved",
                first.bankCountsPerCraft().containsKey(other));
        if (!r.passed()) return r;

        BankCraftingMatcher.Match second = BankCraftingMatcher.findMatch(level, grid, true, bankData);
        if (second == null) return fail("repeated call produced no match");
        r = assertEquals("repeated call picks the identical candidate",
                expected, second.bankPerSlot()[1]);
        if (!r.passed()) return r;
        return pass("candidate picks are deterministic: ascending ItemID, stable across calls");
    }

    // ========================================================================
    // Ghost-recipe path (explicit selection, e.g. via JEI "+", bank mode)
    // ========================================================================

    /**
     * Ghost path: with an explicitly selected recipe the ≥1-physical-seed rule
     * does NOT apply — an EMPTY grid completes the bucket entirely from the bank
     * (3 iron reserved, cells 0/2/4 of the top-left placement bank-filled).
     * The scan-path seed rule is unaffected (see
     * {@code empty_grid_with_full_bank_never_matches}).
     */
    private TestResult testGhostRecipeEmptyGridCompletesFromBank() {
        Level level = level();
        if (level == null) return fail("No server level available");
        RecipeHolder<CraftingRecipe> bucket = craftingRecipe(level, "bucket");
        if (bucket == null) return fail("vanilla bucket recipe not found");
        ItemID iron = id(Items.IRON_INGOT);
        BankCraftingMatcher.Match match = BankCraftingMatcher.findMatch(
                level, emptyGrid(), true, bank(iron, 3L * ONE_ITEM), bucket);
        if (match == null) return fail("ghost bucket + empty grid + 3 bank iron produced no match");
        TestResult r = assertTrue("result is a bucket", match.result().is(Items.BUCKET));
        if (!r.passed()) return r;
        r = assertEquals("the selected recipe is matched", bucket.id(), match.recipe().id());
        if (!r.passed()) return r;
        r = assertEquals("three whole iron are reserved per craft",
                Integer.valueOf(3), match.bankCountsPerCraft().get(iron));
        if (!r.passed()) return r;
        r = assertTrue("top-left placement cells 0, 2 and 4 are bank-filled",
                iron.equals(match.bankPerSlot()[0])
                        && iron.equals(match.bankPerSlot()[2])
                        && iron.equals(match.bankPerSlot()[4]));
        if (!r.passed()) return r;
        r = assertEquals("exactly three slots are bank-filled", 3, countBankSlots(match, iron));
        if (!r.passed()) return r;
        return pass("ghost recipe completes an empty grid entirely from the bank (no seed rule)");
    }

    /**
     * Dupe regression guard: the ghost path is the ONLY avenue that may match an
     * empty grid — it must strictly require bank mode AND a bank snapshot. An
     * empty grid with a ghost recipe but no bank data (or bank mode off) yields
     * no match, so nothing can ever be crafted "from thin air".
     */
    private TestResult testGhostRecipeRequiresBankData() {
        Level level = level();
        if (level == null) return fail("No server level available");
        RecipeHolder<CraftingRecipe> bucket = craftingRecipe(level, "bucket");
        if (bucket == null) return fail("vanilla bucket recipe not found");
        TestResult r = assertNull("ghost + empty grid + NO bank snapshot yields no match",
                BankCraftingMatcher.findMatch(level, emptyGrid(), true, null, bucket));
        if (!r.passed()) return r;
        r = assertNull("ghost + empty grid + bank mode OFF yields no match",
                BankCraftingMatcher.findMatch(level, emptyGrid(), false,
                        bank(id(Items.IRON_INGOT), 64L * ONE_ITEM), bucket));
        if (!r.passed()) return r;
        return pass("empty-grid crafting strictly requires bank mode and a bank snapshot");
    }

    /** Ghost path keeps the balance gate: 2.99 iron cannot cover the 3-iron bucket. */
    private TestResult testGhostRecipeInsufficientBankRejects() {
        Level level = level();
        if (level == null) return fail("No server level available");
        RecipeHolder<CraftingRecipe> bucket = craftingRecipe(level, "bucket");
        if (bucket == null) return fail("vanilla bucket recipe not found");
        ItemID iron = id(Items.IRON_INGOT);
        BankCraftingMatcher.Match match = BankCraftingMatcher.findMatch(
                level, emptyGrid(), true, bank(iron, 3L * ONE_ITEM - 1), bucket);
        return assertNull("2.99 bank iron cannot cover the ghost bucket", match);
    }

    /**
     * Physical-first with a ghost recipe: an iron placed in slot 0 anchors the
     * top-left placement and is consumed physically — only the two remaining
     * cells (2 and 4) are bank-filled.
     */
    private TestResult testGhostRecipePhysicalFirst() {
        Level level = level();
        if (level == null) return fail("No server level available");
        RecipeHolder<CraftingRecipe> bucket = craftingRecipe(level, "bucket");
        if (bucket == null) return fail("vanilla bucket recipe not found");
        ItemID iron = id(Items.IRON_INGOT);
        List<ItemStack> grid = gridWith(Items.IRON_INGOT, 0);
        BankCraftingMatcher.Match match = BankCraftingMatcher.findMatch(
                level, grid, true, bank(iron, 2L * ONE_ITEM), bucket);
        if (match == null) return fail("ghost bucket + physical iron at slot 0 produced no match");
        TestResult r = assertTrue("result is a bucket", match.result().is(Items.BUCKET));
        if (!r.passed()) return r;
        r = assertNull("the physically seeded slot 0 is not bank-filled", match.bankPerSlot()[0]);
        if (!r.passed()) return r;
        r = assertEquals("only two whole iron are reserved",
                Integer.valueOf(2), match.bankCountsPerCraft().get(iron));
        if (!r.passed()) return r;
        r = assertEquals("exactly two slots are bank-filled", 2, countBankSlots(match, iron));
        if (!r.passed()) return r;
        r = assertTrue("virtualGrid[0] keeps the physical iron", match.virtualGrid()[0].is(Items.IRON_INGOT));
        if (!r.passed()) return r;
        return pass("physical items take per-slot precedence over bank sourcing in the ghost path");
    }

    /**
     * No scan fallback: with a ghost recipe selected, ONLY that recipe is tried.
     * A physical stick seed plus a stick-filled bank is scan-completable (e.g.
     * the ladder recipe) — the control call without a ghost proves it. With the
     * ghost bucket selected, the same inputs complete NOTHING: the stick fits no
     * bucket cell and no other recipe may be tried.
     */
    private TestResult testGhostRecipeNoScanFallback() {
        Level level = level();
        if (level == null) return fail("No server level available");
        RecipeHolder<CraftingRecipe> bucket = craftingRecipe(level, "bucket");
        if (bucket == null) return fail("vanilla bucket recipe not found");
        ItemID stick = id(Items.STICK);
        List<ItemStack> grid = gridWith(Items.STICK, 0);
        BankAccountData bankData = bank(stick, 64L * ONE_ITEM);

        TestResult r = assertNotNull("control: the scan path (no ghost) completes a stick recipe",
                BankCraftingMatcher.findMatch(level, grid, true, bankData));
        if (!r.passed()) return r;

        BankCraftingMatcher.Match ghosted = BankCraftingMatcher.findMatch(level, grid, true, bankData, bucket);
        return assertNull("with the ghost bucket selected, no other recipe is tried", ghosted);
    }

    /**
     * Display helper: {@code ghostLayout} of the bucket against an empty grid
     * fills exactly the top-left placement cells (0, 2, 4) with an iron
     * representative and leaves every other cell null. A physically occupied
     * cell is skipped (the real item is rendered instead).
     */
    private TestResult testGhostLayoutRepresentativeCells() {
        Level level = level();
        if (level == null) return fail("No server level available");
        RecipeHolder<CraftingRecipe> bucket = craftingRecipe(level, "bucket");
        if (bucket == null) return fail("vanilla bucket recipe not found");

        ItemStack[] layout = BankCraftingMatcher.ghostLayout(bucket, emptyGrid());
        for (int i = 0; i < BankCraftingMatcher.GRID_SIZE; i++) {
            boolean expectIron = (i == 0 || i == 2 || i == 4);
            if (expectIron) {
                if (layout[i] == null || !layout[i].is(Items.IRON_INGOT))
                    return fail("layout[" + i + "] should be an iron representative");
            } else if (layout[i] != null) {
                return fail("layout[" + i + "] should be empty for the bucket pattern");
            }
        }

        ItemStack[] partial = BankCraftingMatcher.ghostLayout(bucket, gridWith(Items.IRON_INGOT, 0));
        TestResult r = assertNull("physically occupied cell 0 shows no ghost", partial[0]);
        if (!r.passed()) return r;
        r = assertTrue("cell 2 still shows the iron representative",
                partial[2] != null && partial[2].is(Items.IRON_INGOT));
        if (!r.passed()) return r;
        return pass("ghostLayout renders the recipe's representative cells, skipping physical items");
    }

    // ========================================================================
    // "Craft this item" support logic (bank-list product click)
    // ========================================================================

    /**
     * Product lookup: the recipes-for-product scan must find the vanilla bucket
     * recipe for a bucket, must not offer recipes for an uncraftable item
     * (bedrock), and every returned recipe must actually produce the product.
     */
    private TestResult testRecipesForProductLookup() {
        Level level = level();
        if (level == null) return fail("No server level available");

        List<RecipeHolder<CraftingRecipe>> bucketRecipes =
                BankCraftingMatcher.findRecipesForProduct(level, new ItemStack(Items.BUCKET));
        boolean containsVanillaBucket = bucketRecipes.stream()
                .anyMatch(holder -> holder.id().getPath().equals("bucket"));
        TestResult r = assertTrue("bucket lookup contains the vanilla bucket recipe", containsVanillaBucket);
        if (!r.passed()) return r;
        for (RecipeHolder<CraftingRecipe> holder : bucketRecipes) {
            if (!holder.value().getResultItem(level.registryAccess()).is(Items.BUCKET))
                return fail("lookup returned a recipe that does not produce a bucket: " + holder.id());
        }

        List<RecipeHolder<CraftingRecipe>> bedrockRecipes =
                BankCraftingMatcher.findRecipesForProduct(level, new ItemStack(Items.BEDROCK));
        r = assertTrue("bedrock has no crafting recipe", bedrockRecipes.isEmpty());
        if (!r.passed()) return r;
        r = assertTrue("empty product stack yields no recipes",
                BankCraftingMatcher.findRecipesForProduct(level, ItemStack.EMPTY).isEmpty());
        if (!r.passed()) return r;
        return pass("recipes-for-product lookup finds exactly the producing recipes");
    }

    /**
     * Physical satisfiability (checkbox-off preview): the bucket needs 3 iron —
     * one stack of 3 satisfies, three stacks of 1 satisfy (cross-stack
     * assignment), 2 iron do not, unrelated items do not, and the input stacks
     * are never mutated by the check.
     */
    private TestResult testPhysicalSatisfiabilityCheck() {
        Level level = level();
        if (level == null) return fail("No server level available");
        RecipeHolder<CraftingRecipe> bucket = craftingRecipe(level, "bucket");
        if (bucket == null) return fail("vanilla bucket recipe not found");

        ItemStack ironStack = new ItemStack(Items.IRON_INGOT, 3);
        TestResult r = assertTrue("one stack of 3 iron satisfies the bucket",
                BankCraftingMatcher.canSatisfyPhysically(bucket, List.of(ironStack)));
        if (!r.passed()) return r;
        r = assertEquals("the check does not mutate the input stacks", 3, ironStack.getCount());
        if (!r.passed()) return r;

        r = assertTrue("three 1-iron stacks satisfy the bucket (cross-stack assignment)",
                BankCraftingMatcher.canSatisfyPhysically(bucket, List.of(
                        new ItemStack(Items.IRON_INGOT), new ItemStack(Items.IRON_INGOT), new ItemStack(Items.IRON_INGOT))));
        if (!r.passed()) return r;
        r = assertFalse("two iron do not satisfy the bucket",
                BankCraftingMatcher.canSatisfyPhysically(bucket, List.of(new ItemStack(Items.IRON_INGOT, 2))));
        if (!r.passed()) return r;
        r = assertFalse("unrelated items do not satisfy the bucket",
                BankCraftingMatcher.canSatisfyPhysically(bucket, List.of(new ItemStack(Items.OAK_PLANKS, 64))));
        if (!r.passed()) return r;
        r = assertFalse("an empty source list satisfies nothing",
                BankCraftingMatcher.canSatisfyPhysically(bucket, List.of()));
        if (!r.passed()) return r;
        return pass("physical satisfiability assignment works across stacks without mutation");
    }
}
