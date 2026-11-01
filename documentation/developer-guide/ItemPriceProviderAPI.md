# Item Price Provider API

## Overview

BankSystem tracks periodic balance snapshots for each bank account. When an **Item Price Provider** is registered by an external mod (e.g. StockMarket), BankSystem additionally calculates and stores a **Total Wealth** series — the combined monetary value of all assets in each bank account, priced in the registered currency item.

If no provider is registered, the wealth series is simply absent. No errors, no empty data — the chart shows only per-item balance lines.

## API Surface

All methods are on `BankSystemAPI`, obtained via `BankSystemMod.getAPI()`.

### `setItemPriceProvider(@Nullable ItemPriceProvider provider)`

Registers a function that returns the current market price of any item.

```java
@FunctionalInterface
public interface ItemPriceProvider {
    /**
     * @param itemId the BankSystem short ID of the item (from ItemID.getShort())
     * @return price of ONE unit in raw money amount (scaled by ITEM_FRACTION_SCALE_FACTOR = 100),
     *         or 0 if the item has no market price
     */
    long getItemPrice(short itemId);
}
```

**Price format:** Raw money units. BankSystem uses a fixed-point scale factor of 100.
- 1.00 money = raw value `100`
- 3.50 money = raw value `350`
- If iron is worth 2.5 money per unit, return `250`

### `setPriceCurrencyItem(short currencyItemId)`

Tells BankSystem which item represents the currency that prices are denominated in. The currency item's own balance is counted at face value (not multiplied by its price) when calculating wealth.

**Example:** If the currency is the BankSystem money item, pass `MoneyItem.getItemID().getShort()`.

### `getItemPriceProvider()` / `getPriceCurrencyItem()`

Getters for the currently registered provider and currency item. Return `null` / `0` if not set.

## Integration Steps (for StockMarket)

### 1. Listen for BankSystem setup completion

The provider must be registered **after** BankSystem has fully initialized. Use the setup complete event:

```java
BankSystemAPI api = BankSystemMod.getAPI();
api.getEvents().getBanksystemSetupCompleteSignal().addListener(() -> {
    registerPriceProvider();
});
```

Or if your mod initializes after BankSystem (guaranteed by mod load order), call directly in your `onServerStart()` / `onBankSystemSetupComplete()`.

### 2. Register the provider and currency

```java
private void registerPriceProvider() {
    BankSystemAPI api = BankSystemMod.getAPI();
    
    // Register the price lookup function
    api.setItemPriceProvider(itemId -> {
        // Convert BankSystem's short itemId to your market lookup
        // Return the current price in raw money units (scaled by 100)
        // Return 0 for items with no market listing
        Market market = getMarketByBankSystemItemId(itemId);
        if (market == null) return 0;
        return market.getCurrentPrice(); // must be in raw money units
    });
    
    // Register which item is the currency
    // MoneyItem.getItemID().getShort() returns the short ID of the BankSystem money item
    api.setPriceCurrencyItem(MoneyItem.getItemID().getShort());
}
```

### 3. Clean up on server stop (optional)

```java
api.setItemPriceProvider(null);
```

## How Wealth Is Calculated

On each balance snapshot tick (configurable, default 1 minute), **per bank account**:

```
totalWealth = 0
for each bank (itemId, balance, lockedBalance) in account:
    totalBalance = balance + lockedBalance
    if itemId == currencyItemId:
        totalWealth += totalBalance          // currency counted at face value
    else:
        price = provider.getItemPrice(itemId)
        if price > 0:
            totalWealth += totalBalance * price / ITEM_FRACTION_SCALE_FACTOR
```

The wealth value is stored in the `BalanceHistory` SQL table with a sentinel `itemId = Short.MAX_VALUE (32767)`. The balance field contains the total wealth in raw money units.

## What the User Sees

In the Balance History chart screen (opened via the "History" button in the Bank Terminal):

- A gold-colored **"Total Wealth"** line appears alongside the per-item lines
- It can be toggled on/off like any other item series
- Its visibility preference persists in the user's custom data

If no provider is registered, the "Total Wealth" line simply does not appear.

## ItemID Mapping

BankSystem assigns each registered item a `short` ID via `ItemIDManager`. StockMarket needs to map between its own item/market identifiers and BankSystem's short IDs.

**To look up a BankSystem ItemID from an ItemStack:**
```java
ItemID id = ItemID.getOrRegisterFromItemStackServerSide_direct(itemStack);
short shortId = id.getShort();
```

**To create an ItemID from a known short:**
```java
ItemID id = new ItemID(shortId);
ItemStack stack = id.getStack(); // may be null if not registered
```

## Constants

| Constant | Value | Location |
|----------|-------|----------|
| `ITEM_FRACTION_SCALE_FACTOR` | `100` | `BankSystemModSettings` |
| `WEALTH_ITEM_ID` (sentinel) | `32767` (`Short.MAX_VALUE`) | `BalanceHistoryRecord` |
| Snapshot interval setting | `BALANCE_SNAPSHOT_INTERVAL_MINUTES` | `BankSystemModSettings.Utilities` |
