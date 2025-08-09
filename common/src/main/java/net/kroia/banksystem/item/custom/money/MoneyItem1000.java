package net.kroia.banksystem.item.custom.money;

public class MoneyItem1000 extends MoneyItem {
    public static final String NAME = "money1000";

    public MoneyItem1000() {
        super();
    }

    @Override
    public boolean isBankNote()
    {
        return true;
    }
    @Override
    public long worth() {
        return 100000;
    }
}
