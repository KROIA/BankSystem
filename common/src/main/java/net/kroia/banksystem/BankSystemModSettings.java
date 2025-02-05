package net.kroia.banksystem;


import net.kroia.banksystem.item.custom.money.MoneyItem;
import net.kroia.banksystem.item.custom.money.MoneyItem5;
import net.kroia.banksystem.item.custom.money.MoneyItem10;
import net.kroia.banksystem.item.custom.money.MoneyItem20;
import net.kroia.banksystem.item.custom.money.MoneyItem50;
import net.kroia.banksystem.item.custom.money.MoneyItem100;
import net.kroia.banksystem.item.custom.money.MoneyItem200;
import net.kroia.banksystem.item.custom.money.MoneyItem500;
import net.kroia.banksystem.item.custom.money.MoneyItem1000;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class BankSystemModSettings {

    public static void init()
    {
        Bank.init();
    }

    public static void saveSettings(CompoundTag tag)
    {
        tag.putLong("player_starting_balance", Player.STARTING_BALANCE);
        tag.putInt("item_transfer_tick_interval", Bank.ITEM_TRANSFER_TICK_INTERVAL);
    }
    public static void readSettigns(CompoundTag tag)
    {
        if(tag.contains("player_starting_balance"))
            Player.STARTING_BALANCE = tag.getLong("player_starting_balance");
        if(tag.contains("item_transfer_tick_interval"))
            Bank.ITEM_TRANSFER_TICK_INTERVAL = tag.getInt("item_transfer_tick_interval");
    }

    public static final class Player
    {
        public static long STARTING_BALANCE = 0;
    }



    public static final class Bank
    {
        private static boolean isLoaded = false;
        public static int ITEM_TRANSFER_TICK_INTERVAL = 5;

        public static final Map<String, Boolean> ALLOWED_ITEM_IDS = new HashMap<>();
        public static final ArrayList<String> POTENTIAL_ITEM_BLACKLIST = new ArrayList<>();
        public static final ArrayList<String> NOT_REMOVABLE_ITEM_IDS = new ArrayList<>();

        public static void init()
        {
            if(isLoaded)
                return;

            ALLOWED_ITEM_IDS.put(BankSystemMod.MOD_ID+":"+MoneyItem.NAME, true);
            ALLOWED_ITEM_IDS.put("minecraft:iron_ingot", true);
            ALLOWED_ITEM_IDS.put("minecraft:gold_ingot", true);
            ALLOWED_ITEM_IDS.put("minecraft:diamond", true);
            ALLOWED_ITEM_IDS.put("minecraft:emerald", true);
            ALLOWED_ITEM_IDS.put("minecraft:coal", true);



            POTENTIAL_ITEM_BLACKLIST.add("minecraft:air");
            POTENTIAL_ITEM_BLACKLIST.add("minecraft:bedrock");
            POTENTIAL_ITEM_BLACKLIST.add("minecraft:barrier");
            POTENTIAL_ITEM_BLACKLIST.add("minecraft:structure_void");
            POTENTIAL_ITEM_BLACKLIST.add("minecraft:command_block");
            POTENTIAL_ITEM_BLACKLIST.add("minecraft:repeating_command_block");
            POTENTIAL_ITEM_BLACKLIST.add("minecraft:chain_command_block");
            POTENTIAL_ITEM_BLACKLIST.add("minecraft:debug_stick");
            POTENTIAL_ITEM_BLACKLIST.add("minecraft:knowledge_book");

            POTENTIAL_ITEM_BLACKLIST.add(BankSystemMod.MOD_ID+":"+ MoneyItem5.NAME);
            POTENTIAL_ITEM_BLACKLIST.add(BankSystemMod.MOD_ID+":"+ MoneyItem10.NAME);
            POTENTIAL_ITEM_BLACKLIST.add(BankSystemMod.MOD_ID+":"+MoneyItem20.NAME);
            POTENTIAL_ITEM_BLACKLIST.add(BankSystemMod.MOD_ID+":"+MoneyItem50.NAME);
            POTENTIAL_ITEM_BLACKLIST.add(BankSystemMod.MOD_ID+":"+MoneyItem100.NAME);
            POTENTIAL_ITEM_BLACKLIST.add(BankSystemMod.MOD_ID+":"+MoneyItem200.NAME);
            POTENTIAL_ITEM_BLACKLIST.add(BankSystemMod.MOD_ID+":"+MoneyItem500.NAME);
            POTENTIAL_ITEM_BLACKLIST.add(BankSystemMod.MOD_ID+":"+MoneyItem1000.NAME);

            NOT_REMOVABLE_ITEM_IDS.add(BankSystemMod.MOD_ID+":"+MoneyItem.NAME);

            isLoaded = true;
        }
    }
}
