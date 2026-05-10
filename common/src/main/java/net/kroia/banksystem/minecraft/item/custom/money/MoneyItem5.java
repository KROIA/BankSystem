package net.kroia.banksystem.minecraft.item.custom.money;

import net.kroia.banksystem.BankSystemModSettings;

public class MoneyItem5 extends MoneyItem{
    public static final String NAME = "money5";

    public MoneyItem5() {
        super();
    }

    @Override
    public boolean isBankNote()
    {
        return true;
    }

    public long worth() {
        return 5L * BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR; // 5.00 currency units
    }
}
