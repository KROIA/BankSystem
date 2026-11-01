package net.kroia.banksystem.minecraft.item.custom.money;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModSettings;

public class MoneyItem50 extends MoneyItem {
    public static final String NAME = "money50";

    public MoneyItem50() {
        super(BankSystemMod.id(NAME));
    }

    @Override
    public boolean isBankNote()
    {
        return true;
    }
    @Override
    public long worth() {
        return 50L* BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
    }
}
