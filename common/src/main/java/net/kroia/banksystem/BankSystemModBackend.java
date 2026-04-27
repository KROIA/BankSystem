package net.kroia.banksystem;

import dev.architectury.event.events.client.ClientPlayerEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.TickEvent;
import net.kroia.banksystem.api.BankSystemAPI;
import net.kroia.banksystem.api.IBankSystemDataHandler;
import net.kroia.banksystem.api.IBankSystemEvents;
import net.kroia.banksystem.api.bankmanager.IAsyncBankManager;
import net.kroia.banksystem.api.bankmanager.IBankManager;
import net.kroia.banksystem.api.bankmanager.IClientBankManager;
import net.kroia.banksystem.api.command.IBankSystemCommands;
import net.kroia.banksystem.banking.bankmanager.BankManager;
import net.kroia.banksystem.banking.bankmanager.ClientBankManager;
import net.kroia.banksystem.minecraft.block.BankSystemBlocks;
import net.kroia.banksystem.minecraft.command.BankSystemCommands;
import net.kroia.banksystem.minecraft.compat.NEZNAMY_TAB_Placeholders;
import net.kroia.banksystem.minecraft.compat.OldBankDataLoader;
import net.kroia.banksystem.minecraft.entity.BankSystemEntities;
import net.kroia.banksystem.minecraft.entity.custom.BankDownloadBlockEntity;
import net.kroia.banksystem.minecraft.entity.custom.BankTerminalBlockEntity;
import net.kroia.banksystem.minecraft.entity.custom.BankUploadBlockEntity;
import net.kroia.banksystem.minecraft.item.BankSystemCreativeModeTab;
import net.kroia.banksystem.minecraft.item.BankSystemItems;
import net.kroia.banksystem.minecraft.item.custom.software.Software;
import net.kroia.banksystem.minecraft.menu.BankSystemMenus;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.networking.general.SyncItemIDsPacket;
import net.kroia.banksystem.networking.multi_server.BanksystemMetadataRequest;
import net.kroia.banksystem.util.*;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.kroia.modutilities.networking.multi_server.MultiServerConfig;
import net.kroia.modutilities.networking.multi_server.MultiServerManager;
import net.kroia.modutilities.networking.multi_server.slave.SlaveServerClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;

public class BankSystemModBackend implements BankSystemAPI {
    public static class Instances
    {
        public boolean isSlaveServer = false;
        public BankSystemModSettings SERVER_SETTINGS;
        //public BankSystemConfig CONFIG;
        public BankSystemDataHandler SERVER_DATA_HANDLER;
        public IBankManager SERVER_BANK_MANAGER;
        public IClientBankManager CLIENT_BANK_MANAGER;
        public BankSystemEvents SERVER_EVENTS;
        public BankSystemNetworking NETWORKING;
        public ItemIDManager ITEM_ID_MANAGER;
        public IBankSystemCommands COMMAND_HANDLER;

        public BankSystemLogger LOGGER;
    }

    private static Instances INSTANCES = new Instances();


    BankSystemModBackend()
    {
        INSTANCES.LOGGER = new BankSystemLogger(INSTANCES);
        INSTANCES.SERVER_SETTINGS = null;
        INSTANCES.SERVER_DATA_HANDLER = null;
        INSTANCES.SERVER_BANK_MANAGER = null;
        INSTANCES.CLIENT_BANK_MANAGER = null;
        INSTANCES.COMMAND_HANDLER = null;
        INSTANCES.SERVER_EVENTS = null;
        INSTANCES.NETWORKING = null;
        INSTANCES.ITEM_ID_MANAGER = new ItemIDManager();
        MultiServerUtils.setBackend(INSTANCES);
        BankSystemDataHandler.setBackend(INSTANCES);
        BankTerminalBlockEntity.setBackend(INSTANCES);

        BankManager.setBackend(INSTANCES);

        BankSystemModSettings.setBackend(INSTANCES);
        //BankSystemConfig.setBackend(INSTANCES);
        BankSystemCommands.setBackend(INSTANCES);
        BankDownloadBlockEntity.setBackend(INSTANCES);
        BankUploadBlockEntity.setBackend(INSTANCES);
        Software.setBackend(INSTANCES);
        ItemID.setBackend(INSTANCES);

        BankSystemNetworking.setBackend(INSTANCES);
        BankSystemTextMessages.setBackend(INSTANCES);


        BankSystemCommands.registerCommands();


        BankSystemBlocks.init();
        BankSystemItems.init();
        BankSystemEntities.init();
        BankSystemMenus.init();
        BankSystemCreativeModeTab.init();
        BankSystemTextMessages.init();


        INSTANCES.NETWORKING = new BankSystemNetworking();
        INSTANCES.SERVER_EVENTS = new BankSystemEvents();


    }

    // Called from the client side
    public static void onClientSetup()
    {
        BankSystemMenus.setupScreens();
        ClientBankManager.setBackend(INSTANCES);
        INSTANCES.CLIENT_BANK_MANAGER = BankManager.createClient();

        BankSystemGuiScreen.setBackend(INSTANCES);
        BankSystemGuiContainerScreen.setBackend(INSTANCES);
        BankSystemGuiElement.setBackend(INSTANCES);
        BankSystemEntities.registerRenderers();

        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register(BankSystemModBackend::onPlayerLeaveClientSide);
        ClientPlayerEvent.CLIENT_PLAYER_JOIN.register(BankSystemModBackend::onPlayerJoinClientSide);
    }

    // Called from the server side
    public static void onServerSetup()
    {


    }

    // Called from the server side
    public static void onServerStart(MinecraftServer server) {

        NEZNAMY_TAB_Placeholders.setBackend(INSTANCES);
        OldBankDataLoader.setBackend(INSTANCES);


        INSTANCES.SERVER_SETTINGS = new BankSystemModSettings();
        INSTANCES.SERVER_SETTINGS.setLogger(INSTANCES.LOGGER::error, INSTANCES.LOGGER::error, INSTANCES.LOGGER::debug);

        //Path configPath = server.getWorldPath(LevelResource.ROOT).resolve("Finance/BankSystem/BankSystemConfig.json");
        //INSTANCES.CONFIG = BankSystemConfig.create(configPath);
        INSTANCES.SERVER_DATA_HANDLER = new BankSystemDataHandler();

        ItemIDManager.clear();
        MultiServerConfig config = MultiServerConfig.get();
        INSTANCES.ITEM_ID_MANAGER.createDefaultItemIDs(server);
        INSTANCES.isSlaveServer = false;
        if(config.enable)
        {
            setupMultiServerInfrastructure(config, server);
        }
        if(INSTANCES.isSlaveServer)
        {
            INSTANCES.SERVER_BANK_MANAGER = BankManager.createSlave();
            INSTANCES.COMMAND_HANDLER = BankSystemCommands.createSlave();
        }
        else
        {

            INSTANCES.SERVER_BANK_MANAGER = BankManager.createMaster();
            INSTANCES.COMMAND_HANDLER = BankSystemCommands.createMaster();
            TickEvent.SERVER_POST.register(BankSystemModBackend::onServerTick);

            // Save the data when the game saves the world
            LifecycleEvent.SERVER_LEVEL_SAVE.register((ServerLevel level) -> {
                if (level.dimension() == Level.OVERWORLD) {
                    INSTANCES.SERVER_DATA_HANDLER.saveAll();
                }
            });
        }
        loadDataFromFiles(server);



        INSTANCES.SERVER_EVENTS.BANKSYSTEM_SETUP_COMPLETED.notifyListeners();
    }

    // Called from the server side
    public static void onServerStop(MinecraftServer server) {
        MultiServerManager.cleanup();
        if(!INSTANCES.isSlaveServer) {
            TickEvent.SERVER_POST.unregister(BankSystemModBackend::onServerTick);
            saveDataToFiles(server);

            BankSystemDataHandler.resetBankDataLoaded();
            BankSystemDataHandler.resetGlobalDataLoaded();
        }
        //BankSystemConfig.Settings settings = INSTANCES.CONFIG.getSettings();
        //settings.bank.items.add(MoneyItem.getItemID());
        //INSTANCES.CONFIG.save();
        //INSTANCES.SERVER_EVENTS.removeListeners();
    }

    // Called from the server side
    public static void onPlayerJoin(ServerPlayer player)
    {
        INSTANCES.SERVER_BANK_MANAGER.getAsync().onPlayerJoinAsync(player.getUUID(), player.getName().getString());
        ItemIDManager.onPlayerJoined(player);
        /*
        INSTANCES.SERVER_BANK_MANAGER.createUser(
                player,
                new ArrayList<>(),
                true,
                INSTANCES.SERVER_SETTINGS.PLAYER.STARTING_BALANCE.get()
        );*/
    }

    // Called from the server side
    public static void onPlayerLeave(ServerPlayer player)
    {

    }

    // Called from the client side
    private static void onPlayerLeaveClientSide(@Nullable LocalPlayer localPlayer)
    {

    }
    // Called from the client side
    private static void onPlayerJoinClientSide(@Nullable LocalPlayer localPlayer)
    {
        INSTANCES.CLIENT_BANK_MANAGER.getItemFractionScaleFactorAsync();
        //ItemIDManager.clear();
    }

    // Called from the server side
    private static void onServerTick(MinecraftServer server)
    {
        if(INSTANCES.SERVER_DATA_HANDLER != null)
            INSTANCES.SERVER_DATA_HANDLER.tickUpdate();
    }

    public static void loadDataFromFiles(MinecraftServer server)
    {
        if(INSTANCES.SERVER_DATA_HANDLER != null) {
            Path rootSaveFolder = server.getWorldPath(LevelResource.ROOT);
            // Load data from the root save folder
            INSTANCES.SERVER_DATA_HANDLER.setLevelSavePath(rootSaveFolder);
            if (!INSTANCES.SERVER_DATA_HANDLER.loadAll()) {
                INSTANCES.SERVER_DATA_HANDLER.saveAll();
                INSTANCES.SERVER_DATA_HANDLER.loadAll(); // Try loading again after saving
            }
        }

    }
    public static void saveDataToFiles(MinecraftServer server)
    {
        if(INSTANCES.SERVER_DATA_HANDLER != null) {
            Path rootSaveFolder = server.getWorldPath(LevelResource.ROOT);
            // Load data from the root save folder
            INSTANCES.SERVER_DATA_HANDLER.setLevelSavePath(rootSaveFolder);
            INSTANCES.SERVER_DATA_HANDLER.saveAll();
        }
    }
    /*public static boolean isDataLoaded() {
        return INSTANCES.SERVER_DATA_HANDLER.isLoaded();
    }*/


    @Override
    public String getModID()
    {
        return BankSystemMod.MOD_ID;
    }

    @Override
    public String getModVersion() {
        return BankSystemMod.VERSION;
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
    public IBankManager getServerBankManager()
    {
        return INSTANCES.SERVER_BANK_MANAGER;
    }

    @Override
    public IClientBankManager getClientBankManager()
    {
        return INSTANCES.CLIENT_BANK_MANAGER;
    }

    @Override
    public IBankSystemDataHandler getDataHandler()
    {
        return INSTANCES.SERVER_DATA_HANDLER;
    }
    @Override
    public boolean isSlave()
    {
        return INSTANCES.isSlaveServer;
    }




    private static void setupMultiServerInfrastructure(MultiServerConfig config, MinecraftServer server)
    {
        if(config.isMaster)
        {
            INSTANCES.isSlaveServer = false;
            MultiServerManager.createMaster(server, config.sharedSecret, config.masterTcpPort,
                    BankSystemModBackend::onMasterServerStartupComplete,
                    BankSystemModBackend::onMasterServerStartupFailure,
                    BankSystemModBackend::onMasterServerSlaveConnected,
                    BankSystemModBackend::onMasterServerSlaveDisconnected);
            MultiServerManager.start();
        }
        else
        {
            INSTANCES.isSlaveServer = true;
            MultiServerManager.createSlave(server, config.sharedSecret, config.slaveID, config.masterHost, config.masterTcpPort,
                    BankSystemModBackend::onSlaveConnectionAccepted,
                    BankSystemModBackend::onSlaveConnectionFailed,
                    BankSystemModBackend::onSlaveConnectionLost,
                    BankSystemModBackend::onSlaveDisconnected);
            MultiServerManager.start();
        }
    }
    private static void onSlaveConnectionAccepted()
    {
        // Notify the master that players are on this server to create the personal bank account if the players don't have one
        ArrayList<ServerPlayer> players = ServerPlayerUtilities.getOnlinePlayers();
        IAsyncBankManager manager = INSTANCES.SERVER_BANK_MANAGER.getAsync();
        for(ServerPlayer player : players)
        {
            manager.onPlayerJoinAsync(player.getUUID(), player.getName().getString());
        }
    }
    private static void onSlaveConnectionFailed(SlaveServerClient.ConnectionEstablishState state)
    {

    }
    private static void onSlaveConnectionLost(Throwable reason)
    {

    }
    private static void onSlaveDisconnected()
    {

    }

    private static void onMasterServerStartupComplete()
    {

    }
    private static void onMasterServerStartupFailure(Throwable reason)
    {

    }
    private static void onMasterServerSlaveConnected(String slaveID)
    {
        BanksystemMetadataRequest.sendRequestToSlave(slaveID).thenAccept(metadata -> {
            String slaveModVersion = metadata.modversion();
            BankSystemAPI api = BankSystemMod.getAPI();
            if(!api.getModVersion().equals(slaveModVersion))
            {
                String msg = "Slave '"+slaveID+"' uses not the same BankSystemMod version: "+slaveModVersion + " This server uses: "+api.getModVersion()+" Disconnecting slave!";
                INSTANCES.LOGGER.warn(msg);
                MultiServerManager.disconnectSlave(slaveID, msg);
            }
            else
            {
                SyncItemIDsPacket.sendAllItemsToSlave(slaveID);

                INSTANCES.SERVER_EVENTS.MASTER_SERVER_SLAVE_CONNECTED.notifyListeners();
            }
        });

    }
    private static void onMasterServerSlaveDisconnected(String slaveID)
    {

    }
}
