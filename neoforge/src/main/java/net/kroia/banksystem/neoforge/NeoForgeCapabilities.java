package net.kroia.banksystem.neoforge;

// External item-transport mods (e.g. PrettyPipes) use the NeoForge ItemHandler capability
// rather than the vanilla Container interface, so the upload/download BEs must register
// an InvWrapper here to be connectable.

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.minecraft.entity.BankSystemEntities;
import net.kroia.banksystem.minecraft.entity.custom.BankDownloadBlockEntity;
import net.kroia.banksystem.minecraft.entity.custom.BankUploadBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
//import net.neoforged.neoforge.items.wrapper.InvWrapper; // mc<=1.21.8

//@EventBusSubscriber(modid = BankSystemMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD) // mc<=1.21.5
@EventBusSubscriber(modid = BankSystemMod.MOD_ID) // mc>=1.21.6
public class NeoForgeCapabilities {

    @SubscribeEvent
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                //Capabilities.ItemHandler.BLOCK, // mc<=1.21.8
                Capabilities.Item.BLOCK, // mc>=1.21.9 (transfer API)
                (BlockEntityType<BankUploadBlockEntity>) (BlockEntityType<?>) BankSystemEntities.BANK_UPLOAD_BLOCK_ENTITY.get(),
                //(be, side) -> new InvWrapper(be) // mc<=1.21.8
                (be, side) -> net.neoforged.neoforge.transfer.item.VanillaContainerWrapper.of(be) // mc>=1.21.9
        );
        event.registerBlockEntity(
                //Capabilities.ItemHandler.BLOCK, // mc<=1.21.8
                Capabilities.Item.BLOCK, // mc>=1.21.9 (transfer API)
                (BlockEntityType<BankDownloadBlockEntity>) (BlockEntityType<?>) BankSystemEntities.BANK_DOWNLOAD_BLOCK_ENTITY.get(),
                //(be, side) -> new InvWrapper(be) // mc<=1.21.8
                (be, side) -> net.neoforged.neoforge.transfer.item.VanillaContainerWrapper.of(be) // mc>=1.21.9
        );
    }
}
