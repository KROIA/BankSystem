package net.kroia.banksystem.menu.custom;

import net.kroia.banksystem.block.BankSystemBlocks;
//import net.kroia.banksystem.entity.custom.ATMBlockEntity;
import net.kroia.banksystem.menu.BankSystemMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
/*
public class ATMContainerMenu extends AbstractBankContainerMenu{

    private final ATMBlockEntity blockEntity;

    // Client Constructor
    public ATMContainerMenu(int containerId, Inventory playerInv, FriendlyByteBuf additionalData) {
        this(   containerId,
                playerInv,
                (ATMBlockEntity)playerInv.player.level().getBlockEntity(additionalData.readBlockPos()));
    }

    // Server Constructor
    public ATMContainerMenu(int containerId, Inventory playerInv, ATMBlockEntity blockEntity) {
        super(  BankSystemBlocks.BANK_DOWNLOAD_BLOCK.get(),
                BankSystemMenus.BANK_DOWNLOAD_CONTAINER_MENU.get(),
                null,
                containerId, playerInv, blockEntity);
        this.blockEntity = blockEntity;
    }

    public ATMBlockEntity getBlockEntity() {
        return this.blockEntity;
    }
    @Override
    public BlockPos getBlockPos() {
        return this.blockEntity.getBlockPos();
    }
}
*/
