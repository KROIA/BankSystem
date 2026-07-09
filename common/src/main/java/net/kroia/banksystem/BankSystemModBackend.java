package net.kroia.banksystem;

import dev.architectury.event.events.client.ClientPlayerEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.architectury.registry.ReloadListenerRegistry;
import net.kroia.banksystem.api.BankSystemAPI;
import net.kroia.banksystem.api.IBankSystemDataHandler;
import net.kroia.banksystem.api.IBankSystemEvents;
import net.kroia.banksystem.api.ItemPriceProvider;
import net.kroia.banksystem.api.bankmanager.IAsyncBankManager;
import net.kroia.banksystem.api.bankmanager.IBankManager;
import net.kroia.banksystem.api.bankmanager.IClientBankManager;
import net.kroia.banksystem.api.command.IBankSystemCommands;
import net.kroia.banksystem.banking.bankmanager.BankManager;
import net.kroia.banksystem.banking.bankmanager.ClientBankManager;
import net.kroia.banksystem.banking.bankmanager.ServerBankManager;
import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.data.table.BalanceHistoryManager;
import net.kroia.banksystem.data.table.record.BalanceHistoryRecord;
import net.kroia.banksystem.minecraft.block.BankSystemBlocks;
import net.kroia.banksystem.minecraft.command.BankSystemCommands;
import net.kroia.banksystem.minecraft.compat.NEZNAMY_TAB_Placeholders;
import net.kroia.banksystem.minecraft.compat.OldBankDataLoader;
import net.kroia.banksystem.minecraft.entity.BankSystemEntities;
import net.kroia.banksystem.minecraft.entity.custom.BankDownloadBlockEntity;
import net.kroia.banksystem.minecraft.entity.custom.BankSystemDisplayBlockEntity;
import net.kroia.banksystem.minecraft.entity.custom.BankTerminalBlockEntity;
import net.kroia.banksystem.screen.widgets.BalanceHistoryChart;
import net.kroia.banksystem.minecraft.entity.custom.BankUploadBlockEntity;
import net.kroia.banksystem.minecraft.item.BankSystemCreativeModeTab;
import net.kroia.banksystem.minecraft.item.BankSystemItems;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.minecraft.item.custom.software.Software;
import net.kroia.banksystem.minecraft.menu.BankSystemMenus;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.networking.general.SyncItemIDsPacket;
import net.kroia.banksystem.networking.multi_server.BanksystemMetadataRequest;
import net.kroia.modutilities.gui.GuiElementRegistry;
import net.kroia.modutilities.testing.TestRegistry;
import net.kroia.banksystem.testing.BankSystemTestRegistration;
import net.kroia.banksystem.util.*;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.kroia.modutilities.networking.multi_server.MultiServerConfig;
import net.kroia.modutilities.networking.multi_server.MultiServerManager;
import net.kroia.modutilities.networking.multi_server.slave.SlaveServerClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
        public DatabaseManager DATABASE_MANAGER;
        public BalanceHistoryManager BALANCE_HISTORY_MANAGER;
    }

    private static Instances INSTANCES = new Instances();
    private static long snapshotTickCounter = 0;
    private static @Nullable ItemPriceProvider itemPriceProvider = null;
    private static short priceCurrencyItemId = 0;

    /**
     * Master-only accessor for the balance-history SQLite manager.
     * <p>
     * Only used by internal callers that need direct access to the DB layer (e.g. the in-game
     * merge-guard tests exercising the on-merge history purge from Task #12). Public API
     * consumers should query balance history via {@code BalanceHistoryRequest} instead.
     *
     * @return the balance-history manager on the master server, or {@code null} on slave
     *         servers / pre-startup / after shutdown.
     */
    public static @Nullable BalanceHistoryManager getBalanceHistoryManager() {
        return INSTANCES.BALANCE_HISTORY_MANAGER;
    }

    /**
     * Master-only accessor for the {@link DatabaseManager}.
     * <p>
     * Same audience as {@link #getBalanceHistoryManager()} — the in-game tests need the shared
     * DB executor to await queued async work (single-thread executor: submitting a runnable
     * and awaiting its future flushes all previously queued DB ops).
     *
     * @return the DB manager on the master server, or {@code null} otherwise.
     */
    public static @Nullable DatabaseManager getDatabaseManager() {
        return INSTANCES.DATABASE_MANAGER;
    }

    /**
     * Test-only accessor for the shared {@link Instances} container. Same audience as
     * {@link #getBalanceHistoryManager()} — the in-game merge-guard tests use this to reach
     * the data handler for the upgrade-safety assertion on {@code appliedComponentSet}.
     * Production code should not use this — instances are internal state.
     *
     * @return the singleton instances container (never {@code null}, initialized at class
     *         load), possibly with some individual fields still {@code null} depending on
     *         the current lifecycle phase.
     */
    public static Instances getInstances_forTesting() {
        return INSTANCES;
    }


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
        ItemIDManager.setBackend(INSTANCES);
        VolatileItemComponents.setBackend(INSTANCES);
        MultiServerUtils.setBackend(INSTANCES);
        BankSystemDataHandler.setBackend(INSTANCES);
        BankTerminalBlockEntity.setBackend(INSTANCES);

        BankManager.setBackend(INSTANCES);

        BankSystemModSettings.setBackend(INSTANCES);
        //BankSystemConfig.setBackend(INSTANCES);
        BankSystemCommands.setBackend(INSTANCES);
        BankDownloadBlockEntity.setBackend(INSTANCES);
        BankUploadBlockEntity.setBackend(INSTANCES);
        BankSystemDisplayBlockEntity.setBackend(INSTANCES);
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


        GuiElementRegistry.register("balance_history_chart", BalanceHistoryChart.class, BalanceHistoryChart::new);

        INSTANCES.NETWORKING = new BankSystemNetworking();
        INSTANCES.SERVER_EVENTS = new BankSystemEvents();

        // Detect datapack reloads that change the volatile/deposit-gated component tags on a
        // RUNNING server: the change is rejected (normalization keeps the world-load tag
        // snapshot) and an error is logged; the new tags are evaluated by the ItemID merge
        // guard at the next restart. The listener fires during resource apply — the actual
        // tag rebind happens a few scheduler tasks later, so the comparison is deferred to
        // the tick hook below (see VolatileItemComponents#onServerResourcesReloaded()).
        ReloadListenerRegistry.register(PackType.SERVER_DATA,
                (ResourceManagerReloadListener) resourceManager -> VolatileItemComponents.onServerResourcesReloaded());
        TickEvent.SERVER_POST.register(server -> VolatileItemComponents.serverTick());

        if (TestRegistry.ENABLE_TESTS && BankSystemMod.ENABLE_DEV_FEATURES) {
            BankSystemTestRegistration.register();
        }

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

        //Path configPath = server.getWorldPath(LevelResource.ROOT).resolve("data/BankSystem/BankSystemConfig.json");
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

            snapshotTickCounter = 0;
            DatabaseManager.setBackend(INSTANCES);
            INSTANCES.DATABASE_MANAGER = new DatabaseManager();
            INSTANCES.DATABASE_MANAGER.connectToDatabase(server);
            INSTANCES.BALANCE_HISTORY_MANAGER = new BalanceHistoryManager(INSTANCES.DATABASE_MANAGER);

            if (INSTANCES.SERVER_SETTINGS.UTILITIES.BALANCE_SNAPSHOT_MAX_RECORDS_PER_ITEM.get() <= 0) {
                INSTANCES.LOGGER.warn("BALANCE_SNAPSHOT_MAX_RECORDS_PER_ITEM is 0 (unlimited). " +
                        "The balance history database file can grow extremely large over time. " +
                        "Set a positive value to enable automatic pruning of old records.");
            }

            TickEvent.SERVER_POST.register(BankSystemModBackend::onServerTick);

            // Save the data when the game saves the world
            LifecycleEvent.SERVER_LEVEL_SAVE.register((ServerLevel level) -> {
                if (level.dimension() == Level.OVERWORLD && INSTANCES.SERVER_DATA_HANDLER != null) {
                    INSTANCES.SERVER_DATA_HANDLER.saveAll();
                }
            });
        }
        loadDataFromFiles(server);

        // Refresh the cached money ItemID AFTER the world's saved data is fully loaded.
        //
        // WHY: MoneyItem.getItemID() caches a short the first time it is resolved. During
        // startup that first resolution happens inside ItemIDManager.createDefaultItemIDs()
        // (above, via MoneyItem.resetItemID()) — i.e. against the pre-load DEFAULT ItemID map,
        // BEFORE loadDataFromFiles(server) below runs load_itemIDs()/load_bank() and
        // consolidatePendingMerges(). On a saved dedicated world the persisted base-money short
        // (e.g. banksystem:money = 7) can differ from that default-map short (e.g. money_cent50
        // = 6). A stale cached short makes deposits target a wrong/blacklisted denomination, so
        // ServerBank.create -> isItemIDAllowed(...) fails and the deposit is rejected.
        //
        // Recomputing here (resetItemID() sets the cache to INVALID then re-resolves via
        // getItemID()) binds MoneyItem to the fully-loaded, persisted base-money short.
        //
        // MASTER ONLY: the saved ItemID/bank data and the allowed-items set only exist on the
        // master. On a slave the ItemIDs arrive later via SyncItemIDsPacket, so there is nothing
        // to re-resolve against here. This mirrors the isSlaveServer guarding used above.
        if (!INSTANCES.isSlaveServer) {
            MoneyItem.resetItemID();
            INSTANCES.LOGGER.info("Refreshed cached money ItemID after world-data load. "
                    + "Resolved money short = " + MoneyItem.getItemID().getShort());
        }

        if (INSTANCES.BALANCE_HISTORY_MANAGER != null) {
            takeBalanceSnapshot();
        }

        INSTANCES.SERVER_EVENTS.BANKSYSTEM_SETUP_COMPLETED.notifyListeners();
    }

    // Called from the server side
    public static void onServerStop(MinecraftServer server) {
        MultiServerManager.cleanup();

        if (!INSTANCES.isSlaveServer) {
            TickEvent.SERVER_POST.unregister(BankSystemModBackend::onServerTick);
            saveDataToFiles(server);

            if (INSTANCES.DATABASE_MANAGER != null) {
                INSTANCES.DATABASE_MANAGER.close();
            }

            BankSystemDataHandler.resetBankDataLoaded();
            BankSystemDataHandler.resetGlobalDataLoaded();
        }

        INSTANCES.SERVER_BANK_MANAGER = null;
        INSTANCES.SERVER_DATA_HANDLER = null;
        INSTANCES.SERVER_SETTINGS = null;
        INSTANCES.COMMAND_HANDLER = null;
        INSTANCES.DATABASE_MANAGER = null;
        INSTANCES.BALANCE_HISTORY_MANAGER = null;
        INSTANCES.isSlaveServer = false;
        snapshotTickCounter = 0;
        ItemIDManager.clear();
        // Drop the world-load tag snapshot: the next world/server captures its own freshly
        // bound tags (see VolatileItemComponents#captureTagSnapshot()).
        VolatileItemComponents.resetTagSnapshot();
        ItemColorUtil.clearCache();
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
        // A dedicated client leaving a server drops its tag snapshot so the next server it
        // joins captures that server's tags. (In singleplayer the integrated server's stop
        // handler resets it as well — resetting twice is harmless.)
        VolatileItemComponents.resetTagSnapshot();
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

        if (INSTANCES.BALANCE_HISTORY_MANAGER != null && INSTANCES.SERVER_SETTINGS != null) {
            snapshotTickCounter++;
            long intervalTicks = INSTANCES.SERVER_SETTINGS.UTILITIES.BALANCE_SNAPSHOT_INTERVAL_MINUTES.get() * 1200L;
            if (intervalTicks > 0 && snapshotTickCounter >= intervalTicks) {
                snapshotTickCounter = 0;
                takeBalanceSnapshot();
            }
        }
    }

    private static void takeBalanceSnapshot() {
        if (INSTANCES.SERVER_BANK_MANAGER == null) return;
        ServerBankManager bankManager = (ServerBankManager) INSTANCES.SERVER_BANK_MANAGER.getSync();
        if (bankManager == null) return;

        // Diagnostic (fires on the snapshot timer, once every BALANCE_SNAPSHOT_INTERVAL_MINUTES,
        // plus once at server start): surfaces the cached price-currency short handed to us by
        // StockMarket (setPriceCurrencyItem) alongside the LIVE money ItemID shorts, and — most
        // importantly — confirms that the money bank is now recognised as CASH via BankSystem's
        // own denomination-agnostic predicate (MoneyItem.isMoney) rather than the fragile cached
        // short. 'moneyDetectedAsCash' is the check collectBalanceSnapshot() actually performs
        // for the money bank; if it is true, cash contributes to "Total Wealth" regardless of
        // whether the cached currency short is correct. Diagnostic kept at DEBUG: silent at
        // normal log levels but available for future diagnosis (one line per snapshot interval).
        ItemID liveMoneyItemID = MoneyItem.getItemID();
        boolean moneyValid = liveMoneyItemID != null && liveMoneyItemID.isValid();
        short liveMoneyShort = moneyValid ? liveMoneyItemID.getShort() : 0;
        short canonicalMoneyShort = moneyValid ? ItemIDManager.resolveAlias(liveMoneyItemID).getShort() : 0;
        boolean moneyDetectedAsCash = moneyValid && MoneyItem.isMoney(liveMoneyItemID);
        INSTANCES.LOGGER.debug("[BalanceSnapshot][diag] priceCurrencyItemId(cached)=" + priceCurrencyItemId
                + ", liveMoneyShort=" + liveMoneyShort + ", canonicalMoneyShort=" + canonicalMoneyShort
                + ", moneyDetectedAsCash(isMoney)=" + moneyDetectedAsCash
                + " (cash is detected via MoneyItem.isMoney — denomination-agnostic; the cached currency short is only a secondary accept)");

        long now = System.currentTimeMillis();
        List<BalanceHistoryRecord> records = bankManager.collectBalanceSnapshot(now, itemPriceProvider, priceCurrencyItemId);

        if (!records.isEmpty()) {
            INSTANCES.BALANCE_HISTORY_MANAGER.save(records);

            long maxRecords = INSTANCES.SERVER_SETTINGS.UTILITIES.BALANCE_SNAPSHOT_MAX_RECORDS_PER_ITEM.get();
            if (maxRecords > 0) {
                INSTANCES.BALANCE_HISTORY_MANAGER.pruneOldRecords(maxRecords);
            }
        }
    }

    public static void loadDataFromFiles(MinecraftServer server)
    {
        if(INSTANCES.SERVER_DATA_HANDLER != null) {
            Path rootSaveFolder = server.getWorldPath(LevelResource.ROOT);
            // Load data from the root save folder.
            // NOTE: an ItemIDMergeAbortedException thrown by the merge guard inside
            // loadAll() intentionally propagates out of this method — it must abort
            // server startup, and the data handler has already prohibited every save
            // of this session at that point.
            INSTANCES.SERVER_DATA_HANDLER.setLevelSavePath(rootSaveFolder);
            if (!INSTANCES.SERVER_DATA_HANDLER.loadAll()) {
                // Never blind-overwrite unreadable files with in-memory (possibly empty)
                // state: move everything that failed to load aside first
                // (<name>.corrupt-<timestamp>), then write fresh files and re-load.
                // Fresh worlds (no files yet) skip the backup and just create their
                // initial files here. Subsystems that failed to load AND could not be
                // backed up remain save-prohibited inside the handler.
                INSTANCES.SERVER_DATA_HANDLER.backupFailedSubsystemData();
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

    @Override
    public void setItemPriceProvider(@Nullable ItemPriceProvider provider) {
        itemPriceProvider = provider;
    }

    @Override
    public @Nullable ItemPriceProvider getItemPriceProvider() {
        return itemPriceProvider;
    }

    @Override
    public void setPriceCurrencyItem(short currencyItemId) {
        priceCurrencyItemId = currencyItemId;
    }

    @Override
    public short getPriceCurrencyItem() {
        return priceCurrencyItemId;
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
