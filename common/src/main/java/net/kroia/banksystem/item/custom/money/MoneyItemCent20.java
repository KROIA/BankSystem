package net.kroia.banksystem.item.custom.money;

public class MoneyItemCent20 extends MoneyItem{
    public static final String NAME = "money_cent20";

    public MoneyItemCent20() {
        super();
    }

    @Override
    public long worth() {
        return 2000L/ITEM_FRACTION_SCALE_FACTOR;
    }
}
