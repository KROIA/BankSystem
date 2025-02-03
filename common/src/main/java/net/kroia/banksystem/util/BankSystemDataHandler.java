package net.kroia.banksystem.util;


import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.banking.ServerBankManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;


public class BankSystemDataHandler {
    private static final String FOLDER_NAME = "Finance/BankSystem";

    private static final String BANK_DATA_FILE_NAME = "Bank_data.dat";
    private static final String SETTINGS_FILE_NAME = "settings.dat";
    private static final boolean COMPRESSED = false;
    private static boolean isLoaded = false;
    private static File saveFolder;

    private static long tickCounter = 0;
    public static long saveTickInterval = 6000; // 5 minutes

    public static void tickUpdate()
    {
        tickCounter++;
        if(tickCounter >= saveTickInterval)
        {
            tickCounter = 0;
            saveAll();
        }
    }

    public static boolean isLoaded() {
        return isLoaded;
    }
    public static void setSaveFolder(File folder) {
        File rootFolder = new File(folder, FOLDER_NAME);
        // check if folder exists
        if (!rootFolder.exists()) {
            rootFolder.mkdirs();
        }
        saveFolder = rootFolder;
    }
    public static File getSaveFolder() {
        return saveFolder;
    }

    public static boolean saveAll()
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

    public static boolean loadAll()
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

    public static boolean save_bank()
    {
        boolean success = true;
        CompoundTag data = new CompoundTag();
        CompoundTag bankData = new CompoundTag();
        success = ServerBankManager.saveToTag(bankData);
        data.put("banking", bankData);
        saveDataCompound(BANK_DATA_FILE_NAME, data);
        return success;
    }

    public static boolean load_bank()
    {
        CompoundTag data = readDataCompound(BANK_DATA_FILE_NAME);
        if(data == null)
            return false;
        if(!data.contains("banking"))
            return false;

        CompoundTag bankData = data.getCompound("banking");
        return ServerBankManager.loadFromTag(bankData);
    }

    public static boolean save_globalSettings()
    {
        CompoundTag data = new CompoundTag();
        BankSystemModSettings.saveSettings(data);
        return saveDataCompound(SETTINGS_FILE_NAME, data);
    }

    public static boolean load_globalSettings()
    {
        CompoundTag data = readDataCompound(SETTINGS_FILE_NAME);
        if(data == null)
            return false;
        BankSystemModSettings.readSettigns(data);
        return true;
    }


    private static CompoundTag readDataCompound(String fileName)
    {
        CompoundTag dataOut = new CompoundTag();
        File file = new File(saveFolder, fileName);
        if (file.exists()) {
            try {
                CompoundTag data;

                // Define a reasonable quota and depth for NBT reading
                long quota = 2097152L; // 2 MB size limit
                int maxDepth = 512; // Maximum allowed depth for NBT structures
                NbtAccounter accounter = new NbtAccounter(quota, maxDepth);
                if(COMPRESSED)
                    data = NbtIo.readCompressed(new FileInputStream(file), accounter);
                else {
                    DataInputStream dataInputStream = new DataInputStream(new FileInputStream(file));
                    data = NbtIo.read(dataInputStream, accounter);
                }

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
    public static boolean saveDataCompound(String fileName, CompoundTag data) {
        File file = new File(saveFolder, fileName);
        try {
            if (COMPRESSED)
                NbtIo.writeCompressed(data, file.toPath());
            else
                NbtIo.write(data, file.toPath());
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
}
