package net.kroia.banksystem.minecraft.item.custom.money;

import net.kroia.banksystem.BankSystemModSettings;

public class MoneyItemCent20 extends MoneyItem{
    public static final String NAME = "money_cent20";

    public MoneyItemCent20() {
        super();
    }

    @Override
    public long worth() {
        return 2000L/ BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
    }
}
