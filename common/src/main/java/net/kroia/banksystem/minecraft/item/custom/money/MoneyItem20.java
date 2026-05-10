package net.kroia.banksystem.minecraft.item.custom.money;

import net.kroia.banksystem.BankSystemModSettings;

public class MoneyItem20 extends MoneyItem {
    public static final String NAME = "money20";

    public MoneyItem20() {
        super();
    }

    @Override
    public boolean isBankNote()
    {
        return true;
    }
    @Override
    public long worth() {
        return 20L* BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
    }
}
