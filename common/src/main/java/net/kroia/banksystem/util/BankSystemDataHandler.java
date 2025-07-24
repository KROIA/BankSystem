package net.kroia.banksystem.util;


import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.ServerBankManager;
import net.kroia.modutilities.PlayerUtilities;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;


public class BankSystemDataHandler {
    private final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final String FOLDER_NAME = "Finance/BankSystem";

    private final String BANK_DATA_FILE_NAME = "Bank_data.dat";
    private final String SETTINGS_FILE_NAME = "settings.dat";
    private final boolean COMPRESSED = false;
    private boolean isLoaded = false;
    private File saveFolder;

    private long tickCounter = 0;
    private int lastPlayerCount = 0;
    //public static long saveTickInterval = 6000; // 5 minutes

    public void tickUpdate()
    {
        tickCounter++;
        if(tickCounter >= BankSystemMod.SERVER_SETTINGS.UTILITIES.SAVE_INTERVAL_MINUTES.get() * 1200) // 1 minute = 1200 ticks
        {
            tickCounter = 0;
            // Check if any player is online
            int playerCount = PlayerUtilities.getOnlinePlayers().size();
            if(playerCount > 0 || lastPlayerCount > 0) {
                lastPlayerCount = playerCount;
                saveAll();
            }
        }
    }

    public boolean isLoaded() {
        return isLoaded;
    }
    public void setSaveFolder(File folder) {
        File rootFolder = new File(folder, FOLDER_NAME);
        // check if folder exists
        if (!rootFolder.exists()) {
            rootFolder.mkdirs();
        }
        saveFolder = rootFolder;
    }
    public File getSaveFolder() {
        return saveFolder;
    }

    public boolean saveAll()
    {
        BankSystemMod.LOGGER.info("Saving BankSystem Mod data...");
        boolean success = true;
        success &= save_bank();
        success &= save_globalSettings();

        if(success) {
            BankSystemMod.LOGGER.info("BankSystem Mod data saved successfully.");

        }
        else
            BankSystemMod.LOGGER.error("Failed to save BankSystem Mod data.");
        return success;
    }

    public boolean loadAll()
    {
        isLoaded = false;
        BankSystemMod.LOGGER.info("Loading BankSystem Mod data...");
        boolean success = true;
        success &= load_bank();
        success &= load_globalSettings();

        if(success) {
            BankSystemMod.LOGGER.info("BankSystem Mod data loaded successfully.");
            isLoaded = true;
        }
        else
            BankSystemMod.LOGGER.error("Failed to load BankSystem Mod data.");
        return success;
    }

    public boolean save_bank()
    {
        boolean success = true;
        CompoundTag data = new CompoundTag();
        CompoundTag bankData = new CompoundTag();
        success = BankSystemMod.SERVER_BANK_MANAGER.save(bankData);
        data.put("banking", bankData);
        saveDataCompound(BANK_DATA_FILE_NAME, data);
        return success;
    }

    public boolean load_bank()
    {
        CompoundTag data = readDataCompound(BANK_DATA_FILE_NAME);
        if(data == null)
            return false;
        if(!data.contains("banking"))
            return false;

        CompoundTag bankData = data.getCompound("banking");
        return BankSystemMod.SERVER_BANK_MANAGER.load(bankData);
    }

    public boolean save_globalSettings()
    {
        return BankSystemMod.SERVER_SETTINGS.saveSettings();
    }

    public boolean load_globalSettings()
    {
        return BankSystemMod.SERVER_SETTINGS.loadSettings();
    }


    public boolean fileExists(String fileName) {
        File file = new File(getSaveFolder(), fileName);
        return file.exists();
    }

    private CompoundTag readDataCompound(String fileName)
    {
        CompoundTag dataOut = new CompoundTag();
        File file = new File(saveFolder, fileName);
        if (file.exists()) {
            try {
                CompoundTag data;

                if(COMPRESSED)
                    data = NbtIo.readCompressed(file);
                else
                    data = NbtIo.read(file);

                dataOut = data;
                return dataOut;
            } catch (IOException e) {
                BankSystemMod.LOGGER.error("Failed to read data from file: " + fileName);
                e.printStackTrace();
            } catch(Exception e)
            {
                BankSystemMod.LOGGER.error("Failed to read data from file: " + fileName);
                e.printStackTrace();
            }
        }
        return null;
    }
    public boolean saveDataCompound(String fileName, CompoundTag data) {
        File file = new File(saveFolder, fileName);
        try {
            if (COMPRESSED)
                NbtIo.writeCompressed(data, file);
            else
                NbtIo.write(data, file);
        } catch (IOException e) {
            BankSystemMod.LOGGER.error("Failed to save data to file: " + fileName);
            e.printStackTrace();
            return false;
        } catch(Exception e)
        {
            BankSystemMod.LOGGER.error("Failed to save data to file: " + fileName);
            e.printStackTrace();
            return false;
        }
        return true;
    }



    public boolean saveAsJson(Object o, String fileName)
    {
        String json = GSON.toJson(o);
        try {
            Path path = Paths.get(getSaveFolder()+"/"+fileName);
            Files.createDirectories(path.getParent());
            Files.writeString(Paths.get(getSaveFolder()+"/"+fileName), json);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }
    public <T> T loadFromJson(String fileName, Type typeOfT) throws JsonSyntaxException {
        try {
            // Read JSON content
            String json = Files.readString(Paths.get(getSaveFolder()+"/"+fileName));
            return (T) GSON.fromJson(json, TypeToken.get(typeOfT));
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
