package net.kroia.banksystem.item.custom.money;

import net.kroia.banksystem.item.BankSystemCreativeModeTab;
import net.minecraft.world.item.Item;

public class MoneyItem extends Item{
    public static final String NAME = "money";
    public static final String DISPLAY_NAME = "$";

    public MoneyItem() {
        //super(new Properties().tab(BankSystemCreativeModeTab.BANK_SYSTEM_TAB)); // 1.19.2 and below
        super(new Properties().arch$tab(BankSystemCreativeModeTab.BANK_SYSTEM_TAB));
    }

    public int worth() {
        return 1;
    }
}
