package net.kroia.banksystem;


import com.google.gson.reflect.TypeToken;
import net.kroia.banksystem.item.custom.money.*;
import net.kroia.modutilities.setting.ModSettings;
import net.kroia.modutilities.setting.Setting;
import net.kroia.modutilities.setting.SettingsGroup;

import java.util.ArrayList;
import java.util.List;

public final class BankSystemModSettings extends ModSettings {

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;

    public final Utilities UTILITIES = createGroup(new Utilities());
    public final Player PLAYER = createGroup(new Player());
    public final Bank BANK = createGroup(new Bank());

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BankSystemModSettings.BACKEND_INSTANCES = backend;
    }

    public BankSystemModSettings() {
        super("BankSystemModSettings", "settings.json");
    }


    public static final class Utilities extends SettingsGroup
    {
        public final Setting<Long> SAVE_INTERVAL_MINUTES = registerSetting("SAVE_INTERVAL_MINUTES",5L, Long.class); // 5 minutes
        public final Setting<Boolean> LOGGING_ENABLE_INFO = registerSetting("LOGGING_ENABLE_INFO",true, Boolean.class);
        public final Setting<Boolean> LOGGING_ENABLE_WARNING = registerSetting("LOGGING_ENABLE_WARNING",true, Boolean.class);
        public final Setting<Boolean> LOGGING_ENABLE_ERROR = registerSetting("LOGGING_ENABLE_ERROR",true, Boolean.class);
        public final Setting<Boolean> LOGGING_ENABLE_DEBUG = registerSetting("LOGGING_ENABLE_DEBUG",false, Boolean.class);

        public Utilities() { super("Utilities"); }
    }
    public static final class Player extends SettingsGroup
    {
        public final Setting<Long> STARTING_BALANCE = registerSetting("STARTING_BALANCE", 0L, Long.class); // Starting balance for new players

        public Player() { super("Player"); }
    }



    public static final class Bank extends SettingsGroup
    {
        public final Setting<Integer> ITEM_TRANSFER_TICK_INTERVAL = registerSetting("ITEM_TRANSFER_TICK_INTERVAL", 5, Integer.class); // Interval in ticks for item transfer operations

        public final Setting<ArrayList<String>> ALLOWED_ITEM_IDS = registerSetting("ALLOWED_ITEM_IDS", new ArrayList<>(
                List.of(BankSystemMod.MOD_ID+":"+MoneyItem.NAME,
                        "minecraft:iron_ingot",
                        "minecraft:gold_ingot",
                        "minecraft:diamond",
                        "minecraft:emerald",
                        "minecraft:coal"
                )), // Default allowed item IDs
                new TypeToken<ArrayList<String>>() {}.getType()); // List of allowed item IDs for bank transactions

        public final Setting<ArrayList<String>> BLACKLIST_ITEM_IDS = registerSetting("BLACKLIST_ITEM_IDS", new ArrayList<>(
                        List.of("minecraft:air",
                                "minecraft:bedrock",
                                "minecraft:barrier",
                                "minecraft:structure_void",
                                "minecraft:command_block",
                                "minecraft:repeating_command_block",
                                "minecraft:chain_command_block",
                                "minecraft:debug_stick",
                                "minecraft:knowledge_book",

                                BankSystemMod.MOD_ID+":"+MoneyItem5.NAME,
                                BankSystemMod.MOD_ID+":"+MoneyItem10.NAME,
                                BankSystemMod.MOD_ID+":"+MoneyItem20.NAME,
                                BankSystemMod.MOD_ID+":"+MoneyItem50.NAME,
                                BankSystemMod.MOD_ID+":"+MoneyItem100.NAME,
                                BankSystemMod.MOD_ID+":"+MoneyItem200.NAME,
                                BankSystemMod.MOD_ID+":"+MoneyItem500.NAME,
                                BankSystemMod.MOD_ID+":"+MoneyItem1000.NAME
                        )), // Default allowed item IDs
                new TypeToken<ArrayList<String>>() {}.getType()); // List of allowed item IDs for bank transactions

        public final Setting<ArrayList<String>> NOT_REMOVABLE_ITEM_IDS = registerSetting("NOT_REMOVABLE_ITEM_IDS", new ArrayList<>(
                        List.of(BankSystemMod.MOD_ID+":"+MoneyItem.NAME
                        )), // Default allowed item IDs
                new TypeToken<ArrayList<String>>() {}.getType()); // List of allowed item IDs for bank transactions


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
    public String getSettingsFilePath() {
        return BACKEND_INSTANCES.SERVER_DATA_HANDLER.getSaveFolder().getPath();
    }


    @Override
    public boolean saveSettings() {
        boolean success = super.saveSettings();
        if (success) {
            BACKEND_INSTANCES.SERVER_EVENTS.SETTINGS_SAVED_TO_FILE.notifyListeners();
        }
        return success;
    }

    @Override
    public boolean loadSettings() {
        boolean success = super.loadSettings();
        if (success) {
            BACKEND_INSTANCES.SERVER_EVENTS.SETTINGS_LOADED_FROM_FILE.notifyListeners();
        }
        return success;
    }
}
