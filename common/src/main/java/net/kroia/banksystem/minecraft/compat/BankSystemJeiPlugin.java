package net.kroia.banksystem.minecraft.compat;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.transfer.IRecipeTransferInfo;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.minecraft.menu.BankSystemMenus;
import net.kroia.banksystem.minecraft.menu.custom.BankTerminalContainerMenu;
import net.kroia.banksystem.screen.custom.BankDownloadScreen;
import net.kroia.banksystem.screen.custom.BankTerminalScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * JEI integration.
 * <p>
 * <b>Placement decision (Task #2, 2026-07-16):</b> this plugin intentionally
 * stays in {@code common/} with the JEI API as {@code compileOnly}, instead of
 * being duplicated into the {@code fabric/} / {@code neoforge/} platform
 * folders as the original task spec suggested. One shared class keeps the
 * exclusion areas and the recipe transfer handler loader-agnostic and in one
 * place; the mod builds and runs without JEI on the classpath (JEI is not a
 * runtime dependency of any loader module).
 * <p>
 * <b>Discovery is loader-specific:</b> NeoForge JEI scans jars for the
 * {@code @JeiPlugin} annotation, but Fabric/Quilt JEI discovers plugins ONLY via
 * the {@code jei_mod_plugin} entrypoint (see JEI's {@code FabricPluginFinder}) —
 * this class is therefore also declared as that entrypoint in
 * {@code fabric.mod.json} and {@code quilt.mod.json}. Entrypoints are resolved
 * lazily by the loader (only when JEI queries them), so the soft dependency is
 * preserved: without JEI the class is never loaded.
 * <p>
 * Our screens render custom GUI elements across (almost) the whole window
 * instead of only the vanilla centered container rectangle, so JEI is told
 * about the actual element bounds via
 * {@link IGuiContainerHandler#getGuiExtraAreas(net.minecraft.client.gui.screens.inventory.AbstractContainerScreen)}
 * exclusion areas.
 * <p>
 * Approach for JEI's overlay buttons (recipe history, bookmark/cheat-mode,
 * config): JEI 19.x exposes no API to hide or reposition them directly
 * (checked against jei-1.21.1-common-api 19.21.0.247 — {@code IJeiFeatures}
 * only offers {@code disableInventoryEffectRendererGuiHandler}). The supported
 * mechanism is exactly these exclusion areas: JEI lays out its ingredient
 * list, bookmark overlay and their buttons in the screen space NOT covered by
 * the container rectangle + extra areas. Previously this plugin returned the
 * entire window as one exclusion rectangle, which left JEI no space at all —
 * it hid its right-side ingredient list and dropped its overlay buttons to the
 * bottom-left corner on top of our bank list. Returning the true per-element
 * bounds (while the screens reserve a right-hand margin via their
 * JEI-aware width percentage) lets JEI place its panel and buttons in
 * genuinely free space; anything that does not fit is hidden by JEI itself.
 * <p>
 * <b>Recipe transfer ("+" button):</b> the Bank Terminal's 3x3 crafting grid is
 * registered as a standard crafting transfer target, so JEI's recipe view can
 * move matching ingredients from the player inventory into the grid. Bank
 * sourcing is invisible to JEI by design — with "Use Bank Items" active, the
 * grid slots JEI could not fill are completed from the bank automatically and
 * shown as ghost icons.
 */
@JeiPlugin
public class BankSystemJeiPlugin implements IModPlugin {


    public BankSystemJeiPlugin() {
    }

    public static void init()
    {
        //JEIIntegration.registerPlugin(new BankSystemJeiPlugin());
    }
    private static final ResourceLocation PLUGIN_UID = ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return PLUGIN_UID;
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        // Register exclusion areas for our custom screens (see class javadoc).
        registration.addGuiContainerHandler(BankTerminalScreen.class, new IGuiContainerHandler<BankTerminalScreen>() {
            @Override
            public List<Rect2i> getGuiExtraAreas(BankTerminalScreen screen) {
                return screen.getJeiExclusionAreas();
            }
        });
        registration.addGuiContainerHandler(BankDownloadScreen.class, new IGuiContainerHandler<BankDownloadScreen>() {
            @Override
            public List<Rect2i> getGuiExtraAreas(BankDownloadScreen screen) {
                return screen.getJeiExclusionAreas();
            }
        });
    }

    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        // Crafting transfer into the Bank Terminal's 3x3 grid. Two modes:
        // - "Use Bank Items" OFF: JEI's standard physical transfer (recipe slots =
        //   the 9 grid slots). The ingredient source range spans the player
        //   hotbar + main inventory AND the terminal's block inventory (menu
        //   indices 0..62 — everything before the grid), so "+" can also pull
        //   items already stored in the terminal. JEI handles shaped placement
        //   itself; the same range drives shift-"+" (max transfer).
        //   Displacement direction (verified against JEI 19.21's
        //   BasicRecipeTransferHandlerServer.stowItem): displaced grid items and
        //   remainders are stowed in SLOT-LIST ORDER — merge into existing
        //   stacks, then first empty slot — so they return to the player
        //   inventory (0..35) first and only overflow into the block inventory
        //   (36..62), which is visible on the same screen. Ingredient lookup
        //   uses the same order, preferring player-held items over stored ones.
        // - "Use Bank Items" ON: the "+" click selects the recipe as a GHOST
        //   layout instead — no items are moved; the server sources every
        //   ingredient (that the player does not place physically) from the bank.
        IRecipeTransferHandlerHelper transferHelper = registration.getTransferHelper();
        IRecipeTransferInfo<BankTerminalContainerMenu, RecipeHolder<CraftingRecipe>> physicalTransferInfo =
                transferHelper.createBasicRecipeTransferInfo(
                        BankTerminalContainerMenu.class,
                        BankSystemMenus.BANK_TERMINAL_CONTAINER_MENU.get(),
                        RecipeTypes.CRAFTING,
                        BankTerminalContainerMenu.CRAFT_GRID_SLOT_START,
                        BankTerminalContainerMenu.CRAFT_GRID_SLOT_COUNT,
                        BankTerminalContainerMenu.PLAYER_SLOT_START,
                        // count 63 = player slots (0..35) + block inventory (36..62)
                        BankTerminalContainerMenu.CRAFT_GRID_SLOT_START);
        IRecipeTransferHandler<BankTerminalContainerMenu, RecipeHolder<CraftingRecipe>> physicalDelegate =
                transferHelper.createUnregisteredRecipeTransferHandler(physicalTransferInfo);
        registration.addRecipeTransferHandler(
                new BankTerminalRecipeTransferHandler(physicalDelegate), RecipeTypes.CRAFTING);
    }

    /**
     * Crafting transfer handler for the Bank Terminal: ghost-recipe selection in
     * bank mode, JEI's default physical transfer otherwise. Recipe classes without
     * per-slot ingredient info (vanilla "special" recipes, custom modded classes)
     * cannot be bank-completed, so they always use the physical delegate.
     */
    private static class BankTerminalRecipeTransferHandler
            implements IRecipeTransferHandler<BankTerminalContainerMenu, RecipeHolder<CraftingRecipe>> {

        private final IRecipeTransferHandler<BankTerminalContainerMenu, RecipeHolder<CraftingRecipe>> physicalDelegate;

        private BankTerminalRecipeTransferHandler(IRecipeTransferHandler<BankTerminalContainerMenu, RecipeHolder<CraftingRecipe>> physicalDelegate) {
            this.physicalDelegate = physicalDelegate;
        }

        @Override
        public Class<? extends BankTerminalContainerMenu> getContainerClass() {
            return BankTerminalContainerMenu.class;
        }

        @Override
        public Optional<MenuType<BankTerminalContainerMenu>> getMenuType() {
            return Optional.of(BankSystemMenus.BANK_TERMINAL_CONTAINER_MENU.get());
        }

        @Override
        public mezz.jei.api.recipe.RecipeType<RecipeHolder<CraftingRecipe>> getRecipeType() {
            return RecipeTypes.CRAFTING;
        }

        @Override
        public @Nullable IRecipeTransferError transferRecipe(BankTerminalContainerMenu container,
                                                             RecipeHolder<CraftingRecipe> recipe,
                                                             IRecipeSlotsView recipeSlots, Player player,
                                                             boolean maxTransfer, boolean doTransfer) {
            CraftingRecipe craftingRecipe = recipe.value();
            boolean bankCompletable = (craftingRecipe instanceof ShapedRecipe || craftingRecipe instanceof ShapelessRecipe)
                    && craftingRecipe.canCraftInDimensions(3, 3);
            if (container.isUseBankItems() && bankCompletable) {
                // Ghost mode: no items are moved, so there is nothing to
                // pre-validate — the button is always available and the server
                // decides at "take result" whether the bank can satisfy the
                // recipe (result slot stays empty otherwise).
                if (doTransfer)
                    container.requestGhostRecipe(recipe);
                return null;
            }
            return physicalDelegate.transferRecipe(container, recipe, recipeSlots, player, maxTransfer, doTransfer);
        }
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        // Optional: Interact with JEI runtime if needed
    }
}
