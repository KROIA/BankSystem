package net.kroia.banksystem;


import net.kroia.banksystem.item.BankSystemItems;
import net.kroia.modutilities.ItemUtilities;

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
    }
}
