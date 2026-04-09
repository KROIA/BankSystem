package net.kroia.banksystem;

import dev.architectury.event.events.client.ClientPlayerEvent;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.TickEvent;
import net.kroia.banksystem.api.BankSystemAPI;
import net.kroia.banksystem.api.IBankSystemDataHandler;
import net.kroia.banksystem.api.IBankSystemEvents;
import net.kroia.banksystem.api.bankmanager.IBankManager;
import net.kroia.banksystem.api.bankmanager.IClientBankManager;
import net.kroia.banksystem.banking.bank.AsyncBank;
import net.kroia.banksystem.banking.bank.ServerBank;
import net.kroia.banksystem.banking.bankaccount.AsyncBankAccount;
import net.kroia.banksystem.banking.bankmanager.BankManager;
import net.kroia.banksystem.banking.bankmanager.ClientBankManager;
import net.kroia.banksystem.block.BankSystemBlocks;
import net.kroia.banksystem.command.BankSystemCommands;
import net.kroia.banksystem.compat.NEZNAMY_TAB_Placeholders;
import net.kroia.banksystem.compat.OldBankDataLoader;
import net.kroia.banksystem.entity.BankSystemEntities;
import net.kroia.banksystem.entity.custom.BankDownloadBlockEntity;
import net.kroia.banksystem.entity.custom.BankTerminalBlockEntity;
import net.kroia.banksystem.entity.custom.BankUploadBlockEntity;
import net.kroia.banksystem.item.BankSystemCreativeModeTab;
import net.kroia.banksystem.item.BankSystemItems;
import net.kroia.banksystem.item.custom.software.Software;
import net.kroia.banksystem.menu.BankSystemMenus;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.networking.packet.general.SyncItemIDsPacket;
import net.kroia.banksystem.util.*;
import net.kroia.modutilities.networking.server_server.ServerServerConfig;
import net.kroia.modutilities.networking.server_server.ServerServerManager;
import net.kroia.modutilities.networking.server_server.slave.SlaveServerClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

public class BankSystemModBackend implements BankSystemAPI {
    public static class Instances
    {
        public boolean isSlaveServer = false;
        public BankSystemModSettings SERVER_SETTINGS;
        public BankSystemDataHandler SERVER_DATA_HANDLER;
        public BankManager SERVER_BANK_MANAGER;
        public IClientBankManager CLIENT_BANK_MANAGER;
        public BankSystemEvents SERVER_EVENTS;
        public BankSystemNetworking NETWORKING;
        public ItemIDManager ITEM_ID_MANAGER;

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
        INSTANCES.SERVER_EVENTS = null;
        INSTANCES.NETWORKING = null;
        INSTANCES.ITEM_ID_MANAGER = new ItemIDManager();
        BankSystemDataHandler.setBackend(INSTANCES);
        BankTerminalBlockEntity.setBackend(INSTANCES);

        BankManager.setBackend(INSTANCES);
        //ClientBank.setBackend(INSTANCES);
        AsyncBank.setBackend(INSTANCES);
        BankSystemModSettings.setBackend(INSTANCES);
        BankSystemCommands.setBackend(INSTANCES);
        BankDownloadBlockEntity.setBackend(INSTANCES);
        BankUploadBlockEntity.setBackend(INSTANCES);
        Software.setBackend(INSTANCES);
        ItemID.setBackend(INSTANCES);

        BankSystemNetworkPacket.setBackend(INSTANCES);
        BankSystemGenericRequest.setBackend(INSTANCES);
        BankSystemTextMessages.setBackend(INSTANCES);
        ServerBank.setBackend(INSTANCES);
        AsyncBankAccount.setBackend(INSTANCES);

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
        if(INSTANCES.SERVER_EVENTS == null)
            INSTANCES.SERVER_EVENTS = new BankSystemEvents();

    }

    // Called from the server side
    public static void onServerStart(MinecraftServer server) {
        ServerServerConfig config = ServerServerConfig.get();
        if(config.enable)
        {
            if(config.isMaster)
            {
                INSTANCES.isSlaveServer = false;
                ServerServerManager.createMaster(server, config.sharedSecret, config.masterTcpPort,
                        BankSystemModBackend::onMasterServerStartupComplete,
                        BankSystemModBackend::onMasterServerStartupFailure,
                        BankSystemModBackend::onMasterServerSlaveConnected,
                        BankSystemModBackend::onMasterServerSlaveDisconnected);
                ServerServerManager.start();
            }
            else
            {
                INSTANCES.isSlaveServer = true;
                ServerServerManager.createSlave(server, config.sharedSecret, config.slaveID, config.masterHost, config.masterTcpPort,
                        BankSystemModBackend::onSlaveConnectionAccepted,
                        BankSystemModBackend::onSlaveConnectionFailed,
                        BankSystemModBackend::onSlaveConnectionLost,
                        BankSystemModBackend::onSlaveDisconnected);
                ServerServerManager.start();
            }
        }
        NEZNAMY_TAB_Placeholders.setBackend(INSTANCES);
        OldBankDataLoader.setBackend(INSTANCES);

        INSTANCES.SERVER_SETTINGS = new BankSystemModSettings();
        INSTANCES.SERVER_SETTINGS.setLogger(INSTANCES.LOGGER::error, INSTANCES.LOGGER::error, INSTANCES.LOGGER::debug);

        if(INSTANCES.isSlaveServer)
        {
            INSTANCES.SERVER_BANK_MANAGER = BankManager.createSlave();
        }
        else
        {
            INSTANCES.SERVER_DATA_HANDLER = new BankSystemDataHandler();
            INSTANCES.SERVER_BANK_MANAGER = BankManager.createMaster();

            loadDataFromFiles(server);
            TickEvent.SERVER_POST.register(BankSystemModBackend::onServerTick);


            // Save the data when the game saves the world
            LifecycleEvent.SERVER_LEVEL_SAVE.register((ServerLevel level) -> {
                if (level.dimension() == Level.OVERWORLD) {
                    INSTANCES.SERVER_DATA_HANDLER.saveAll();
                }
            });
        }

    }

    // Called from the server side
    public static void onServerStop(MinecraftServer server) {
        ServerServerManager.stop();
        if(!INSTANCES.isSlaveServer) {
            TickEvent.SERVER_POST.unregister(BankSystemModBackend::onServerTick);
            saveDataToFiles(server);

            BankSystemDataHandler.resetBankDataLoaded();
            BankSystemDataHandler.resetGlobalDataLoaded();
        }

        INSTANCES.SERVER_EVENTS.removeListeners();
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
        ItemIDManager.clear();
    }
    // Called from the client side
    private static void onPlayerJoinClientSide(@Nullable LocalPlayer localPlayer)
    {
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



    private static void onSlaveConnectionAccepted()
    {

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
        SyncItemIDsPacket.sendAllItemsToSlave(slaveID);
    }
    private static void onMasterServerSlaveDisconnected(String slaveID)
    {

    }
}
