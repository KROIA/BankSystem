# Transaction Ledger

BankSystem writes one row per balance change to the `TransactionLog` table in `banksystem.db` on the master server. Writes are best-effort and asynchronous. If a row cannot be written, a warning is logged and the banking operation still completes. Only the master writes rows. On a slave server `log` and `logTrade` do nothing.

## Multi-server

Every money or item change runs on the master, so the master writes the row. A slave's request to the master carries the ledger type code with the amount, plus the player when the master cannot tell who caused it. The master checks the code against an allow-list for that request kind:

| Request | Allowed codes |
|---|---|
| `DepositItemsInBankRequest`, `AsyncBank` deposit calls | DEPOSIT, CONVERTER_DEPOSIT, UPLOAD_DEPOSIT, CRAFT_OUTPUT, WITHDRAW_REFUND |
| `WithdrawItemsFromBankRequest`, `AsyncBank` withdraw calls (incl. locked) | WITHDRAW, CONVERTER_WITHDRAW, DOWNLOAD_WITHDRAW, CRAFT_CONSUME |
| `AsyncBank` transfer calls | TRANSFER |
| `AsyncBank` set-balance, lock and unlock calls | none (no-log only) |

A code that is unknown or not allowed is written as UNKNOWN_LEGACY (0) with the note `rejected code <n> from slave <id>`, and a warning is logged. The operation itself still runs. Raw `AsyncBank` calls without a code (for example StockMarket settlement, which logs through `logTrade`) send the wire-only code `-1` (`TransactionLedger.NO_LOG`) and write no row. Transfers always write their TRANSFER pair.

## Writing rows from another mod

```java
ITransactionLedger ledger = BankSystemAPI.getLedger(); // via your BankSystemAPI instance

// A generic row with an explicit type and actor:
ledger.log(accountNumber, LedgerType.ADMIN_ADD, MoneyItem.getItemID(), rawAmount,
        LedgerActor.console(), /* ref */ null, /* note */ "reward");

// A trade slice (StockMarket):
ledger.logTrade(new TradeLogEntry(accountNumber, playerUuid, TradeSide.BUY,
        itemId, qtyRaw, currencyId, moneyRaw, avgPriceRaw, virtualQtyOrNull,
        orderRef, TradeRowKind.PERIODIC, System.currentTimeMillis(), null));
```

Trade rows are **deltas**. The rows that share one `ref` (PERIODIC, SHUTDOWN, CLOSE) add up to the order totals.

Actors (`LedgerActor`): `player(uuid, name)`, `admin(uuid, name)`, `console()`, `system()`, `stockmarket()`.

Package: `net.kroia.banksystem.api.ledger` (`ITransactionLedger`, `LedgerType`, `LedgerActor`, `TradeLogEntry`, `TradeSide`, `TradeRowKind`).

## Type codes

Codes are stored in `type_code` and are **append-only**: a code is never renumbered or reused. `LedgerType.fromCode` maps an unknown code to `UNKNOWN_LEGACY`.

| Code | Type | Written when |
|---:|---|---|
| 0 | UNKNOWN_LEGACY | Old row whose kind could not be mapped, or an unknown code |
| 1 | DEPOSIT | Items or money deposited at a Bank Terminal or through a deposit request |
| 2 | WITHDRAW | Items or money withdrawn (Bank Terminal, banknote withdraw, withdraw request) |
| 3 | TRANSFER | Bank-to-bank transfer. Two rows are written: `-x` on the sender and `+x` on the receiver. `other_account` holds the counterparty |
| 4 | TRADE_BUY | StockMarket buy slice (`logTrade`) |
| 5 | TRADE_SELL | StockMarket sell slice (`logTrade`) |
| 6 | WITHDRAW_REFUND | Part of a banknote withdrawal that could not be handed out and was put back |
| 7 | ADMIN_SET | Balance set by an admin (command or account screen); amount = absolute change, note = `old->new` |
| 8 | ADMIN_ADD | `/money add` |
| 9 | ADMIN_REMOVE | `/money remove` |
| 10 | ADMIN_UNLOCK | Admin released a bank's locked balance; amount = released amount |
| 11 | ADMIN_ACCOUNT_EDIT | Account renamed, members/permissions or icon changed; amount 0, note lists the changes |
| 12 | BANK_CLOSED | A bank or account was removed while it still held a balance; amount = destroyed balance |
| 13 | CONVERTER_DEPOSIT | Bank Converter committed items to the bank |
| 14 | CONVERTER_WITHDRAW | Reserved (the converter currently never withdraws from a bank) |
| 15 | UPLOAD_DEPOSIT | Bank Upload block deposited items; actor = block owner, note = position |
| 16 | DOWNLOAD_WITHDRAW | Bank Download block withdrew items; actor SYSTEM, note = position |
| 17 | CRAFT_CONSUME | Crafting from bank ingredients consumed a locked amount |
| 18 | CRAFT_OUTPUT | A crafted result was deposited back into the bank |
| 19 | STARTING_BALANCE | Starting balance paid into a new personal account |
| 20 | PAYOUT | Company payout (`company_id` set) |
| 21 | DIVIDEND | Dividend payment (`company_id` set) |
| 22 | SHARE_STAMP | Shares stamped at a Share Stamper |
| 23 | SHARE_REDEEM | Shares redeemed at a Share Stamper |
| 24 | SHARE_TRADE | Stamped shares deposited into or withdrawn from a bank |
| 25 | ACCOUNT_DELETE_PAYOUT | Balance paid out when a user and their account were deleted |
| 26 | ACCOUNT_FREEZE | Reserved, not written yet |
| 27 | ADMIN_DISALLOW_ITEM | An admin disallowed an item; one row per holder with the removed (non-refunded) balance |

Amounts are unsigned, except for TRANSFER, where the sign gives the direction.

## Columns

| Column | Meaning |
|---|---|
| `id` | Row id (insert order) |
| `account_number` | Account the row belongs to |
| `actor_uuid` | Player or admin who caused the change; null for console/system |
| `actor_name` | Actor name at the time of the change |
| `source_kind` | `PLAYER`, `ADMIN`, `CONSOLE`, `SYSTEM` or `STOCKMARKET`; `UNKNOWN` on old rows |
| `type_code` | Type code (table above) |
| `kind` | Type name (kept for older readers) |
| `item_id` | Item of the bank that changed |
| `amount` | Raw amount (see [Raw vs Real Amounts](API.md#raw-vs-real-amounts)) |
| `other_account` | Counterparty account (transfers) |
| `company_id` | Company (payouts, dividends, share rows) |
| `ts` | Time in epoch milliseconds |
| `note` | Free text (details such as `old->new`, block position, item count) |
| `ref` | External reference (StockMarket order id) |
| `tag` | Trade row kind: `PERIODIC`, `SHUTDOWN` or `CLOSE` |
| `money_amount` | Trade: money moved by this slice |
| `price` | Trade: average price of this slice |
| `currency_item` | Trade: currency item id |
| `virtual_qty` | Trade: quantity filled against virtual (bot) liquidity, with no counterparty account |
| `balance_after` | Bank total (free + locked) right after the change; null for trade rows |

## Reads (master)

`TransactionLogManager`: `getByAccount`, `getByAccountAndType(account, typeCode, sinceMs, limit)`, `getByRef(ref)` (oldest first), `getByActor(uuid, sinceMs, limit)`, plus the company reads. All return newest first unless noted.

## Upgrading old databases

On start-up, the missing columns and indexes are added. Old rows get a type code from their `kind` text. `TRANSFER_OUT` rows become TRANSFER with a negated amount, and `TRANSFER_IN` rows become TRANSFER with a positive amount. The update only touches rows that are still at code 0, so running it again never negates a row twice. Old rows that cannot be mapped stay at code 0 and are still readable.
