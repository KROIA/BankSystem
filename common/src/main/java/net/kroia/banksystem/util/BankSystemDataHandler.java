package net.kroia.banksystem.util;


import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.IBankSystemDataHandler;
import net.kroia.modutilities.DataPersistence;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.minecraft.nbt.CompoundTag;

import java.nio.file.Path;
import java.nio.file.Paths;


public class BankSystemDataHandler extends DataPersistence implements IBankSystemDataHandler {

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;

    private final String BANK_DATA_FILE_NAME = "Bank_data.dat";
    private final String BANK_SETTINGS_FILE_NAME = "settings.json";
    private long tickCounter = 0;
    private int lastPlayerCount = 0;
    private static boolean globalSettingsLoaded = false;
    private static boolean bankDataLoaded = false;
    public BankSystemDataHandler() {
        super(JsonFormat.PRETTY, NbtFormat.UNCOMPRESSED, Paths.get("Finance/BankSystem"));
        bankDataLoaded = false;
        globalSettingsLoaded = false;
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
        info("Saving BankSystem Mod data...");
        boolean success = true;
        success &= save_globalSettings();
        success &= save_bank();


        if(success) {
            info("BankSystem Mod data saved successfully.");

        }
        else
            error("Failed to save BankSystem Mod data.");
        return success;
    }

    @Override
    public boolean loadAll()
    {
        info("Loading BankSystem Mod data...");
        boolean success = true;
        Path settingsFilePath = getGlobalSettingsFilePath();
        if(!fileExists(settingsFilePath)) {
            warn("Bank settings file not found, creating default settings file.");
            success &= save_globalSettings(settingsFilePath);
        }
        else
            success &= load_globalSettings(settingsFilePath);
        success &= load_bank();


        if(success) {
            info("BankSystem Mod data loaded successfully.");
        }
        else
            error("Failed to load BankSystem Mod data.");
        return success;
    }

    @Override
    public boolean save_bank()
    {
        boolean success = true;
        CompoundTag data = new CompoundTag();
        CompoundTag bankData = new CompoundTag();
        success = BACKEND_INSTANCES.SERVER_BANK_MANAGER.save(bankData);
        data.put("banking", bankData);
        data.putString("version", BankSystemMod.VERSION);
        saveDataCompound(getAbsoluteSavePath(BANK_DATA_FILE_NAME), data);
        if(success)
        {
            BACKEND_INSTANCES.SERVER_EVENTS.BANK_DATA_SAVED_TO_FILE.notifyListeners();
        }
        return success;
    }

    @Override
    public boolean load_bank()
    {
        CompoundTag data = readDataCompound(getAbsoluteSavePath(BANK_DATA_FILE_NAME));
        if(data == null)
            return false;
        if(!data.contains("banking"))
            return false;

        CompoundTag bankData = data.getCompound("banking");
        String version = data.getString("version");
        if(BACKEND_INSTANCES.SERVER_BANK_MANAGER.load(bankData))
        {
            bankDataLoaded = true;
            BACKEND_INSTANCES.SERVER_EVENTS.BANK_DATA_LOADED_FROM_FILE.notifyListeners();
            return true;
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
