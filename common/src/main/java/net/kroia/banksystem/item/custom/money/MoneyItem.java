package net.kroia.banksystem.item.custom.money;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.item.BankSystemCreativeModeTab;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

public class MoneyItem extends Item{
    public static final String NAME = "money";
    private static final Component ITEM_NAME = Component.translatable("item."+ BankSystemMod.MOD_ID+".money_name");
    private static final Component CURRENCY_NAME = Component.translatable("item."+ BankSystemMod.MOD_ID+".currency");

    public static String getCurrencyName() {
        return CURRENCY_NAME.getString();
    }
    public static String getName() {
        return ITEM_NAME.getString();
    }

    public MoneyItem() {
        //super(new Properties().tab(BankSystemCreativeModeTab.BANK_SYSTEM_TAB)); // 1.19.2 and below
        super(new Properties().arch$tab(BankSystemCreativeModeTab.BANK_SYSTEM_TAB));
    }

    public int worth() {
        return 1;
    }

    public static boolean isMoney(String itemID)
    {
        // "money" -> "money"
        // "money10" -> "money"
        // "money20" -> "money"
        // "money50" -> "money"
        // "money100" -> "money"
        // "money1000" -> "money"

        boolean hasMoney = itemID.contains("money");
        String modID = itemID.split(":")[0];
        return hasMoney && modID.compareTo(BankSystemMod.MOD_ID) == 0;
    }
    public static boolean isMoney(ItemID itemID)
    {
        return isMoney(itemID.getName());
    }
}
