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
import net.kroia.banksystem.BankSystemModBackend;
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
 * <p>
 * <b>Overlay hide-on-open for upload / download screens (Task, 2026-07-26):</b>
 * The Bank Upload and Bank Download screens want JEI's ingredient list AND
 * bookmark overlays — including JEI's own overlay buttons in the corner —
 * completely hidden while they are open, and restored on close. JEI 19.21's
 * public API has no {@code setVisible}/{@code hide} method on either
 * {@link mezz.jei.api.runtime.IIngredientListOverlay} or
 * {@link mezz.jei.api.runtime.IBookmarkOverlay}, and full-window exclusion
 * areas only relocate the buttons rather than hiding them (see previous
 * paragraph). This class therefore reaches into JEI's internals via
 * reflection on {@link #onRuntimeAvailable(IJeiRuntime)} and installs a
 * {@link java.util.function.Consumer Consumer&lt;Boolean&gt;} into
 * {@link BankSystemJeiOverlayBroker} that the screens call from their
 * lifecycle hooks. Three JEI state slots are flipped together: the
 * {@code IClientToggleState.overlayEnabled} flag (hides ingredient list +
 * bookmark list contents), plus the {@code visible} field on the config
 * button and the bookmark button (hides the two toggle widgets that JEI
 * draws independently of {@code overlayEnabled} — verified in the
 * {@code drawScreen} methods of {@code IngredientListOverlay} and
 * {@code BookmarkOverlay}). Reflection failures degrade to a silent no-op
 * — JEI just stays as it was.
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

    /**
     * Installs the JEI overlay-hide implementation into
     * {@link BankSystemJeiOverlayBroker}. Reaches JEI's internal
     * {@code mezz.jei.common.config.IClientToggleState} via reflection on the
     * concrete {@link mezz.jei.api.runtime.IIngredientListOverlay} instance
     * returned by {@link IJeiRuntime#getIngredientListOverlay()} — JEI 19.x
     * exposes no public API for this (verified against 19.21.0.247).
     * <p>
     * <b>What we flip:</b>
     * <ol>
     *   <li>{@code IClientToggleState.toggleOverlayEnabled()} — hides the
     *       ingredient list contents and the bookmark list contents (bookmark
     *       overlay {@code isListDisplayed} depends on ingredient overlay
     *       being enabled).</li>
     *   <li>{@code IngredientListOverlay.configButton.button.visible = false}
     *       — hides the gear/config button. This button is rendered by
     *       {@code drawScreen} guarded only on
     *       {@code screenPropertiesCache.hasValidScreen()} (verified in
     *       {@code mezz.jei.gui.overlay.IngredientListOverlay#drawScreen}),
     *       so the overlay-enabled flag alone does NOT hide it.</li>
     *   <li>{@code BookmarkOverlay.bookmarkButton.button.visible = false} —
     *       hides the "B" bookmark button. Same guard as the config button
     *       (verified in {@code mezz.jei.gui.overlay.bookmarks.BookmarkOverlay#drawScreen}).</li>
     * </ol>
     * We do NOT touch {@code setBookmarkEnabled} — its raw backing flag is
     * not readable via the public {@code isBookmarkOverlayEnabled()} accessor
     * (returns {@code isOverlayEnabled() && bookmarkOverlayEnabled}), so we
     * cannot restore it faithfully on close. Hiding via the overlay flag +
     * the two visible flags is sufficient.
     * <p>
     * <b>Field name resolution:</b> internal JEI field names ({@code toggleState},
     * {@code configButton}, {@code bookmarkButton}, {@code button}) are stable
     * across loaders because they belong to JEI's own bytecode. The Minecraft
     * {@code AbstractWidget.visible} field, however, is mojmap on NeoForge
     * but intermediary ({@code field_22764}) on Fabric at runtime — we try
     * both names in order.
     * <p>
     * <b>Failure policy:</b> any {@link Throwable} at install time or per
     * call is caught and the broker either stays at (or reverts to) its
     * no-op consumer. Worst case JEI stays visible, which is harmless.
     * <p>
     * <b>Ownership tracking:</b> the installed consumer snapshots the current
     * state of every field on first hide via {@link HiderState}; restore
     * writes the saved value back only if it looks like WE were the ones that
     * changed it (e.g. we don't force-re-enable an overlay the user
     * re-toggled with 'O' while inside our screen). See per-field comments
     * in the lambda below.
     */
    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        try {
            Object listOverlay = jeiRuntime.getIngredientListOverlay();
            Object bookmarkOverlay = jeiRuntime.getBookmarkOverlay();
            if (listOverlay == null) {
                logWarn("IJeiRuntime.getIngredientListOverlay() returned null; overlay hider disabled.");
                return;
            }

            // 1) IClientToggleState via IngredientListOverlay#toggleState.
            Object toggleState = readFieldByName(listOverlay, "toggleState");
            if (toggleState == null) {
                logWarn("Could not resolve toggleState on " + listOverlay.getClass().getName()
                        + "; JEI overlay hide-on-open disabled.");
                return;
            }
            Method isOverlayEnabled = toggleState.getClass().getMethod("isOverlayEnabled");
            Method toggleOverlayEnabled = toggleState.getClass().getMethod("toggleOverlayEnabled");

            // 2) Inner AbstractWidget of IngredientListOverlay#configButton.
            Object configInner = resolveInnerButton(listOverlay, "configButton");

            // 3) Inner AbstractWidget of BookmarkOverlay#bookmarkButton (if reachable).
            Object bookmarkInner = bookmarkOverlay != null
                    ? resolveInnerButton(bookmarkOverlay, "bookmarkButton")
                    : null;

            // Minecraft 'visible' field on AbstractWidget — mojmap on NeoForge,
            // intermediary on Fabric. Look it up once via the runtime class of
            // whichever inner button we managed to grab.
            Field visibleField = null;
            Object visibleProbe = configInner != null ? configInner : bookmarkInner;
            if (visibleProbe != null) {
                visibleField = findFieldByAnyName(visibleProbe.getClass(),
                        "visible",       // mojmap (NeoForge runtime)
                        "field_22764");  // intermediary (Fabric runtime)
                if (visibleField == null) {
                    logWarn("Could not resolve AbstractWidget#visible field on "
                            + visibleProbe.getClass().getName()
                            + "; JEI overlay buttons will remain visible.");
                }
            }

            final Object toggleStateRef = toggleState;
            final Object configInnerRef = configInner;
            final Object bookmarkInnerRef = bookmarkInner;
            final Field visibleFieldRef = visibleField;
            final HiderState state = new HiderState();

            BankSystemJeiOverlayBroker.install(hidden -> {
                try {
                    if (hidden) {
                        // Idempotent — if we already hid on a previous call, skip.
                        // Prevents double-save on screen resize (updateLayout re-fires).
                        if (state.hiddenByUs) return;

                        // Snapshot current state so restore can put things back exactly
                        // as they were, respecting the user's own JEI toggle preferences.
                        state.savedOverlayEnabled = (Boolean) isOverlayEnabled.invoke(toggleStateRef);
                        state.savedConfigVisible = readVisible(visibleFieldRef, configInnerRef);
                        state.savedBookmarkVisible = readVisible(visibleFieldRef, bookmarkInnerRef);

                        // Apply the hide.
                        if (state.savedOverlayEnabled) {
                            toggleOverlayEnabled.invoke(toggleStateRef);
                        }
                        if (state.savedConfigVisible != null) {
                            visibleFieldRef.setBoolean(configInnerRef, false);
                        }
                        if (state.savedBookmarkVisible != null) {
                            visibleFieldRef.setBoolean(bookmarkInnerRef, false);
                        }
                        state.hiddenByUs = true;
                    } else {
                        if (!state.hiddenByUs) return;

                        // Overlay: only re-toggle if it is STILL disabled. If the
                        // user pressed 'O' inside our screen to re-show JEI, we
                        // leave their choice alone (savedOverlayEnabled=true &&
                        // currentlyEnabled=true → skip the toggle).
                        boolean currentlyEnabled = (Boolean) isOverlayEnabled.invoke(toggleStateRef);
                        if (state.savedOverlayEnabled && !currentlyEnabled) {
                            toggleOverlayEnabled.invoke(toggleStateRef);
                        }
                        // Buttons: restore whatever we saw before. In practice
                        // savedConfigVisible / savedBookmarkVisible are always
                        // true on entry (there is no JEI UI to hide these
                        // buttons), but we track the snapshot for correctness.
                        if (state.savedConfigVisible != null) {
                            visibleFieldRef.setBoolean(configInnerRef, state.savedConfigVisible);
                        }
                        if (state.savedBookmarkVisible != null) {
                            visibleFieldRef.setBoolean(bookmarkInnerRef, state.savedBookmarkVisible);
                        }
                        state.hiddenByUs = false;
                    }
                } catch (Throwable ignored) {
                    // Silent per-call failure — a mid-run reflection error must
                    // never bubble out and crash the calling screen. The broker's
                    // setHidden also swallows, but this belt-and-suspenders means
                    // one bad open does not poison later opens.
                }
            });
        } catch (Throwable t) {
            BankSystemJeiOverlayBroker.install(null);
            logWarn("Could not install JEI overlay hider: " + t);
        }
    }

    /**
     * State snapshot for the installed overlay hider — captured on first
     * {@code setHidden(true)} and consumed on the matching {@code setHidden(false)}.
     * <p>
     * {@code savedConfigVisible} and {@code savedBookmarkVisible} are boxed so
     * {@code null} can encode "we never resolved this field" — in that case
     * we skip both the save and the restore for that button rather than
     * writing garbage.
     */
    private static class HiderState {
        boolean hiddenByUs = false;
        boolean savedOverlayEnabled;
        Boolean savedConfigVisible;
        Boolean savedBookmarkVisible;
    }

    /**
     * Resolves the inner Minecraft-{@code AbstractWidget}-derived button of
     * one of JEI's overlays: for example, {@code listOverlay.configButton.button}.
     * <p>
     * JEI's {@code IngredientListOverlay} and {@code BookmarkOverlay} each
     * own a {@code GuiIconToggleButton} (private final field named
     * {@code configButton} / {@code bookmarkButton}). That wrapper holds a
     * protected final {@code GuiIconButton} field named {@code button} — the
     * actual Minecraft widget whose {@code visible} flag gates rendering
     * (see {@code AbstractWidget#render}).
     * <p>
     * Returns {@code null} on any reflection failure so the caller can
     * degrade gracefully (that button stays visible; not a crash).
     */
    private static Object resolveInnerButton(Object overlay, String wrapperFieldName) {
        try {
            Object wrapper = readFieldByName(overlay, wrapperFieldName);
            if (wrapper == null) return null;
            return readFieldByName(wrapper, "button");
        } catch (Throwable t) {
            logWarn("Could not resolve inner button for " + wrapperFieldName + ": " + t);
            return null;
        }
    }

    /**
     * Reads the boolean {@code visible} from the given widget instance via the
     * pre-resolved {@link Field}. Returns {@code null} if either the field or
     * the target is {@code null} so callers can skip save/restore for that
     * widget.
     */
    private static Boolean readVisible(Field visibleField, Object widget) {
        if (visibleField == null || widget == null) return null;
        try {
            return visibleField.getBoolean(widget);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Reads a declared field by name from any level of the class hierarchy of
     * the given instance. Handles {@code setAccessible} and returns
     * {@code null} on any failure.
     */
    private static Object readFieldByName(Object instance, String fieldName) {
        if (instance == null) return null;
        Field f = findFieldByName(instance.getClass(), fieldName);
        if (f == null) return null;
        try {
            f.setAccessible(true);
            return f.get(instance);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Walks the class hierarchy of {@code cls} looking for a declared field
     * with the given name. Returns {@code null} if not found on any level up
     * to (but not including) {@link Object}. Used so this plugin does not
     * hardcode the internal impl class name of JEI's ingredient list overlay.
     */
    private static Field findFieldByName(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // try parent
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /**
     * Convenience wrapper around {@link #findFieldByName(Class, String)} that
     * tries multiple candidate names in order and returns the first one that
     * resolves. Used for the Minecraft {@code AbstractWidget.visible} field,
     * which is {@code visible} in mojmap (NeoForge runtime) and
     * {@code field_22764} in intermediary (Fabric runtime).
     */
    private static Field findFieldByAnyName(Class<?> cls, String... names) {
        for (String name : names) {
            Field f = findFieldByName(cls, name);
            if (f != null) return f;
        }
        return null;
    }

    /**
     * Best-effort logger access. The JEI plugin can, in principle, be loaded
     * before {@link BankSystemModBackend} has initialized; in that (unlikely)
     * case we silently skip the log rather than NPE.
     */
    private static void logWarn(String message) {
        try {
            BankSystemModBackend.Instances instances = BankSystemModBackend.getInstances_forTesting();
            if (instances != null && instances.LOGGER != null) {
                instances.LOGGER.warn("[BankSystemJeiPlugin] " + message);
            }
        } catch (Throwable ignored) {
            // Best-effort logging only.
        }
    }
}
