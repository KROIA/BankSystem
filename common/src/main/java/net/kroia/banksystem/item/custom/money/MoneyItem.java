package net.kroia.banksystem.item.custom.money;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.item.BankSystemCreativeModeTab;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

public class MoneyItem extends Item{
    public static final String NAME = "money";
    //public static final String CURRENCY_NAME = "Money";
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
}
