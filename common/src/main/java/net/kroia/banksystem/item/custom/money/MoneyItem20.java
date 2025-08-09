package net.kroia.banksystem.item.custom.money;

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
        return 2000;
    }
}
