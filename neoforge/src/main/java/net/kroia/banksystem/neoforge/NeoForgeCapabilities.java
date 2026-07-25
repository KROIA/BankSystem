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
import net.neoforged.neoforge.items.wrapper.InvWrapper;

@EventBusSubscriber(modid = BankSystemMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public class NeoForgeCapabilities {

    @SubscribeEvent
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                (BlockEntityType<BankUploadBlockEntity>) (BlockEntityType<?>) BankSystemEntities.BANK_UPLOAD_BLOCK_ENTITY.get(),
                (be, side) -> new InvWrapper(be)
        );
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                (BlockEntityType<BankDownloadBlockEntity>) (BlockEntityType<?>) BankSystemEntities.BANK_DOWNLOAD_BLOCK_ENTITY.get(),
                (be, side) -> new InvWrapper(be)
        );
    }
}
