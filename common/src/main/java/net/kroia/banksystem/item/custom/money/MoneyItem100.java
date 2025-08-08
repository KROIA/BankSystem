package net.kroia.banksystem.item.custom.money;

public class MoneyItem100 extends MoneyItem {
    public static final String NAME = "money100";

    public MoneyItem100() {
        super();
    }

    @Override
    public long worth() {
        return 10000;
    }
}
