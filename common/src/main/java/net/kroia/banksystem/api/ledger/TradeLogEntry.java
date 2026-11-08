package net.kroia.banksystem.api.ledger;

import net.kroia.banksystem.util.ItemID;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Task #109 (v2.1.0) — one trade-ledger row, written by StockMarket via
 * {@link ITransactionLedger#logTrade}.
 * <p>
 * A row is a <b>delta</b>: the part of an order that filled since the previous row for the
 * same {@code ref}. All rows of one {@code ref} sum to the order totals.
 *
 * @param accountNumber account the order trades for
 * @param actorUuid     player who placed the order, or {@code null} (bot / system order)
 * @param side          BUY or SELL
 * @param item          traded item (stored in {@code item_id})
 * @param qtyRaw        filled item quantity in this slice, raw units (stored in {@code amount})
 * @param currency      currency item (stored in {@code currency_item})
 * @param moneyRaw      money moved in this slice, raw units (stored in {@code money_amount})
 * @param avgPriceRaw   average fill price of this slice, raw units (stored in {@code price})
 * @param virtualQty    part of {@code qtyRaw} filled against virtual/bot liquidity (minted or
 *                      burned, no counterparty account), or {@code null} if not tracked
 * @param ref           order reference; groups the rows of one order
 * @param rowKind       why the row was written (stored in {@code tag})
 * @param timeMs        epoch millis of the row
 * @param note          free text, may be {@code null}
 */
public record TradeLogEntry(
        int accountNumber,
        @Nullable UUID actorUuid,
        TradeSide side,
        ItemID item,
        long qtyRaw,
        ItemID currency,
        long moneyRaw,
        long avgPriceRaw,
        @Nullable Long virtualQty,
        String ref,
        TradeRowKind rowKind,
        long timeMs,
        @Nullable String note
) {
}
