package net.kroia.banksystem.item.custom.money;

import net.kroia.banksystem.BankSystemModSettings;

public class MoneyItem10 extends MoneyItem {
    public static final String NAME = "money10";

    public MoneyItem10() {
        super();
    }

    @Override
    public boolean isBankNote()
    {
        return true;
    }
    @Override
    public long worth() {
        return 10L* BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
    }
}
