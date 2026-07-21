package net.kroia.banksystem.util;

import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bank-assisted crafting-recipe matching for the Bank Terminal's 3x3 crafting grid.
 * <p>
 * Matching runs in two stages:
 * <ol>
 *   <li><b>Physical:</b> the grid contents alone are matched via the vanilla
 *       {@code RecipeManager.getRecipeFor} path. This covers <i>every</i>
 *       {@link RecipeType#CRAFTING} recipe, including special/modded recipe classes.</li>
 *   <li><b>Bank-assisted</b> (only when "Use Bank Items" is enabled, a bank data
 *       snapshot is available and at least one grid slot is physically filled):
 *       all crafting recipes are scanned; for {@link ShapedRecipe} and
 *       {@link ShapelessRecipe} the empty grid slots are virtually filled with
 *       items from the selected bank account so the recipe completes. Every
 *       candidate fill is verified with {@code recipe.matches(...)} before it is
 *       accepted, so component-strict ingredients are honored.</li>
 * </ol>
 * <b>Deliberate limitations</b> (documented design decisions):
 * <ul>
 *   <li>Bank-assisted filling only supports shaped and shapeless recipes — other
 *       recipe classes (vanilla "special" recipes such as firework rockets, and
 *       custom modded recipe classes) expose no per-slot ingredient information,
 *       so they can only match when the grid is physically complete (stage 1).</li>
 *   <li>At least one grid slot must be physically filled by the player. Without a
 *       seed item, every recipe in the game would be bank-completable and the
 *       result slot would show an arbitrary recipe.</li>
 *   <li>Money items are never sourced from the bank (money banks use
 *       worth-scaled balances, not item counts).</li>
 *   <li>Candidate bank items are scanned in ascending {@link ItemID} order so the
 *       client-side ghost preview and the server-side authoritative match pick the
 *       same item deterministically.</li>
 * </ul>
 * This class is side-agnostic: the server uses it with a {@link BankAccountData}
 * snapshot fetched through the async bank API (authoritative at "take result" via
 * the lock/withdraw flow), the client uses it with the streamed copy for the
 * result preview and the ghost icons.
 */
public final class BankCraftingMatcher {

    public static final int GRID_WIDTH = 3;
    public static final int GRID_HEIGHT = 3;
    public static final int GRID_SIZE = GRID_WIDTH * GRID_HEIGHT;

    /**
     * A successful match of the (possibly bank-completed) crafting grid.
     *
     * @param recipe            the matched recipe
     * @param result            the assembled result stack (single craft)
     * @param virtualGrid       the 9 stacks used for matching: physical grid items
     *                          plus one-count bank template stacks in filled slots
     * @param bankPerSlot       per grid slot, the bank {@link ItemID} that fills it,
     *                          or {@code null} when the slot is physical or unused
     * @param bankCountsPerCraft item counts (whole items, not raw units) the bank
     *                          must provide per single craft, keyed by ItemID
     */
    public record Match(RecipeHolder<CraftingRecipe> recipe,
                        ItemStack result,
                        ItemStack[] virtualGrid,
                        @Nullable ItemID[] bankPerSlot,
                        Map<ItemID, Integer> bankCountsPerCraft) {

        /** @return true if at least one grid slot is filled from the bank */
        public boolean usesBankItems() {
            return !bankCountsPerCraft.isEmpty();
        }
    }

    private BankCraftingMatcher() {
    }

    /**
     * Attempts to match the crafting grid, optionally completing it with bank items
     * (scan-based path — no explicit recipe selection). Equivalent to
     * {@link #findMatch(Level, List, boolean, BankAccountData, RecipeHolder)} with a
     * {@code null} ghost recipe.
     */
    public static @Nullable Match findMatch(Level level, List<ItemStack> grid, boolean useBank, @Nullable BankAccountData bankData) {
        return findMatch(level, grid, useBank, bankData, null);
    }

    /**
     * Attempts to match the crafting grid, optionally completing it with bank items.
     *
     * @param level       the level whose recipe manager is queried (works on both sides)
     * @param grid        the 9 grid stacks (row-major, not modified)
     * @param useBank     whether bank-assisted completion is allowed
     * @param bankData    bank account snapshot used for candidate lookup, may be null
     * @param ghostRecipe an explicitly selected recipe (e.g. via JEI's "+" button in
     *                    bank mode), or null for the scan-based path. With an explicit
     *                    selection the ≥1-physical-seed rule does NOT apply (the rule
     *                    only exists to prevent scanning every recipe against an empty
     *                    grid) and ONLY this recipe is tried for bank completion — no
     *                    scan fallback (the user picked a specific recipe; matching a
     *                    different one would be surprising). Physical-first semantics
     *                    are unchanged: items the player places are consumed before
     *                    the bank fills the rest.
     * @return the match, or null if nothing matches
     */
    public static @Nullable Match findMatch(Level level, List<ItemStack> grid, boolean useBank,
                                            @Nullable BankAccountData bankData,
                                            @Nullable RecipeHolder<CraftingRecipe> ghostRecipe) {
        if (grid.size() != GRID_SIZE)
            return null;

        // Stage 1: physical-only match (covers ALL crafting recipe classes).
        CraftingInput physicalInput = CraftingInput.of(GRID_WIDTH, GRID_HEIGHT, grid);
        if (!physicalInput.isEmpty()) {
            Optional<RecipeHolder<CraftingRecipe>> physical =
                    level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, physicalInput, level);
            if (physical.isPresent()) {
                ItemStack result = physical.get().value().assemble(physicalInput, level.registryAccess());
                if (!result.isEmpty()) {
                    ItemStack[] virtualGrid = new ItemStack[GRID_SIZE];
                    for (int i = 0; i < GRID_SIZE; i++)
                        virtualGrid[i] = grid.get(i).copy();
                    return new Match(physical.get(), result, virtualGrid, new ItemID[GRID_SIZE], Map.of());
                }
            }
        }

        // Stage 2: bank-assisted completion.
        if (!useBank || bankData == null)
            return null;

        List<Map.Entry<ItemID, BankData>> candidates = collectBankCandidates(bankData);
        if (candidates.isEmpty())
            return null;

        if (ghostRecipe != null) {
            // Explicit selection: only this recipe, empty grid allowed.
            return tryRecipe(level, ghostRecipe, grid, candidates);
        }

        // Scan path: require at least one physical seed item — without one, every
        // recipe in the game would be bank-completable against the empty grid.
        if (physicalInput.isEmpty())
            return null;

        for (RecipeHolder<CraftingRecipe> holder : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            Match match = tryRecipe(level, holder, grid, candidates);
            if (match != null)
                return match;
        }
        return null;
    }

    /**
     * Tries to bank-complete a single recipe against the grid. Only shaped and
     * shapeless recipes expose per-slot ingredient information; other recipe
     * classes can only match physically (stage 1).
     */
    private static @Nullable Match tryRecipe(Level level, RecipeHolder<CraftingRecipe> holder,
                                             List<ItemStack> grid, List<Map.Entry<ItemID, BankData>> candidates) {
        CraftingRecipe recipe = holder.value();
        if (!recipe.canCraftInDimensions(GRID_WIDTH, GRID_HEIGHT))
            return null;
        if (recipe instanceof ShapedRecipe shaped)
            return tryShaped(level, holder, shaped, grid, candidates);
        if (recipe instanceof ShapelessRecipe shapeless)
            return tryShapeless(level, holder, shapeless, grid, candidates);
        return null;
    }

    /**
     * The recipe's per-grid-cell ingredient layout: shaped recipes anchored
     * top-left (the placement the matcher tries first), shapeless recipes in
     * slot order. Cells the recipe does not use are {@code null}. Only shaped
     * and shapeless recipes have a layout; other classes return all-null.
     */
    public static @Nullable Ingredient[] ingredientLayout(RecipeHolder<CraftingRecipe> holder) {
        Ingredient[] layout = new Ingredient[GRID_SIZE];
        CraftingRecipe recipe = holder.value();
        if (recipe instanceof ShapedRecipe shaped) {
            int width = shaped.getWidth();
            int height = shaped.getHeight();
            if (width > GRID_WIDTH || height > GRID_HEIGHT)
                return layout;
            List<Ingredient> ingredients = shaped.getIngredients();
            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    Ingredient ingredient = ingredients.get(row * width + col);
                    if (ingredient != null && !ingredient.isEmpty())
                        layout[row * GRID_WIDTH + col] = ingredient;
                }
            }
        } else if (recipe instanceof ShapelessRecipe shapeless) {
            int slot = 0;
            for (Ingredient ingredient : shapeless.getIngredients()) {
                if (slot >= GRID_SIZE)
                    break;
                if (ingredient != null && !ingredient.isEmpty())
                    layout[slot++] = ingredient;
            }
        }
        return layout;
    }

    /**
     * Builds the 3x3 display layout of an explicitly selected ghost recipe:
     * a representative stack per ingredient cell (see {@link #ingredientLayout}),
     * with {@code null} in cells whose grid slot is physically occupied
     * (the real item is shown instead) and in cells the recipe does not use.
     * <p>
     * Used by the client to render the ghost layout even when the bank cannot
     * currently satisfy the recipe (so the player sees what is needed) — when a
     * bank-assisted match exists, the match's per-slot picks take precedence.
     */
    public static @Nullable ItemStack[] ghostLayout(RecipeHolder<CraftingRecipe> holder, List<ItemStack> grid) {
        ItemStack[] layout = new ItemStack[GRID_SIZE];
        Ingredient[] ingredients = ingredientLayout(holder);
        for (int i = 0; i < GRID_SIZE; i++) {
            if (ingredients[i] == null)
                continue;
            if (grid.size() == GRID_SIZE && !grid.get(i).isEmpty())
                continue; // physical item shown instead
            layout[i] = representative(ingredients[i]);
        }
        return layout;
    }

    /**
     * All crafting recipes that produce the given item (component-exact result
     * match) and are eligible for the Bank Terminal grid — same rules as the
     * ghost path: shaped/shapeless only, must fit 3x3. Used by the bank-list
     * "craft this item" interaction.
     */
    public static List<RecipeHolder<CraftingRecipe>> findRecipesForProduct(Level level, ItemStack product) {
        List<RecipeHolder<CraftingRecipe>> out = new ArrayList<>();
        if (product.isEmpty())
            return out;
        for (RecipeHolder<CraftingRecipe> holder : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = holder.value();
            if (!(recipe instanceof ShapedRecipe) && !(recipe instanceof ShapelessRecipe))
                continue;
            if (!recipe.canCraftInDimensions(GRID_WIDTH, GRID_HEIGHT))
                continue;
            ItemStack result = recipe.getResultItem(level.registryAccess());
            if (!result.isEmpty() && ItemStack.isSameItemSameComponents(result, product))
                out.add(holder);
        }
        return out;
    }

    /**
     * True when one craft of the recipe can be covered by the given physical
     * item stacks (each stack contributes up to its count; one item consumed per
     * non-empty ingredient). Backtracking assignment, does NOT mutate the
     * stacks. Used for the "craft this item" satisfiability preview when
     * "Use Bank Items" is off (source = player + terminal inventories + grid,
     * the same range the physical fill uses).
     */
    public static boolean canSatisfyPhysically(RecipeHolder<CraftingRecipe> holder, List<ItemStack> available) {
        CraftingRecipe recipe = holder.value();
        if (!(recipe instanceof ShapedRecipe) && !(recipe instanceof ShapelessRecipe))
            return false;
        if (!recipe.canCraftInDimensions(GRID_WIDTH, GRID_HEIGHT))
            return false;
        List<Ingredient> needed = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient != null && !ingredient.isEmpty())
                needed.add(ingredient);
        }
        if (needed.isEmpty() || needed.size() > GRID_SIZE)
            return false;
        int[] remaining = new int[available.size()];
        for (int i = 0; i < available.size(); i++)
            remaining[i] = available.get(i).isEmpty() ? 0 : available.get(i).getCount();
        return assignIngredientsToStacks(needed, 0, available, remaining);
    }

    private static boolean assignIngredientsToStacks(List<Ingredient> needed, int index,
                                                     List<ItemStack> available, int[] remaining) {
        if (index >= needed.size())
            return true;
        Ingredient ingredient = needed.get(index);
        for (int i = 0; i < available.size(); i++) {
            if (remaining[i] <= 0 || !ingredient.test(available.get(i)))
                continue;
            remaining[i]--;
            if (assignIngredientsToStacks(needed, index + 1, available, remaining))
                return true;
            remaining[i]++;
        }
        return false;
    }

    /** First display stack of an ingredient, or null when it has none (empty cell). */
    private static @Nullable ItemStack representative(@Nullable Ingredient ingredient) {
        if (ingredient == null || ingredient.isEmpty())
            return null;
        ItemStack[] items = ingredient.getItems();
        return items.length > 0 ? items[0] : null;
    }

    /**
     * Collects the bank items usable as crafting ingredients, ordered
     * deterministically (ascending ItemID) so client preview and server match agree.
     * Money and invalid/empty templates are excluded.
     */
    private static List<Map.Entry<ItemID, BankData>> collectBankCandidates(BankAccountData bankData) {
        List<Map.Entry<ItemID, BankData>> candidates = new ArrayList<>();
        for (Map.Entry<ItemID, BankData> entry : bankData.bankData.entrySet()) {
            ItemID itemID = entry.getKey();
            BankData data = entry.getValue();
            if (itemID == null || data == null || data.balance() < BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR)
                continue; // less than one whole free item
            if (!itemID.isValid() || MoneyItem.isMoney(itemID))
                continue;
            candidates.add(entry);
        }
        candidates.sort(Comparator.comparingInt(e -> e.getKey().getShort()));
        return candidates;
    }

    private static @Nullable Match tryShaped(Level level, RecipeHolder<CraftingRecipe> holder, ShapedRecipe recipe,
                                             List<ItemStack> grid, List<Map.Entry<ItemID, BankData>> candidates) {
        int width = recipe.getWidth();
        int height = recipe.getHeight();
        if (width > GRID_WIDTH || height > GRID_HEIGHT)
            return null;
        List<Ingredient> ingredients = recipe.getIngredients(); // row-major, size width*height

        for (int mirror = 0; mirror <= 1; mirror++) {
            for (int offsetY = 0; offsetY <= GRID_HEIGHT - height; offsetY++) {
                for (int offsetX = 0; offsetX <= GRID_WIDTH - width; offsetX++) {
                    // Build the expected 3x3 ingredient layout for this placement.
                    Ingredient[] expected = new Ingredient[GRID_SIZE];
                    for (int row = 0; row < height; row++) {
                        for (int col = 0; col < width; col++) {
                            int sourceCol = (mirror == 1) ? (width - 1 - col) : col;
                            Ingredient ingredient = ingredients.get(row * width + sourceCol);
                            expected[(offsetY + row) * GRID_WIDTH + offsetX + col] = ingredient;
                        }
                    }
                    Match match = tryFill(level, holder, expected, grid, candidates);
                    if (match != null)
                        return match;
                }
            }
        }
        return null;
    }

    private static @Nullable Match tryShapeless(Level level, RecipeHolder<CraftingRecipe> holder, ShapelessRecipe recipe,
                                                List<ItemStack> grid, List<Map.Entry<ItemID, BankData>> candidates) {
        List<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty() || ingredients.size() > GRID_SIZE)
            return null;

        // Collect physical grid slots; every physical item must be consumed by the recipe.
        List<Integer> physicalSlots = new ArrayList<>();
        for (int i = 0; i < GRID_SIZE; i++) {
            if (!grid.get(i).isEmpty())
                physicalSlots.add(i);
        }
        if (physicalSlots.size() > ingredients.size())
            return null;

        // Backtracking assignment: physical item -> distinct ingredient index.
        boolean[] usedIngredient = new boolean[ingredients.size()];
        if (!assignPhysical(grid, physicalSlots, 0, ingredients, usedIngredient))
            return null;

        // Remaining ingredients are sourced from the bank into empty grid slots.
        Ingredient[] expected = new Ingredient[GRID_SIZE];
        for (int slot : physicalSlots)
            expected[slot] = null; // marker: physical slots are validated below via recipe.matches
        int emptySlotCursor = 0;
        for (int ingredientIndex = 0; ingredientIndex < ingredients.size(); ingredientIndex++) {
            if (usedIngredient[ingredientIndex])
                continue;
            // find next empty grid slot
            while (emptySlotCursor < GRID_SIZE && !grid.get(emptySlotCursor).isEmpty())
                emptySlotCursor++;
            if (emptySlotCursor >= GRID_SIZE)
                return null;
            expected[emptySlotCursor] = ingredients.get(ingredientIndex);
            emptySlotCursor++;
        }
        // Physical slots keep their items: build an expected[] that accepts them as-is.
        for (int slot : physicalSlots)
            expected[slot] = Ingredient.of(grid.get(slot).getItem());
        return tryFill(level, holder, expected, grid, candidates);
    }

    /**
     * Backtracking bipartite assignment of physical grid items to shapeless
     * ingredient indices (each ingredient consumed at most once).
     */
    private static boolean assignPhysical(List<ItemStack> grid, List<Integer> physicalSlots, int slotIndex,
                                          List<Ingredient> ingredients, boolean[] usedIngredient) {
        if (slotIndex >= physicalSlots.size())
            return true;
        ItemStack stack = grid.get(physicalSlots.get(slotIndex));
        for (int i = 0; i < ingredients.size(); i++) {
            if (usedIngredient[i] || !ingredients.get(i).test(stack))
                continue;
            usedIngredient[i] = true;
            if (assignPhysical(grid, physicalSlots, slotIndex + 1, ingredients, usedIngredient))
                return true;
            usedIngredient[i] = false;
        }
        return false;
    }

    /**
     * Attempts to complete the grid against the expected per-slot ingredient layout:
     * physical items must satisfy their cell's ingredient, empty cells with a
     * non-empty ingredient are filled from the bank. The candidate fill is finally
     * verified with {@code recipe.matches(...)} — the authoritative check.
     */
    private static @Nullable Match tryFill(Level level, RecipeHolder<CraftingRecipe> holder, Ingredient[] expected,
                                           List<ItemStack> grid, List<Map.Entry<ItemID, BankData>> candidates) {
        ItemStack[] virtualGrid = new ItemStack[GRID_SIZE];
        ItemID[] bankPerSlot = new ItemID[GRID_SIZE];
        Map<ItemID, Integer> bankCounts = new HashMap<>();
        boolean anyBankFill = false;

        for (int i = 0; i < GRID_SIZE; i++) {
            ItemStack physical = grid.get(i);
            Ingredient ingredient = expected[i];
            boolean ingredientEmpty = ingredient == null || ingredient.isEmpty();
            if (ingredientEmpty) {
                if (!physical.isEmpty())
                    return null; // grid item outside the recipe pattern
                virtualGrid[i] = ItemStack.EMPTY;
                continue;
            }
            if (!physical.isEmpty()) {
                if (!ingredient.test(physical))
                    return null;
                virtualGrid[i] = physical.copy();
                continue;
            }
            // Empty slot that the recipe needs — source from the bank.
            ItemID pick = pickBankItem(ingredient, candidates, bankCounts);
            if (pick == null)
                return null;
            bankPerSlot[i] = pick;
            bankCounts.merge(pick, 1, Integer::sum);
            ItemStack bankStack = pick.getStack();
            bankStack.setCount(1);
            virtualGrid[i] = bankStack;
            anyBankFill = true;
        }
        if (!anyBankFill)
            return null; // pure physical layouts are handled by stage 1

        CraftingInput virtualInput = CraftingInput.of(GRID_WIDTH, GRID_HEIGHT, List.of(virtualGrid));
        if (!holder.value().matches(virtualInput, level))
            return null;
        ItemStack result = holder.value().assemble(virtualInput, level.registryAccess());
        if (result.isEmpty())
            return null;
        return new Match(holder, result, virtualGrid, bankPerSlot, bankCounts);
    }

    /**
     * Picks the first (deterministically ordered) bank item that satisfies the
     * ingredient and still has enough free balance for one more unit on top of
     * what this fill already reserved.
     */
    private static @Nullable ItemID pickBankItem(Ingredient ingredient, List<Map.Entry<ItemID, BankData>> candidates,
                                                 Map<ItemID, Integer> alreadyReserved) {
        for (Map.Entry<ItemID, BankData> entry : candidates) {
            ItemID itemID = entry.getKey();
            ItemStack template = itemID.getStackTemplate(); // read-only test
            if (template.isEmpty() || !ingredient.test(template))
                continue;
            long needed = (alreadyReserved.getOrDefault(itemID, 0) + 1L) * BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
            if (entry.getValue().balance() >= needed)
                return itemID;
        }
        return null;
    }
}
