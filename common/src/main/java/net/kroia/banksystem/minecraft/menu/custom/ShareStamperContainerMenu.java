package net.kroia.banksystem.minecraft.menu.custom;

import net.kroia.banksystem.minecraft.entity.custom.ShareStamperBlockEntity;
import net.kroia.banksystem.minecraft.item.custom.share.StampedShareItem;
import net.kroia.banksystem.minecraft.menu.BankSystemMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Task #47 (v2.1.0) — Share Stamper container menu. Two BE slots (input, output),
 * player inventory + hotbar, and a {@link ContainerData} carrying progress/mode/supply.
 */
public class ShareStamperContainerMenu extends AbstractContainerMenu {

    private final ShareStamperBlockEntity blockEntity;
    private final ContainerData data;

    /** Client constructor — no BE on client side is common for extended menus; use dummy. */
    public ShareStamperContainerMenu(int id, Inventory playerInv, FriendlyByteBuf buf) {
        this(id, playerInv,
                (ShareStamperBlockEntity) playerInv.player.level().getBlockEntity(buf.readBlockPos()));
    }

    public ShareStamperContainerMenu(int id, Inventory playerInv, ShareStamperBlockEntity be) {
        super(BankSystemMenus.SHARE_STAMPER_CONTAINER_MENU.get(), id);
        this.blockEntity = be;
        this.data = be != null ? be.data : new SimpleContainerData(10);

        // Two BE slots (0=input, 1=output). If BE is null (client with missing BE), use dummy.
        SimpleContainer dummy = new SimpleContainer(2);
        // Vertical stack: input on top, progress bar between, output below.
        addSlot(new Slot(be != null ? be : dummy, ShareStamperBlockEntity.SLOT_INPUT, 134, 18));
        addSlot(new Slot(be != null ? be : dummy, ShareStamperBlockEntity.SLOT_OUTPUT, 134, 50) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
        });

        // Player inventory (3x9) starting at y=84, hotbar at y=142.
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                addSlot(new Slot(playerInv, 9 + col + row * 9, 8 + col * 18, 84 + row * 18));
        for (int col = 0; col < 9; col++)
            addSlot(new Slot(playerInv, col, 8 + col * 18, 142));

        addDataSlots(this.data);
    }

    public ShareStamperBlockEntity getBlockEntity() { return blockEntity; }
    public BlockPos getBlockPos() { return blockEntity != null ? blockEntity.getBlockPos() : BlockPos.ZERO; }

    public int getStampProgress()   { return data.get(0); }
    public boolean isProcessing()   { return data.get(1) != 0; }
    public int getBoundCompanyId()  { return data.get(2); }
    public int getModeOrdinal()     { return data.get(3); }
    public boolean isAutoInput()    { return data.get(4) != 0; }
    public boolean isAutoOutput()   { return data.get(5) != 0; }
    public long getTotalIssued()    { return ((long)(data.get(6) & 0xFFFF)) | (((long)(data.get(7) & 0xFFFF)) << 16); }
    public long getMaxSupply()      { return ((long)(data.get(8) & 0xFFFF)) | (((long)(data.get(9) & 0xFFFF)) << 16); }

    @Override
    public boolean stillValid(Player player) {
        return blockEntity != null && blockEntity.stillValid(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // Two BE slots [0..1], then 27 inv, then 9 hotbar.
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack src = slot.getItem();
        ItemStack copy = src.copy();

        final int INV_START = 2, INV_END = 2 + 36; // exclusive
        if (index < INV_START) {
            if (!moveItemStackTo(src, INV_START, INV_END, true)) return ItemStack.EMPTY;
        } else {
            // From inventory into slot 0 (input). If REDEEM item, auto-flip mode.
            if (src.getItem() instanceof StampedShareItem
                    && blockEntity != null
                    && java.util.Objects.equals(
                            StampedShareItem.getCompanyId(src), blockEntity.getBoundCompanyId())) {
                blockEntity.setMode(ShareStamperBlockEntity.Mode.REDEEM);
            }
            if (!moveItemStackTo(src, 0, 1, false)) return ItemStack.EMPTY;
        }
        if (src.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
        return copy;
    }
}
