package net.kroia.banksystem.util;

import net.kroia.banksystem.networking.entity.S2CCraftingRecipesPacket;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation; // mc<=1.21.10
//import net.minecraft.resources.Identifier; // mc>=1.21.11
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Crafting-recipe access for the Bank Terminal, identical on every MC version.
 * <p>
 * Since MC 1.21.2 the client no longer receives recipes, so BankSystem syncs the
 * shaped and shapeless crafting recipes itself ({@link S2CCraftingRecipesPacket})
 * when a player opens a Bank Terminal. The server always uses its full
 * {@link RecipeManager} crafting list and stays authoritative for the result slot;
 * the client copy only feeds previews, the recipe list and JEI transfer.
 * <p>
 * All version-specific recipe/ingredient API calls are concentrated here.
 */
public final class BankCraftingRecipes {

    /** Recipes per sync packet (keeps each payload far below the 1 MiB limit). */
    private static final int SYNC_CHUNK_SIZE = 256;

    private static @Nullable RecipeManager cachedManager;
    private static List<RecipeHolder<CraftingRecipe>> cachedServerRecipes = List.of();
    /** Which recipe manager generation each connected player has received. */
    private static final Map<ServerPlayer, RecipeManager> SYNCED = new WeakHashMap<>();

    private static volatile List<RecipeHolder<CraftingRecipe>> clientRecipes = List.of();
    private static List<RecipeHolder<CraftingRecipe>> clientIncoming = new ArrayList<>();

    private BankCraftingRecipes() {
    }

    // ------------------------------------------------------------------
    // Recipe lists
    // ------------------------------------------------------------------

    /** All crafting recipes visible on this side (server: full list, client: synced shaped/shapeless). */
    public static List<RecipeHolder<CraftingRecipe>> all(Level level) {
        if (level.isClientSide())
            return clientRecipes;
        MinecraftServer server = level.getServer();
        return server == null ? List.of() : serverRecipes(server.getRecipeManager());
    }

    public static @Nullable RecipeHolder<CraftingRecipe> byId(Level level, ResourceLocation recipeId) { // mc<=1.21.10
    //public static @Nullable RecipeHolder<CraftingRecipe> byId(Level level, Identifier recipeId) { // mc>=1.21.11
        for (RecipeHolder<CraftingRecipe> holder : all(level)) {
            if (id(holder).equals(recipeId))
                return holder;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static synchronized List<RecipeHolder<CraftingRecipe>> serverRecipes(RecipeManager manager) {
        if (manager != cachedManager) {
            List<RecipeHolder<CraftingRecipe>> list = new ArrayList<>();
            for (RecipeHolder<?> holder : manager.getRecipes()) {
                if (holder.value().getType() == RecipeType.CRAFTING && holder.value() instanceof CraftingRecipe)
                    list.add((RecipeHolder<CraftingRecipe>) holder);
            }
            cachedServerRecipes = List.copyOf(list);
            cachedManager = manager;
        }
        return cachedServerRecipes;
    }

    // ------------------------------------------------------------------
    // Sync
    // ------------------------------------------------------------------

    /**
     * Sends the shaped/shapeless crafting recipes to the player unless they already
     * have the current set (a datapack reload creates a new recipe manager). Call
     * before opening a Bank Terminal menu.
     */
    public static void syncTo(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null)
            return;
        RecipeManager manager = server.getRecipeManager();
        synchronized (SYNCED) {
            if (SYNCED.get(player) == manager)
                return;
            SYNCED.put(player, manager);
        }
        List<RecipeHolder<?>> syncable = new ArrayList<>();
        for (RecipeHolder<CraftingRecipe> holder : serverRecipes(manager)) {
            if (holder.value() instanceof ShapedRecipe || holder.value() instanceof ShapelessRecipe)
                syncable.add(holder);
        }
        int chunks = Math.max(1, (syncable.size() + SYNC_CHUNK_SIZE - 1) / SYNC_CHUNK_SIZE);
        for (int i = 0; i < chunks; i++) {
            List<RecipeHolder<?>> chunk = syncable.subList(i * SYNC_CHUNK_SIZE,
                    Math.min(syncable.size(), (i + 1) * SYNC_CHUNK_SIZE));
            S2CCraftingRecipesPacket.sendTo(player, i == 0, i == chunks - 1, new ArrayList<>(chunk));
        }
    }

    /** Client side: receives one sync chunk; the list becomes visible once the last chunk arrived. */
    @SuppressWarnings("unchecked")
    public static void acceptChunk(boolean first, boolean last, List<RecipeHolder<?>> recipes) {
        if (first)
            clientIncoming = new ArrayList<>();
        for (RecipeHolder<?> holder : recipes) {
            if (holder.value() instanceof CraftingRecipe)
                clientIncoming.add((RecipeHolder<CraftingRecipe>) holder);
        }
        if (last) {
            clientRecipes = List.copyOf(clientIncoming);
            clientIncoming = new ArrayList<>();
        }
    }

    // ------------------------------------------------------------------
    // Version-neutral recipe accessors (all version toggles live here)
    // ------------------------------------------------------------------

    public static ResourceLocation id(RecipeHolder<?> holder) { // mc<=1.21.10
    //public static Identifier id(RecipeHolder<?> holder) { // mc>=1.21.11
        //return holder.id(); // mc<=1.21.1
        return holder.id().location(); // mc>=1.21.2 mc<=1.21.10
        //return holder.id().identifier(); // mc>=1.21.11
    }

    /** True if the recipe can be crafted in the 3x3 Bank Terminal grid. */
    public static boolean fits3x3(CraftingRecipe recipe) {
        if (recipe instanceof ShapedRecipe shaped)
            return shaped.getWidth() <= 3 && shaped.getHeight() <= 3;
        if (recipe instanceof ShapelessRecipe)
            return ingredients(recipe).size() <= 9;
        //return recipe.canCraftInDimensions(3, 3); // mc<=1.21.1
        // 1.21.2+: special recipes have no size; they only match physically.
        return true; // mc>=1.21.2
    }

    /**
     * Ingredient list of a shaped (row-major, width*height, {@code null} for empty
     * cells) or shapeless (no nulls) recipe; empty for every other recipe class.
     */
    public static List<Ingredient> ingredients(CraftingRecipe recipe) {
        List<Ingredient> out = new ArrayList<>();
        if (recipe instanceof ShapedRecipe shaped) {
            //for (Ingredient ingredient : shaped.getIngredients()) out.add(isEmpty(ingredient) ? null : ingredient); // mc<=1.21.1
            for (java.util.Optional<Ingredient> ingredient : shaped.getIngredients()) out.add(ingredient.orElse(null)); // mc>=1.21.2
        } else if (recipe instanceof ShapelessRecipe shapeless) {
            //for (Ingredient ingredient : shapeless.getIngredients()) if (!isEmpty(ingredient)) out.add(ingredient); // mc<=1.21.1
            out.addAll(shapeless.placementInfo().ingredients()); // mc>=1.21.2
        }
        return out;
    }

    public static boolean isEmpty(@Nullable Ingredient ingredient) {
        //return ingredient == null || ingredient.isEmpty(); // mc<=1.21.1
        return ingredient == null; // mc>=1.21.2
    }

    /** First display stack of an ingredient, or null when it has none. */
    public static @Nullable ItemStack representative(@Nullable Ingredient ingredient) {
        if (isEmpty(ingredient))
            return null;
        //ItemStack[] items = ingredient.getItems(); // mc<=1.21.1
        //return items.length > 0 ? items[0] : null; // mc<=1.21.1
        //var items = ingredient.items(); // mc>=1.21.2 mc<=1.21.3
        //return items.isEmpty() ? null : new ItemStack(items.get(0)); // mc>=1.21.2 mc<=1.21.3
        return ingredient.items().findFirst().map(item -> new ItemStack(item)).orElse(null); // mc>=1.21.4
    }

    /** Result stack of a shaped/shapeless recipe (their assemble() ignores the input). */
    public static ItemStack resultOf(RecipeHolder<CraftingRecipe> holder, HolderLookup.Provider registries) {
        CraftingRecipe recipe = holder.value();
        if (!(recipe instanceof ShapedRecipe) && !(recipe instanceof ShapelessRecipe))
            return ItemStack.EMPTY;
        return recipe.assemble(CraftingInput.EMPTY, registries);
    }
}
