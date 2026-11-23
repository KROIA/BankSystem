package net.kroia.banksystem.fabric;

// External item-transport mods on Fabric use the Fabric Transfer API rather than the vanilla
// Container interface, so the upload/download BEs must register an InventoryStorage view here
// to be connectable.

import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage; // mc<=1.21.11
//import net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage; // mc>=26.1
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.kroia.banksystem.minecraft.entity.BankSystemEntities;
import net.kroia.banksystem.minecraft.entity.custom.BankDownloadBlockEntity;
import net.kroia.banksystem.minecraft.entity.custom.BankUploadBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

public class FabricItemStorageProviders {

    public static void register() {
        ItemStorage.SIDED.registerForBlockEntity(
                (be, direction) -> InventoryStorage.of(be, direction), // mc<=1.21.11
                //(be, direction) -> ContainerStorage.of(be, direction), // mc>=26.1
                (BlockEntityType<BankUploadBlockEntity>) (BlockEntityType<?>) BankSystemEntities.BANK_UPLOAD_BLOCK_ENTITY.get()
        );
        ItemStorage.SIDED.registerForBlockEntity(
                (be, direction) -> InventoryStorage.of(be, direction), // mc<=1.21.11
                //(be, direction) -> ContainerStorage.of(be, direction), // mc>=26.1
                (BlockEntityType<BankDownloadBlockEntity>) (BlockEntityType<?>) BankSystemEntities.BANK_DOWNLOAD_BLOCK_ENTITY.get()
        );
    }
}
