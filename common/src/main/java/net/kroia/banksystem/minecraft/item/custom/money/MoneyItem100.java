package net.kroia.banksystem.minecraft.item.custom.money;

import net.kroia.banksystem.BankSystemModSettings;

public class MoneyItem100 extends MoneyItem {
    public static final String NAME = "money100";

    public MoneyItem100() {
        super();
    }

    @Override
    public boolean isBankNote()
    {
        return true;
    }
    @Override
    public long worth() {
        return 100L* BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
    }
}
