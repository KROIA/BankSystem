package net.kroia.banksystem.item.custom.money;

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
        return 10L*ITEM_FRACTION_SCALE_FACTOR;
    }
}
