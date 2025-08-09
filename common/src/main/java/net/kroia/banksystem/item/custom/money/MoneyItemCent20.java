package net.kroia.banksystem.item.custom.money;

public class MoneyItemCent20 extends MoneyItem{
    public static final String NAME = "money_cent20";

    public MoneyItemCent20() {
        super();
    }

    @Override
    public long worth() {
        return 20;
    }
}
