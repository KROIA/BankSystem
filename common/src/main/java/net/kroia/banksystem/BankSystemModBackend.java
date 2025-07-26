package net.kroia.banksystem;

import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.TickEvent;
import net.kroia.banksystem.api.BankSystemAPI;
import net.kroia.banksystem.api.IBankSystemEvents;
import net.kroia.banksystem.api.IServerBankManager;
import net.kroia.banksystem.banking.BankUser;
import net.kroia.banksystem.banking.ClientBankManager;
import net.kroia.banksystem.banking.ServerBankManager;
import net.kroia.banksystem.block.BankSystemBlocks;
import net.kroia.banksystem.command.BankSystemCommands;
import net.kroia.banksystem.entity.BankSystemEntities;
import net.kroia.banksystem.entity.custom.BankDownloadBlockEntity;
import net.kroia.banksystem.entity.custom.BankTerminalBlockEntity;
import net.kroia.banksystem.entity.custom.BankUploadBlockEntity;
import net.kroia.banksystem.item.BankSystemCreativeModeTab;
import net.kroia.banksystem.item.BankSystemItems;
import net.kroia.banksystem.menu.BankSystemMenus;
import net.kroia.banksystem.networking.BankSystemNetworkPacket;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.networking.request.BankSystemGenericRequest;
import net.kroia.banksystem.screen.custom.*;
import net.kroia.banksystem.screen.uiElements.ItemInfoWidget;
import net.kroia.banksystem.util.BankSystemDataHandler;
import net.kroia.banksystem.util.BankSystemEvents;
import net.kroia.banksystem.util.BankSystemLogger;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.util.ArrayList;

public class BankSystemModBackend implements BankSystemAPI {


    //private static final Logger LOGGER = LogUtils.getLogger();
    public static class Instances
    {
        public BankSystemModSettings SERVER_SETTINGS;
        public BankSystemDataHandler SERVER_DATA_HANDLER;
        public ServerBankManager SERVER_BANK_MANAGER;
        public BankSystemEvents SERVER_EVENTS;

        public ClientBankManager CLIENT_BANK_MANAGER;

        public BankSystemNetworking NETWORKING;

        public BankSystemLogger LOGGER;
    }

    private static Instances INSTANCES = new Instances();





    public static void init() {
        INSTANCES.LOGGER = new BankSystemLogger(INSTANCES);
        BankSystemDataHandler.setBackend(INSTANCES);
        BankTerminalBlockEntity.setBackend(INSTANCES);
        ServerBankManager.setBackend(INSTANCES);
        BankUser.setBackend(INSTANCES);
        BankSystemModSettings.setBackend(INSTANCES);
        //SyncBankDataPacket.setBackend(INSTANCES);
        //SyncItemInfoPacket.setBackend(INSTANCES);
        //RequestAllowNewBankItemIDPacket.setBackend(INSTANCES);
        //UpdateBankTerminalBlockEntityPacket.setBackend(INSTANCES);
        //RequestDisallowBankingItemIDPacket.setBackend(INSTANCES);
        BankSystemCommands.setBackend(INSTANCES);
        //BankSystemJeiPlugin.setBackend(INSTANCES);
        BankDownloadBlockEntity.setBackend(INSTANCES);
        BankUploadBlockEntity.setBackend(INSTANCES);


        //WithdrawMoneyPacket.setBackend(INSTANCES);
        //UpdateBankDownloadBlockEntityPacket.setBackend(INSTANCES);

        BankSystemNetworkPacket.setBackend(INSTANCES);
        BankSystemGenericRequest.setBackend(INSTANCES);
        BankSystemTextMessages.setBackend(INSTANCES);

        CommandRegistrationEvent.EVENT.register((dispatcher, registryAccess, environment) -> {
            BankSystemCommands.register(dispatcher);
        });



        BankSystemBlocks.init();
        BankSystemItems.init();
        BankSystemEntities.init();
        BankSystemMenus.init();
        BankSystemCreativeModeTab.init();
        BankSystemTextMessages.init();


        INSTANCES.NETWORKING = new BankSystemNetworking();
        //BankSystemNetworking.setupClientReceiverPackets();
        //BankSystemNetworking.setupServerReceiverPackets();


        BankSystemModSettings.setLogger((msg)->{INSTANCES.LOGGER.error(msg);}, (msg)->{INSTANCES.LOGGER.info(msg);});
    }

    // Called from the client side
    public static void onClientSetup()
    {
        BankSystemMenus.setupScreens();
        INSTANCES.CLIENT_BANK_MANAGER = new ClientBankManager(INSTANCES);

        BankTerminalScreen.setBackend(INSTANCES);
        BankAccountManagementScreen.setBackend(INSTANCES);
        BankSystemSettingScreen.setBackend(INSTANCES);
        ATMScreen.setBackend(INSTANCES);
        BankDownloadScreen.setBackend(INSTANCES);
        ItemInfoWidget.setBackend(INSTANCES);
    }

    // Called from the server side
    public static void onServerSetup()
    {
        if(INSTANCES.SERVER_EVENTS == null)
            INSTANCES.SERVER_EVENTS = new BankSystemEvents();
    }

    // Called from the server side
    public static void onServerStart(MinecraftServer server) {
        INSTANCES.SERVER_SETTINGS = new BankSystemModSettings();
        INSTANCES.SERVER_DATA_HANDLER = new BankSystemDataHandler();
        INSTANCES.SERVER_BANK_MANAGER = new ServerBankManager();



        loadDataFromFiles(server);
        TickEvent.SERVER_POST.register(BankSystemModBackend::onServerTick);
    }

    // Called from the server side
    public static void onServerStop(MinecraftServer server) {
        TickEvent.SERVER_POST.unregister(BankSystemModBackend::onServerTick);
        saveDataToFiles(server);
        INSTANCES.SERVER_SETTINGS = null;
        INSTANCES.SERVER_DATA_HANDLER = null;
        INSTANCES.SERVER_BANK_MANAGER = null;
        INSTANCES.SERVER_EVENTS.clearListeners();
    }

    // Called from the server side
    public static void onPlayerJoin(ServerPlayer player)
    {
        INSTANCES.SERVER_BANK_MANAGER.createUser(
                player,
                new ArrayList<>(),
                true,
                INSTANCES.SERVER_SETTINGS.PLAYER.STARTING_BALANCE.get()
        );
    }

    // Called from the server side
    public static void onPlayerLeave(ServerPlayer player)
    {

    }

    // Called from the server side
    private static void onServerTick(MinecraftServer server)
    {
        INSTANCES.SERVER_DATA_HANDLER.tickUpdate();
    }

    public static void loadDataFromFiles(MinecraftServer server)
    {
        File rootSaveFolder = server.getWorldPath(LevelResource.ROOT).toFile();
        // Load data from the root save folder
        INSTANCES.SERVER_DATA_HANDLER.setSaveFolder(rootSaveFolder);
        INSTANCES.SERVER_DATA_HANDLER.loadAll();
    }
    public static void saveDataToFiles(MinecraftServer server)
    {
        File rootSaveFolder = server.getWorldPath(LevelResource.ROOT).toFile();
        // Load data from the root save folder
        INSTANCES.SERVER_DATA_HANDLER.setSaveFolder(rootSaveFolder);
        INSTANCES.SERVER_DATA_HANDLER.saveAll();
    }
    public static boolean isDataLoaded() {
        return INSTANCES.SERVER_DATA_HANDLER.isLoaded();
    }

    /*public static void logInfo(String message) {
        boolean enabled = true;
        if(INSTANCES.SERVER_SETTINGS != null)
            enabled = INSTANCES.SERVER_SETTINGS.UTILITIES.LOGGING_ENABLE_INFO.get();
        if(enabled)
            LOGGER.info(message);
    }
    public static void logError(String message) {
        boolean enabled = true;
        if(INSTANCES.SERVER_SETTINGS != null)
            enabled = INSTANCES.SERVER_SETTINGS.UTILITIES.LOGGING_ENABLE_ERROR.get();
        if(enabled)
            LOGGER.error(message);
    }
    public static void logWarning(String message) {
        boolean enabled = true;
        if(INSTANCES.SERVER_SETTINGS != null)
            enabled = INSTANCES.SERVER_SETTINGS.UTILITIES.LOGGING_ENABLE_WARNING.get();
        if(enabled)
            LOGGER.warn(message);
    }
    public static void logDebug(String message) {
        boolean enabled = true;
        if(INSTANCES.SERVER_SETTINGS != null)
            enabled = INSTANCES.SERVER_SETTINGS.UTILITIES.LOGGING_ENABLE_DEBUG.get();
        if(enabled)
            LOGGER.debug(message);
    }*/


    @Override
    public String getModID()
    {
        return BankSystemMod.MOD_ID;
    }
    @Override
    public IBankSystemEvents getEvents() {
        if(INSTANCES.SERVER_EVENTS == null)
        {
            INSTANCES.SERVER_EVENTS = new BankSystemEvents();
        }
        return INSTANCES.SERVER_EVENTS;
    }

    @Override
    public IServerBankManager getServerBankManager()
    {
        return INSTANCES.SERVER_BANK_MANAGER;
    }

    @Override
    public ClientBankManager getClientBankManager()
    {
        return INSTANCES.CLIENT_BANK_MANAGER;
    }
}
