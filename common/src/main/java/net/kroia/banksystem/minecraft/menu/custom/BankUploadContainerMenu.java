package net.kroia.banksystem.minecraft.menu.custom;

import net.kroia.banksystem.minecraft.block.BankSystemBlocks;
import net.kroia.banksystem.minecraft.entity.custom.BankUploadBlockEntity;
import net.kroia.banksystem.minecraft.menu.BankSystemMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;

public class BankUploadContainerMenu extends AbstractBankContainerMenu {
    private final BankUploadBlockEntity blockEntity;

    // Client Constructor
    public BankUploadContainerMenu(int containerId, Inventory playerInv, FriendlyByteBuf additionalData) {
        this(containerId, playerInv, (BankUploadBlockEntity)playerInv.player.level().getBlockEntity(additionalData.readBlockPos()));
    }

    // Server Constructor
    public BankUploadContainerMenu(int containerId, Inventory playerInv, BankUploadBlockEntity blockEntity) {
        super(  BankSystemBlocks.BANK_UPLOAD_BLOCK.get(),
                BankSystemMenus.BANK_UPLOAD_CONTAINER_MENU.get(),
                blockEntity.getInventory(),
                containerId, playerInv, blockEntity);
        this.blockEntity = blockEntity;
    }

    public BankUploadBlockEntity getBlockEntity() {
        return this.blockEntity;
    }

    @Override
    public BlockPos getBlockPos() {
        return this.blockEntity.getBlockPos();
    }
}
