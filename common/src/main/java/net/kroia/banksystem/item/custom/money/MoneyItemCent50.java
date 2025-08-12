package net.kroia.banksystem.item.custom.money;

public class MoneyItemCent50 extends MoneyItem{
    public static final String NAME = "money_cent50";

    public MoneyItemCent50() {
        super();
    }

    @Override
    public long worth() {
        return 5000L/ITEM_FRACTION_SCALE_FACTOR; // 50 cents is 5000 in the scale factor
    }
}
