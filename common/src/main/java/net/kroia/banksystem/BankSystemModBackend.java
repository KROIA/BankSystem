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
import net.kroia.banksystem.minecraft.menu.custom.BankTerminalContainerMenu;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.networking.general.PlayerJoinSyncPacket;
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
import java.util.concurrent.atomic.AtomicBoolean;

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

        /**
         * Client-held settings snapshot synced from the server at player join
         * (see {@link net.kroia.banksystem.networking.general.PlayerJoinSyncPacket}).
         * Only meaningful on the client side; carries the isMasterServer flag used
         * to gate master-only UI (the "Mod Settings" button).
         */
        public ClientSettings CLIENT_SETTINGS = new ClientSettings();
    }

    private static Instances INSTANCES = new Instances();
    private static long snapshotTickCounter = 0;
    private static @Nullable ItemPriceProvider itemPriceProvider = null;
    private static short priceCurrencyItemId = 0;

    /**
     * Slave-side watchdog counter for the Task #22 registration latch: ticks up every
     * SERVER_POST while {@code INSTANCES.isSlaveServer && ItemIDManager.isRegistrationReady() == false}
     * (i.e. we're a slave that has not yet received {@code SyncItemIDsPacket} from the master).
     * Reset to {@code 0} on the slave branch of {@link #onServerStart(MinecraftServer)}.
     */
    private static int slaveLatchWatchTicks = 0;

    /**
     * One-shot flag so the slave-latch-still-armed WARN fires only once per boot when the
     * master fails to answer within the timeout window (see
     * {@link #onSlaveLatchTimeoutTick(MinecraftServer)}).
     */
    private static boolean slaveLatchTimeoutWarned = false;

    /**
     * Threshold (in server ticks @ 20 TPS) after which
     * {@link #onSlaveLatchTimeoutTick(MinecraftServer)} logs the slave-latch WARN. 600 ticks
     * = 30 seconds — long enough to cover reasonable master boot / handshake delay, short
     * enough that admins notice a genuinely stuck slave.
     */
    private static final int SLAVE_LATCH_WARN_TICKS = 600;

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
        BankTerminalContainerMenu.setBackend(INSTANCES);

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
        // Determine the multi-server role BEFORE creating the bank manager or bringing up
        // MultiServerManager (Task #21). Historically INSTANCES.isSlaveServer was set inside
        // setupMultiServerInfrastructure — but that call also starts the master connection
        // on netty threads, whose callbacks can fire before setupMultiServerInfrastructure
        // has even returned (observed: onSlaveConnectionAccepted at ~76 ms on localhost).
        // Deciding the role up front lets us initialize SERVER_BANK_MANAGER / COMMAND_HANDLER
        // before any callback can dereference them (see the null-check in
        // onSlaveConnectionAccepted below for defense-in-depth against reconnect races).
        boolean multiServerEnabled = config.enable;
        boolean isSlave = multiServerEnabled && !config.isMaster;
        INSTANCES.isSlaveServer = isSlave;

        if(isSlave)
        {
            // Slave init — order matters (Task #21):
            //   1. SERVER_BANK_MANAGER / COMMAND_HANDLER first, so any netty callback fired
            //      by setupMultiServerInfrastructure below observes a non-null backend.
            //   2. Default ItemID registration is DEFERRED (Task #22): the registration
            //      latch armed by ItemIDManager.clear() above stays armed until
            //      SyncItemIDsPacket arrives from the master and finalizeSlaveSync()
            //      releases it. This makes SyncItemIDsPacket the slave-side analog of the
            //      master's ItemIDs.nbt load — master's authoritative shorts always win via
            //      register-if-absent, and new-in-mod-update items no longer collide with
            //      the master's alias plan (observed 2026-07-17 for bank_terminal_block,
            //      atm_block, display_demo_back_panel_block).
            INSTANCES.SERVER_BANK_MANAGER = BankManager.createSlave();
            INSTANCES.COMMAND_HANDLER = BankSystemCommands.createSlave();
            // Reset the slave-latch startup-timeout watcher so a fresh boot always gets a
            // fresh 30-second window before warning.
            slaveLatchWatchTicks = 0;
            slaveLatchTimeoutWarned = false;
            TickEvent.SERVER_POST.register(BankSystemModBackend::onSlaveLatchTimeoutTick);
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

        // Bring up multi-server infrastructure LAST (Task #21): MultiServerManager.start()
        // fires-and-forgets the netty client/server bootstrap; on a slave the master
        // connection completes on a netty thread while this method is still executing, and
        // its onConnectionAccepted callback dereferences INSTANCES.SERVER_BANK_MANAGER.
        // Every field the callback touches must be assigned before we get here.
        if(multiServerEnabled)
        {
            setupMultiServerInfrastructure(config, server);
        }
        loadDataFromFiles(server);

        // Refresh the cached money ItemID AFTER the world's saved data is fully loaded.
        //
        // WHY: MoneyItem.getItemID() caches a short the first time it is resolved. On the
        // master, default/registry ItemIDs are now registered INSIDE loadAll() — AFTER
        // load_itemIDs() has restored the persisted map — via register-if-absent, so the
        // persisted base-money short (e.g. banksystem:money = 7) is kept rather than being
        // overwritten by a freshly minted low short. This block remains as a safety net:
        // resetItemID() sets the cache to INVALID then re-resolves via getItemID() against
        // the fully-loaded, consolidated registry, guaranteeing MoneyItem binds to the
        // correct persisted base-money short even if something resolved it earlier. A stale
        // cached short would make deposits target a wrong/blacklisted denomination
        // (ServerBank.create -> isItemIDAllowed(...) fails and the deposit is rejected).
        //
        // MASTER ONLY: the saved ItemID/bank data and the allowed-items set only exist on the
        // master. On a slave the ItemIDs arrive later via SyncItemIDsPacket — the slave-side
        // equivalent of this cache refresh is run by ItemIDManager.finalizeSlaveSync() from
        // SyncItemIDsPacket.handleOnSlave (Task #22).
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
        } else {
            // Mirror the master-side unregister above: the slave-latch watchdog is registered
            // on every slave onServerStart, so it must be unregistered on every stop to avoid
            // stacking a fresh handler on each session (see onSlaveLatchTimeoutTick Javadoc).
            TickEvent.SERVER_POST.unregister(BankSystemModBackend::onSlaveLatchTimeoutTick);
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
        // Sync the ClientSettings snapshot (isMasterServer flag) to the joining
        // client — used to gate master-only UI such as the "Mod Settings" button.
        PlayerJoinSyncPacket.send(player);
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
        // Reset the synced isMasterServer flag to its safe default (false) — the next
        // server the client joins re-syncs it via PlayerJoinSyncPacket.
        INSTANCES.CLIENT_SETTINGS.setMasterServer(false);
    }
    // Called from the client side
    private static void onPlayerJoinClientSide(@Nullable LocalPlayer localPlayer)
    {
        INSTANCES.CLIENT_BANK_MANAGER.getItemFractionScaleFactorAsync();
        //ItemIDManager.clear();
    }

    /**
     * Task #22 slave-latch startup-timeout watcher. Registered on the slave branch of
     * {@link #onServerStart(MinecraftServer)}; logs a WARN once when the registration latch
     * is still armed after {@link #SLAVE_LATCH_WARN_TICKS} ticks — i.e. no
     * {@code SyncItemIDsPacket} has arrived from the master within ~30 seconds. Silent
     * afterwards to avoid log spam. Auto-silences once the latch releases.
     * <p>
     * Unregistered on {@link #onServerStop(MinecraftServer)} (mirroring the master-side
     * {@code onServerTick} pattern) so the handler does not stack across sessions. As
     * belt-and-braces the handler also self-guards on {@code isSlaveServer} and
     * {@code isRegistrationReady()}, staying a no-op on the master or after the sync has
     * been received.
     */
    private static void onSlaveLatchTimeoutTick(MinecraftServer server) {
        if (!INSTANCES.isSlaveServer) return;
        if (ItemIDManager.isRegistrationReady()) return; // sync arrived, latch released — silent
        slaveLatchWatchTicks++;
        if (slaveLatchWatchTicks >= SLAVE_LATCH_WARN_TICKS && !slaveLatchTimeoutWarned) {
            slaveLatchTimeoutWarned = true;
            if (INSTANCES.LOGGER != null) {
                INSTANCES.LOGGER.warn("[BankSystemModBackend] Slave has been waiting more than "
                        + (SLAVE_LATCH_WARN_TICKS / 20) + " seconds for SyncItemIDsPacket from "
                        + "master — the ItemID registration latch is still armed. New ItemID "
                        + "registrations will be REJECTED with INVALID_ID until the master "
                        + "responds. Check that the master server is running and reachable at "
                        + "the configured host:port. This warning fires once per boot.");
            }
        }
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
            // NOTE: a BankSystemStartupAbortException thrown by a load guard inside
            // loadAll() (ItemID merge guard, save-format gate on newer-mod files, or
            // the world-repair guard) intentionally propagates out of this method —
            // it must abort server startup, and the data handler has already
            // prohibited every save of this session at that point.
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

    /**
     * Issue #64: edge-latch for the slave&rarr;master connection so
     * {@code SLAVE_CONNECTION_LOST} fires once per connected&rarr;disconnected
     * transition instead of on every failed reconnect attempt. Set {@code true}
     * only where {@code SLAVE_CONNECTION_ACCEPTED} actually fires; a CAS
     * {@code true}&rarr;{@code false} in the loss paths gates the LOST signal so
     * the auto-reconnect retry loop cannot re-fire it while the master stays down.
     * Reset on each slave setup so a fresh world load starts disconnected.
     */
    private static final AtomicBoolean slaveConnectionActive = new AtomicBoolean(false);

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
            slaveConnectionActive.set(false);
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
        // Task #21 defense-in-depth: even with the fixed init ordering
        // (SERVER_BANK_MANAGER set before setupMultiServerInfrastructure), a reconnect race
        // can still fire this callback on a netty thread between onServerStop's
        // SERVER_BANK_MANAGER=null (line ~331) and the next onServerStart's re-assignment.
        // Skip cleanly with a distinct WARN — the alternative is an NPE inside a netty
        // thread that only surfaces via the SlaveServerClient failed-callback log.
        if (INSTANCES.SERVER_BANK_MANAGER == null) {
            if (INSTANCES.LOGGER != null) {
                INSTANCES.LOGGER.warn("[BankSystemModBackend] onSlaveConnectionAccepted fired "
                        + "while SERVER_BANK_MANAGER is null — skipping player-join sync. "
                        + "This should only happen during a reconnect while the server is "
                        + "shutting down.");
            }
            return;
        }
        // Notify the master that players are on this server to create the personal bank account if the players don't have one
        ArrayList<ServerPlayer> players = ServerPlayerUtilities.getOnlinePlayers();
        IAsyncBankManager manager = INSTANCES.SERVER_BANK_MANAGER.getAsync();
        for(ServerPlayer player : players)
        {
            manager.onPlayerJoinAsync(player.getUUID(), player.getName().getString());
        }

        // Notify dependent mods (e.g. StockMarket) that the slave→master
        // handshake has completed and BankSystem's async forwarder is now
        // usable. Fire AFTER the SERVER_BANK_MANAGER null guard above so
        // listeners never see a partially torn-down state. Fired on a Netty
        // event-loop thread — see IBankSystemEvents contract.
        if (INSTANCES.SERVER_EVENTS != null) {
            // Arm the loss latch (Issue #64) so the next real disconnect fires
            // SLAVE_CONNECTION_LOST exactly once. Fires again on every reconnect.
            slaveConnectionActive.set(true);
            INSTANCES.SERVER_EVENTS.SLAVE_CONNECTION_ACCEPTED.notifyListeners();
        }
    }
    private static void onSlaveConnectionFailed(SlaveServerClient.ConnectionEstablishState state)
    {

    }
    private static void onSlaveConnectionLost(Throwable reason)
    {
        // Task #23: preserve the positive itemIDMap (master re-sends full sync on reconnect
        // via SyncItemIDsPacket, and register-if-absent semantics are idempotent), but drop
        // the negative / in-flight cache: the reconnecting master might have a different
        // registry (mod update on master while slave was disconnected), and we want a single
        // fresh retry per unknown item per reconnect. Idempotent on master (no-op).
        ItemIDManager.clearSlaveNegativeCacheOnDisconnect();
        // Pair to onSlaveConnectionAccepted: tell dependent mods that master is no longer
        // reachable so any cache populated from the last handshake is now stale. On reconnect,
        // SLAVE_CONNECTION_ACCEPTED fires again and caches can be re-fetched.
        // Issue #64: the auto-reconnect loop re-enters this callback on every failed
        // attempt; the CAS ensures SLAVE_CONNECTION_LOST fires only on the first
        // connected->disconnected transition, not once per retry.
        if (INSTANCES.SERVER_EVENTS != null && slaveConnectionActive.compareAndSet(true, false)) {
            INSTANCES.SERVER_EVENTS.SLAVE_CONNECTION_LOST.notifyListeners();
        }
    }
    private static void onSlaveDisconnected()
    {
        // Same rationale as onSlaveConnectionLost above — clear the negative cache so
        // reconnect retries formerly-INVALID lookups exactly once.
        ItemIDManager.clearSlaveNegativeCacheOnDisconnect();
        // Clean-disconnect path (local disconnect() call). Dependent mods invalidate their
        // caches the same way — the semantics are identical: master is unreachable.
        // Issue #64: share the same edge-latch as onSlaveConnectionLost so a clean
        // disconnect that follows a connection-lost storm doesn't double-fire.
        if (INSTANCES.SERVER_EVENTS != null && slaveConnectionActive.compareAndSet(true, false)) {
            INSTANCES.SERVER_EVENTS.SLAVE_CONNECTION_LOST.notifyListeners();
        }
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
        // Issue #65: counterpart to onMasterServerSlaveConnected — tell dependent
        // mods on the master (e.g. StockMarket) that a slave left so they can evict
        // any per-slave state keyed to it. Carries the departing slaveID.
        if (INSTANCES.SERVER_EVENTS != null) {
            INSTANCES.SERVER_EVENTS.MASTER_SERVER_SLAVE_DISCONNECTED.notifyListeners(slaveID);
        }
    }
}
