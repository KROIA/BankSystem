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
 * extra input code — this element only blits the baked full-screen background
 * ({@code bank_terminal.png}: crafting panel + slot cells + arrow + inventory
 * strip) and draws the ghost icons.
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

    /** Translucent overlay that fades the ghost item into the slot background. */
    private static final int COLOR_GHOST_FADE = 0xAAC6C6C6;

    private final @Nullable ItemStack[] ghostStacks = new ItemStack[BankCraftingMatcher.GRID_SIZE];

    /**
     * @param backgroundTexture the full BankTerminal background atlas
     *                          ({@code textures/gui/bank_terminal.png}, 256x256;
     *                          usable area 176x(62+166) at the origin) — crafting
     *                          panel and inventory strip are baked in, not drawn
     *                          procedurally.
     */
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
        // Single blit of the full baked background (crafting panel with bevels,
        // 3x3 grid + result slot cells, arrow, and the inventory strip) from
        // the 256x256 bank_terminal.png atlas.
        drawTexture(background_texture.getResourceLocation(), 0, 0, 0, 0,
                getWidth(), TEXTURE_HEIGHT + CRAFT_H,
                background_texture.getWidth(), background_texture.getHeight());
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
