package net.kroia.banksystem;


import net.kroia.banksystem.item.BankSystemItems;
import net.kroia.modutilities.ItemUtilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class BankSystemSettings {
    public static final class Player
    {
        public static final long STARTING_BALANCE = 0;
    }

    public static final class Bank
    {
        public static final int ITEM_TRANSFER_TICK_INTERVAL = 20;

        public static final Map<String, Boolean> ALLOWED_ITEM_IDS = new HashMap<>();
        static
        {
            ALLOWED_ITEM_IDS.put(ItemUtilities.getItemID(BankSystemItems.MONEY.get()), true);
            ALLOWED_ITEM_IDS.put("minecraft:iron_ingot", true);
            ALLOWED_ITEM_IDS.put("minecraft:gold_ingot", true);
            ALLOWED_ITEM_IDS.put("minecraft:diamond", true);
            ALLOWED_ITEM_IDS.put("minecraft:emerald", true);
            ALLOWED_ITEM_IDS.put("minecraft:coal", true);
        }

        public static final ArrayList<String> POTENTIAL_ITEM_TAGS = new ArrayList<>();
        static
        {
            POTENTIAL_ITEM_TAGS.add("ores");
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
        }

        public static final ArrayList<String> POTENTIAL_ITEM_BLACKLIST = new ArrayList<>();
        static
        {
            POTENTIAL_ITEM_BLACKLIST.add("minecraft:air");
            POTENTIAL_ITEM_BLACKLIST.add("minecraft:bedrock");
            POTENTIAL_ITEM_BLACKLIST.add("minecraft:barrier");
            POTENTIAL_ITEM_BLACKLIST.add("minecraft:structure_void");
            POTENTIAL_ITEM_BLACKLIST.add("minecraft:command_block");
            POTENTIAL_ITEM_BLACKLIST.add("minecraft:repeating_command_block");
            POTENTIAL_ITEM_BLACKLIST.add("minecraft:chain_command_block");
            POTENTIAL_ITEM_BLACKLIST.add("minecraft:debug_stick");
            POTENTIAL_ITEM_BLACKLIST.add("minecraft:knowledge_book");
            POTENTIAL_ITEM_BLACKLIST.add(ItemUtilities.getItemID(BankSystemItems.MONEY5.get()));
            POTENTIAL_ITEM_BLACKLIST.add(ItemUtilities.getItemID(BankSystemItems.MONEY10.get()));
            POTENTIAL_ITEM_BLACKLIST.add(ItemUtilities.getItemID(BankSystemItems.MONEY20.get()));
            POTENTIAL_ITEM_BLACKLIST.add(ItemUtilities.getItemID(BankSystemItems.MONEY50.get()));
            POTENTIAL_ITEM_BLACKLIST.add(ItemUtilities.getItemID(BankSystemItems.MONEY100.get()));
            POTENTIAL_ITEM_BLACKLIST.add(ItemUtilities.getItemID(BankSystemItems.MONEY200.get()));
            POTENTIAL_ITEM_BLACKLIST.add(ItemUtilities.getItemID(BankSystemItems.MONEY500.get()));
            POTENTIAL_ITEM_BLACKLIST.add(ItemUtilities.getItemID(BankSystemItems.MONEY1000.get()));
        }
        public static final ArrayList<String> NOT_REMOVABLE_ITEM_IDS = new ArrayList<>();
        static
        {
            NOT_REMOVABLE_ITEM_IDS.add(ItemUtilities.getItemID(BankSystemItems.MONEY.get()));
        }
    }
}
