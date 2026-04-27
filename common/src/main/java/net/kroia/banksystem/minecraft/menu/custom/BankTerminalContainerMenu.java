package net.kroia.banksystem.minecraft.menu.custom;

import net.kroia.banksystem.minecraft.block.BankSystemBlocks;
import net.kroia.banksystem.minecraft.entity.custom.BankTerminalBlockEntity;
import net.kroia.banksystem.minecraft.menu.BankSystemMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;

public class BankTerminalContainerMenu extends AbstractBankContainerMenu {
    private final BankTerminalBlockEntity blockEntity;

    // Client Constructor
    public BankTerminalContainerMenu(int containerId, Inventory playerInv, FriendlyByteBuf additionalData) {
        this(   containerId,
                playerInv,
                (BankTerminalBlockEntity) playerInv.player.level().getBlockEntity(additionalData.readBlockPos()));
    }

    // Server Constructor
    public BankTerminalContainerMenu(int containerId, Inventory playerInv, BankTerminalBlockEntity blockEntity) {
        super(  BankSystemBlocks.BANK_TERMINAL_BLOCK.get(),
                BankSystemMenus.BANK_TERMINAL_CONTAINER_MENU.get(),
                blockEntity.getInventory(playerInv.player.getUUID()),
                containerId, playerInv, blockEntity);
        this.blockEntity = blockEntity;
    }

    public BankTerminalBlockEntity getBlockEntity() {
        return this.blockEntity;
    }
    @Override
    public BlockPos getBlockPos() {
        return this.blockEntity.getBlockPos();
    }
}
