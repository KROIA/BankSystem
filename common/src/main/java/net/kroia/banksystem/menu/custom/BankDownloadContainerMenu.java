package net.kroia.banksystem.menu.custom;

import net.kroia.banksystem.block.BankSystemBlocks;
import net.kroia.banksystem.entity.custom.BankDownloadBlockEntity;
import net.kroia.banksystem.menu.BankSystemMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;

public class BankDownloadContainerMenu extends AbstractBankContainerMenu {
    private final BankDownloadBlockEntity blockEntity;

    // Client Constructor
    public BankDownloadContainerMenu(int containerId, Inventory playerInv, FriendlyByteBuf additionalData) {
        this(   containerId,
                playerInv,
                (BankDownloadBlockEntity)playerInv.player.level().getBlockEntity(additionalData.readBlockPos()));
    }

    // Server Constructor
    public BankDownloadContainerMenu(int containerId, Inventory playerInv, BankDownloadBlockEntity blockEntity) {
        super(  BankSystemBlocks.BANK_DOWNLOAD_BLOCK.get(),
                BankSystemMenus.BANK_DOWNLOAD_CONTAINER_MENU.get(),
                blockEntity.getInventory(),
                containerId, playerInv, blockEntity);
        this.blockEntity = blockEntity;
    }

    public BankDownloadBlockEntity getBlockEntity() {
        return this.blockEntity;
    }
    @Override
    public BlockPos getBlockPos() {
        return this.blockEntity.getBlockPos();
    }
}
