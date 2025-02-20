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
import net.kroia.banksystem.util.ItemID;
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

        //public static final Map<ItemID, Boolean> ALLOWED_ITEM_IDS = new HashMap<>();
        //public static final ArrayList<ItemID> POTENTIAL_ITEM_BLACKLIST = new ArrayList<>();
        //public static final ArrayList<ItemID> NOT_REMOVABLE_ITEM_IDS = new ArrayList<>();

        public static void init()
        {
            if(isLoaded)
                return;
/*
            ALLOWED_ITEM_IDS.put(new ItemID(BankSystemMod.MOD_ID+":"+MoneyItem.NAME), true);
            ALLOWED_ITEM_IDS.put(new ItemID("minecraft:iron_ingot"), true);
            ALLOWED_ITEM_IDS.put(new ItemID("minecraft:gold_ingot"), true);
            ALLOWED_ITEM_IDS.put(new ItemID("minecraft:diamond"), true);
            ALLOWED_ITEM_IDS.put(new ItemID("minecraft:emerald"), true);
            ALLOWED_ITEM_IDS.put(new ItemID("minecraft:coal"), true);



            POTENTIAL_ITEM_BLACKLIST.add(new ItemID("minecraft:air"));
            POTENTIAL_ITEM_BLACKLIST.add(new ItemID("minecraft:bedrock"));
            POTENTIAL_ITEM_BLACKLIST.add(new ItemID("minecraft:barrier"));
            POTENTIAL_ITEM_BLACKLIST.add(new ItemID("minecraft:structure_void"));
            POTENTIAL_ITEM_BLACKLIST.add(new ItemID("minecraft:command_block"));
            POTENTIAL_ITEM_BLACKLIST.add(new ItemID("minecraft:repeating_command_block"));
            POTENTIAL_ITEM_BLACKLIST.add(new ItemID("minecraft:chain_command_block"));
            POTENTIAL_ITEM_BLACKLIST.add(new ItemID("minecraft:debug_stick"));
            POTENTIAL_ITEM_BLACKLIST.add(new ItemID("minecraft:knowledge_book"));

            POTENTIAL_ITEM_BLACKLIST.add(new ItemID(BankSystemMod.MOD_ID+":"+ MoneyItem5.NAME));
            POTENTIAL_ITEM_BLACKLIST.add(new ItemID(BankSystemMod.MOD_ID+":"+ MoneyItem10.NAME));
            POTENTIAL_ITEM_BLACKLIST.add(new ItemID(BankSystemMod.MOD_ID+":"+MoneyItem20.NAME));
            POTENTIAL_ITEM_BLACKLIST.add(new ItemID(BankSystemMod.MOD_ID+":"+MoneyItem50.NAME));
            POTENTIAL_ITEM_BLACKLIST.add(new ItemID(BankSystemMod.MOD_ID+":"+MoneyItem100.NAME));
            POTENTIAL_ITEM_BLACKLIST.add(new ItemID(BankSystemMod.MOD_ID+":"+MoneyItem200.NAME));
            POTENTIAL_ITEM_BLACKLIST.add(new ItemID(BankSystemMod.MOD_ID+":"+MoneyItem500.NAME));
            POTENTIAL_ITEM_BLACKLIST.add(new ItemID(BankSystemMod.MOD_ID+":"+MoneyItem1000.NAME));

            NOT_REMOVABLE_ITEM_IDS.add(new ItemID(BankSystemMod.MOD_ID+":"+MoneyItem.NAME));
*/
            isLoaded = true;
        }
        public static Map<ItemID, Boolean> getAllowedItemIDs()
        {
            Map<ItemID, Boolean> itemIDs = new HashMap<>();
            itemIDs.put(new ItemID(BankSystemMod.MOD_ID+":"+MoneyItem.NAME), true);
            itemIDs.put(new ItemID("minecraft:iron_ingot"), true);
            itemIDs.put(new ItemID("minecraft:gold_ingot"), true);
            itemIDs.put(new ItemID("minecraft:diamond"), true);
            itemIDs.put(new ItemID("minecraft:emerald"), true);
            itemIDs.put(new ItemID("minecraft:coal"), true);
            return itemIDs;
        }
        public static ArrayList<ItemID> getPotentialItemBlacklist()
        {
            ArrayList<ItemID> itemIDs = new ArrayList<>();
            itemIDs.add(new ItemID("minecraft:air"));
            itemIDs.add(new ItemID("minecraft:bedrock"));
            itemIDs.add(new ItemID("minecraft:barrier"));
            itemIDs.add(new ItemID("minecraft:structure_void"));
            itemIDs.add(new ItemID("minecraft:command_block"));
            itemIDs.add(new ItemID("minecraft:repeating_command_block"));
            itemIDs.add(new ItemID("minecraft:chain_command_block"));
            itemIDs.add(new ItemID("minecraft:debug_stick"));
            itemIDs.add(new ItemID("minecraft:knowledge_book"));

            itemIDs.add(new ItemID(BankSystemMod.MOD_ID+":"+ MoneyItem5.NAME));
            itemIDs.add(new ItemID(BankSystemMod.MOD_ID+":"+ MoneyItem10.NAME));
            itemIDs.add(new ItemID(BankSystemMod.MOD_ID+":"+MoneyItem20.NAME));
            itemIDs.add(new ItemID(BankSystemMod.MOD_ID+":"+MoneyItem50.NAME));
            itemIDs.add(new ItemID(BankSystemMod.MOD_ID+":"+MoneyItem100.NAME));
            itemIDs.add(new ItemID(BankSystemMod.MOD_ID+":"+MoneyItem200.NAME));
            itemIDs.add(new ItemID(BankSystemMod.MOD_ID+":"+MoneyItem500.NAME));
            itemIDs.add(new ItemID(BankSystemMod.MOD_ID+":"+MoneyItem1000.NAME));
            return itemIDs;
        }

        public static ArrayList<ItemID> getNotRemovableItemIDs()
        {
            ArrayList<ItemID> itemIDs = new ArrayList<>();
            itemIDs.add(new ItemID(BankSystemMod.MOD_ID+":"+MoneyItem.NAME));
            return itemIDs;
        }
    }
}
