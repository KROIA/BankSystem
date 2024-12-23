package net.kroia.banksystem.util;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.ServerBankManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.File;
import java.io.IOException;


public class DataHandler {
    private static final String FOLDER_NAME = "BankSystem";

    private static final String BANK_DATA_FILE_NAME = "Bank_data.dat";
    private static final boolean COMPRESSED = false;
    private static File saveFolder;

   // private static ScheduledExecutorService saveScheduler;
    private static int saveTickCounter = 0;

    public DataHandler()
    {

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
        BankSystemMod.LOGGER.info("Saving StockMarket Mod data...");
        boolean success = true;
        success &= save_bank();

        if(success)
            BankSystemMod.LOGGER.info("BankSystem Mod data saved successfully.");
        else
            BankSystemMod.LOGGER.error("Failed to save BankSystem Mod data.");
        return success;
    }

    public static boolean loadAll()
    {
        BankSystemMod.LOGGER.info("Loading BankSystem Mod data...");
        boolean success = true;
        success &= load_bank();

        if(success)
            BankSystemMod.LOGGER.info("BankSystem Mod data loaded successfully.");
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


    private static CompoundTag readDataCompound(String fileName)
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
    public static boolean saveDataCompound(String fileName, CompoundTag data) {
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
}
