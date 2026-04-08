package net.kroia.banksystem.util;


import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.IBankSystemDataHandler;
import net.kroia.banksystem.banking.bankmanager.SyncBankManager;
import net.kroia.banksystem.compat.OldBankDataLoader;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.kroia.modutilities.persistence.DataPersistence;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;


public class BankSystemDataHandler extends DataPersistence implements IBankSystemDataHandler {

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;

    private final String ITEM_IDS_FOLDER_NAME = "ItemIDs";
    private final String BANK_DATA_FOLDER_NAME = "Bank_data";
    private final String META_DATA_FILE_NAME = "Meta_data.nbt";
    private final String BANK_SETTINGS_FILE_NAME = "settings.json";
    private long tickCounter = 0;
    private int lastPlayerCount = 0;
    private static boolean globalSettingsLoaded = false;
    private static boolean bankDataLoaded = false;
    public BankSystemDataHandler() {
        super(JsonFormat.PRETTY, NbtFormat.COMPRESSED, Paths.get("Finance/BankSystem"));
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
        super.setLevelSavePath(path);
        createSaveFolder();
    }

    @Override
    public boolean saveAll()
    {
        debug("Saving BankSystem Mod data...");
        boolean success = true;
        success &= save_metadata();
        success &= save_globalSettings();
        success &= save_bank();
        success &= save_itemIDs();


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
        boolean success = true;
        success &= load_metadata();
        boolean loadInCompatibilityMode = !success;
        success &= load_itemIDs();

        // check if file exists
        loadInCompatibilityMode |= fileExists(getAbsoluteSavePath("Bank_data.dat"));


        if(loadInCompatibilityMode)
        {
            success = true;
            // load in compatibility mode
            info("Loading BankSystem Mod data in compatibility mode. This is only needed if you updated the mod to a newer version and the old data is not compatible with the new version.");
            success &= load_bank_compatibilityMode();

            // delete old settings file
            Path oldSettingsFilePath = getAbsoluteSavePath("settings.dat");
            if(fileExists(oldSettingsFilePath)) {
                try {
                    Files.delete(oldSettingsFilePath);
                    info("Deleted old settings file: " + oldSettingsFilePath);
                } catch (IOException e) {
                    error("Failed to delete old settings file: " + oldSettingsFilePath, e);
                }
            }
            Path potentialBankableItemsFile = getAbsoluteSavePath("PotentialBankableItems.json");
            if(fileExists(potentialBankableItemsFile)) {
                try {
                    Files.delete(potentialBankableItemsFile);
                    info("Deleted old potential bankable items file: " + potentialBankableItemsFile);
                } catch (IOException e) {
                    error("Failed to delete old potential bankable items file: " + potentialBankableItemsFile, e);
                }
            }

            success &= save_globalSettings(getGlobalSettingsFilePath());

            if(success) {
                debug("BankSystem Mod data loaded successfully.");
            }
            else
                error("Failed to load BankSystem Mod data.");
            return success;
        }

        Path settingsFilePath = getGlobalSettingsFilePath();
        if(!fileExists(settingsFilePath)) {
            warn("SyncServerBank settings file not found, creating default settings file.");
            success &= save_globalSettings(settingsFilePath);
        }
        else
            success &= load_globalSettings(settingsFilePath);
        success &= load_bank();



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
        boolean success = true;
        Map<String, ListTag> idData = new HashMap<>();
        success = BACKEND_INSTANCES.ITEM_ID_MANAGER.save(idData);
        if(!idData.isEmpty()) {
            saveDataCompoundListMap(getAbsoluteSavePath(ITEM_IDS_FOLDER_NAME), idData);
        }
        if(success)
        {
           // BACKEND_INSTANCES.SERVER_EVENTS.BANK_DATA_SAVED_TO_FILE.notifyListeners();
        }
        return success;
    }
    public boolean load_itemIDs()
    {
        Map<String, ListTag> idData = readDataCompoundListMap(getAbsoluteSavePath(ITEM_IDS_FOLDER_NAME));
        return idData != null && BACKEND_INSTANCES.ITEM_ID_MANAGER.load(idData);
    }

    @Override
    public boolean save_bank()
    {
        boolean success = true;
        Map<String, ListTag> bankData = new HashMap<>();
        SyncBankManager bankManager = (SyncBankManager)BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
        if(bankManager != null)
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
        SyncBankManager bankManager = (SyncBankManager) BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
        if(bankData == null || bankData.isEmpty() || !bankData.containsKey("itemCentScaleFactors")) {

            if(bankManager != null)
                bankManager.setupDefaultItems();
            //return false;
        }

        if(bankManager != null) {
            if (bankData != null && bankManager.load(bankData)) {
                bankDataLoaded = true;
                return true;
            }
        }
        bankDataLoaded = false;
        return false;
    }
    public boolean load_bank_compatibilityMode()
    {
        CompoundTag data = readDataCompound(getAbsoluteSavePath("Bank_data.dat"));
        SyncBankManager bankManager = (SyncBankManager) BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
        if(bankManager != null) {
            if (data == null) {
                bankManager.setupDefaultItems();
                return false;
            }
            if (!data.contains("banking")) {
                bankManager.setupDefaultItems();
                return false;
            }

            CompoundTag bankData = data.getCompound("banking");

            OldBankDataLoader oldBankDataLoader = new OldBankDataLoader(bankManager);
            if (oldBankDataLoader.load(bankData)) {
                bankDataLoaded = true;
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
        return false;
    }


    public boolean save_globalSettings(Path filePath)
    {
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
            return true;
        }
        else
        {
            globalSettingsLoaded = false;
            return false;
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

    public boolean save_metadata()
    {
        CompoundTag data = new CompoundTag();
        data.putString("version", BankSystemMod.VERSION);
        // Save metadata to a separate file
        return saveDataCompound(getMetaDataFilePath(), data);
    }
    public boolean load_metadata()
    {
        CompoundTag data = readDataCompound(getMetaDataFilePath());
        if(data == null)
        {
            boolean backupSuccess = true;
            // copy stockmarket folder to a backup folder
            Path backupPath = getAbsoluteSavePath("../BankSystemBackup_" + System.currentTimeMillis());
            // create path
            if(!createFolder(backupPath)) {
                error("Failed to create backup folder: " + backupPath);
                backupSuccess = false;
            }
            // copy folder
            Path bankSystemFolder = getAbsoluteSavePath();
            try{
                copyFolder(bankSystemFolder, backupPath);
            }catch (IOException e) {
                error("Failed to copy BankSystem folder to backup folder: " + backupPath, e);
                backupSuccess = false;
            }
            String msg = "Market data file is missing version information. This means you updated the mod to a newer version.\n";
            if(backupSuccess)
                msg += "To prevent losing the save with the old bank data, a copy is created at:\n" + backupPath + "\n";
            else {
                msg += "The backup folder could not be created.";
            }
            error(msg);
            if(backupSuccess)
            {
                /*// Delete all files and paths in the market data folder recursively
                try {
                    deleteFolderContents(getAbsoluteSavePath());
                } catch (IOException e) {
                    error("Failed to delete old banksystem data files.", e);
                }*/
            }
            return false;
        }
        return true;
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
