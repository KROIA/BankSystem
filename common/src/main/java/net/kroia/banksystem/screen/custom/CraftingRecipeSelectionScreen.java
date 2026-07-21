package net.kroia.banksystem.screen.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.util.BankCraftingMatcher;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.ItemView;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.elements.base.ListView;
import net.kroia.modutilities.gui.layout.LayoutVertical;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.List;
import java.util.function.Consumer;

/**
 * Recipe picker for the Bank Terminal's "craft this item" interaction (clicking
 * an item icon in the bank list): lists every eligible crafting recipe that
 * produces the selected product, with the recipe result and an ingredient
 * preview per row. Recipes whose ingredients are not currently coverable (from
 * the bank in bank mode, from the player + terminal inventories otherwise) are
 * marked "(missing ingredients)" but stay selectable on purpose: in bank mode
 * the selection becomes a ghost layout that shows the player what is needed,
 * in physical mode the grid fills best-effort with whatever is available.
 * <p>
 * When the product has no crafting recipe at all, the same window opens with an
 * explanatory message instead of the list — one consistent surface for every
 * outcome (the ModUtilities screens have no transient toast idiom, and chat
 * messages are hidden behind the open container screen).
 * <p>
 * Follows the {@link BankAccountSelectionScreen} pattern: opened over the
 * terminal screen with {@code parent} set, so closing (or picking) returns to
 * the terminal with its menu intact.
 */
public class CraftingRecipeSelectionScreen extends BankSystemGuiScreen {

    public static class TEXT {
        public static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".recipe_selection_screen.";
        public static final Component TITLE = Component.translatable(PREFIX + "title");
        public static final Component TITLE_LABEL = Component.translatable(PREFIX + "title_label");
        public static final Component MISSING_INGREDIENTS = Component.translatable(PREFIX + "missing_ingredients");
        public static final Component NO_RECIPE = Component.translatable(PREFIX + "no_recipe");
    }

    /** A selectable recipe plus its current satisfiability (preview only). */
    public record Candidate(RecipeHolder<CraftingRecipe> recipe, boolean satisfiable) {
    }

    /** One selectable row: result icon + name, ingredient preview, missing marker. */
    private static class RecipeButton extends Button {
        private static final int ICON_SIZE = 18;

        private final ItemView resultView;
        private final Label nameLabel;
        private final List<ItemView> ingredientViews;
        private final Label missingLabel;

        RecipeButton(Candidate candidate, ItemStack result) {
            super("");
            resultView = new ItemView();
            resultView.setItemStack(result);
            nameLabel = new Label(result.getHoverName().getString()
                    + (result.getCount() > 1 ? (" x" + result.getCount()) : ""));
            missingLabel = new Label(candidate.satisfiable() ? "" : MISSING_LABEL_TEXT);
            missingLabel.setTextColor(0xFFFF5555);
            missingLabel.setTextFontScale(0.7f);

            // Ingredient preview: representative stacks of the recipe layout.
            ItemStack[] layout = BankCraftingMatcher.ghostLayout(candidate.recipe(), List.of());
            ingredientViews = new java.util.ArrayList<>();
            for (ItemStack stack : layout) {
                if (stack == null || stack.isEmpty())
                    continue;
                ItemView view = new ItemView();
                view.setItemStack(stack);
                ingredientViews.add(view);
            }

            removeChilds();
            addChild(resultView);
            addChild(nameLabel);
            addChild(missingLabel);
            for (ItemView view : ingredientViews)
                addChild(view);
            setHeight(2 * ICON_SIZE + 4);
        }

        private static final String MISSING_LABEL_TEXT = TEXT.MISSING_INGREDIENTS.getString();

        @Override
        protected void layoutChanged() {
            int padding = 2;
            int width = getWidth();
            resultView.setBounds(padding, padding, ICON_SIZE, ICON_SIZE);
            nameLabel.setBounds(resultView.getRight() + padding, padding,
                    width - resultView.getRight() - padding * 2, ICON_SIZE);
            // Second row: ingredient icons left, missing marker right.
            int x = padding;
            int y = resultView.getBottom() + padding;
            for (ItemView view : ingredientViews) {
                view.setBounds(x, y, ICON_SIZE, ICON_SIZE);
                x += ICON_SIZE;
            }
            missingLabel.setBounds(x + padding, y, Math.max(0, width - x - padding * 2), ICON_SIZE);
        }
    }

    private final Label titleLabel;
    private final Label noRecipeLabel;
    private final ListView recipeListView;

    public CraftingRecipeSelectionScreen(Screen parent, ItemStack product, List<Candidate> candidates,
                                         Consumer<RecipeHolder<CraftingRecipe>> onSelected) {
        super(TEXT.TITLE, parent);

        titleLabel = new Label(TEXT.TITLE_LABEL.getString() + ": " + product.getHoverName().getString());
        titleLabel.setAlignment(GuiElement.Alignment.CENTER);
        addElement(titleLabel);

        noRecipeLabel = new Label(TEXT.NO_RECIPE.getString()
                .replace("{item_name}", product.getHoverName().getString()));
        noRecipeLabel.setAlignment(GuiElement.Alignment.CENTER);
        noRecipeLabel.setEnabled(candidates.isEmpty());
        addElement(noRecipeLabel);

        recipeListView = new VerticalListView();
        LayoutVertical layout = new LayoutVertical();
        layout.stretchX = true;
        recipeListView.setLayout(layout);
        recipeListView.setEnabled(!candidates.isEmpty());
        addElement(recipeListView);

        // NOTE: Screen.minecraft is only set in init() — use the singleton here.
        var clientLevel = net.minecraft.client.Minecraft.getInstance().level;
        for (Candidate candidate : candidates) {
            if (clientLevel == null)
                break;
            ItemStack result = candidate.recipe().value().getResultItem(clientLevel.registryAccess());
            RecipeButton row = new RecipeButton(candidate, result);
            row.setOnRisingEdge(() -> {
                onSelected.accept(candidate.recipe());
                this.onClose();
            });
            recipeListView.addChild(row);
        }
    }

    @Override
    protected void updateLayout(Gui gui) {
        int width = getWidth();
        int height = getHeight();
        int padding = 5;
        int spacing = 5;

        titleLabel.setBounds(width / 4, padding, width / 2, 20);
        noRecipeLabel.setBounds(width / 4, titleLabel.getBottom() + spacing, titleLabel.getWidth(), 20);
        recipeListView.setBounds(width / 4, titleLabel.getBottom() + spacing, titleLabel.getWidth(),
                height - titleLabel.getBottom() - padding - spacing);
    }
}
