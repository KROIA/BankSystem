package net.kroia.banksystem.screen.uiElements;

import net.kroia.banksystem.minecraft.menu.custom.BankTerminalContainerMenu;
import net.kroia.banksystem.util.BankCraftingMatcher;
import net.kroia.modutilities.gui.GuiTexture;
import net.kroia.modutilities.gui.client.ContainerView;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Container view of the Bank Terminal screen with a 3x3 crafting panel rendered
 * above the standard container/player-inventory texture.
 * <p>
 * The crafting slots live in the same menu as the inventory slots (indices
 * {@link BankTerminalContainerMenu#CRAFT_GRID_SLOT_START}..72), so the inherited
 * {@link ContainerView} slot rendering and click handling cover them without any
 * extra input code — this element only draws the panel background (procedural,
 * matching the flat style of {@code inventory_hpc.png}) and the ghost icons.
 * <p>
 * <b>Ghost icons:</b> when "Use Bank Items" is active, the screen computes which
 * bank item would fill each empty grid slot (via {@link BankCraftingMatcher} on
 * the streamed client bank data) and pushes the per-slot preview stacks here via
 * {@link #setGhostStacks(ItemStack[])}. They render faded in empty slots and show
 * the item tooltip on hover.
 */
public class BankTerminalCraftingView extends ContainerView<BankTerminalContainerMenu> {

    private static final int CRAFT_H = BankTerminalContainerMenu.CRAFTING_AREA_HEIGHT;
    private static final int TEXTURE_HEIGHT = 166;

    // Colors sampled from the classic container-texture style.
    private static final int COLOR_PANEL = 0xFFC6C6C6;
    private static final int COLOR_BEVEL_LIGHT = 0xFFFFFFFF;
    private static final int COLOR_BEVEL_DARK = 0xFF555555;
    private static final int COLOR_SLOT_INNER = 0xFF8B8B8B;
    private static final int COLOR_SLOT_DARK = 0xFF373737;
    private static final int COLOR_ARROW = 0xFF8B8B8B;
    /** Translucent overlay that fades the ghost item into the slot background. */
    private static final int COLOR_GHOST_FADE = 0xAAC6C6C6;

    private final @Nullable ItemStack[] ghostStacks = new ItemStack[BankCraftingMatcher.GRID_SIZE];

    public BankTerminalCraftingView(BankTerminalContainerMenu menu, Inventory playerInventory, Component title, GuiTexture backgroundTexture) {
        super(menu, playerInventory, title, backgroundTexture);
        setSize(176, TEXTURE_HEIGHT + CRAFT_H);
    }

    /**
     * Sets the per-grid-slot ghost preview stacks (index = grid slot 0..8;
     * {@code null}/empty = no ghost). The array is copied.
     */
    public void setGhostStacks(@Nullable ItemStack[] stacks) {
        for (int i = 0; i < ghostStacks.length; i++)
            ghostStacks[i] = (stacks != null && i < stacks.length) ? stacks[i] : null;
    }

    /** Removes all ghost previews. */
    public void clearGhostStacks() {
        setGhostStacks(null);
    }

    @Override
    public void renderBackground() {
        // Crafting panel (procedural, matches the flat texture style).
        drawRect(0, 0, getWidth(), CRAFT_H, COLOR_PANEL);
        drawRect(0, 0, getWidth(), 1, COLOR_BEVEL_LIGHT);
        drawRect(0, 0, 1, CRAFT_H, COLOR_BEVEL_LIGHT);
        drawRect(getWidth() - 1, 0, 1, CRAFT_H, COLOR_BEVEL_DARK);

        // Slot cells: 3x3 grid + result.
        for (int row = 0; row < BankCraftingMatcher.GRID_HEIGHT; row++) {
            for (int col = 0; col < BankCraftingMatcher.GRID_WIDTH; col++) {
                drawSlotCell(BankTerminalContainerMenu.CRAFT_GRID_X + col * 18,
                        BankTerminalContainerMenu.CRAFT_GRID_Y + row * 18);
            }
        }
        drawSlotCell(BankTerminalContainerMenu.CRAFT_RESULT_X, BankTerminalContainerMenu.CRAFT_RESULT_Y);
        drawArrow();

        // Standard container texture below the crafting panel.
        drawTexture(background_texture.getResourceLocation(), 0, CRAFT_H, 0, 0,
                getWidth(), TEXTURE_HEIGHT, background_texture.getWidth(), background_texture.getHeight());
    }

    /** Draws one classic 18x18 slot cell whose 16x16 item area starts at (itemX, itemY). */
    private void drawSlotCell(int itemX, int itemY) {
        int x = itemX - 1;
        int y = itemY - 1;
        drawRect(x, y, 18, 18, COLOR_SLOT_INNER);
        drawRect(x, y, 18, 1, COLOR_SLOT_DARK);           // top
        drawRect(x, y, 1, 18, COLOR_SLOT_DARK);           // left
        drawRect(x, y + 17, 18, 1, COLOR_BEVEL_LIGHT);    // bottom
        drawRect(x + 17, y, 1, 18, COLOR_BEVEL_LIGHT);    // right
    }

    /** Simple arrow between the crafting grid and the result slot. */
    private void drawArrow() {
        int startX = BankTerminalContainerMenu.CRAFT_GRID_X + BankCraftingMatcher.GRID_WIDTH * 18 + 4;
        int endX = BankTerminalContainerMenu.CRAFT_RESULT_X - 5;
        int centerY = BankTerminalContainerMenu.CRAFT_RESULT_Y + 8;
        int shaftEnd = endX - 5;
        drawRect(startX, centerY - 2, Math.max(1, shaftEnd - startX), 4, COLOR_ARROW);
        // Arrow head (three shrinking columns).
        drawRect(shaftEnd, centerY - 5, 2, 10, COLOR_ARROW);
        drawRect(shaftEnd + 2, centerY - 3, 2, 6, COLOR_ARROW);
        drawRect(shaftEnd + 4, centerY - 1, 1, 2, COLOR_ARROW);
    }

    @Override
    public void render() {
        super.render();
        renderGhostStacks();
    }

    private void renderGhostStacks() {
        int mouseX = getMouseX();
        int mouseY = getMouseY();
        for (int i = 0; i < ghostStacks.length; i++) {
            ItemStack ghost = ghostStacks[i];
            if (ghost == null || ghost.isEmpty())
                continue;
            var slot = this.menu.slots.get(BankTerminalContainerMenu.CRAFT_GRID_SLOT_START + i);
            if (slot.hasItem())
                continue; // only preview in empty slots
            drawItem(ghost, slot.x, slot.y);
            // Fade overlay above the item (items render with a positive z offset).
            graphicsPushPose();
            graphicsTranslate(0.0F, 0.0F, 250.0F);
            drawRect(slot.x, slot.y, 16, 16, COLOR_GHOST_FADE);
            graphicsPopPose();
            if (this.menu.getCarried().isEmpty()
                    && mouseX >= slot.x - 1 && mouseX < slot.x + 17
                    && mouseY >= slot.y - 1 && mouseY < slot.y + 17) {
                drawTooltip(ghost, getMousePos());
            }
        }
    }

    @Override
    protected void layoutChanged() {
        super.layoutChanged();
        // The inherited label positions assume the texture starts at y=0 — shift
        // them below the crafting panel.
        this.titleLabelY = CRAFT_H + 6;
        this.inventoryLabelY = CRAFT_H + (int) (TEXTURE_HEIGHT * 0.433f);
    }
}
