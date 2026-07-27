package net.kroia.banksystem.minecraft.item.custom.money;

import net.kroia.banksystem.BankSystemModSettings;

public class MoneyItem100000 extends MoneyItem {
    public static final String NAME = "money100000";

    public MoneyItem100000() {
        super();
    }

    @Override
    public boolean isBankNote()
    {
        return true;
    }
    @Override
    public long worth() {
        return 100000L* BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
    }
}
