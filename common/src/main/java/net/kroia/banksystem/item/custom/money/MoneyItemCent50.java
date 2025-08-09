package net.kroia.banksystem.item.custom.money;

public class MoneyItemCent50 extends MoneyItem{
    public static final String NAME = "money_cent50";

    public MoneyItemCent50() {
        super();
    }

    @Override
    public long worth() {
        return 50;
    }
}
