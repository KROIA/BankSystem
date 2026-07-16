package net.kroia.banksystem;


import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.kroia.banksystem.minecraft.item.BankSystemItems;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.setting.ModSettings;
import net.kroia.modutilities.setting.Setting;
import net.kroia.modutilities.setting.SettingsGroup;
import net.kroia.modutilities.setting.parser.CustomJsonParser;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

public final class BankSystemModSettings extends ModSettings {

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;


    public static final int ITEM_FRACTION_SCALE_FACTOR = 100;

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
        public final Setting<Long> BALANCE_SNAPSHOT_INTERVAL_MINUTES = registerSetting("BALANCE_SNAPSHOT_INTERVAL_MINUTES",1L, Long.class); // 1 minute (for testing)
        // Max snapshot records per item per account. Oldest records are pruned when exceeded.
        // 0 = unlimited (WARNING: database file can grow extremely large over time)
        public final Setting<Long> BALANCE_SNAPSHOT_MAX_RECORDS_PER_ITEM = registerSetting("BALANCE_SNAPSHOT_MAX_RECORDS_PER_ITEM", 1440L, Long.class);
        public final Setting<Boolean> LOGGING_ENABLE_INFO = registerSetting("LOGGING_ENABLE_INFO",true, Boolean.class);
        public final Setting<Boolean> LOGGING_ENABLE_WARNING = registerSetting("LOGGING_ENABLE_WARNING",true, Boolean.class);
        public final Setting<Boolean> LOGGING_ENABLE_ERROR = registerSetting("LOGGING_ENABLE_ERROR",true, Boolean.class);
        public final Setting<Boolean> LOGGING_ENABLE_DEBUG = registerSetting("LOGGING_ENABLE_DEBUG",false, Boolean.class);
        //public final Setting<Integer> ADMIN_PERMISSION_LEVEL = registerSetting("ADMIN_PERMISSION_LEVEL",2, Integer.class);

        public Utilities() { super("Utilities"); }
    }
    public static final class Player extends SettingsGroup
    {
        public final Setting<Long> STARTING_BALANCE = registerSetting("STARTING_BALANCE", 0L, Long.class); // Starting balance for new players

        public Player() { super("Player"); }
    }



    public static final class Bank extends SettingsGroup
    {
        public static final class ItemIDArrayParser implements CustomJsonParser<List<ItemID>>
        {
            //private static final ItemStackJsonParser stackParser = new ItemStackJsonParser();
            @Override
            public JsonElement toJson(List<ItemID> value) {
                JsonArray jsonArray = new JsonArray();
                for (ItemID itemID : value) {
                    jsonArray.add(itemID.toJson());
                }
                return jsonArray;
            }

            @Override
            public List<ItemID> fromJson(JsonElement json) {
                List<ItemID> itemIDs = new ArrayList<>();
                if (json.isJsonArray()) {
                    JsonArray jsonArray = json.getAsJsonArray();
                    for (JsonElement element : jsonArray) {
                        ItemID id = ItemID.fromJson(element);
                        if (id != null) {
                           itemIDs.add(id);
                        }
                    }
                }
                return itemIDs;
            }
        }
        /*public static final class ItemIDAndScaleFactorListParser implements CustomJsonParser<List<ItemIDAndScaleFactor>>
        {

            //private static final ItemStackJsonParser stackParser = new ItemStackJsonParser();
            @Override
            public JsonElement toJson(List<ItemIDAndScaleFactor> value) {
                JsonArray jsonArray = new JsonArray();
                for (ItemIDAndScaleFactor item : value) {
                    JsonObject jsonObject = new JsonObject();
                    jsonObject.add("itemID", item.itemID.toJson());
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
                            ItemID itemID = ItemID.fromJson(jsonObject.get("itemID"));
                            int scaleFactor = jsonObject.has("itemFractionScaleFactor") ? jsonObject.get("itemFractionScaleFactor").getAsInt() : 1;
                            itemList.add(new ItemIDAndScaleFactor(itemID, scaleFactor));
                        }
                    }
                }
                return itemList;
            }
        }*/
        public final Setting<Integer> ITEM_TRANSFER_TICK_INTERVAL = registerSetting("ITEM_TRANSFER_TICK_INTERVAL", 2, Integer.class); // Interval in ticks for item transfer operations

        /**
         * Additional <b>volatile item component</b> type ids (e.g. {@code "tfc:food"}) that are
         * stripped from ItemStacks before they participate in ItemID identity.
         * This list extends the datapack tag {@code banksystem:volatile_item_components}
         * (see {@code data/banksystem/tags/data_component_type/volatile_item_components.json})
         * and is meant for server admins who cannot ship a datapack.
         * The list is synced to clients and forwarded to slave servers automatically.
         */
        public final Setting<List<String>> ADDITIONAL_VOLATILE_COMPONENTS = registerSetting(
                "ADDITIONAL_VOLATILE_COMPONENTS",
                new ArrayList<>(),
                new TypeToken<List<String>>() {}.getType());

        /**
         * Additional <b>deposit-gated item component</b> type ids (e.g. {@code "tfc:food"}).
         * Gated components are ignored for ItemID identity (like volatile components), but a
         * physical stack carrying one may only be deposited if it is equivalent to the fresh
         * stack the bank would hand back — this blocks laundering item state through the bank
         * (deposit rotten food, withdraw fresh).
         * This list extends the datapack tag {@code banksystem:deposit_gated_components}
         * (see {@code data/banksystem/tags/data_component_type/deposit_gated_components.json})
         * and is meant for server admins who cannot ship a datapack.
         * The list is synced to clients and forwarded to slave servers automatically.
         */
        public final Setting<List<String>> ADDITIONAL_DEPOSIT_GATED_COMPONENTS = registerSetting(
                "ADDITIONAL_DEPOSIT_GATED_COMPONENTS",
                new ArrayList<>(),
                new TypeToken<List<String>>() {}.getType());

        /**
         * <b>One-shot confirmation flag for the ItemID merge guard.</b>
         * <p>
         * When the effective volatile/deposit-gated component set (datapack tags + the two
         * config lists above) changed since the world was last normalized AND applying the
         * new set would irreversibly merge genuinely distinct ItemIDs (e.g. two different
         * bank items or markets becoming one), the master server <b>refuses to start</b> and
         * logs a report listing every merge. Setting this flag to {@code true} approves that
         * exact merge on the next startup; after the merge is applied the flag is
         * automatically reset to {@code false} and saved, so it can never act as a standing
         * bypass. Harmless <i>healing</i> merges (duplicates collapsing under an unchanged
         * set) never require confirmation. See {@code ItemIDManager.detectCollapseCollisions}.
         */
        public final Setting<Boolean> CONFIRM_ITEMID_MERGE = registerSetting(
                "CONFIRM_ITEMID_MERGE", false, Boolean.class);

        /**
         * <b>One-shot confirmation flag for the ItemID world-repair guard.</b>
         * <p>
         * When world load detects the cent-shift corruption signature (an
         * {@code ItemIDs.nbt} overwritten by a pre-v2.0.3 buggy build with a fresh
         * cent-shifted default mapping while all other world data still references the old
         * shorts — see {@code ItemIDWorldRepair}), the master server <b>refuses to start</b>
         * and logs a full repair report with the proposed short→item remap table. Setting
         * this flag to {@code true} approves that exact repair on the next startup: the old
         * mapping is restored, the previous {@code ItemIDs.nbt} is copied aside as
         * {@code ItemIDs.nbt.pre-repair-<timestamp>}, and the repaired state is persisted.
         * After the repair is applied the flag is automatically reset to {@code false} and
         * saved, so it can never act as a standing bypass — the exact same one-shot contract
         * as {@link #CONFIRM_ITEMID_MERGE}. Healthy worlds never trigger the guard.
         */
        public final Setting<Boolean> CONFIRM_ITEMID_REPAIR = registerSetting(
                "CONFIRM_ITEMID_REPAIR", false, Boolean.class);


        public final List<ItemStack> INITIAL_ALLOWED_ITEMS = createInitialAllowedItems();

        /**
         * Builds the initial allowed-items list. Static so pure consumers (in particular
         * {@code ItemIDWorldRepair.simulateBootOrderAssignment()}, which reconstructs the
         * historical boot-time ItemID registration order) can obtain the exact list content
         * without a live settings instance. The instance field above is what production
         * code reads; the list content is a build-time constant either way.
         */
        public static List<ItemStack> createInitialAllowedItems() {
            return List.of(
                    BankSystemItems.MONEY.get().getDefaultInstance(),
                    Items.IRON_INGOT.getDefaultInstance(),
                    Items.GOLD_INGOT.getDefaultInstance(),
                    Items.DIAMOND.getDefaultInstance(),
                    Items.EMERALD.getDefaultInstance(),
                    Items.COAL.getDefaultInstance());
        }

        //public final Setting<List<ItemIDAndScaleFactor>> INITIAL_ALLOWED_ITEM_IDS = registerSetting("INITIAL_ALLOWED_ITEM_IDS",
        //        new ArrayList<>(List.of(
        //                new ItemIDAndScaleFactor(new ItemID(BankSystemMod.MOD_ID+":"+MoneyItem.NAME), MoneyItem.ITEM_FRACTION_SCALE_FACTOR),
        //                new ItemIDAndScaleFactor(new ItemID("minecraft:iron_ingot")),
        //                new ItemIDAndScaleFactor(new ItemID("minecraft:gold_ingot")),
        //                new ItemIDAndScaleFactor(new ItemID("minecraft:diamond")),
        //                new ItemIDAndScaleFactor(new ItemID("minecraft:emerald")),
        //                new ItemIDAndScaleFactor(new ItemID("minecraft:coal"))
        //        )), // Default allowed item IDs
        //        new TypeToken<List<ItemIDAndScaleFactor>>() {}.getType(),
        //        new ItemIDAndScaleFactorListParser()); // List of allowed item IDs for bank transactions

        public final List<ItemStack> INITIAL_BLACKLIST_ITEMS = createInitialBlacklistItems();

        /**
         * Builds the initial blacklist list. Static for the same reason as
         * {@link #createInitialAllowedItems()} — the list ORDER is load-bearing for
         * {@code ItemIDWorldRepair}'s boot-order candidate mapping: builds that registered
         * the blacklist pre-load minted shorts in exactly this sequence (bedrock=1, ...,
         * money200=19, ...). Do not reorder without checking that consumer.
         */
        public static List<ItemStack> createInitialBlacklistItems() {
            return List.of(
                    //Items.AIR.getDefaultInstance(),
                    Items.BEDROCK.getDefaultInstance(),
                    Items.BARRIER.getDefaultInstance(),
                    Items.STRUCTURE_VOID.getDefaultInstance(),
                    Items.COMMAND_BLOCK.getDefaultInstance(),
                    Items.REPEATING_COMMAND_BLOCK.getDefaultInstance(),
                    Items.CHAIN_COMMAND_BLOCK.getDefaultInstance(),
                    Items.DEBUG_STICK.getDefaultInstance(),
                    Items.KNOWLEDGE_BOOK.getDefaultInstance(),
                    BankSystemItems.MONEY_CENT1.get().getDefaultInstance(),
                    BankSystemItems.MONEY_CENT5.get().getDefaultInstance(),
                    BankSystemItems.MONEY_CENT10.get().getDefaultInstance(),
                    BankSystemItems.MONEY_CENT20.get().getDefaultInstance(),
                    BankSystemItems.MONEY_CENT50.get().getDefaultInstance(),
                    BankSystemItems.MONEY5.get().getDefaultInstance(),
                    BankSystemItems.MONEY10.get().getDefaultInstance(),
                    BankSystemItems.MONEY20.get().getDefaultInstance(),
                    BankSystemItems.MONEY50.get().getDefaultInstance(),
                    BankSystemItems.MONEY100.get().getDefaultInstance(),
                    BankSystemItems.MONEY200.get().getDefaultInstance(),
                    BankSystemItems.MONEY500.get().getDefaultInstance(),
                    BankSystemItems.MONEY1000.get().getDefaultInstance()
            );
        }

       /* public final Setting<List<ItemID>> BLACKLIST_ITEM_IDS = registerSetting("BLACKLIST_ITEM_IDS",
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
*/

        public final List<ItemStack> INITIAL_NOT_REMOVABLE_ITEMS = createInitialNotRemovableItems();

        /**
         * Builds the initial not-removable list. Static for the same reason as
         * {@link #createInitialAllowedItems()} (boot-order reconstruction in
         * {@code ItemIDWorldRepair}).
         */
        public static List<ItemStack> createInitialNotRemovableItems() {
            return List.of(
                    BankSystemItems.MONEY.get().getDefaultInstance()
            );
        }

        //public final Setting<List<ItemID>> NOT_REMOVABLE_ITEM_IDS = registerSetting("NOT_REMOVABLE_ITEM_IDS",
        //        new ArrayList<>(List.of(new ItemID(BankSystemMod.MOD_ID+":"+MoneyItem.NAME)
        //        )), // Default allowed item IDs
        //        new TypeToken<List<ItemID>>() {}.getType(),
        //        new ItemIDArrayParser()); // List of allowed item IDs for bank transactions

        public final Setting<Integer> BANK_DOWNLOAD_BLOCK_UPDATE_TICK_INTERVAL = registerSetting("BANK_DOWNLOAD_BLOCK_UPDATE_TICK_INTERVAL", 20, Integer.class); // Interval in ticks for bank download block updates
        public final Setting<Integer> BANK_UPLOAD_BLOCK_UPDATE_TICK_INTERVAL = registerSetting("BANK_UPLOAD_BLOCK_UPDATE_TICK_INTERVAL", 20, Integer.class); // Interval in ticks for bank upload block updates

        public Bank()
        {
            super("ServerBank");
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
