package net.kroia.banksystem.minecraft.item.custom.money;

import net.kroia.banksystem.BankSystemModSettings;

public class MoneyItem10000 extends MoneyItem {
    public static final String NAME = "money10000";

    public MoneyItem10000() {
        super();
    }

    @Override
    public boolean isBankNote()
    {
        return true;
    }
    @Override
    public long worth() {
        return 10000L* BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
    }
}
