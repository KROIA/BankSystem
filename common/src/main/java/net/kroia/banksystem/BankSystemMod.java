package net.kroia.banksystem;

import com.mojang.logging.LogUtils;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import net.kroia.banksystem.banking.ClientBankManager;
import net.kroia.banksystem.banking.ServerBankManager;
import net.kroia.banksystem.block.BankSystemBlocks;
import net.kroia.banksystem.command.BankSystemCommands;
import net.kroia.banksystem.entity.BankSystemEntities;
import net.kroia.banksystem.item.BankSystemCreativeModeTab;
import net.kroia.banksystem.item.BankSystemItems;
import net.kroia.banksystem.menu.BankSystemMenus;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.util.BankSystemDataHandler;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.File;
import java.util.ArrayList;

public final class BankSystemMod {
    public static final String MOD_ID = "banksystem";


    public static final Logger LOGGER = LogUtils.getLogger();

    public static BankSystemModSettings SERVER_SETTINGS;
    public static BankSystemDataHandler SERVER_DATA_HANDLER;
    public static ServerBankManager SERVER_BANK_MANAGER;


    public static ClientBankManager CLIENT_BANK_MANAGER;

    public static void init() {
        CommandRegistrationEvent.EVENT.register((dispatcher, registryAccess, environment) -> {
            BankSystemCommands.register(dispatcher);
        });
        BankSystemBlocks.init();
        BankSystemItems.init();
        BankSystemEntities.init();
        BankSystemMenus.init();
        BankSystemCreativeModeTab.init();
        BankSystemTextMessages.init();

        BankSystemNetworking.setupClientReceiverPackets();
        BankSystemNetworking.setupServerReceiverPackets();


        BankSystemModSettings.setLogger(BankSystemMod::logError, BankSystemMod::logInfo);
    }

    // Called from the client side
    public static void onClientSetup()
    {
        BankSystemMenus.setupScreens();
        CLIENT_BANK_MANAGER = new ClientBankManager();
    }

    // Called from the server side
    public static void onServerSetup()
    {

    }

    // Called from the server side
    public static void onServerStart(MinecraftServer server) {
        SERVER_SETTINGS = new BankSystemModSettings();
        SERVER_DATA_HANDLER = new BankSystemDataHandler();
        SERVER_BANK_MANAGER = new ServerBankManager();


        loadDataFromFiles(server);
    }

    // Called from the server side
    public static void onServerStop(MinecraftServer server) {
        saveDataToFiles(server);
        SERVER_SETTINGS = null;
        SERVER_DATA_HANDLER = null;
        SERVER_BANK_MANAGER = null;
    }

    // Called from the server side
    public static void onPlayerJoin(ServerPlayer player)
    {
        SERVER_BANK_MANAGER.createUser(
                player,
                new ArrayList<>(),
                true,
                BankSystemMod.SERVER_SETTINGS.PLAYER.STARTING_BALANCE.get()
        );
    }

    // Called from the server side
    public static void onPlayerLeave(ServerPlayer player)
    {

    }

    public static void loadDataFromFiles(MinecraftServer server)
    {
        File rootSaveFolder = server.getWorldPath(LevelResource.ROOT).toFile();
        // Load data from the root save folder
        SERVER_DATA_HANDLER.setSaveFolder(rootSaveFolder);
        SERVER_DATA_HANDLER.loadAll();
    }
    public static void saveDataToFiles(MinecraftServer server)
    {
        File rootSaveFolder = server.getWorldPath(LevelResource.ROOT).toFile();
        // Load data from the root save folder
        SERVER_DATA_HANDLER.setSaveFolder(rootSaveFolder);
        SERVER_DATA_HANDLER.saveAll();
    }
    public static boolean isDataLoaded() {
        return SERVER_DATA_HANDLER.isLoaded();
    }

    public static void logInfo(String message) {
        if(SERVER_SETTINGS.UTILITIES.LOGGING_ENABLE_INFO.get())
            LOGGER.info(message);
    }
    public static void logError(String message) {
        if(SERVER_SETTINGS.UTILITIES.LOGGING_ENABLE_ERROR.get())
            LOGGER.error(message);
    }
    public static void logWarning(String message) {
        if(SERVER_SETTINGS.UTILITIES.LOGGING_ENABLE_WARNING.get())
            LOGGER.warn(message);
    }
    public static void logDebug(String message) {
        if(SERVER_SETTINGS.UTILITIES.LOGGING_ENABLE_DEBUG.get())
            LOGGER.debug(message);
    }

}
