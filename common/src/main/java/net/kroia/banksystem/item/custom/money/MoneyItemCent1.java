package net.kroia.banksystem.item.custom.money;

public class MoneyItemCent1 extends MoneyItem{
    public static final String NAME = "money_cent1";

    public MoneyItemCent1() {
        super();
    }

    @Override
    public long worth() {
        return 1;
    }
}
