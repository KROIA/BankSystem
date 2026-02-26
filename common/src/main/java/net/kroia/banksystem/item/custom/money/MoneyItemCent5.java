package net.kroia.banksystem.item.custom.money;

public class MoneyItemCent5 extends MoneyItem{
    public static final String NAME = "money_cent5";

    public MoneyItemCent5() {
        super();
    }

    @Override
    public long worth() {
        return 500L/ITEM_FRACTION_SCALE_FACTOR; // 0.05 currency units
    }
}
