package net.kroia.banksystem.minecraft.item.custom.money;

import net.kroia.banksystem.BankSystemModSettings;

public class MoneyItem20000 extends MoneyItem {
    public static final String NAME = "money20000";

    public MoneyItem20000() {
        super();
    }

    @Override
    public boolean isBankNote()
    {
        return true;
    }
    @Override
    public long worth() {
        return 20000L* BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
    }
}
