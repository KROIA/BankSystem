package net.kroia.banksystem.util;


import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.IBankSystemDataHandler;
import net.kroia.banksystem.banking.bankmanager.ServerBankManager;
import net.kroia.banksystem.minecraft.compat.OldBankDataLoader;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.kroia.modutilities.persistence.DataPersistence;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class BankSystemDataHandler extends DataPersistence implements IBankSystemDataHandler {

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;

    private final String ITEM_IDS_FILE_NAME = "ItemIDs.nbt";
    private final String BANK_DATA_FOLDER_NAME = "Bank_data";
    private final String META_DATA_FILE_NAME = "Meta_data.nbt";
    private final String BANK_SETTINGS_FILE_NAME = "settings.json";
    public static final Path BASE_PATH = Path.of("data", "BankSystem");
    private static final Path OLD_PATH = Path.of("Finance", "BankSystem");
    private long tickCounter = 0;
    private int lastPlayerCount = 0;
    private static boolean globalSettingsLoaded = false;
    private static boolean bankDataLoaded = false;

    /**
     * Load state of one persisted subsystem for the current session.
     * <p>
     * Used to gate every save: a subsystem may only be written back to disk when its data
     * was either successfully loaded this session ({@link #LOADED}) or genuinely absent on
     * disk at load time ({@link #FRESH} — a brand-new world, where saving creates the very
     * first file). While a subsystem is {@link #NOT_LOADED} (load never ran, or ran and
     * failed on existing data), saving would overwrite the on-disk data with empty or
     * half-initialized in-memory state — exactly the failure that wiped {@code Bank_data/}
     * when the ItemID merge guard aborted startup and the vanilla crash-save
     * ({@code SERVER_LEVEL_SAVE}) fired against the still-empty bank manager.
     */
    private enum LoadState {
        /** Load never ran, or ran and FAILED while data exists on disk — never save. */
        NOT_LOADED,
        /** No data existed on disk at load time — fresh world, saving may create it. */
        FRESH,
        /** Loaded successfully this session — saving persists real in-memory state. */
        LOADED
    }

    private LoadState metadataLoadState = LoadState.NOT_LOADED;
    private LoadState settingsLoadState = LoadState.NOT_LOADED;
    private LoadState itemIDsLoadState = LoadState.NOT_LOADED;
    private LoadState bankLoadState = LoadState.NOT_LOADED;

    /**
     * Session-wide save kill-switch, set when any startup-abort guard fires
     * ({@link BankSystemStartupAbortException}: the ItemID merge guard, the save-format
     * gate on files written by a newer BankSystem, or the world-repair guard). At that
     * point the ItemID registry is absent or half-initialized in memory and the bank
     * manager is still empty, so NOTHING of this session may ever be saved — including the
     * vanilla crash-save ({@code SERVER_LEVEL_SAVE} during "Saving worlds") and the
     * shutdown save. This flag is what makes the abort reports' "Nothing has been changed
     * or saved" guarantee actually true.
     */
    private boolean savingProhibited = false;

    /**
     * The effective volatile/deposit-gated component set (sorted id strings, see
     * {@link VolatileItemComponents#getEffectiveComponentIds()}) as read from
     * {@code Meta_data.nbt} at load time. {@code null} = the world has no record yet
     * (world created before this guard existed, or fresh world) — the startup merge guard
     * then treats the load as a migration and merges silently.
     */
    private List<String> storedEffectiveComponentSet = null;

    /**
     * The effective component set that is currently applied to the ItemID registry.
     * Initialized from {@link #storedEffectiveComponentSet} at load (so slaves and
     * untouched worlds re-save what they read) and overwritten by the startup merge guard
     * after every successful normalization pass. Persisted by {@link #save_metadata()}.
     */
    private List<String> appliedEffectiveComponentSet = null;
    public BankSystemDataHandler() {
        super(JsonFormat.PRETTY, NbtFormat.COMPRESSED, BASE_PATH);
        bankDataLoaded = false;
        globalSettingsLoaded = false;
        setLogger(this::error, this::error, this::info, this::warn);
    }
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BankSystemDataHandler.BACKEND_INSTANCES = backend;


    }

    public static boolean isGlobalSettingsLoaded() {
        return globalSettingsLoaded;
    }
    public static void resetGlobalDataLoaded() {
        globalSettingsLoaded = false;
    }
    public static boolean isBankDataLoaded() {
        return bankDataLoaded;
    }
    public static void resetBankDataLoaded() {
        bankDataLoaded = false;
    }

    public void tickUpdate()
    {
        tickCounter++;
        if(tickCounter >= BACKEND_INSTANCES.SERVER_SETTINGS.UTILITIES.SAVE_INTERVAL_MINUTES.get() * 1200) // 1 minute = 1200 ticks
        {
            tickCounter = 0;
            // Check if any player is online
            int playerCount = ServerPlayerUtilities.getOnlinePlayers().size();
            if(playerCount > 0 || lastPlayerCount > 0) {
                lastPlayerCount = playerCount;
                saveAll();
            }
        }
    }

    @Override
    public void setLevelSavePath(Path path) {
        migrateFromOldPath(path);
        super.setLevelSavePath(path);
        createSaveFolder();
    }

    private void migrateFromOldPath(Path worldRoot) {
        Path oldDir = worldRoot.resolve(OLD_PATH);
        if (!Files.isDirectory(oldDir)) {
            return;
        }

        info("Found old data at " + oldDir + ", migrating to new location...");

        Path backupDir = worldRoot.resolve("Finance").resolve("BankSystemBackup_" + System.currentTimeMillis());
        try {
            copyFolder(oldDir, backupDir);
            info("Created backup of old data at: " + backupDir);
        } catch (IOException e) {
            error("Failed to backup old data, aborting migration", e);
            return;
        }

        Path newDir = worldRoot.resolve(BASE_PATH);
        try {
            if (!Files.exists(newDir)) {
                Files.createDirectories(newDir);
            }
            copyFolder(oldDir, newDir);
            info("Migrated old data from " + oldDir + " to " + newDir);
        } catch (IOException e) {
            error("Failed to copy old data to new location", e);
            return;
        }

        try {
            deleteFolderContents(oldDir);
            Files.deleteIfExists(oldDir);
            info("Deleted old data directory: " + oldDir);
        } catch (IOException e) {
            error("Failed to delete old data directory: " + oldDir, e);
        }
    }

    /**
     * Central save gate: decides whether one subsystem may be written to disk this session.
     * A subsystem is saveable only when it was successfully loaded ({@link LoadState#LOADED})
     * or its data was genuinely absent at load time ({@link LoadState#FRESH}), and no
     * session-wide save prohibition (merge-guard abort) is active.
     * Skipped saves are logged as WARN, naming the subsystem and the reason.
     *
     * @param subsystemName human-readable subsystem name for the log message
     * @param state         the subsystem's load state
     * @return {@code true} when saving is allowed
     */
    private boolean canSave(String subsystemName, LoadState state) {
        if (savingProhibited) {
            warn("Skipping save of '" + subsystemName + "': all saves are prohibited for this session " +
                    "(startup was aborted by a BankSystem load guard — ItemID merge guard, save-format " +
                    "gate, or world-repair guard; see the startup log. On-disk data is left untouched).");
            return false;
        }
        if (state == LoadState.NOT_LOADED) {
            warn("Skipping save of '" + subsystemName + "': it was not successfully loaded this session. " +
                    "Saving now would overwrite the on-disk data with empty/incomplete in-memory state.");
            return false;
        }
        return true;
    }

    @Override
    public boolean saveAll()
    {
        // Merge-guard abort: nothing of this session may be saved (see savingProhibited).
        // This intercepts the crash-save (SERVER_LEVEL_SAVE), the shutdown save and the
        // periodic save-interval path in one place; the individual save_* methods repeat
        // the check for callers that bypass saveAll().
        if (savingProhibited) {
            warn("Skipping BankSystem data save: startup was aborted by a BankSystem load guard " +
                    "(ItemID merge guard, save-format gate, or world-repair guard — see the startup " +
                    "log). Nothing is written so the world data on disk stays exactly as it was.");
            return false;
        }
        debug("Saving BankSystem Mod data...");
        boolean success = true;
        success &= save_metadata();
        success &= save_globalSettings();
        if(!BACKEND_INSTANCES.isSlaveServer) {
            success &= save_bank();
            success &= save_itemIDs();
        }


        if(success) {
            debug("BankSystem Mod data saved successfully.");
        }
        else
            error("Failed to save BankSystem Mod data.");
        return success;
    }

    @Override
    public boolean loadAll()
    {
        debug("Loading BankSystem Mod data...");

        // Snapshot the datapack-tag part of the volatile/deposit-gated component sets.
        // From now on identity normalization uses this world-load view of the tags, so a
        // runtime /reload cannot change ItemID identity mid-session (rejected until restart —
        // see VolatileItemComponents#captureTagSnapshot()).
        VolatileItemComponents.captureTagSnapshot();

        boolean success = true;
        boolean metadataLoaded = load_metadata();
        success &= metadataLoaded;

        // Load (or create) the global settings BEFORE the ItemID registry: the startup merge
        // guard inside load_itemIDs() must know the config-sourced part of the component sets
        // (ADDITIONAL_VOLATILE_COMPONENTS / ADDITIONAL_DEPOSIT_GATED_COMPONENTS) and the
        // CONFIRM_ITEMID_MERGE flag to compare the stored effective set against the current one.
        Path settingsFilePath = getGlobalSettingsFilePath();
        if(!fileExists(settingsFilePath)) {
            warn("ServerBank settings file not found, creating default settings file.");
            // No settings file on disk → genuinely fresh: creating the default file is the
            // one save that must be allowed before a successful load.
            settingsLoadState = LoadState.FRESH;
            success &= save_globalSettings(settingsFilePath);
            // Apply the (default) component lists too: this resets any stale config-sourced
            // set left behind by a previously loaded world in the same session.
            applyVolatileComponentSettings();
        }
        else
            success &= load_globalSettings(settingsFilePath);

        if(!BACKEND_INSTANCES.isSlaveServer) {
            boolean loadInCompatibilityMode = !metadataLoaded;
            success &= load_itemIDs();

            // Register default/registry ItemIDs AFTER the persisted map has loaded so
            // existing items keep their saved shorts (register-if-absent) and only new
            // items get freshly minted appended shorts. Running this before load (the old
            // behaviour) minted fresh low shorts that then collided/merged with the
            // persisted shorts, corrupting item identity (e.g. money 7 -> alias of 6).
            //
            // Skipped while the registration latch is still armed (Task #16): that only
            // happens when ItemIDs.nbt exists but failed to load — minting fresh shorts
            // against a world whose real assignment could not be read is exactly what the
            // latch prevents, and running the pass would just produce ~one refusal per
            // registry item. The recovery flow (backupFailedSubsystemData + saveAll +
            // loadAll retry in BankSystemModBackend.loadDataFromFiles) re-runs this with
            // the latch released.
            if (ItemIDManager.isRegistrationReady()) {
                BACKEND_INSTANCES.ITEM_ID_MANAGER.createDefaultItemIDs();
            } else {
                warn("Skipping default ItemID registration: ItemIDs.nbt exists but failed to "
                        + "load, so the registration latch is still armed. No fresh shorts are "
                        + "minted against the unreadable assignment; the load-failure recovery "
                        + "re-runs this after the unreadable file has been backed up.");
            }

            // check if file exists
            loadInCompatibilityMode |= fileExists(getAbsoluteSavePath("Bank_data.dat"));


            if (loadInCompatibilityMode) {
                success = true;
                // load in compatibility mode
                info("Loading BankSystem Mod data in compatibility mode. This is only needed if you updated the mod to a newer version and the old data is not compatible with the new version.");

                success &= load_bank_compatibilityMode();

                // delete old settings file
                Path oldSettingsFilePath = getAbsoluteSavePath("settings.dat");
                if (fileExists(oldSettingsFilePath)) {
                    try {
                        Files.delete(oldSettingsFilePath);
                        info("Deleted old settings file: " + oldSettingsFilePath);
                    } catch (IOException e) {
                        error("Failed to delete old settings file: " + oldSettingsFilePath, e);
                    }
                }
                Path potentialBankableItemsFile = getAbsoluteSavePath("PotentialBankableItems.json");
                if (fileExists(potentialBankableItemsFile)) {
                    try {
                        Files.delete(potentialBankableItemsFile);
                        info("Deleted old potential bankable items file: " + potentialBankableItemsFile);
                    } catch (IOException e) {
                        error("Failed to delete old potential bankable items file: " + potentialBankableItemsFile, e);
                    }
                }

                if (success) {
                    // Consolidate any healing-merge aliases into the freshly migrated bank
                    // data (see ItemIDManager.consolidatePendingMerges) before re-saving.
                    ItemIDManager.consolidatePendingMerges();
                    saveAll();
                    debug("BankSystem Mod data loaded and saved in new format.");
                } else
                    error("Failed to load BankSystem Mod data.");
                return success;
            }
        }

        if(!BACKEND_INSTANCES.isSlaveServer) {
            success &= load_bank();
            // The startup merge pass inside load_itemIDs() ran BEFORE the bank data existed
            // in memory, so its alias→canonical pairs were queued. Now that the banks are
            // loaded, consolidate balances/locked balances/allowed items under the canonical
            // IDs and fire the ITEM_IDS_MERGED event for dependent mods (no-op when nothing
            // was merged).
            ItemIDManager.consolidatePendingMerges();
            // Renumbering tripwire (Task #16): the load — including default registration,
            // merges and consolidation — is complete; diff the live registry against last
            // session's short→item-name digest and ERROR-report every persisted short that
            // now resolves to a different item. Report-only; silent when identical or when
            // no digest exists yet (older saves / fresh worlds).
            ItemIDManager.reportShortNameDigestMismatches(storedItemIDDigest);
        }



        if(success) {
            debug("BankSystem Mod data loaded successfully.");
            BACKEND_INSTANCES.SERVER_EVENTS.BANK_DATA_LOADED_FROM_FILE.notifyListeners();
        }
        else
            error("Failed to load BankSystem Mod data.");
        return success;
    }

    public boolean save_itemIDs()
    {
        // Save gate: never overwrite ItemIDs.nbt when it was not successfully loaded this
        // session (see LoadState / savingProhibited).
        if (!canSave("ItemIDs", itemIDsLoadState))
            return false;
        boolean success = true;
        CompoundTag tag  = new CompoundTag();
        success = BACKEND_INSTANCES.ITEM_ID_MANAGER.save(tag);

        saveDataCompound(getAbsoluteSavePath(ITEM_IDS_FILE_NAME), tag);

        if(success)
        {
           // BACKEND_INSTANCES.SERVER_EVENTS.BANK_DATA_SAVED_TO_FILE.notifyListeners();
        }
        return success;
    }
    public boolean load_itemIDs()
    {
        Path filePath = getAbsoluteSavePath(ITEM_IDS_FILE_NAME);
        boolean fileWasPresent = fileExists(filePath);
        CompoundTag tag = fileWasPresent ? readDataCompound(filePath) : null;
        try {
            boolean success = tag != null && BACKEND_INSTANCES.ITEM_ID_MANAGER.load(tag);
            if (success) {
                itemIDsLoadState = LoadState.LOADED;
                // Pre-repair backup: when a CONFIRMED world repair was applied during
                // load(), COPY the still-corrupted ItemIDs.nbt aside before the one-shot
                // re-save below overwrites it with the repaired state. A copy (not a move)
                // — the live file must remain in place until the new one is written, so a
                // crash between here and the save leaves a loadable file either way.
                if (ItemIDManager.consumeRepairWasApplied()) {
                    backupPreRepairItemIDs(filePath);
                    // Deferred one-shot flag persist: the repair guard consumed
                    // CONFIRM_ITEMID_REPAIR in memory only — persisting the reset is
                    // deliberately delayed until HERE, after the whole load (including the
                    // merge guard, which can still abort) succeeded. If the load had
                    // aborted, the settings file would keep the flag true and the admin's
                    // confirmation would not be burned without the repair persisting.
                    save_globalSettings();
                }
                // One-shot durability: persist the post-load state immediately when either
                // the alias-table repair pass touched entries (Task #13 Fix D) or the load
                // requires a format-conversion re-save (legacy version-less file,
                // migration-seeded counter, applied world repair). Both consume* helpers
                // clear their flag after reading — idempotent across multiple loadAll()
                // invocations in the same session.
                int repaired = ItemIDManager.consumeLastLoadRepairCount();
                boolean formatResave = ItemIDManager.consumeLastLoadRequiresResave();
                if (repaired > 0 || formatResave) {
                    info("Persisting ItemIDs via one-shot save_itemIDs() ("
                            + (repaired > 0 ? repaired + " alias-table repairs" : "no alias repairs")
                            + (formatResave ? ", format/repair conversion re-save" : "") + ").");
                    save_itemIDs();
                }
            }
            else {
                // Absent file → fresh world (saving may create it); present but
                // unreadable/invalid → never overwrite it with in-memory state.
                itemIDsLoadState = fileWasPresent ? LoadState.NOT_LOADED : LoadState.FRESH;
                // Fresh world: there is nothing to load, so the load phase is complete —
                // release the master-side registration latch so the default registration
                // that follows (createDefaultItemIDs / setupDefaultItems) may mint shorts.
                // A PRESENT but unreadable file keeps the latch armed: its subsystem is
                // save-prohibited anyway, and minting fresh shorts against a world whose
                // real assignment could not be read is exactly what the latch prevents.
                if (!fileWasPresent)
                    ItemIDManager.markRegistryReady();
            }
            return success;
        } catch (BankSystemStartupAbortException e) {
            // A startup-abort guard fired: the merge guard (unconfirmed collapse merge),
            // the format gate (file written by a newer BankSystem), or the world-repair
            // guard (unconfirmed cent-shift repair). In every case the in-memory registry
            // is absent or half-initialized, so ItemIDs deliberately do NOT count as
            // "loaded" — and because the bank data was never loaded either (load_bank runs
            // after load_itemIDs), the whole session is marked save-prohibited. This keeps
            // the vanilla crash-save ("Saving worlds" after the startup failure) and the
            // shutdown save from writing the empty bank manager or the half-initialized
            // registry over the world data on disk.
            itemIDsLoadState = LoadState.NOT_LOADED;
            savingProhibited = true;
            throw e;
        }
    }

    /**
     * Copies the current (still-corrupted) {@code ItemIDs.nbt} to
     * {@code ItemIDs.nbt.pre-repair-<timestamp>} right before a confirmed world repair's
     * one-shot re-save overwrites it. Copy failures are logged but do not abort the repair
     * — the admin explicitly confirmed via {@code CONFIRM_ITEMID_REPAIR}, and the repair
     * report has already instructed them to take a full world backup first.
     *
     * @param filePath the live {@code ItemIDs.nbt} path
     */
    private void backupPreRepairItemIDs(Path filePath) {
        if (!Files.exists(filePath))
            return; // nothing to back up (should not happen — repair implies a loaded file)
        Path backupPath = filePath.resolveSibling(
                filePath.getFileName().toString() + ".pre-repair-" + System.currentTimeMillis());
        try {
            Files.copy(filePath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            info("World repair: copied the pre-repair ItemIDs.nbt aside to " + backupPath
                    + " (restore it to undo the repair mapping).");
        } catch (IOException e) {
            error("World repair: failed to copy the pre-repair ItemIDs.nbt to " + backupPath
                    + " — continuing with the repair (the admin-confirmed repair takes precedence; "
                    + "a full world backup was requested by the repair report).", e);
        }
    }

    @Override
    public boolean save_bank()
    {
        // Save gate: never overwrite Bank_data/ when the bank data was not successfully
        // loaded this session — an aborted/failed load would otherwise be crash-saved as
        // an EMPTY bank manager, destroying every account (see LoadState).
        if (!canSave("Bank_data", bankLoadState))
            return false;
        ServerBankManager bankManager = (ServerBankManager)BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
        if(bankManager == null)
            return false;
        boolean success = true;
        Map<String, ListTag> bankData = new HashMap<>();
        success = bankManager.save(bankData);
        saveDataCompoundListMap(getAbsoluteSavePath(BANK_DATA_FOLDER_NAME), bankData);
        if(success)
        {
            BACKEND_INSTANCES.SERVER_EVENTS.BANK_DATA_SAVED_TO_FILE.notifyListeners();
        }
        return success;
    }

    @Override
    public boolean load_bank()
    {
        Map<String, ListTag> bankData = readDataCompoundListMap(getAbsoluteSavePath(BANK_DATA_FOLDER_NAME));
        ServerBankManager bankManager = (ServerBankManager) BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
        if(bankData == null || bankData.isEmpty() || !bankData.containsKey("itemCentScaleFactors")) {

            if(bankManager != null)
                bankManager.setupDefaultItems();
            //return false;
        }

        if(bankManager != null) {
            if (bankData != null && bankManager.load(bankData)) {
                bankDataLoaded = true;
                bankLoadState = LoadState.LOADED;
                return true;
            }
        }
        bankDataLoaded = false;
        // Folder absent → fresh world (saving creates it); folder present but unreadable →
        // never overwrite it (see LoadState).
        bankLoadState = folderExists(getAbsoluteSavePath(BANK_DATA_FOLDER_NAME))
                ? LoadState.NOT_LOADED : LoadState.FRESH;
        return false;
    }
    public boolean load_bank_compatibilityMode()
    {
        CompoundTag data = readDataCompound(getAbsoluteSavePath("Bank_data.dat"));
        ServerBankManager bankManager = (ServerBankManager) BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
        if(bankManager != null) {
            if (data == null) {
                bankManager.setupDefaultItems();
                // No old-format data on disk → fresh from the bank subsystem's perspective.
                bankLoadState = LoadState.FRESH;
                return false;
            }
            if (!data.contains("banking")) {
                bankManager.setupDefaultItems();
                bankLoadState = LoadState.NOT_LOADED;
                return false;
            }

            CompoundTag bankData = data.getCompound("banking");

            OldBankDataLoader oldBankDataLoader = new OldBankDataLoader(bankManager);
            if (oldBankDataLoader.load(bankData)) {
                bankDataLoaded = true;
                bankLoadState = LoadState.LOADED;
                // BACKEND_INSTANCES.SERVER_EVENTS.BANK_DATA_LOADED_FROM_FILE.notifyListeners();

                // delete the file
                try {
                    Files.deleteIfExists(getAbsoluteSavePath("Bank_data.dat"));
                } catch (IOException e) {
                    error("Failed to delete old bank data file.", e);
                }
                return true;
            }
        }
        bankDataLoaded = false;
        bankLoadState = LoadState.NOT_LOADED;
        return false;
    }


    public boolean save_globalSettings(Path filePath)
    {
        // Save gate: never overwrite settings.json when it exists but could not be loaded
        // (see LoadState). Fresh worlds (state FRESH) may still create the default file.
        if (!canSave("settings", settingsLoadState))
            return false;
        return BACKEND_INSTANCES.SERVER_SETTINGS.saveSettings(filePath.toString());
    }
    @Override
    public boolean save_globalSettings()
    {
        return save_globalSettings(getGlobalSettingsFilePath());
    }

    public boolean load_globalSettings(Path filePath)
    {
        if(BACKEND_INSTANCES.SERVER_SETTINGS.loadSettings(filePath.toString()))
        {
            globalSettingsLoaded = true;
            settingsLoadState = LoadState.LOADED;
            applyVolatileComponentSettings();
            return true;
        }
        else
        {
            globalSettingsLoaded = false;
            // Existing settings file failed to load — never overwrite it (see LoadState).
            settingsLoadState = LoadState.NOT_LOADED;
            return false;
        }
    }

    /**
     * Applies the config-sourced volatile and deposit-gated component lists
     * ({@code ServerBank.ADDITIONAL_VOLATILE_COMPONENTS} /
     * {@code ServerBank.ADDITIONAL_DEPOSIT_GATED_COMPONENTS}) to {@link VolatileItemComponents}.
     * If either effective set changed, all registered ItemID templates are re-normalized and
     * colliding IDs are merged (see {@link ItemIDManager#renormalizeAndMerge()}) — gated
     * components are stripped at the identity boundary too, so both sets affect identity.
     * <p>
     * Note: on slave servers and clients these local values are later overwritten by the lists
     * carried in the master's SyncItemIDsPacket, so all sides normalize identically.
     * <p>
     * Note on ordering: {@link #loadAll()} calls this <b>before</b> the saved ItemIDs are
     * loaded, so the renormalize below only ever touches this session's freshly created
     * default templates (harmless duplicates). Merges affecting <b>saved</b> world data are
     * exclusively decided by the startup merge guard inside {@code ItemIDManager.load()}.
     */
    private void applyVolatileComponentSettings()
    {
        if(BACKEND_INSTANCES.SERVER_SETTINGS == null)
            return;
        // Non-short-circuiting |: both lists must always be applied.
        boolean changed = VolatileItemComponents.setConfigComponentIds(
                BACKEND_INSTANCES.SERVER_SETTINGS.BANK.ADDITIONAL_VOLATILE_COMPONENTS.get());
        changed |= VolatileItemComponents.setGatedConfigComponentIds(
                BACKEND_INSTANCES.SERVER_SETTINGS.BANK.ADDITIONAL_DEPOSIT_GATED_COMPONENTS.get());
        if(changed)
        {
            ItemIDManager.renormalizeAndMerge();
        }
    }
    @Override
    public boolean load_globalSettings()
    {
        return load_globalSettings(getGlobalSettingsFilePath());
    }

    public Path getGlobalSettingsFilePath()
    {
        return getAbsoluteSavePath(BANK_SETTINGS_FILE_NAME);
    }
    public Path getMetaDataFilePath()
    {
        return getAbsoluteSavePath(META_DATA_FILE_NAME);
    }

    /** NBT key inside {@code Meta_data.nbt} holding the applied effective component set. */
    private static final String APPLIED_COMPONENT_SET_KEY = "appliedComponentSet";
    /** NBT key inside {@code Meta_data.nbt} holding the world-repair audit record. */
    private static final String REPAIR_APPLIED_KEY = "repairApplied";
    /** NBT key inside {@code Meta_data.nbt} holding the warn-path evidence acknowledgment hash. */
    private static final String REPAIR_EVIDENCE_ACK_KEY = "repairEvidenceAcknowledged";

    /**
     * The mod version string read from {@code Meta_data.nbt} at load time — i.e. the
     * BankSystem version that last saved this world. {@code null} until a metadata file
     * with a {@code version} entry has been loaded this session. Report/diagnostic context
     * only (e.g. the world-repair report names it); never used for load decisions.
     */
    private String lastSavedByModVersion = null;

    // World-repair audit record (see recordRepairApplied): loaded from and re-persisted to
    // Meta_data.nbt so the fact that a repair ran survives forever in the world data.
    private long repairAppliedTimestamp = -1;
    private String repairAppliedModVersion = null;
    private int repairAppliedChangedShortCount = -1;

    /**
     * The renumbering-tripwire digest (short→item-name, see
     * {@link ItemIDManager#buildShortNameDigest()}) read from {@code Meta_data.nbt} at load
     * time — i.e. the mapping of the LAST session. {@code null} until a metadata file with
     * a digest has been loaded this session (worlds saved before the tripwire existed, or
     * fresh worlds). Diffed against the live registry by {@link #loadAll()} after the whole
     * load completed; every subsequent {@link #save_metadata()} replaces the on-disk digest
     * with the CURRENT registry state, so healthy renames (confirmed merges, applied world
     * repairs) warn at most once.
     * <p>
     * <b>Retention across loads (Task #16 review):</b> {@code load_metadata()} only
     * OVERWRITES this field when the file actually carries a digest — it never resets it to
     * {@code null}. Safe: the data handler is constructed fresh in every
     * {@code onServerStart} (one instance per world/session), so a stale digest can never
     * leak across worlds. Necessary: on the unreadable-{@code ItemIDs.nbt} recovery path
     * ({@code BankSystemModBackend.loadDataFromFiles()} backup → {@code saveAll()} →
     * second {@code loadAll()}), the intermediate save writes a metadata file WITHOUT a
     * digest (the registry is still empty at that point), so the second pass would
     * otherwise diff against nothing — silencing the tripwire on the one path where every
     * short can change meaning. With retention, the second pass diffs the freshly
     * re-created registry against the PRE-recovery digest and reports the remapping.
     */
    private ListTag storedItemIDDigest = null;

    /**
     * Evidence hash of a cent-shift corruption signature whose repair plan failed
     * cross-validation and was therefore only WARNED about (warn-and-continue path of the
     * world-repair guard). Once recorded, later startups with the SAME evidence log a
     * single WARN line instead of the full ERROR report. Persisted in {@code Meta_data.nbt};
     * cleared (set {@code null}) when a confirmed repair is applied.
     */
    private String acknowledgedRepairEvidenceHash = null;

    public boolean save_metadata()
    {
        // Save gate: never overwrite Meta_data.nbt when it exists but could not be loaded
        // (its appliedComponentSet record steers the merge guard — see LoadState).
        if (!canSave("Meta_data", metadataLoadState))
            return false;
        CompoundTag data = new CompoundTag();
        // Save-format stamp (BankSystemSaveFormat): legacy files carry no such key and are
        // re-stamped by this very call on their first post-update save (save_metadata()
        // already runs at the end of every startup merge-guard pass).
        data.putInt(BankSystemSaveFormat.KEY_FORMAT_VERSION, BankSystemSaveFormat.META_DATA_FORMAT_CURRENT);
        // The mod version string is kept alongside the format version: the format version
        // gates loading, the mod version provides human-readable provenance ("world last
        // saved by BankSystem x.y.z") for logs and repair reports.
        data.putString("version", BankSystemMod.VERSION);
        // Persist the effective volatile/deposit-gated component set that the ItemID
        // registry was last normalized with. The startup merge guard compares it against
        // the current set on the next load (see ItemIDManager#detectCollapseCollisions).
        if(appliedEffectiveComponentSet != null)
        {
            ListTag setTag = new ListTag();
            for(String id : appliedEffectiveComponentSet)
                setTag.add(StringTag.valueOf(id));
            data.put(APPLIED_COMPONENT_SET_KEY, setTag);
        }
        // World-repair audit record: persisted forever once a repair ran on this world.
        if (repairAppliedTimestamp > 0)
        {
            CompoundTag repairTag = new CompoundTag();
            repairTag.putLong("timestamp", repairAppliedTimestamp);
            repairTag.putString("modVersion", repairAppliedModVersion == null ? "" : repairAppliedModVersion);
            repairTag.putInt("changedShortCount", repairAppliedChangedShortCount);
            data.put(REPAIR_APPLIED_KEY, repairTag);
        }
        // Warn-path acknowledgment: suppresses the repeated full ERROR report for an
        // already-reported (validation-failed) evidence state — see the field Javadoc.
        if (acknowledgedRepairEvidenceHash != null)
            data.putString(REPAIR_EVIDENCE_ACK_KEY, acknowledgedRepairEvidenceHash);
        // Renumbering tripwire (Task #16): persist the CURRENT short→item-name digest on
        // every save so the next load can detect shorts that silently changed meaning.
        // Master only — slaves never load ItemIDs from disk, so a slave-side digest would
        // describe the master's synced registry and could never be meaningfully diffed.
        if (BACKEND_INSTANCES == null || !BACKEND_INSTANCES.isSlaveServer) {
            ListTag digest = ItemIDManager.buildShortNameDigest();
            if (!digest.isEmpty())
                data.put(BankSystemSaveFormat.KEY_ITEM_ID_DIGEST, digest);
        }
        // Save metadata to a separate file
        return saveDataCompound(getMetaDataFilePath(), data);
    }
    public boolean load_metadata()
    {
        storedEffectiveComponentSet = null;
        appliedEffectiveComponentSet = null;
        // NOTE: storedItemIDDigest is deliberately NOT reset here — see its Javadoc. The
        // handler is constructed fresh per server start, so retention cannot leak across
        // worlds; it exists to bridge the unreadable-file recovery retry.
        boolean fileWasPresent = fileExists(getMetaDataFilePath());
        CompoundTag data = readDataCompound(getMetaDataFilePath());
        if(data == null)
        {
            // Absent file → fresh/pre-2.0 world (compat mode creates it); present but
            // unreadable → never overwrite it with in-memory state (see LoadState).
            metadataLoadState = fileWasPresent ? LoadState.NOT_LOADED : LoadState.FRESH;
            if (fileWasPresent)
                error("Metadata file exists but could not be read. Loading in compatibility mode; "
                        + "the unreadable file is not overwritten (see load-state save gate).");
            else
                // A brand-new world (or a pre-2.0 one) simply has no metadata yet — this is
                // the expected first start, NOT an error. The file is created on the first
                // save. (Previously this logged a misleading "you updated the mod" ERROR on
                // every world creation.)
                info("No BankSystem metadata file found - fresh world or pre-2.0 data. "
                        + "The metadata file is created on the first save.");
            return false;
        }
        // Save-format gate (BankSystemSaveFormat): a missing key is the implicit legacy
        // format 1; anything above this build's supported version means the world was
        // saved by a NEWER BankSystem — abort startup exactly like a newer ItemIDs.nbt
        // (save-prohibited so the newer file on disk stays byte-identical).
        int formatVersion = data.contains(BankSystemSaveFormat.KEY_FORMAT_VERSION)
                ? data.getInt(BankSystemSaveFormat.KEY_FORMAT_VERSION)
                : 1;
        if (formatVersion > BankSystemSaveFormat.META_DATA_FORMAT_CURRENT)
        {
            metadataLoadState = LoadState.NOT_LOADED;
            savingProhibited = true;
            throw new BankSystemStartupAbortException(
                    "Meta_data.nbt has save-format version " + formatVersion
                            + ", but this BankSystem build (v" + BankSystemMod.VERSION
                            + ") only supports versions up to " + BankSystemSaveFormat.META_DATA_FORMAT_CURRENT
                            + ". This world was saved by a newer BankSystem — update the mod. "
                            + "Nothing was loaded or saved; the world data on disk is untouched.");
        }
        metadataLoadState = LoadState.LOADED;
        // Provenance: which BankSystem build last saved this world. INFO on every load;
        // WARN when the saved version is above the running one (likely mod downgrade —
        // the format gate above still guarantees the files are readable, but newer-build
        // behavior differences may surface).
        if (data.contains("version", Tag.TAG_STRING))
        {
            lastSavedByModVersion = data.getString("version");
            info("World last saved by BankSystem " + lastSavedByModVersion + ".");
            if (isVersionNewerThan(lastSavedByModVersion, BankSystemMod.VERSION))
                warn("This world was last saved by BankSystem " + lastSavedByModVersion
                        + " but the running build is v" + BankSystemMod.VERSION
                        + " — a mod DOWNGRADE is likely. The data formats are compatible, "
                        + "but consider updating the mod back to avoid behavior differences.");
        }
        // Worlds saved before the merge guard existed have no stored set: null signals the
        // guard to treat this load as a migration (silent healing merge + store the set).
        if(data.contains(APPLIED_COMPONENT_SET_KEY, Tag.TAG_LIST))
        {
            List<String> set = new ArrayList<>();
            ListTag setTag = data.getList(APPLIED_COMPONENT_SET_KEY, Tag.TAG_STRING);
            for(Tag element : setTag)
                set.add(element.getAsString());
            storedEffectiveComponentSet = set;
            // Keep re-saving what we read until the guard applies a newer set (slave servers
            // never run the guard but must not drop the master-era record from the file).
            appliedEffectiveComponentSet = set;
        }
        // World-repair audit record: adopt what we read so every subsequent save_metadata()
        // re-persists it (the record must never be dropped by an unrelated save).
        if (data.contains(REPAIR_APPLIED_KEY, Tag.TAG_COMPOUND))
        {
            CompoundTag repairTag = data.getCompound(REPAIR_APPLIED_KEY);
            repairAppliedTimestamp = repairTag.getLong("timestamp");
            repairAppliedModVersion = repairTag.getString("modVersion");
            repairAppliedChangedShortCount = repairTag.getInt("changedShortCount");
        }
        // Warn-path acknowledgment (reset first: a world without the key must not inherit a
        // previously loaded world's acknowledgment in the same session).
        acknowledgedRepairEvidenceHash = data.contains(REPAIR_EVIDENCE_ACK_KEY, Tag.TAG_STRING)
                ? data.getString(REPAIR_EVIDENCE_ACK_KEY)
                : null;
        // Renumbering-tripwire digest of the LAST session. Only OVERWRITTEN when the file
        // carries the key; a key-less file RETAINS the previously read digest (see the
        // field Javadoc: per-session handler instance, so no cross-world leakage — the
        // retention bridges the unreadable-ItemIDs recovery retry, where the intermediate
        // saveAll() drops the digest from disk before the second loadAll() pass). Consumed
        // by loadAll() after the whole load completed; null = no record yet (older save) →
        // the tripwire stays silent.
        if (data.contains(BankSystemSaveFormat.KEY_ITEM_ID_DIGEST, Tag.TAG_LIST))
            storedItemIDDigest = data.getList(BankSystemSaveFormat.KEY_ITEM_ID_DIGEST, Tag.TAG_COMPOUND);
        return true;
    }

    /**
     * @return the acknowledged evidence hash of an already-reported, validation-failed
     *         cent-shift signature (see the field Javadoc), or {@code null} when no such
     *         report was acknowledged yet. Consumed by the world-repair guard to decide
     *         between the one-time full ERROR report and the recurring single WARN line.
     */
    public String getAcknowledgedRepairEvidenceHash()
    {
        return acknowledgedRepairEvidenceHash;
    }

    /**
     * Records (or clears, with {@code null}) the warn-path evidence acknowledgment hash.
     * Persisted by the next {@link #save_metadata()} — the startup merge guard invokes that
     * at the end of every load, so the acknowledgment is durable before the first tick.
     *
     * @param hash the evidence hash from {@code ItemIDWorldRepair.evidenceFingerprint},
     *             or {@code null} to clear (after a confirmed repair consumed the evidence)
     */
    public void setAcknowledgedRepairEvidenceHash(String hash)
    {
        acknowledgedRepairEvidenceHash = hash;
    }

    /**
     * @return the BankSystem version string that last saved this world (read from
     *         {@code Meta_data.nbt}), or {@code null} if not known this session.
     *         Consumed by the world-repair guard for report provenance.
     */
    public String getLastSavedByModVersion()
    {
        return lastSavedByModVersion;
    }

    /**
     * Records the audit entry for an applied ItemID world repair; persisted into
     * {@code Meta_data.nbt} by the next {@link #save_metadata()} (the startup merge guard
     * calls it at the end of every load) and re-persisted on every save thereafter.
     * Called by {@code ItemIDManager.applyCorruptionRepairGuard()} right after a confirmed
     * repair was applied.
     *
     * @param timestamp         epoch millis at which the repair was applied
     * @param modVersion        the BankSystem version that applied the repair
     * @param changedShortCount how many shorts changed meaning (the plan's changed-short set)
     */
    public void recordRepairApplied(long timestamp, String modVersion, int changedShortCount)
    {
        repairAppliedTimestamp = timestamp;
        repairAppliedModVersion = modVersion;
        repairAppliedChangedShortCount = changedShortCount;
    }

    /**
     * Loose "is version A newer than version B" comparison on dotted version strings
     * (numeric segment-wise; non-numeric segments compared lexicographically). Used only
     * for the downgrade WARN — never for load decisions — so a parse oddity harmlessly
     * suppresses or emits a warning and nothing else.
     */
    private static boolean isVersionNewerThan(String a, String b)
    {
        if (a == null || b == null)
            return false;
        String[] partsA = a.trim().split("\\.");
        String[] partsB = b.trim().split("\\.");
        int len = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < len; i++)
        {
            String segA = i < partsA.length ? partsA[i] : "0";
            String segB = i < partsB.length ? partsB[i] : "0";
            int cmp;
            try {
                cmp = Integer.compare(Integer.parseInt(segA), Integer.parseInt(segB));
            } catch (NumberFormatException e) {
                cmp = segA.compareTo(segB);
            }
            if (cmp != 0)
                return cmp > 0;
        }
        return false;
    }

    /**
     * @return the effective component set read from {@code Meta_data.nbt} at load time,
     *         or {@code null} if this world has no record yet (pre-guard world / fresh world).
     *         Consumed by the startup merge guard in {@link ItemIDManager}.
     */
    public List<String> getStoredEffectiveComponentSet()
    {
        return storedEffectiveComponentSet;
    }

    /**
     * Records the effective component set that the ItemID registry has just been normalized
     * with. Called by the startup merge guard after every successful normalization pass;
     * persisted by the next {@link #save_metadata()}.
     *
     * @param set sorted id strings from {@link VolatileItemComponents#getEffectiveComponentIds()}
     */
    public void setAppliedEffectiveComponentSet(List<String> set)
    {
        appliedEffectiveComponentSet = set == null ? null : new ArrayList<>(set);
    }

    /**
     * Read-only view of the effective component set that the ItemID registry has been
     * normalized with in this session (as assigned by the startup merge guard).
     * Intended for in-game tests only — production code has no need to inspect it.
     *
     * @return an unmodifiable copy of the applied effective component set, or {@code null}
     *         if the guard has not yet written the field this session
     */
    public List<String> getAppliedEffectiveComponentSet_forTesting()
    {
        return appliedEffectiveComponentSet == null ? null : List.copyOf(appliedEffectiveComponentSet);
    }

    /**
     * Moves the on-disk data of every subsystem whose load FAILED this session
     * ({@link LoadState#NOT_LOADED} while data exists on disk) aside to
     * {@code <name>.corrupt-<timestamp>}, so a subsequent fresh-state save can never
     * silently destroy the unreadable original. Subsystems whose backup succeeded are
     * re-marked {@link LoadState#FRESH} (saving may now create new files); subsystems
     * whose backup failed stay {@link LoadState#NOT_LOADED} and therefore stay
     * save-prohibited.
     * <p>
     * Called by the load-failure fallback in
     * {@code BankSystemModBackend.loadDataFromFiles()} before it re-initializes with
     * fresh files. Never reached after a merge-guard abort — the
     * {@link ItemIDMergeAbortedException} propagates out of {@link #loadAll()} first and
     * aborts startup.
     */
    public void backupFailedSubsystemData() {
        String suffix = ".corrupt-" + System.currentTimeMillis();
        if (metadataLoadState == LoadState.NOT_LOADED && backupPath(getMetaDataFilePath(), suffix))
            metadataLoadState = LoadState.FRESH;
        if (settingsLoadState == LoadState.NOT_LOADED && backupPath(getGlobalSettingsFilePath(), suffix))
            settingsLoadState = LoadState.FRESH;
        if (itemIDsLoadState == LoadState.NOT_LOADED && backupPath(getAbsoluteSavePath(ITEM_IDS_FILE_NAME), suffix)) {
            itemIDsLoadState = LoadState.FRESH;
            // The unreadable file was moved aside — the world is now officially fresh from
            // the ItemID subsystem's perspective, so the load phase is complete: release
            // the registration latch (same release the no-file path performs).
            ItemIDManager.markRegistryReady();
        }
        if (bankLoadState == LoadState.NOT_LOADED && backupPath(getAbsoluteSavePath(BANK_DATA_FOLDER_NAME), suffix))
            bankLoadState = LoadState.FRESH;
    }

    /**
     * Moves a file or folder aside by appending the given suffix to its name.
     *
     * @param path   the file/folder to back up
     * @param suffix backup suffix (e.g. {@code .corrupt-<timestamp>})
     * @return {@code true} when nothing exists at the path (nothing to back up — the
     *         subsystem is genuinely fresh) or when the move succeeded; {@code false}
     *         when the move failed (the caller must keep the subsystem save-prohibited)
     */
    private boolean backupPath(Path path, String suffix) {
        if (!Files.exists(path))
            return true; // nothing on disk — genuinely fresh
        Path target = path.resolveSibling(path.getFileName().toString() + suffix);
        try {
            Files.move(path, target);
            error("Backed up unreadable BankSystem data: " + path + " -> " + target + ". " +
                    "A fresh file will be created in its place; restore the backup manually if this data matters.");
            return true;
        } catch (IOException e) {
            error("Failed to back up unreadable BankSystem data at " + path +
                    " — it will NOT be overwritten (saves of this subsystem stay disabled).", e);
            return false;
        }
    }



    public static void copyFolder(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path targetDir = target.resolve(source.relativize(dir));
                if (!Files.exists(targetDir)) {
                    Files.createDirectories(targetDir);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path targetFile = target.resolve(source.relativize(file));
                Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
    public static void deleteFolderContents(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            throw new IllegalArgumentException("Provided path is not a directory: " + folder);
        }

        Files.walkFileTree(folder, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (!dir.equals(folder)) {
                    Files.delete(dir); // delete subdirectory
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }



    protected void info(String msg)
    {
        BACKEND_INSTANCES.LOGGER.info("[BankSystemDataHandler] " + msg);
    }
    protected void error(String msg)
    {
        BACKEND_INSTANCES.LOGGER.error("[BankSystemDataHandler] " + msg);
    }
    protected void error(String msg, Throwable e)
    {
        BACKEND_INSTANCES.LOGGER.error("[BankSystemDataHandler] " + msg, e);
    }
    protected void warn(String msg)
    {
        BACKEND_INSTANCES.LOGGER.warn("[BankSystemDataHandler] " + msg);
    }
    protected void debug(String msg)
    {
        BACKEND_INSTANCES.LOGGER.debug("[BankSystemDataHandler] " + msg);
    }
    
}
