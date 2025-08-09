package net.kroia.banksystem.item.custom.money;

public class MoneyItem500 extends MoneyItem {
    public static final String NAME = "money500";

    public MoneyItem500() {
        super();
    }

    @Override
    public boolean isBankNote()
    {
        return true;
    }
    @Override
    public long worth() {
        return 50000;
    }
}
