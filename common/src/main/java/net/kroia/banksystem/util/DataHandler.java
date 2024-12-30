package net.kroia.banksystem.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemSettings;
import net.kroia.banksystem.banking.ServerBankManager;
import net.kroia.modutilities.ItemUtilities;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;


public class DataHandler {
    private static final String FOLDER_NAME = "Finance/BankSystem";

    private static final String BANK_DATA_FILE_NAME = "Bank_data.dat";
    private static final String POTENTIAL_BANK_ITEM_FILE_NAME = "PotentialBankableItems.json";
    private static final boolean COMPRESSED = false;
    private static boolean isLoaded = false;
    private static File saveFolder;

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
        BankSystemMod.LOGGER.info("Saving StockMarket Mod data...");
        boolean success = true;
        success &= save_bank();
        success &= savePotentialItemIDs(POTENTIAL_BANK_ITEM_FILE_NAME);

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
        success &= loadPotentialItemIDs(POTENTIAL_BANK_ITEM_FILE_NAME);

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


    public static boolean loadPotentialItemIDs(String fileName)
    {
        File file = new File(saveFolder, fileName);
        if (file.exists()) {
            try {
                JsonReader reader = new JsonReader(new FileReader(file));
                JsonObject json = new JsonParser().parse(reader).getAsJsonObject();
                JsonArray array = json.getAsJsonArray("potentialBankItemIDs");
                ArrayList<String> itemIDS = new ArrayList<>();
                for (int i = 0; i < array.size(); i++) {
                    String normalized = ItemUtilities.getNormalizedItemID(array.get(i).getAsString());
                    if(normalized != null)
                        itemIDS.add(normalized);
                    else
                        BankSystemMod.LOGGER.error("Failed to normalize itemID: " + array.get(i).getAsString() + " from file: " + fileName);
                }
                ServerBankManager.setPotientialBankItemIDs(itemIDS);

                reader.close();
                return true;
            } catch (Exception e) {
                BankSystemMod.LOGGER.error("Failed to read data from file: " + fileName);
                e.printStackTrace();

                return false;
            }
        }
        else {
            BankSystemMod.LOGGER.info("Failed to read data from file: " + fileName + " Creating default data...");
            ServerBankManager.setPotientialBankItemIDs(ItemUtilities.getAllItemIDs(BankSystemSettings.Bank.POTENTIAL_ITEM_TAGS, BankSystemSettings.Bank.POTENTIAL_ITEM_CONTAINS_STR));
            return savePotentialItemIDs(fileName);
        }
    }
    public static boolean savePotentialItemIDs(String fileName)
    {
        File file = new File(saveFolder, fileName);
        try {
            JsonObject json = new JsonObject();
            JsonArray array = new JsonArray();
            for (String itemID : ServerBankManager.getPotentialBankItemIDs()) {
                array.add(itemID);
            }
            json.add("potentialBankItemIDs", array);
            JsonWriter writer = new JsonWriter(new java.io.FileWriter(file));
            writer.setIndent("  ");
            new com.google.gson.GsonBuilder().create().toJson(json, JsonObject.class, writer);
            writer.close();
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
