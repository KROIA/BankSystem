package net.kroia.banksystem.item.custom.money;

public class MoneyItem200 extends MoneyItem {
    public static final String NAME = "money200";

    public MoneyItem200() {
        super();
    }

    @Override
    public boolean isBankNote()
    {
        return true;
    }
    @Override
    public long worth() {
        return 200L*ITEM_FRACTION_SCALE_FACTOR;
    }
}
