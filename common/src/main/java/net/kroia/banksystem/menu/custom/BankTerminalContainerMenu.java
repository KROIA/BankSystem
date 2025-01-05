package net.kroia.banksystem.menu.custom;

import net.kroia.banksystem.block.BankSystemBlocks;
import net.kroia.banksystem.entity.custom.BankTerminalBlockEntity;
import net.kroia.banksystem.menu.BankSystemMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
public class BankTerminalContainerMenu extends AbstractContainerMenu {
    private final BankTerminalBlockEntity blockEntity;
    private final ContainerLevelAccess levelAccess;

    public static final int POS_X = 0;
    public static final int POS_Y = 0;



    // Client Constructor
    public BankTerminalContainerMenu(int containerId, Inventory playerInv, FriendlyByteBuf additionalData) {
        this(containerId, playerInv, playerInv.player.level.getBlockEntity(additionalData.readBlockPos()));
    }

    // Server Constructor
    public BankTerminalContainerMenu(int containerId, Inventory playerInv, BlockEntity blockEntity) {
        super(BankSystemMenus.BANK_TERMINAL_CONTAINER_MENU.get(), containerId);
        if(blockEntity instanceof BankTerminalBlockEntity be) {
            this.blockEntity = be;
        } else {
            throw new IllegalStateException("Incorrect block entity class (%s) passed into ExampleMenu!"
                    .formatted(blockEntity.getClass().getCanonicalName()));
        }

        this.levelAccess = ContainerLevelAccess.create(blockEntity.getLevel(), blockEntity.getBlockPos());

        createPlayerHotbar(playerInv);
        createPlayerInventory(playerInv);
        createBlockEntityInventory(be.getInventory(playerInv.player.getUUID()));
    }




    private void createBlockEntityInventory(BankTerminalBlockEntity.TerminalInventory inventory) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                Slot slot = new Slot(inventory, column + (row * 9), 8 + (column * 18)+POS_X, 18 + (row * 18)+POS_Y);
                addSlot(slot);
            }
        }
    }

    private void createPlayerInventory(Inventory playerInv) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(playerInv,
                        9 + column + (row * 9),
                        8 + (column * 18)+POS_X,
                        84 + (row * 18)+POS_Y));
            }
        }
    }

    private void createPlayerHotbar(Inventory playerInv) {
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(playerInv,
                    column,
                    8 + (column * 18)+POS_X,
                    142+POS_Y));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player pPlayer, int pIndex) {
        Slot fromSlot = getSlot(pIndex);
        ItemStack fromStack = fromSlot.getItem();

        if(fromStack.getCount() <= 0)
            fromSlot.set(ItemStack.EMPTY);

        if(!fromSlot.hasItem())
            return ItemStack.EMPTY;

        ItemStack copyFromStack = fromStack.copy();

        if(pIndex < 36) {
            // We are inside of the player's inventory
            if(!moveItemStackTo(fromStack, 36, 63, false))
                return ItemStack.EMPTY;
        } else if (pIndex < 63) {
            // We are inside of the block entity inventory
            if(!moveItemStackTo(fromStack, 0, 36, false))
                return ItemStack.EMPTY;
        } else {
            System.err.println("Invalid slot index: " + pIndex);
            return ItemStack.EMPTY;
        }

        fromSlot.setChanged();
        fromSlot.onTake(pPlayer, fromStack);

        return copyFromStack;
    }

    @Override
    public boolean stillValid(Player pPlayer) {
        return stillValid(this.levelAccess, pPlayer, BankSystemBlocks.BANK_TERMINAL_BLOCK.get());
    }

    public BankTerminalBlockEntity getBlockEntity() {
        return this.blockEntity;
    }

    public BlockPos getBlockPos() {
        return this.blockEntity.getBlockPos();
    }
}
