package net.kroia.banksystem.util;

import net.kroia.banksystem.banking.ServerBankManager;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.File;

@Mod.EventBusSubscriber
public class ServerEvents {
    private static DataHandler DATA_HANDLER;

    @SubscribeEvent
    public static void onServerStart(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            DATA_HANDLER = new DataHandler();
            MinecraftServer server = serverLevel.getServer();
            ResourceKey<Level> levelKey = serverLevel.dimension();

            // Only load data for the main overworld level
            if (levelKey.equals(ServerLevel.OVERWORLD)) {
                File rootSaveFolder = server.getWorldPath(LevelResource.ROOT).toFile();



                // Load data from the root save folder
                DATA_HANDLER.setSaveFolder(rootSaveFolder);
                DATA_HANDLER.loadAll();
            }
        }
    }

    @SubscribeEvent
    public static void onServerStop(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            MinecraftServer server = serverLevel.getServer();
            ResourceKey<Level> levelKey = serverLevel.dimension();

            // Only save data for the main overworld level
            if (levelKey.equals(ServerLevel.OVERWORLD)) {

                // Save data to the root save folder
                DATA_HANDLER.saveAll();

                // Cleanup
                ServerBankManager.clear();
            }
        }
    }

    public static DataHandler getDataHandler() {
        return DATA_HANDLER;
    }

    @SubscribeEvent
    public static void onWorldSave(LevelEvent.Save event) {
        if (!event.getLevel().isClientSide() &&
                event.getLevel() instanceof ServerLevel serverLevel &&
                serverLevel.dimension().equals(ServerLevel.OVERWORLD)) {
            DATA_HANDLER.saveAll();
        }
    }

}
