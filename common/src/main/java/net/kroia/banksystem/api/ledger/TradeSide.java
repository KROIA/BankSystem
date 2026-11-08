package net.kroia.banksystem.api.ledger;

/** Task #109 — side of a trade row. BUY is written as {@link LedgerType#TRADE_BUY}, SELL as {@link LedgerType#TRADE_SELL}. */
public enum TradeSide {
    BUY, SELL;

    public LedgerType type() {
        return this == BUY ? LedgerType.TRADE_BUY : LedgerType.TRADE_SELL;
    }
}
