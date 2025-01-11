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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class BankSystemModSettings {

    public static void init()
    {
        Bank.init();
    }
    public static final class Player
    {
        public static final long STARTING_BALANCE = 0;
    }

    public static final class Bank
    {
        private static boolean isLoaded = false;
        public static final int ITEM_TRANSFER_TICK_INTERVAL = 20;

        public static final Map<String, Boolean> ALLOWED_ITEM_IDS = new HashMap<>();
        //public static final ArrayList<String> POTENTIAL_ITEM_TAGS = new ArrayList<>();
        //public static final ArrayList<String> POTENTIAL_ITEM_CONTAINS_STR = new ArrayList<>();
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


           /* POTENTIAL_ITEM_TAGS.add("ores");
            POTENTIAL_ITEM_TAGS.add("ingots");
            POTENTIAL_ITEM_TAGS.add("planks");
            POTENTIAL_ITEM_TAGS.add("logs");
            POTENTIAL_ITEM_TAGS.add("gemstones");
            POTENTIAL_ITEM_TAGS.add("cobblestone");
            POTENTIAL_ITEM_TAGS.add("stone");
            POTENTIAL_ITEM_TAGS.add("wool");
            POTENTIAL_ITEM_TAGS.add("crops");
            POTENTIAL_ITEM_TAGS.add("seeds");
            POTENTIAL_ITEM_TAGS.add("flowers");
            POTENTIAL_ITEM_TAGS.add("dirt");
            POTENTIAL_ITEM_TAGS.add("sand");
            POTENTIAL_ITEM_TAGS.add("glass");
            POTENTIAL_ITEM_TAGS.add("gravel");
            POTENTIAL_ITEM_TAGS.add("gems");
            POTENTIAL_ITEM_TAGS.add("trim_materials");
            POTENTIAL_ITEM_TAGS.add("trim_blocks");
            POTENTIAL_ITEM_TAGS.add("wool");
            POTENTIAL_ITEM_TAGS.add("foods");
            POTENTIAL_ITEM_TAGS.add("ender_pearls");

            POTENTIAL_ITEM_CONTAINS_STR.add("ingot");
            POTENTIAL_ITEM_CONTAINS_STR.add("ore");
            POTENTIAL_ITEM_CONTAINS_STR.add("cooked");
            POTENTIAL_ITEM_CONTAINS_STR.add("ender_eye");
            POTENTIAL_ITEM_CONTAINS_STR.add("ender_pearl");
            POTENTIAL_ITEM_CONTAINS_STR.add("honey");

            // Foods
            POTENTIAL_ITEM_CONTAINS_STR.add("beetroot");
            POTENTIAL_ITEM_CONTAINS_STR.add("apple");
            POTENTIAL_ITEM_CONTAINS_STR.add("golden_carrot");
            POTENTIAL_ITEM_CONTAINS_STR.add("honey_bottle");
            POTENTIAL_ITEM_CONTAINS_STR.add("rabbit_stew");
            POTENTIAL_ITEM_CONTAINS_STR.add("porkchop");
            POTENTIAL_ITEM_CONTAINS_STR.add("chicken");
            POTENTIAL_ITEM_CONTAINS_STR.add("cooked_beef");
            POTENTIAL_ITEM_CONTAINS_STR.add("rotten_flesh");
            POTENTIAL_ITEM_CONTAINS_STR.add("potato");
            POTENTIAL_ITEM_CONTAINS_STR.add("cooked_rabbit");
            POTENTIAL_ITEM_CONTAINS_STR.add("spider_eye");
            POTENTIAL_ITEM_CONTAINS_STR.add("tropical_fish");
            POTENTIAL_ITEM_CONTAINS_STR.add("beef");
            POTENTIAL_ITEM_CONTAINS_STR.add("mutton");
            POTENTIAL_ITEM_CONTAINS_STR.add("cooked_cod");
            POTENTIAL_ITEM_CONTAINS_STR.add("cod");
            POTENTIAL_ITEM_CONTAINS_STR.add("salmon");
            POTENTIAL_ITEM_CONTAINS_STR.add("baked_potato");
            POTENTIAL_ITEM_CONTAINS_STR.add("rabbit");
            POTENTIAL_ITEM_CONTAINS_STR.add("cooked_porkchop");
            POTENTIAL_ITEM_CONTAINS_STR.add("bread");
            POTENTIAL_ITEM_CONTAINS_STR.add("carrot");
            POTENTIAL_ITEM_CONTAINS_STR.add("glow_berries");
            POTENTIAL_ITEM_CONTAINS_STR.add("cooked_chicken");
            POTENTIAL_ITEM_CONTAINS_STR.add("sweet_berries");
            POTENTIAL_ITEM_CONTAINS_STR.add("golden_apple");
            POTENTIAL_ITEM_CONTAINS_STR.add("cooked_mutton");
            POTENTIAL_ITEM_CONTAINS_STR.add("pufferfish");
            POTENTIAL_ITEM_CONTAINS_STR.add("beetroot_soup");
            POTENTIAL_ITEM_CONTAINS_STR.add("cookie");
            POTENTIAL_ITEM_CONTAINS_STR.add("cooked_salmon");
            POTENTIAL_ITEM_CONTAINS_STR.add("suspicious_stew");
            POTENTIAL_ITEM_CONTAINS_STR.add("mushroom_stew");
            POTENTIAL_ITEM_CONTAINS_STR.add("pumpkin_pie");
            POTENTIAL_ITEM_CONTAINS_STR.add("enchanted_golden_apple");
            POTENTIAL_ITEM_CONTAINS_STR.add("dried_kelp");
            POTENTIAL_ITEM_CONTAINS_STR.add("chorus_fruit");
            POTENTIAL_ITEM_CONTAINS_STR.add("melon_slice");
            POTENTIAL_ITEM_CONTAINS_STR.add("poisonous_potato");
*/



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
