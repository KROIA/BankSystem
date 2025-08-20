package net.kroia.banksystem;


import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
    public final Placeholder PLACEHOLDER = createGroup(new Placeholder());

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
        public static final class ItemIDAndScaleFactor
        {
            public ItemID itemID;
            public int itemFractionScaleFactor = 1;
            public ItemIDAndScaleFactor()
            {

            }
            public ItemIDAndScaleFactor(ItemID itemID, int itemFractionScaleFactor)
            {
                this.itemID = itemID;
                this.itemFractionScaleFactor = itemFractionScaleFactor;
            }
            public ItemIDAndScaleFactor(ItemID itemID)
            {
                this.itemID = itemID;
                this.itemFractionScaleFactor = 1;
            }
        }
        public static final class ItemIDAndScaleFactorListParser implements CustomJsonParser<List<ItemIDAndScaleFactor>>
        {

            private static final ItemStackJsonParser stackParser = new ItemStackJsonParser();
            @Override
            public JsonElement toJson(List<ItemIDAndScaleFactor> value) {
                JsonArray jsonArray = new JsonArray();
                for (ItemIDAndScaleFactor item : value) {
                    JsonObject jsonObject = new JsonObject();
                    jsonObject.add("itemID", stackParser.toJson(item.itemID.getStack()));
                    jsonObject.addProperty("itemFractionScaleFactor", item.itemFractionScaleFactor);
                    jsonArray.add(jsonObject);
                }
                return jsonArray;
            }

            @Override
            public List<ItemIDAndScaleFactor> fromJson(JsonElement json) {
                List<ItemIDAndScaleFactor> itemList = new ArrayList<>();
                if (json.isJsonArray()) {
                    JsonArray jsonArray = json.getAsJsonArray();
                    for (JsonElement element : jsonArray) {
                        if (element.isJsonObject()) {
                            JsonObject jsonObject = element.getAsJsonObject();
                            ItemID itemID = new ItemID(stackParser.fromJson(jsonObject.get("itemID")));
                            int scaleFactor = jsonObject.has("itemFractionScaleFactor") ? jsonObject.get("itemFractionScaleFactor").getAsInt() : 1;
                            itemList.add(new ItemIDAndScaleFactor(itemID, scaleFactor));
                        }
                    }
                }
                return itemList;
            }
        }
        public final Setting<Integer> ITEM_TRANSFER_TICK_INTERVAL = registerSetting("ITEM_TRANSFER_TICK_INTERVAL", 2, Integer.class); // Interval in ticks for item transfer operations

        public final Setting<List<ItemIDAndScaleFactor>> INITIAL_ALLOWED_ITEM_IDS = registerSetting("INITIAL_ALLOWED_ITEM_IDS",
                new ArrayList<>(List.of(
                        new ItemIDAndScaleFactor(new ItemID(BankSystemMod.MOD_ID+":"+MoneyItem.NAME), MoneyItem.ITEM_FRACTION_SCALE_FACTOR),
                        new ItemIDAndScaleFactor(new ItemID("minecraft:iron_ingot")),
                        new ItemIDAndScaleFactor(new ItemID("minecraft:gold_ingot")),
                        new ItemIDAndScaleFactor(new ItemID("minecraft:diamond")),
                        new ItemIDAndScaleFactor(new ItemID("minecraft:emerald")),
                        new ItemIDAndScaleFactor(new ItemID("minecraft:coal"))
                )), // Default allowed item IDs
                new TypeToken<List<ItemIDAndScaleFactor>>() {}.getType(),
                new ItemIDAndScaleFactorListParser()); // List of allowed item IDs for bank transactions

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


    public static final class Placeholder extends SettingsGroup
    {
        public static final class PlaceholderSettingParser implements CustomJsonParser<PlaceholderSettingData> {

            @Override
            public JsonElement toJson(PlaceholderSettingData value) {
                JsonObject jsonObject = new JsonObject();
                jsonObject.addProperty("identifier", value.getIdentifier());
                jsonObject.addProperty("refreshRate", value.getRefreshRate());
                return jsonObject;
            }

            @Override
            public PlaceholderSettingData fromJson(JsonElement json) {
                if (json.isJsonObject()) {
                    JsonObject jsonObject = json.getAsJsonObject();
                    String identifier = jsonObject.has("identifier") ? jsonObject.get("identifier").getAsString() : "";
                    int refreshRate = jsonObject.has("refreshRate") ? jsonObject.get("refreshRate").getAsInt() : 0;
                    return new PlaceholderSettingData(identifier, refreshRate);
                }
                return null; // or throw an exception if invalid format
            }
        }
        public static final class PlaceholderSettingData
        {
            private String identifier;
            private int refreshRate;

            public PlaceholderSettingData(String identifier, int refreshRate) {
                this.identifier = identifier;
                this.refreshRate = refreshRate;
            }
            public String getIdentifier() {
                return identifier;
            }
            public int getRefreshRate() {
                return refreshRate;
            }
        }
        private static final PlaceholderSettingParser parser = new PlaceholderSettingParser();
        public final Setting<PlaceholderSettingData> PLAYER_BALANCE = registerSetting("PLAYER_BALANCE",new PlaceholderSettingData("%banksystem_player_balance%", 1000), PlaceholderSettingData.class, parser);
        public final Setting<PlaceholderSettingData> PLAYER_LOCKED_BALANCE = registerSetting("PLAYER_LOCKED_BALANCE",new PlaceholderSettingData("%banksystem_player_locked_balance%", 1000), PlaceholderSettingData.class, parser);
        public final Setting<PlaceholderSettingData> PLAYER_TOTAL_BALANCE = registerSetting("PLAYER_TOTAL_BALANCE",new PlaceholderSettingData("%banksystem_player_total_balance%", 1000), PlaceholderSettingData.class, parser);
        public final Setting<PlaceholderSettingData> PLAYER_BANKUSER_JSON = registerSetting("PLAYER_BANKUSER_JSON",new PlaceholderSettingData("%banksystem_bankuser_json%", 10000), PlaceholderSettingData.class, parser);
        public final Setting<PlaceholderSettingData> SERVER_CIRCULATION_JSON = registerSetting("SERVER_CIRCULATION_JSON",new PlaceholderSettingData("%banksystem_server_circulation_json%", 10000), PlaceholderSettingData.class, parser);


        public Placeholder() { super("Placeholder"); }
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
