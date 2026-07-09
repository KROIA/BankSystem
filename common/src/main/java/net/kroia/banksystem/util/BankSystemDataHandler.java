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
     * Session-wide save kill-switch, set when the ItemID merge guard aborts startup
     * ({@link ItemIDMergeAbortedException}). At that point the ItemID registry is
     * half-initialized in memory (raw templates, merge NOT applied) and the bank manager
     * is still empty, so NOTHING of this session may ever be saved — including the vanilla
     * crash-save ({@code SERVER_LEVEL_SAVE} during "Saving worlds") and the shutdown save.
     * This flag is what makes the abort report's "Nothing has been changed or saved"
     * guarantee actually true.
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
                    "(startup was aborted by the ItemID merge guard; on-disk data is left untouched).");
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
            warn("Skipping BankSystem data save: startup was aborted by the ItemID merge guard — " +
                    "nothing is written so the world data on disk stays exactly as it was.");
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
            BACKEND_INSTANCES.ITEM_ID_MANAGER.createDefaultItemIDs();

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
                // Task #13 Fix D one-shot durability: if the load-time alias-table repair
                // touched any entries, persist the cleaned-up state immediately so a crash
                // between now and the next periodic save doesn't lose the healing work.
                // consumeLastLoadRepairCount() clears the counter after reading — idempotent
                // across multiple loadAll() invocations in the same session.
                int repaired = ItemIDManager.consumeLastLoadRepairCount();
                if (repaired > 0) {
                    info("Persisting ItemID alias-table repairs (" + repaired
                            + " entries) via one-shot save_itemIDs().");
                    save_itemIDs();
                }
            }
            else
                // Absent file → fresh world (saving may create it); present but
                // unreadable/invalid → never overwrite it with in-memory state.
                itemIDsLoadState = fileWasPresent ? LoadState.NOT_LOADED : LoadState.FRESH;
            return success;
        } catch (ItemIDMergeAbortedException e) {
            // The merge guard aborted startup. The ItemID registry is fully populated in
            // memory but the merge was NOT applied (raw un-normalized templates, undecided
            // alias state), so ItemIDs deliberately do NOT count as "loaded" — and because
            // the bank data was never loaded either (load_bank runs after load_itemIDs),
            // the whole session is marked save-prohibited. This keeps the vanilla
            // crash-save ("Saving worlds" after the startup failure) and the shutdown save
            // from writing the empty bank manager or the half-initialized registry over
            // the world data on disk.
            itemIDsLoadState = LoadState.NOT_LOADED;
            savingProhibited = true;
            throw e;
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

    public boolean save_metadata()
    {
        // Save gate: never overwrite Meta_data.nbt when it exists but could not be loaded
        // (its appliedComponentSet record steers the merge guard — see LoadState).
        if (!canSave("Meta_data", metadataLoadState))
            return false;
        CompoundTag data = new CompoundTag();
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
        // Save metadata to a separate file
        return saveDataCompound(getMetaDataFilePath(), data);
    }
    public boolean load_metadata()
    {
        storedEffectiveComponentSet = null;
        appliedEffectiveComponentSet = null;
        boolean fileWasPresent = fileExists(getMetaDataFilePath());
        CompoundTag data = readDataCompound(getMetaDataFilePath());
        if(data == null)
        {
            // Absent file → fresh/pre-2.0 world (compat mode creates it); present but
            // unreadable → never overwrite it with in-memory state (see LoadState).
            metadataLoadState = fileWasPresent ? LoadState.NOT_LOADED : LoadState.FRESH;
            error("Metadata file is missing version information. This means you updated the mod to a newer version.");
            return false;
        }
        metadataLoadState = LoadState.LOADED;
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
        return true;
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
        if (itemIDsLoadState == LoadState.NOT_LOADED && backupPath(getAbsoluteSavePath(ITEM_IDS_FILE_NAME), suffix))
            itemIDsLoadState = LoadState.FRESH;
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
