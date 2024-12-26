package net.kroia.banksystem.gui.elements;

import com.mojang.blaze3d.vertex.PoseStack;
import net.kroia.banksystem.gui.elements.base.GuiElement;
import net.kroia.banksystem.gui.geometry.Rectangle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public class InventoryView extends GuiElement {
    public static final int SLOT_SIZE = 18;
    public static final int SLOT_SPACING = 1;
    public static final int SLOT_PADDING = 1;
    public static final int SLOT_ROWS = 4;
    public static final int SLOT_COLUMNS = 9;

    private final Inventory inventory;
    private ItemStack dragingStack = ItemStack.EMPTY;
    public InventoryView(int x, int y, Inventory inventory) {
        super(x, y, 0, 0);
        this.inventory = inventory;
    }

    @Override
    protected void render() {
        for (int row = 0; row < SLOT_ROWS; row++) {
            for (int column = 0; column < SLOT_COLUMNS; column++) {
                int slotIndex = column + (row * SLOT_COLUMNS);
                int slotX = SLOT_PADDING + (column * (SLOT_SIZE + SLOT_SPACING));
                int slotY = SLOT_PADDING + (row * (SLOT_SIZE + SLOT_SPACING));
                renderSlot(slotX, slotY, slotIndex);
            }
        }

        if(!dragingStack.isEmpty())
        {
            // Render dragging item
            drawItemWithDecoration(dragingStack, getMouseX()-8, getMouseY()-8, 210,0);
        }
    }

    @Override
    protected void layoutChanged() {

    }

    protected void renderSlot(int x, int y, int slotIndex) {
        // Render slot
        ItemStack stack = inventory.getItem(slotIndex);
        // Render slot background
        if(isMouseOverSlot(x, y))
        {
            // Render hover effect
            drawRect(x, y, SLOT_SIZE, SLOT_SIZE, 0x80FFFFFF);
        }

        // Render item stack
        if (!stack.isEmpty()) {
            drawItemWithDecoration(stack, x+1, y+1);
        }
    }
    protected boolean isMouseOverSlot(int x, int y)
    {
        return new Rectangle(x, y, SLOT_SIZE, SLOT_SIZE).contains(getMouseX(), getMouseY());
    }

    @Override
    protected boolean mouseClickedOverElement(int button) {
        // Left click
        if(button == 0)
        {
            int slotIndex = getMouseSlotIndex();
            if(slotIndex != -1) {
                if (dragingStack.isEmpty()) {
                    dragingStack = inventory.getItem(slotIndex);
                    inventory.setItem(slotIndex, ItemStack.EMPTY);
                } else {
                    ItemStack stack = inventory.getItem(slotIndex);
                    // Try to merge stacks
                    if(stack.isEmpty())
                    {
                        inventory.setItem(slotIndex, dragingStack);
                        dragingStack = ItemStack.EMPTY;
                    }
                    else if (stack.getItem().getDescriptionId().compareTo(dragingStack.getItem().getDescriptionId()) == 0) {
                        int max = stack.getMaxStackSize();
                        if (stack.getCount() + dragingStack.getCount() <= max) {
                            stack.grow(dragingStack.getCount());
                            inventory.setItem(slotIndex, stack);
                            dragingStack = ItemStack.EMPTY;
                        } else {
                            int diff = max - stack.getCount();
                            stack.grow(diff);
                            dragingStack.shrink(diff);
                        }
                    } else {
                        // Swap stacks
                        inventory.setItem(slotIndex, dragingStack);
                        dragingStack = stack;
                    }
                }
            }
        }
        return false;
    }

    private int getMouseSlotIndex()
    {
        for (int row = 0; row < SLOT_ROWS; row++) {
            for (int column = 0; column < SLOT_COLUMNS; column++) {
                int slotIndex = column + (row * SLOT_COLUMNS);
                int slotX = SLOT_PADDING + (column * (SLOT_SIZE + SLOT_SPACING));
                int slotY = SLOT_PADDING + (row * (SLOT_SIZE + SLOT_SPACING));
                if (isMouseOverSlot(slotX, slotY)) {
                    return slotIndex;
                }
            }
        }
        return -1;
    }
}
