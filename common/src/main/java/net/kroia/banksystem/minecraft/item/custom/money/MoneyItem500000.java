package net.kroia.banksystem.minecraft.item.custom.money;

import net.kroia.banksystem.BankSystemModSettings;

public class MoneyItem500000 extends MoneyItem {
    public static final String NAME = "money500000";

    public MoneyItem500000() {
        super();
    }

    @Override
    public boolean isBankNote()
    {
        return true;
    }
    @Override
    public long worth() {
        return 500000L* BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
    }
}
