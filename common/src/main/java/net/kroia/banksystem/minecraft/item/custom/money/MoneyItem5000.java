package net.kroia.banksystem.minecraft.item.custom.money;

import net.kroia.banksystem.BankSystemModSettings;

public class MoneyItem5000 extends MoneyItem {
    public static final String NAME = "money5000";

    public MoneyItem5000() {
        super();
    }

    @Override
    public boolean isBankNote()
    {
        return true;
    }
    @Override
    public long worth() {
        return 5000L* BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
    }
}
