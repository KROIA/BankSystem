package net.kroia.banksystem;


import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import net.kroia.banksystem.item.custom.money.*;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.setting.ModSettings;
import net.kroia.modutilities.setting.Setting;
import net.kroia.modutilities.setting.SettingsGroup;
import net.kroia.modutilities.setting.parser.CustomJsonParser;
import net.kroia.modutilities.setting.parser.ItemStackJsonParser;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class BankSystemModSettings extends ModSettings {

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;

    public final Utilities UTILITIES = createGroup(new Utilities());
    public final Player PLAYER = createGroup(new Player());
    public final Bank BANK = createGroup(new Bank());

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    public BankSystemModSettings() {
        super("BankSystemModSettings");
    }


    public static final class Utilities extends SettingsGroup
    {
        public final Setting<Long> SAVE_INTERVAL_MINUTES = registerSetting("SAVE_INTERVAL_MINUTES",5L, Long.class); // 5 minutes
        public final Setting<Boolean> LOGGING_ENABLE_INFO = registerSetting("LOGGING_ENABLE_INFO",true, Boolean.class);
        public final Setting<Boolean> LOGGING_ENABLE_WARNING = registerSetting("LOGGING_ENABLE_WARNING",true, Boolean.class);
        public final Setting<Boolean> LOGGING_ENABLE_ERROR = registerSetting("LOGGING_ENABLE_ERROR",true, Boolean.class);
        public final Setting<Boolean> LOGGING_ENABLE_DEBUG = registerSetting("LOGGING_ENABLE_DEBUG",false, Boolean.class);
        public final Setting<Integer> ADMIN_PERMISSION_LEVEL = registerSetting("ADMIN_PERMISSION_LEVEL",2, Integer.class);

        public Utilities() { super("Utilities"); }
    }
    public static final class Player extends SettingsGroup
    {
        public final Setting<Float> STARTING_BALANCE = registerSetting("STARTING_BALANCE", 0.f, Float.class); // Starting balance for new players

        public Player() { super("Player"); }
    }



    public static final class Bank extends SettingsGroup
    {
        public static final class ItemIDArrayParser implements CustomJsonParser<List<ItemID>>
        {
            private static final ItemStackJsonParser stackParser = new ItemStackJsonParser();
            @Override
            public JsonElement toJson(List<ItemID> value) {
                JsonArray jsonArray = new JsonArray();
                for (ItemID itemID : value) {
                    jsonArray.add(stackParser.toJson(itemID.getStack()));
                }
                return jsonArray;
            }

            @Override
            public List<ItemID> fromJson(JsonElement json) {
                List<ItemID> itemIDs = new ArrayList<>();
                if (json.isJsonArray()) {
                    JsonArray jsonArray = json.getAsJsonArray();
                    for (JsonElement element : jsonArray) {
                        ItemStack stack = stackParser.fromJson(element);
                        if (stack != null && !stack.isEmpty()) {
                            itemIDs.add(new ItemID(stack));
                        }
                    }
                }
                return itemIDs;
            }
        }
        public final Setting<Integer> ITEM_TRANSFER_TICK_INTERVAL = registerSetting("ITEM_TRANSFER_TICK_INTERVAL", 5, Integer.class); // Interval in ticks for item transfer operations

        public final Setting<List<ItemID>> ALLOWED_ITEM_IDS = registerSetting("ALLOWED_ITEM_IDS",
                new ArrayList<>(List.of(new ItemID(BankSystemMod.MOD_ID+":"+MoneyItem.NAME),
                        new ItemID("minecraft:iron_ingot"),
                        new ItemID("minecraft:gold_ingot"),
                        new ItemID("minecraft:diamond"),
                        new ItemID("minecraft:emerald"),
                        new ItemID("minecraft:coal")
                )), // Default allowed item IDs
                new TypeToken<List<ItemID>>() {}.getType(),
                new ItemIDArrayParser()); // List of allowed item IDs for bank transactions

        public final Setting<List<ItemID>> BLACKLIST_ITEM_IDS = registerSetting("BLACKLIST_ITEM_IDS",
                new ArrayList<>(List.of(new ItemID("minecraft:air"),
                                new ItemID("minecraft:bedrock"),
                                new ItemID("minecraft:barrier"),
                                new ItemID("minecraft:structure_void"),
                                new ItemID("minecraft:command_block"),
                                new ItemID("minecraft:repeating_command_block"),
                                new ItemID("minecraft:chain_command_block"),
                                new ItemID("minecraft:debug_stick"),
                                new ItemID("minecraft:knowledge_book"),

                                new ItemID(BankSystemMod.MOD_ID+":"+MoneyItem5.NAME),
                                new ItemID(BankSystemMod.MOD_ID+":"+MoneyItem10.NAME),
                                new ItemID(BankSystemMod.MOD_ID+":"+MoneyItem20.NAME),
                                new ItemID(BankSystemMod.MOD_ID+":"+MoneyItem50.NAME),
                                new ItemID(BankSystemMod.MOD_ID+":"+MoneyItem100.NAME),
                                new ItemID(BankSystemMod.MOD_ID+":"+MoneyItem200.NAME),
                                new ItemID(BankSystemMod.MOD_ID+":"+MoneyItem500.NAME),
                                new ItemID(BankSystemMod.MOD_ID+":"+MoneyItem1000.NAME)
                        )), // Default allowed item IDs
                new TypeToken<List<ItemID>>() {}.getType(),
                new ItemIDArrayParser()); // List of allowed item IDs for bank transactions

        public final Setting<List<ItemID>> NOT_REMOVABLE_ITEM_IDS = registerSetting("NOT_REMOVABLE_ITEM_IDS",
                new ArrayList<>(List.of(new ItemID(BankSystemMod.MOD_ID+":"+MoneyItem.NAME)
                )), // Default allowed item IDs
                new TypeToken<List<ItemID>>() {}.getType(),
                new ItemIDArrayParser()); // List of allowed item IDs for bank transactions

        public final Setting<Integer> BANK_DOWNLOAD_BLOCK_UPDATE_TICK_INTERVAL = registerSetting("BANK_DOWNLOAD_BLOCK_UPDATE_TICK_INTERVAL", 20, Integer.class); // Interval in ticks for bank download block updates
        public final Setting<Integer> BANK_UPLOAD_BLOCK_UPDATE_TICK_INTERVAL = registerSetting("BANK_UPLOAD_BLOCK_UPDATE_TICK_INTERVAL", 20, Integer.class); // Interval in ticks for bank download block updates

        public Bank()
        {
            super("Bank");
        }
    }






    /**
     * ---------------------------------------------------------------------------------------
     *                Utilities for creating and managing settings groups
     * ---------------------------------------------------------------------------------------
     */

    @Override
    public boolean saveSettings(String filePath) {
        boolean success = super.saveSettings(filePath);
        if (success) {
            BACKEND_INSTANCES.SERVER_EVENTS.SETTINGS_SAVED_TO_FILE.notifyListeners();
        }
        return success;
    }

    @Override
    public boolean loadSettings(String filePaht) {
        boolean success = super.loadSettings(filePaht);
        if (success) {
            BACKEND_INSTANCES.SERVER_EVENTS.SETTINGS_LOADED_FROM_FILE.notifyListeners();
        }
        return success;
    }
}
