package net.kroia.banksystem.api;

@FunctionalInterface
public interface ItemPriceProvider {
    /**
     * Returns the current price of one unit of the given item in raw money amount
     * (scaled by ITEM_FRACTION_SCALE_FACTOR).
     *
     * @param itemId the short ID of the item
     * @return the price in raw money units, or 0 if the item has no market price
     */
    long getItemPrice(short itemId);
}
