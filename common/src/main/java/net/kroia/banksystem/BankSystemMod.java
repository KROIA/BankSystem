package net.kroia.banksystem;

import com.mojang.logging.LogUtils;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import net.kroia.banksystem.block.BankSystemBlocks;
import net.kroia.banksystem.command.BankSystemCommands;
import net.kroia.banksystem.entity.BankSystemEntities;
import net.kroia.banksystem.item.BankSystemCreativeModeTab;
import net.kroia.banksystem.item.BankSystemItems;
import net.kroia.banksystem.menu.BankSystemMenus;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.util.DataHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.File;

public final class BankSystemMod {
    public static final String MOD_ID = "banksystem";


    public static final Logger LOGGER = LogUtils.getLogger();

    public static void init() {
        BankSystemModSettings.init();
        CommandRegistrationEvent.EVENT.register((dispatcher, registryAccess, environment) -> {
            BankSystemCommands.register(dispatcher);
        });
        BankSystemBlocks.init();
        BankSystemItems.init();
        BankSystemEntities.init();
        BankSystemMenus.init();
        BankSystemCreativeModeTab.init();

        BankSystemNetworking.setupClientReceiverPackets();
        BankSystemNetworking.setupServerReceiverPackets();
    }

    public static void onClientSetup()
    {
        BankSystemMenus.setupScreens();

    }

    public static void onServerSetup()
    {
        //BankSystemNetworking.setupServerReceiverPackets();
    }

    public static void loadDataFromFiles(MinecraftServer server)
    {
        File rootSaveFolder = server.getWorldPath(LevelResource.ROOT).toFile();
        // Load data from the root save folder
        DataHandler.setSaveFolder(rootSaveFolder);
        DataHandler.loadAll();
    }
    public static void saveDataToFiles(MinecraftServer server)
    {
        File rootSaveFolder = server.getWorldPath(LevelResource.ROOT).toFile();
        // Load data from the root save folder
        DataHandler.setSaveFolder(rootSaveFolder);
        DataHandler.saveAll();
    }
    public static boolean isDataLoaded() {
        return DataHandler.isLoaded();
    }
}
