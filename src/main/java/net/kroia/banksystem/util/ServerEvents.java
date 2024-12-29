package net.kroia.banksystem.util;

import net.kroia.banksystem.BankSystemMod;
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


    @SubscribeEvent
    public static void onServerStart(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            MinecraftServer server = serverLevel.getServer();
            ResourceKey<Level> levelKey = serverLevel.dimension();

            // Only load data for the main overworld level
            if (levelKey.equals(ServerLevel.OVERWORLD)) {
                BankSystemMod.loadDataFromFiles(server);
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
                BankSystemMod.saveDataToFiles(server);

                // Cleanup
                ServerBankManager.clear();
            }
        }
    }

    @SubscribeEvent
    public static void onWorldSave(LevelEvent.Save event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            MinecraftServer server = serverLevel.getServer();
            BankSystemMod.saveDataToFiles(server);
        }
    }

}
