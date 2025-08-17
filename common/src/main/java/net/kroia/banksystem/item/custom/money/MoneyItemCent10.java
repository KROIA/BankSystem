package net.kroia.banksystem.item.custom.money;

public class MoneyItemCent10 extends MoneyItem{
    public static final String NAME = "money_cent10";

    public MoneyItemCent10() {
        super();
    }

    @Override
    public long worth() {
        return 1000L/ITEM_FRACTION_SCALE_FACTOR; // 10 cents
    }
}
