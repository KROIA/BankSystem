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
import net.kroia.banksystem.api.currency.ExternalCurrencyProvider;
import net.kroia.banksystem.banking.binding.BankAccountBindings;
import net.kroia.banksystem.banking.bankmanager.BankManager;
import net.kroia.banksystem.banking.bankmanager.ClientBankManager;
import net.kroia.banksystem.banking.bankmanager.ServerBankManager;
import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.data.DiagnosticsDatabase;
import net.kroia.banksystem.data.table.BalanceHistoryManager;
import net.kroia.banksystem.data.table.record.BalanceHistoryRecord;
import net.kroia.banksystem.minecraft.block.BankSystemBlocks;
import net.kroia.banksystem.minecraft.command.BankSystemCommands;
import net.kroia.banksystem.minecraft.compat.BankSystemDiscord;
import net.kroia.banksystem.minecraft.compat.BankSystemPlaceholders;
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
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
        /** Task #123 — write-only diagnostics DB for the audit tool (every role; null outside a server session). */
        public DiagnosticsDatabase DIAGNOSTICS_DB;
        public BalanceHistoryManager BALANCE_HISTORY_MANAGER;
        /** Task #44 (v2.1.0) — SQLite transaction ledger (master-only, nullable on slaves). */
        public net.kroia.banksystem.data.table.TransactionLogManager TRANSACTION_LOG_MANAGER;
        /** Task #110 (v2.2.0) — SuspicionFlag table; master only. */
        public net.kroia.banksystem.data.table.SuspicionFlagManager SUSPICION_FLAG_MANAGER;
        /** Task #45 (v2.1.0) — payout history SQLite writer (master-only, nullable on slaves). */
        public net.kroia.banksystem.data.table.PayoutHistoryManager PAYOUT_HISTORY_MANAGER;
        /** Task #45 (v2.1.0) — public payout API impl (always non-null; degrades to fail-closed on slave). */
        public net.kroia.banksystem.api.payout.IPayoutManager PAYOUT_MANAGER;
        /** Task #49 (v2.1.0) — dividend payer (always non-null; degrades to fail-closed on slave). */
        public net.kroia.banksystem.api.dividend.IDividendPayer DIVIDEND_PAYER;
        /** Task #52 (v2.1.0) — dividend event SQLite store (master-only, nullable on slaves). */
        public net.kroia.banksystem.banking.company.DividendHistoryStore DIVIDEND_HISTORY_STORE;
        /** Task #54 (v2.1.0) — share symbol texture store (master: authoritative; slave: mirror). */
        public net.kroia.banksystem.banking.company.ShareSymbolStore SHARE_SYMBOL_STORE;

        /**
         * External-currency binding table (Task #33, v2.0.5). Master-authoritative;
         * on slave servers this instance stays empty. See {@link BankAccountBindings}.
         */
        public BankAccountBindings BANK_ACCOUNT_BINDINGS;

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
    /**
     * Task #41 (v2.0.7): tick counter for the tiered-retention sweep cadence. Runs on a
     * separate schedule from the balance snapshot itself so the (heavier) DELETE-per-band
     * SQL does not fire every minute. Reset to {@code 0} on server start/stop; the first
     * sweep runs synchronously at {@link #onServerStart(MinecraftServer)}.
     */
    private static long retentionSweepTickCounter = 0;
    /** Task #114 (v2.2.0): tick counter for the wealth-jump sweep. */
    private static long wealthSweepTickCounter = 0;
    /**
     * Task #45 (v2.1.0) — monotonic tick counter for the payout scheduler. Advances on every
     * master-side server tick; the scheduler evaluates due schedules every
     * {@link net.kroia.banksystem.banking.company.PayoutExecutor#PAYOUT_TICK_INTERVAL} ticks.
     * Reset on server start / stop.
     */
    private static long payoutTickCounter = 0;
    /** Task #146: set on master start; the first tick runs the foreign-binding check once. */
    private static boolean foreignBindingCheckPending = false;
    private static @Nullable ItemPriceProvider itemPriceProvider = null;
    private static short priceCurrencyItemId = 0;

    /**
     * Registry of external currency-mod adapters (Task #33, v2.0.5). Keyed by
     * {@link ExternalCurrencyProvider#providerId()}. Adapters register themselves
     * during mod init via
     * {@link BankSystemAPI#registerCurrencyProvider(ExternalCurrencyProvider)}.
     * Concurrent map so registration happening off the server thread (mod-init
     * threads) is safe against readers on the server thread.
     */
    private static final Map<String, ExternalCurrencyProvider> CURRENCY_PROVIDERS = new ConcurrentHashMap<>();

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
     * Master-only accessor for the transaction-ledger SQLite manager
     * (Task #44, v2.1.0). Returns {@code null} on slaves / pre-startup / shutdown so
     * write-site hooks can no-op without a special path.
     */
    public static @Nullable net.kroia.banksystem.data.table.TransactionLogManager getTransactionLogManager() {
        return INSTANCES.TRANSACTION_LOG_MANAGER;
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
        INSTANCES.BANK_ACCOUNT_BINDINGS = new BankAccountBindings();
        BankAccountBindings.setBackend(INSTANCES);
        VolatileItemComponents.setBackend(INSTANCES);
        AdminFlagSync.setBackend(INSTANCES);
        // Client-only class: touching it on a dedicated server crashes class loading.
        //if (dev.architectury.platform.Platform.getEnvironment() == dev.architectury.utils.Env.CLIENT) net.kroia.banksystem.client.render.StampedShareSpecialRenderer.setBackend(INSTANCES); // mc>=1.21.4
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
        net.kroia.banksystem.minecraft.entity.custom.ShareStamperBlockEntity.setBackend(INSTANCES);
        BankSystemDisplayBlockEntity.setBackend(INSTANCES);
        Software.setBackend(INSTANCES);
        ItemID.setBackend(INSTANCES);

        BankSystemNetworking.setBackend(INSTANCES);
        net.kroia.banksystem.banking.ledger.TransactionLedger.setBackend(INSTANCES); // Task #109
        DiagnosticsDatabase.setBackend(INSTANCES); // Task #123
        net.kroia.banksystem.data.TransferJournal.setBackend(INSTANCES); // Task #124
        net.kroia.banksystem.banking.ledger.AccountFreeze.setBackend(INSTANCES); // Task #117
        net.kroia.banksystem.banking.ledger.AdminAudit.setBackend(INSTANCES); // Task #110
        net.kroia.banksystem.banking.ledger.SuspicionChecks.setBackend(INSTANCES); // Tasks #111/#114/#115
        net.kroia.banksystem.banking.ledger.AuditDigest.setBackend(INSTANCES); // Task #113
        net.kroia.banksystem.banking.company.ShareIntegrity.setBackend(INSTANCES); // BS-235
        BankSystemTextMessages.setBackend(INSTANCES);

        net.kroia.banksystem.integration.numismatics.NumismaticsProvider.setBackend(INSTANCES);
        net.kroia.banksystem.integration.numismatics.NumismaticsAccount.setBackend(INSTANCES);
        net.kroia.banksystem.integration.lightmanscurrency.LightmansCurrencyProvider.setBackend(INSTANCES);
        net.kroia.banksystem.integration.lightmanscurrency.LightmansCurrencyAccount.setBackend(INSTANCES);


        BankSystemCommands.registerCommands();


        BankSystemBlocks.init();
        net.kroia.banksystem.minecraft.component.BankSystemDataComponents.init();
        BankSystemItems.init();
        BankSystemEntities.init();
        BankSystemMenus.init();
        BankSystemCreativeModeTab.init();
        BankSystemTextMessages.init();


        GuiElementRegistry.register("balance_history_chart", BalanceHistoryChart.class, BalanceHistoryChart::new);

        INSTANCES.NETWORKING = new BankSystemNetworking();
        INSTANCES.SERVER_EVENTS = new BankSystemEvents();
        BankSystemDiscord.init(INSTANCES); // Task #119: ledger/flag listeners, once per JVM

        // Detect datapack reloads that change the volatile/deposit-gated component tags on a
        // RUNNING server: the change is rejected (normalization keeps the world-load tag
        // snapshot) and an error is logged; the new tags are evaluated by the ItemID merge
        // guard at the next restart. The listener fires during resource apply — the actual
        // tag rebind happens a few scheduler tasks later, so the comparison is deferred to
        // the tick hook below (see VolatileItemComponents#onServerResourcesReloaded()).
        ReloadListenerRegistry.register(PackType.SERVER_DATA,
                (ResourceManagerReloadListener) resourceManager -> VolatileItemComponents.onServerResourcesReloaded(),
                BankSystemMod.id("volatile_components_reload"));
        TickEvent.SERVER_POST.register(server -> VolatileItemComponents.serverTick());
        TickEvent.SERVER_POST.register(server -> net.kroia.banksystem.data.CreativeNoteFlow.tick()); // BS-227: every role
        AdminFlagSync.init(); // Task #107: LuckPerms listener, no-op without LuckPerms

        if (BankSystemMod.ENABLE_DEV_FEATURES) {
            initDevFeatures();
        }

        // Register external currency providers (Task #34+, v2.0.5)
        registerExternalCurrencyProviders();

    }

    /**
     * Starts the dev-only code (unit tests, test commands). It is compiled only when
     * gradle.properties has {@code enable_dev_features=true}, so it is reached reflectively:
     * release code must compile without it.
     */
    private static void initDevFeatures() {
        try {
            Class.forName(BankSystemMod.DEV_BOOTSTRAP).getMethod("init").invoke(null);
        } catch (ReflectiveOperationException e) {
            INSTANCES.LOGGER.error("Dev features are enabled but " + BankSystemMod.DEV_BOOTSTRAP
                    + " is missing or failed; check enable_dev_features in gradle.properties", e);
        }
    }

    // Called from the client side
    public static void onClientSetup()
    {
        BankSystemClientHooks.setupMenuScreens();
        ClientBankManager.setBackend(INSTANCES);
        INSTANCES.CLIENT_BANK_MANAGER = BankManager.createClient();
        // Task #54 (v2.1.0) — initialise client-side symbol cache directory.
        net.kroia.banksystem.client.company.ClientSymbolRegistry.init();

        BankSystemGuiScreen.setBackend(INSTANCES);
        BankSystemGuiContainerScreen.setBackend(INSTANCES);
        BankSystemGuiElement.setBackend(INSTANCES);
        BankSystemEntities.registerRenderers();

        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register(BankSystemModBackend::onPlayerLeaveClientSide);
        ClientPlayerEvent.CLIENT_PLAYER_JOIN.register(BankSystemModBackend::onPlayerJoinClientSide);
        // BS-195: explanation screen after a refused singleplayer world load.
        net.kroia.banksystem.client.StartupRefusalClient.setBackend(INSTANCES);
        dev.architectury.event.events.client.ClientTickEvent.CLIENT_POST.register(
                net.kroia.banksystem.client.StartupRefusalClient::onClientTick);
    }

    // Called from the server side
    public static void onServerSetup()
    {


    }

    // Listener instances: a method reference at another call site is a different object, so
    // register/unregister must use the same instance or handlers stack across world loads.
    private static final TickEvent.Server ON_SERVER_TICK = BankSystemModBackend::onServerTick;
    private static final TickEvent.Server ON_SLAVE_LATCH_TICK = BankSystemModBackend::onSlaveLatchTimeoutTick;
    private static final LifecycleEvent.ServerLevelState ON_LEVEL_SAVE = (ServerLevel level) -> {
        if (level.dimension() == Level.OVERWORLD && INSTANCES.SERVER_DATA_HANDLER != null) {
            INSTANCES.SERVER_DATA_HANDLER.saveAll();
        }
    };

    // Called from the server side
    public static void onServerStart(MinecraftServer server) {

        OldBankDataLoader.setBackend(INSTANCES);


        INSTANCES.SERVER_SETTINGS = new BankSystemModSettings();
        INSTANCES.SERVER_SETTINGS.setLogger(INSTANCES.LOGGER::error, INSTANCES.LOGGER::error, INSTANCES.LOGGER::debug);

        //Path configPath = server.getWorldPath(LevelResource.ROOT).resolve("data/BankSystem/BankSystemConfig.json");
        //INSTANCES.CONFIG = BankSystemConfig.create(configPath);
        INSTANCES.SERVER_DATA_HANDLER = new BankSystemDataHandler();

        ItemIDManager.clear();
        // Task #126 (B6): transfer results are per session; transfers sent before now answer UNKNOWN.
        net.kroia.banksystem.networking.multi_server.TransferStatusRequest.RESULTS.reset(System.currentTimeMillis());
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
        // Task #123: every role. Task #175: open now on its writer thread, not inside the first write.
        INSTANCES.DIAGNOSTICS_DB = new DiagnosticsDatabase(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve(DatabaseManager.DATABASE_PATH).resolve(DiagnosticsDatabase.FILE_NAME));
        INSTANCES.DIAGNOSTICS_DB.openAsync();

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
            TickEvent.SERVER_POST.register(ON_SLAVE_LATCH_TICK);
            // Task #54 (v2.1.0) — share symbol store (slave: mirror mode, populated by S2S sync).
            INSTANCES.SHARE_SYMBOL_STORE = new net.kroia.banksystem.banking.company.ShareSymbolStore(INSTANCES.LOGGER);
            INSTANCES.SHARE_SYMBOL_STORE.open(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT), true);
        }
        else
        {
            INSTANCES.SERVER_BANK_MANAGER = BankManager.createMaster();
            INSTANCES.COMMAND_HANDLER = BankSystemCommands.createMaster();

            snapshotTickCounter = 0;
            retentionSweepTickCounter = 0;
            payoutTickCounter = 0;
            foreignBindingCheckPending = true;
            DatabaseManager.setBackend(INSTANCES);
            INSTANCES.DATABASE_MANAGER = new DatabaseManager();
            INSTANCES.DATABASE_MANAGER.connectToDatabase(server);
            INSTANCES.BALANCE_HISTORY_MANAGER = new BalanceHistoryManager(INSTANCES.DATABASE_MANAGER);
            // Task #44 (v2.1.0) — transaction ledger writer/reader.
            INSTANCES.TRANSACTION_LOG_MANAGER =
                    new net.kroia.banksystem.data.table.TransactionLogManager(INSTANCES.DATABASE_MANAGER);
            INSTANCES.SUSPICION_FLAG_MANAGER =
                    new net.kroia.banksystem.data.table.SuspicionFlagManager(INSTANCES.DATABASE_MANAGER);
            // Task #45 (v2.1.0) — payout history writer + IPayoutManager impl.
            INSTANCES.PAYOUT_HISTORY_MANAGER =
                    new net.kroia.banksystem.data.table.PayoutHistoryManager(INSTANCES.DATABASE_MANAGER);
            INSTANCES.PAYOUT_MANAGER =
                    new net.kroia.banksystem.banking.company.PayoutManagerImpl(INSTANCES);
            // Task #49 (v2.1.0) — dividend distributor (master-only).
            INSTANCES.DIVIDEND_PAYER =
                    new net.kroia.banksystem.banking.share.DividendPayer(INSTANCES);
            // Task #52 (v2.1.0) — dividend event history store.
            INSTANCES.DIVIDEND_HISTORY_STORE = new net.kroia.banksystem.banking.company.DividendHistoryStore(INSTANCES.LOGGER);
            INSTANCES.DIVIDEND_HISTORY_STORE.open(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT));
            // Task #54 (v2.1.0) — share symbol store (master: authoritative).
            INSTANCES.SHARE_SYMBOL_STORE = new net.kroia.banksystem.banking.company.ShareSymbolStore(INSTANCES.LOGGER);
            INSTANCES.SHARE_SYMBOL_STORE.open(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT), false);
            // On revision bump, push manifest to all clients and all slaves, then push all bytes to slaves.
            INSTANCES.SHARE_SYMBOL_STORE.setBroadcastListener(() -> {
                if (server.isRunning()) {
                    net.kroia.banksystem.networking.general.S2CShareSymbolManifestPacket
                            .broadcastToAll(server, INSTANCES.SHARE_SYMBOL_STORE);
                    if (net.kroia.modutilities.networking.multi_server.MultiServerManager.isRunning()
                            && net.kroia.modutilities.networking.multi_server.MultiServerManager.isMaster()) {
                        net.kroia.banksystem.networking.multi_server.S2SShareSymbolManifestPacket
                                .broadcastToSlaves(INSTANCES.SHARE_SYMBOL_STORE);
                        for (net.kroia.banksystem.banking.company.ShareSymbolStore.SymbolEntry e
                                : INSTANCES.SHARE_SYMBOL_STORE.getEntries()) {
                            byte[] bytes = INSTANCES.SHARE_SYMBOL_STORE.getSymbolBytes(e.id());
                            if (bytes != null) {
                                net.kroia.banksystem.networking.multi_server.S2SShareSymbolDataPacket
                                        .broadcastChunksToSlaves(e.sha256(), bytes);
                            }
                        }
                    }
                }
            });

            // Task #41 (v2.0.7): one-shot deprecation WARN for the old flat-cap setting.
            // Fires only when the user still has a non-zero cap on disk — the tiered
            // retention model has fully superseded this cap. Placed inside onServerStart
            // so it runs exactly once per boot (not on every snapshot tick).
            if (INSTANCES.SERVER_SETTINGS.UTILITIES.BALANCE_SNAPSHOT_MAX_RECORDS_PER_ITEM.get() > 0) {
                INSTANCES.LOGGER.warn("BALANCE_SNAPSHOT_MAX_RECORDS_PER_ITEM is deprecated as of "
                        + "v2.0.7 — the tiered retention model supersedes the flat cap. This "
                        + "setting is now ignored. Set it to 0 in settings.json to silence this "
                        + "warning; the sweep cadence is controlled by "
                        + "BALANCE_HISTORY_RETENTION_SWEEP_MINUTES.");
            }

            TickEvent.SERVER_POST.register(ON_SERVER_TICK);
            BankSystemDiscord.register(); // Task #119: every start, even with the feed off, so the config file exists

            // Save the data when the game saves the world
            LifecycleEvent.SERVER_LEVEL_SAVE.register(ON_LEVEL_SAVE);
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
        singleplayerRefusal = null;
        try {
            loadDataFromFiles(server);
        } catch (BankSystemStartupAbortException e) {
            refuseStartup(server, e);
            return; // BANKSYSTEM_SETUP_COMPLETED is not fired: dependent mods stay down too
        }

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
            // Task #41 (v2.0.7): run the tiered-retention sweep once BEFORE the first
            // snapshot of this session. Existing histories from the flat-cap era (up to
            // 1440 rows/item in the last 24h) are already inside the untouched 0..24h band
            // and stay put; older rows from long-running worlds get downsampled into the
            // tiered shape on this first pass. Fire-and-forget on the DB thread — the
            // async future is not awaited so onServerStart never blocks on SQL.
            INSTANCES.BALANCE_HISTORY_MANAGER.applyTieredRetention(System.currentTimeMillis());
            takeBalanceSnapshot();
        }

        AdminFlagSync.migrateIfNeeded(); // Task #107: one-time push of admins to LuckPerms (master, setting on)
        // api.MoneyFormat is shared with StockMarketPlaceholders: apply the pattern even with placeholders off.
        net.kroia.banksystem.api.MoneyFormat.setPattern(INSTANCES.SERVER_SETTINGS.INTEGRATIONS.MONEY_FORMAT.get());
        if (INSTANCES.SERVER_SETTINGS.INTEGRATIONS.PLACEHOLDERS_ENABLED.get())
            BankSystemPlaceholders.start(INSTANCES); // TAB / Placeholder API via ModUtilities
        INSTANCES.SERVER_EVENTS.BANKSYSTEM_SETUP_COMPLETED.notifyListeners();
        // BS-195: after setup-complete, so dependent mods have registered and loaded their data.
        if (!INSTANCES.isSlaveServer && !ItemIDManager.getLastRemovedMissing().isEmpty())
            INSTANCES.SERVER_EVENTS.ITEM_IDS_REMOVED.notifyListeners(ItemIDManager.getLastRemovedMissing());
    }

    /**
     * What the singleplayer client shows after BankSystem refused to load the world (BS-195).
     * {@code lines}: missing namespaces with counts, or merge groups. {@code pendingMerges}: for
     * missing mods, the merges the next successful load would apply (BS-250). {@code worldDir}: the
     * world folder, for the screen's one-shot confirm button and for reopening the world.
     */
    public record StartupRefusal(Kind kind, int count, List<String> lines, List<String> pendingMerges, Path worldDir) {
        public enum Kind { MISSING_MODS, MERGE, OTHER }
    }

    /** Set on the integrated server's thread, consumed by the client tick (same JVM). */
    private static volatile StartupRefusal singleplayerRefusal = null;

    public static @Nullable StartupRefusal getSingleplayerRefusal() {
        return singleplayerRefusal;
    }

    public static void clearSingleplayerRefusal() {
        singleplayerRefusal = null;
    }

    /**
     * BS-195: a load guard refused this world. Instead of letting the exception crash the
     * server (crash report; in singleplayer the whole game closes), log the report and stop
     * the server cleanly. Saves stay prohibited (set by the data handler). Dedicated: halt
     * right away. Integrated: halt on the first tick — the client waits in its loading loop
     * until the server has ticked once, so halting earlier would hang it — and hand the
     * client a {@link StartupRefusal}; its tick shows the explanation screen once the server
     * thread has ended.
     */
    private static void refuseStartup(MinecraftServer server, BankSystemStartupAbortException e) {
        INSTANCES.LOGGER.error("BankSystem refused to load this world. Stopping the server; nothing was saved.\n"
                + e.getMessage());
        TickEvent.SERVER_POST.unregister(ON_SERVER_TICK);
        LifecycleEvent.SERVER_LEVEL_SAVE.unregister(ON_LEVEL_SAVE);
        if (server.isDedicatedServer()) {
            server.halt(false);
            return;
        }
        StartupRefusal.Kind kind = StartupRefusal.Kind.OTHER;
        int count = 0;
        List<String> lines = new ArrayList<>();
        List<String> pendingMerges = List.of();
        if (e instanceof ItemIDMissingModsException missing) {
            kind = StartupRefusal.Kind.MISSING_MODS;
            count = missing.missingCount;
            missing.missingNamespaces.forEach((ns, n) -> lines.add(ns + " (" + n + ")"));
            pendingMerges = missing.pendingMerges;
        } else if (e instanceof ItemIDMergeAbortedException merge) {
            kind = StartupRefusal.Kind.MERGE;
            count = merge.summary.size();
            lines.addAll(merge.summary);
        }
        singleplayerRefusal = new StartupRefusal(kind, count, List.copyOf(lines), pendingMerges,
                server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize());
        server.tell(new net.minecraft.server.TickTask(server.getTickCount(), () -> server.halt(false))); // mc<=1.21.1
        //server.schedule(new net.minecraft.server.TickTask(server.getTickCount(), () -> server.halt(false))); // mc>=1.21.2
    }

    // Called from the server side
    public static void onServerStop(MinecraftServer server) {
        BankSystemPlaceholders.stop();
        MultiServerManager.cleanup();

        if (!INSTANCES.isSlaveServer) {
            TickEvent.SERVER_POST.unregister(ON_SERVER_TICK);
            LifecycleEvent.SERVER_LEVEL_SAVE.unregister(ON_LEVEL_SAVE);
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
            TickEvent.SERVER_POST.unregister(ON_SLAVE_LATCH_TICK);
        }
        net.kroia.banksystem.data.CreativeNoteFlow.flushAll(); // BS-227: before the diagnostics DB closes
        // Task #123: after the shutdown save above, so its bank_last_save_ms write is drained too.
        if (INSTANCES.DIAGNOSTICS_DB != null) {
            INSTANCES.DIAGNOSTICS_DB.close();
        }
        INSTANCES.DIAGNOSTICS_DB = null;

        if (INSTANCES.SERVER_BANK_MANAGER != null
                && INSTANCES.SERVER_BANK_MANAGER.getSync() instanceof net.kroia.banksystem.banking.bankmanager.ServerBankManager serverBankManager)
            serverBankManager.shutdown();
        INSTANCES.SERVER_BANK_MANAGER = null;
        INSTANCES.SERVER_DATA_HANDLER = null;
        INSTANCES.SERVER_SETTINGS = null;
        INSTANCES.COMMAND_HANDLER = null;
        INSTANCES.DATABASE_MANAGER = null;
        INSTANCES.BALANCE_HISTORY_MANAGER = null;
        INSTANCES.TRANSACTION_LOG_MANAGER = null;
        INSTANCES.SUSPICION_FLAG_MANAGER = null;
        INSTANCES.PAYOUT_HISTORY_MANAGER = null;
        INSTANCES.PAYOUT_MANAGER = null;
        INSTANCES.DIVIDEND_PAYER = null;
        if (INSTANCES.DIVIDEND_HISTORY_STORE != null) {
            INSTANCES.DIVIDEND_HISTORY_STORE.close();
        }
        INSTANCES.DIVIDEND_HISTORY_STORE = null;
        if (INSTANCES.SHARE_SYMBOL_STORE != null) {
            INSTANCES.SHARE_SYMBOL_STORE.close();
        }
        INSTANCES.SHARE_SYMBOL_STORE = null;
        INSTANCES.isSlaveServer = false;
        snapshotTickCounter = 0;
        retentionSweepTickCounter = 0;
        payoutTickCounter = 0;
        ItemIDManager.clear();
        // Drop the world-load tag snapshot: the next world/server captures its own freshly
        // bound tags (see VolatileItemComponents#captureTagSnapshot()).
        VolatileItemComponents.resetTagSnapshot();
        // Reset the external-currency binding table so a subsequent world load starts
        // from a clean slate (see BankAccountBindings#clear).
        if (INSTANCES.BANK_ACCOUNT_BINDINGS != null) {
            INSTANCES.BANK_ACCOUNT_BINDINGS.clear();
        }
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
        // Task #39 (v2.0.7): ATM Money Converter tab. If the player disconnects
        // with a nonzero converter cache, drop the remaining balance at their feet
        // as the minimum-item banknote split so no value is silently lost. This is
        // the authoritative safety net for crashed / force-killed clients that
        // never send the ConverterDropAll packet on GUI close. Cache is per-server;
        // this hook runs on whichever server the player was connected to.
        if (player != null) {
            net.kroia.banksystem.data.CreativeNoteFlow.flush(player.getUUID()); // BS-227
            try {
                net.kroia.banksystem.networking.entity.ConverterDropAllPacket.dropAllForPlayer(player);
            } catch (Throwable t) {
                if (INSTANCES.LOGGER != null) {
                    INSTANCES.LOGGER.warn("Converter auto-drop-on-disconnect failed for player "
                            + player.getUUID() + ": " + t.getMessage());
                }
            }
        }
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
        // Task #54 (v2.1.0) — purge in-flight symbol sync state on disconnect.
        net.kroia.banksystem.client.company.ClientSymbolRegistry.clear();
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

        // Task #146: once per start, after every mod's SERVER_STARTED handler (provider data loaded).
        if (foreignBindingCheckPending && INSTANCES.SERVER_BANK_MANAGER != null
                && INSTANCES.SERVER_BANK_MANAGER.getSync() instanceof ServerBankManager bm) {
            foreignBindingCheckPending = false;
            bm.suspendForeignBindings();
        }

        if (INSTANCES.BALANCE_HISTORY_MANAGER != null && INSTANCES.SERVER_SETTINGS != null) {
            snapshotTickCounter++;
            long intervalTicks = INSTANCES.SERVER_SETTINGS.UTILITIES.BALANCE_SNAPSHOT_INTERVAL_MINUTES.get() * 1200L;
            if (intervalTicks > 0 && snapshotTickCounter >= intervalTicks) {
                snapshotTickCounter = 0;
                takeBalanceSnapshot();
            }

            // Task #41 (v2.0.7): tiered-retention sweep, on its own (longer) cadence.
            // Running the sweep every snapshot tick is wasteful — the SQL still scans each
            // affected age band even when it's already downsampled. Default 60 minutes;
            // 0 disables retention entirely (WARNING: unbounded growth). The first sweep
            // of a session is triggered directly from onServerStart above so the wait
            // window here starts from a clean baseline.
            retentionSweepTickCounter++;
            long sweepIntervalTicks = INSTANCES.SERVER_SETTINGS.UTILITIES
                    .BALANCE_HISTORY_RETENTION_SWEEP_MINUTES.get() * 1200L;
            if (sweepIntervalTicks > 0 && retentionSweepTickCounter >= sweepIntervalTicks) {
                retentionSweepTickCounter = 0;
                INSTANCES.BALANCE_HISTORY_MANAGER.applyTieredRetention(System.currentTimeMillis());
            }

            // Task #114 (v2.2.0): wealth-jump sweep over the WEALTH series.
            wealthSweepTickCounter++;
            long wealthTicks = INSTANCES.SERVER_SETTINGS.AUDIT.WEALTH_SWEEP_MINUTES.get() * 1200L;
            if (wealthTicks > 0 && wealthSweepTickCounter >= wealthTicks) {
                wealthSweepTickCounter = 0;
                if (INSTANCES.SERVER_BANK_MANAGER != null && INSTANCES.SERVER_BANK_MANAGER.getSync() instanceof ServerBankManager bm) {
                    long now = System.currentTimeMillis();
                    List<Integer> accounts = bm.getAllAccountNumbers();
                    net.kroia.banksystem.banking.ledger.SuspicionChecks.sweepWealth(accounts, now);
                    // Task #112: money-supply growth flag
                    net.kroia.banksystem.banking.ledger.SuspicionChecks.checkMoneySupply(accounts, bm.getMoneySupplyRaw(), now);
                }
            }
            // Task #113: daily digest for the previous day, first tick after the date changes
            net.kroia.banksystem.banking.ledger.AuditDigest.tick(System.currentTimeMillis());
        }

        // Task #45 (v2.1.0) — recurring payout evaluator. Master-only; the executor
        // internally short-circuits on slave/pre-startup state.
        payoutTickCounter++;
        if (payoutTickCounter % net.kroia.banksystem.banking.company.PayoutExecutor.PAYOUT_TICK_INTERVAL == 0) {
            net.kroia.banksystem.banking.company.PayoutExecutor.tick(payoutTickCounter, INSTANCES);
        }
        BankSystemDiscord.flush(); // Task #119: coalesced dividend / disallow posts of this tick
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
            // Pruning is done by the tiered-retention sweep in onServerTick above
            // (every BALANCE_HISTORY_RETENTION_SWEEP_MINUTES), not per snapshot.
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
     * One-time registration of all external currency providers during mod init
     * (Task #34+, v2.0.5). Each adapter checks {@code Platform.isModLoaded(...)}
     * before registering; absent mods are skipped without error.
     */
    private void registerExternalCurrencyProviders() {
        // Numismatics adapter (Task #34)
        try {
            if (dev.architectury.platform.Platform.isModLoaded("numismatics")) {
                registerCurrencyProvider(net.kroia.banksystem.integration.numismatics.NumismaticsProvider.INSTANCE);
            }
        } catch (NoClassDefFoundError e) {
            // Numismatics classes couldn't load — mod was removed or class moved.
            // isAvailable() will also return false; no registration happens.
            if (INSTANCES.LOGGER != null)
                INSTANCES.LOGGER.debug("Numismatics adapter skipped: classes not resolvable.");
        }

        // Lightman's Currency adapter (Task #36)
        try {
            if (dev.architectury.platform.Platform.isModLoaded("lightmanscurrency")) {
                registerCurrencyProvider(net.kroia.banksystem.integration.lightmanscurrency.LightmansCurrencyProvider.INSTANCE);
            }
        } catch (NoClassDefFoundError e) {
            // LC classes couldn't load — mod was removed or class moved.
            // isAvailable() will also return false; no registration happens.
            if (INSTANCES.LOGGER != null)
                INSTANCES.LOGGER.debug("Lightman's Currency adapter skipped: classes not resolvable.");
        }
    }

    @Override
    public void registerCurrencyProvider(@Nullable ExternalCurrencyProvider provider) {
        if (provider == null) return;
        String id = provider.providerId();
        if (id == null || id.isEmpty()) {
            if (INSTANCES.LOGGER != null)
                INSTANCES.LOGGER.warn("registerCurrencyProvider ignored: provider " + provider.getClass().getName()
                        + " returned a null / empty providerId()");
            return;
        }
        ExternalCurrencyProvider prev = CURRENCY_PROVIDERS.put(id, provider);
        if (INSTANCES.LOGGER != null) {
            if (prev == null)
                INSTANCES.LOGGER.info("Registered external currency provider '" + id + "' ("
                        + provider.getClass().getName() + ")");
            else
                INSTANCES.LOGGER.info("Replaced external currency provider '" + id + "' ("
                        + prev.getClass().getName() + " -> " + provider.getClass().getName() + ")");
        }
    }

    @Override
    public boolean unregisterCurrencyProvider(@Nullable String providerId) {
        if (providerId == null || providerId.isEmpty()) return false;
        ExternalCurrencyProvider removed = CURRENCY_PROVIDERS.remove(providerId);
        if (removed != null && INSTANCES.LOGGER != null) {
            INSTANCES.LOGGER.info("Unregistered external currency provider '" + providerId + "' ("
                    + removed.getClass().getName() + ")");
        }
        return removed != null;
    }

    @Override
    public Collection<ExternalCurrencyProvider> getCurrencyProviders() {
        // Defensive snapshot — callers must not be able to mutate the registry through
        // the returned view, and iteration must be safe against concurrent registration.
        return CURRENCY_PROVIDERS.values().stream()
                .filter(p -> providerEnabled(p.providerId())).toList();
    }

    @Override
    public @Nullable ExternalCurrencyProvider getCurrencyProvider(@Nullable String providerId) {
        if (providerId == null || providerId.isEmpty() || !providerEnabled(providerId)) return null;
        return CURRENCY_PROVIDERS.get(providerId);
    }

    /** Task #119: a provider switched off in Integrations behaves exactly like its mod being absent. */
    private static boolean providerEnabled(String id) {
        BankSystemModSettings s = INSTANCES.SERVER_SETTINGS;
        if (s == null) return true; // before world load / client
        return switch (id) {
            case "numismatics" -> s.INTEGRATIONS.NUMISMATICS_ENABLED.get();
            case "lightmanscurrency" -> s.INTEGRATIONS.LIGHTMANS_CURRENCY_ENABLED.get();
            default -> true; // third-party API providers are not gated
        };
    }

    /**
     * Task #45 (v2.1.0) — payout manager accessor. On slaves / before startup completes,
     * returns a fail-closed shim (a fresh {@code PayoutManagerImpl} bound to the shared
     * {@link Instances}) so callers never have to null-check.
     */
    @Override
    public net.kroia.banksystem.api.payout.IPayoutManager getPayoutManager() {
        if (INSTANCES.PAYOUT_MANAGER == null) {
            INSTANCES.PAYOUT_MANAGER = new net.kroia.banksystem.banking.company.PayoutManagerImpl(INSTANCES);
        }
        return INSTANCES.PAYOUT_MANAGER;
    }

    /** Task #109 (v2.1.0) — transaction ledger writer. Always non-null. */
    @Override
    public net.kroia.banksystem.api.ledger.ITransactionLedger getLedger() {
        return net.kroia.banksystem.banking.ledger.TransactionLedger.INSTANCE;
    }

    /** Task #124 — note flow log (diagnostics DB). Always non-null. */
    @Override
    public net.kroia.banksystem.api.diagnostics.INoteFlowLog getNoteFlowLog() {
        return net.kroia.banksystem.data.NoteFlowLog.INSTANCE;
    }

    /**
     * Task #49 (v2.1.0) — dividend payer accessor. Always non-null; on slaves / before
     * startup completes the returned instance short-circuits with
     * {@link net.kroia.banksystem.api.PayDividendResult.Reason#NOT_MASTER}. Callers on
     * the slave should hop through the ARRS {@code AsyncCompanyManager.PAY_DIVIDEND}
     * function instead of this shim.
     */
    @Override
    public net.kroia.banksystem.api.dividend.IDividendPayer getDividendPayer() {
        if (INSTANCES.DIVIDEND_PAYER == null) {
            INSTANCES.DIVIDEND_PAYER = new net.kroia.banksystem.banking.share.DividendPayer(INSTANCES);
        }
        return INSTANCES.DIVIDEND_PAYER;
    }

    /**
     * Task #50 (v2.1.0) — client-side visual lookup. Delegates to {@code ShareVisualCache}
     * (populated by S2C visual packets) and self-heals on cache miss via
     * {@code ShareVisualCache.tryLookup}. On dedicated servers the cache is unpopulated
     * so every call returns {@code null}, which matches the interface contract.
     */
    private static final net.kroia.banksystem.api.company.IBankSystemVisualLookup VISUAL_LOOKUP =
            itemId -> {
                Integer companyId = net.kroia.banksystem.minecraft.item.custom.share.StampedShareItem
                        .getCompanyIdForItemID(itemId);
                if (companyId == null) return null;
                if (!net.kroia.banksystem.client.cache.ShareVisualCache.has(companyId)) {
                    net.kroia.banksystem.client.cache.ShareVisualCache.tryLookup(companyId);
                    return null;
                }
                net.kroia.banksystem.banking.company.ShareVisuals internal =
                        net.kroia.banksystem.client.cache.ShareVisualCache.getVisualsOrPlaceholder(companyId);
                return new net.kroia.banksystem.api.company.ShareVisuals(
                        internal.getBgLayer().symbolId(),
                        internal.getBgLayer().tint(),
                        internal.getFgLayer().symbolId(),
                        internal.getFgLayer().tint(),
                        net.minecraft.network.chat.Component.literal(internal.getDisplayName()),
                        net.minecraft.network.chat.Component.literal(internal.getDescription()),
                        net.kroia.banksystem.client.cache.ShareVisualCache.getIssued(companyId),
                        net.kroia.banksystem.client.cache.ShareVisualCache.getMax(companyId)
                );
            };

    @Override
    public net.kroia.banksystem.api.company.IBankSystemVisualLookup getVisualLookup() {
        return VISUAL_LOOKUP;
    }

    /**
     * Task #50 R4 (v2.1.0) — client-side share icon renderer. Returns the concrete
     * implementation wired in {@code BankSystemColorHandlers}; on dedicated servers
     * the client class is never loaded and the accessor returns {@code null}, matching
     * the interface contract.
     */
    @Override
    public net.kroia.banksystem.api.company.IShareIconRenderer getShareIconRenderer() {
        try {
            return net.kroia.banksystem.client.render.BankSystemColorHandlers.SHARE_ICON_RENDERER;
        } catch (Throwable t) {
            // Dedicated-server / class-not-available fallback — silent per contract.
            return null;
        }
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
        boolean created;
        if(config.isMaster)
        {
            INSTANCES.isSlaveServer = false;
            created = MultiServerManager.createMaster(server, config.sharedSecret, config.masterTcpPort,
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
            created = MultiServerManager.createSlave(server, config.sharedSecret, config.slaveID, config.masterHost, config.masterTcpPort,
                    BankSystemModBackend::onSlaveConnectionAccepted,
                    BankSystemModBackend::onSlaveConnectionFailed,
                    BankSystemModBackend::onSlaveConnectionLost,
                    BankSystemModBackend::onSlaveDisconnected);
            MultiServerManager.start();
        }
        // Task #176: false = another mod created the MultiServerManager first. ModUtilities routes
        // master-bound requests by its own role, so a role mismatch makes this slave handle them locally.
        // We keep running in the configured role: the bank manager is already built for it and
        // switching roles here would put a master database on a slave world.
        if(INSTANCES.isSlaveServer != MultiServerManager.isSlave())
            INSTANCES.LOGGER.error("[BankSystemModBackend] Multi-server misconfiguration: BankSystem is configured as "
                    + (INSTANCES.isSlaveServer ? "slave" : "master") + " but another mod already set up this server as "
                    + (MultiServerManager.isSlave() ? "slave" : "master")
                    + ". Cross-server bank requests will fail or run on the wrong server. Give every mod's multi-server config the same role.");
        else if(!created)
            INSTANCES.LOGGER.warn("[BankSystemModBackend] Another mod already set up the multi-server connection; "
                    + "reusing it. BankSystem's connect/disconnect callbacks will not fire.");
    }
    private static void onSlaveConnectionAccepted()
    {
        // Task #23 / #167: drop the negative / in-flight ItemID cache on (re)connect, not on every
        // failed reconnect attempt: the master might have a different registry now (mod update
        // while we were away), and each unknown item gets one fresh retry per reconnect.
        ItemIDManager.clearSlaveNegativeCacheOnDisconnect();
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
        // Notify the master that players are on this server to create the personal bank account if the players don't have one.
        // First drain any joins that were queued while the slave→master link was down (silent-drop fix),
        // then iterate currently online players — deduping so a queued player who is still online is not double-forwarded.
        IAsyncBankManager manager = INSTANCES.SERVER_BANK_MANAGER.getAsync();
        Set<UUID> alreadyReplayed = Collections.emptySet();
        if (manager instanceof net.kroia.banksystem.banking.bankmanager.AsyncBankManager asyncMgr) {
            alreadyReplayed = asyncMgr.flushPendingJoins();
        }
        ArrayList<ServerPlayer> players = ServerPlayerUtilities.getOnlinePlayers();
        for(ServerPlayer player : players)
        {
            if (alreadyReplayed.contains(player.getUUID())) continue;
            manager.onPlayerJoinAsync(player.getUUID(), player.getName().getString());
        }

        // Task #54 (v2.1.0) — slave requests the full company visuals+info snapshot
        // from master on every handshake and populates SlaveCompanyMirror. Failures
        // (master unreachable, timeout) leave the mirror empty — join-time bulk sync
        // then silently skips and per-id ARRS self-heal covers the miss.
        try {
            net.kroia.banksystem.banking.company.AsyncCompanyManager.listAllCompanyVisualsAsync()
                    .whenComplete((out, err) -> {
                        if (err != null || out == null) return;
                        net.kroia.banksystem.banking.company.SlaveCompanyMirror.putAll(out.entries());
                    });
        } catch (Throwable t) {
            if (INSTANCES.LOGGER != null) {
                INSTANCES.LOGGER.warn("[BankSystemModBackend] listAllCompanyVisualsAsync request failed: " + t);
            }
        }

        // Task #54 (v2.1.0) — slave requests current symbol manifest from master on handshake,
        // then pulls bytes for any entries it doesn't have on disk yet.
        try {
            net.kroia.banksystem.banking.company.AsyncCompanyManager.getSymbolManifestAsync()
                    .whenComplete((out, err) -> {
                        if (err != null || out == null || INSTANCES.SHARE_SYMBOL_STORE == null) return;
                        INSTANCES.SHARE_SYMBOL_STORE.mirrorApplyManifest(out.revision(), out.entries());
                        java.util.List<byte[]> missing = new java.util.ArrayList<>();
                        for (net.kroia.banksystem.banking.company.ShareSymbolStore.SymbolEntry e : out.entries()) {
                            if (INSTANCES.SHARE_SYMBOL_STORE.getSymbolBytes(e.id()) == null) {
                                missing.add(e.sha256());
                            }
                        }
                        if (!missing.isEmpty()) {
                            net.kroia.banksystem.banking.company.AsyncCompanyManager.pullSymbolBytesAsync(missing);
                        }
                    });
        } catch (Throwable t) {
            if (INSTANCES.LOGGER != null) {
                INSTANCES.LOGGER.warn("[BankSystemModBackend] getSymbolManifestAsync request failed: " + t);
            }
        }

        // Notify dependent mods (e.g. StockMarket) that the slave→master
        // handshake has completed and BankSystem's async forwarder is now
        // usable. Fire AFTER the SERVER_BANK_MANAGER null guard above so
        // listeners never see a partially torn-down state. Fired on a Netty
        // event-loop thread — see IBankSystemEvents contract.
        // Arm the loss latch (Issue #64) so the next real disconnect runs onSlaveLinkDown
        // exactly once. Re-armed on every reconnect.
        slaveConnectionActive.set(true);
        if (INSTANCES.SERVER_EVENTS != null)
            INSTANCES.SERVER_EVENTS.SLAVE_CONNECTION_ACCEPTED.notifyListeners();
    }
    private static void onSlaveConnectionFailed(SlaveServerClient.ConnectionEstablishState state)
    {
        if (INSTANCES.LOGGER != null) {
            INSTANCES.LOGGER.warn("[BankSystemModBackend] slave→master handshake failed: "
                    + (state != null ? state.name() : "unknown"));
        }
    }
    private static void onSlaveConnectionLost(Throwable reason)
    {
        onSlaveLinkDown();
    }
    private static void onSlaveDisconnected()
    {
        // Clean-disconnect path (local disconnect() call): same semantics, master is unreachable.
        onSlaveLinkDown();
    }
    /**
     * Shared by the lost / disconnected callbacks. Issue #64 / Task #167: the auto-reconnect
     * loop re-enters onSlaveConnectionLost on every failed attempt (Netty thread), so all work
     * here runs once per link-down period, on the connected->down edge only.
     */
    private static void onSlaveLinkDown()
    {
        if (!slaveConnectionActive.compareAndSet(true, false))
            return;
        // Task #54 (v2.1.0) — drop the slave-side company mirror so a stale snapshot
        // cannot leak across reconnects when the master might have mutated companies.
        net.kroia.banksystem.banking.company.SlaveCompanyMirror.clear();
        // Pair to onSlaveConnectionAccepted: tell dependent mods that master is no longer
        // reachable so any cache populated from the last handshake is now stale. On reconnect,
        // SLAVE_CONNECTION_ACCEPTED fires again and caches can be re-fetched.
        if (INSTANCES.SERVER_EVENTS != null)
            INSTANCES.SERVER_EVENTS.SLAVE_CONNECTION_LOST.notifyListeners();
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
