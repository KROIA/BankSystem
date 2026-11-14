# Transaction Ledger

*Since 2.2.0.*

BankSystem writes one row per balance change to the `TransactionLog` table in `banksystem.db` on the master server. Writes are best-effort and asynchronous. If a row cannot be written, a warning is logged and the banking operation still completes. Only the master writes rows. On a slave server `log` and `logTrade` do nothing.

## Multi-server

Every money or item change runs on the master, so the master writes the row, with the slave's id in `origin_server`. A slave's request to the master carries the ledger type code with the amount, plus the player when the master cannot tell who caused it. The master checks the code against an allow-list for that request kind:

| Request | Allowed codes |
|---|---|
| `DepositItemsInBankRequest`, `AsyncBank` deposit calls | DEPOSIT, CONVERTER_DEPOSIT, UPLOAD_DEPOSIT, CRAFT_OUTPUT, WITHDRAW_REFUND |
| `WithdrawItemsFromBankRequest`, `AsyncBank` withdraw calls (incl. locked) | WITHDRAW, CONVERTER_WITHDRAW, DOWNLOAD_WITHDRAW, CRAFT_CONSUME |
| `AsyncBank` transfer calls | TRANSFER |
| `AsyncBank` set-balance, lock and unlock calls | none (the code is ignored; a change of the total balance writes RAW_CHANGE) |

A code that is unknown or not allowed is written as UNKNOWN_LEGACY (0) with the note `rejected code <n> from slave <id>`, and a warning is logged. The operation itself still runs. Raw `AsyncBank` calls without a code (for example StockMarket settlement, which logs through `logTrade`) send the wire-only code `-1` (`TransactionLedger.NO_LOG`); the master writes a RAW_CHANGE row for them (see [Changes nobody logs](#changes-nobody-logs)). Transfers always write their TRANSFER pair.

Deposit and withdraw calls from a slave also carry a **transfer id**. The master writes it into the row's `ref`, so the slave's own records (its diagnostics `TransferJournal`) can be matched to the master's rows. The master applies each transfer id **at most once**: a repeated request gets the first answer and writes no second row. When the answer to a slave is lost, the slave asks the master for the transfer's status before it refunds anything; if the outcome stays unknown, nothing is refunded (see [When an async call fails](API.md#when-an-async-call-fails)).

## Writing rows from another mod

```java
ITransactionLedger ledger = BankSystemMod.getAPI().getLedger();

// A generic row with an explicit type and actor:
ledger.log(accountNumber, LedgerType.ADMIN_ADD, MoneyItem.getItemID(), rawAmount,
        LedgerActor.console(), /* ref */ null, /* note */ "reward");

// A trade slice (StockMarket):
ledger.logTrade(new TradeLogEntry(accountNumber, playerUuid, TradeSide.BUY,
        itemId, qtyRaw, currencyId, moneyRaw, avgPriceRaw, virtualQtyOrNull,
        orderRef, TradeRowKind.PERIODIC, System.currentTimeMillis(), null));
```

Trade rows are **deltas**. The rows that share one `ref` (PERIODIC, SHUTDOWN, CLOSE) add up to the order totals.

Rows you write are treated like BankSystem's own: they fire [`getTransactionLoggedEvent()`](EventsAndSignals.md#gettransactionloggedevent) and then run through the admin-audit and suspicion hooks below (an ADMIN_* row lands in the audit file, a large TRANSFER into a new account can raise a flag).

To **react** to rows instead of writing them, subscribe to `api.getEvents().getTransactionLoggedEvent()` on the master (fires on the writer's thread).

Actors (`LedgerActor`): `player(uuid, name)`, `admin(uuid, name)`, `console()`, `system()`, `stockmarket()`, `unknown()`.

### Changes nobody logs

Every change of a bank's total balance (free + locked) writes a row where it happens, so a caller cannot skip it:

- A raw bank call (`deposit`, `withdraw`, `setBalance`, `withdrawLocked*` and their async/`Real` variants) that no typed row covers writes a RAW_CHANGE row with the signed change, source `UNKNOWN` (or the player of a slave request) and `balance_after`.
- Creating a bank slot with a start balance, removing a slot and removing all slots of an account write RAW_CHANGE rows.
- Binding a slot to an external currency account, or unbinding it, writes EXTERNAL_BIND / EXTERNAL_UNBIND with the change it caused.
- Lock and unlock only move value between free and locked and write nothing. `transferFromLocked*` writes its TRANSFER pair like `transfer`.

So for every account and item: closing total − opening total = signed sum of the rows in between. REFUND_LOST rows move nothing and are left out of this sum.

A mod that writes its own rows for its bank calls (for example trade settlement logged with `logTrade`) makes those calls inside `callerLogs`, so they are not logged twice:

```java
ledger.callerLogs(() -> {
    itemBank.deposit(qty);
    return moneyBank.withdrawLockedPrefered(cost);
});
ledger.logTrade(...);
```

`callerLogs` covers the current thread only and does not stop the TRANSFER pair of a transfer.

Package: `net.kroia.banksystem.api.ledger` (`ITransactionLedger`, `LedgerType`, `LedgerActor`, `TradeLogEntry`, `TradeSide`, `TradeRowKind`). `TradeSide.type()` gives the row type (`BUY` → TRADE_BUY, `SELL` → TRADE_SELL). `TradeRowKind`: `PERIODIC` (interval flush of an open order), `SHUTDOWN` (flush on server stop), `CLOSE` (the order filled or was cancelled).

## Type codes

Codes are stored in `type_code` and are **append-only**: a code is never renumbered or reused. `LedgerType.code()` returns a type's code; `LedgerType.fromCode(int)` maps a code back and returns `UNKNOWN_LEGACY` for an unknown one (never `null`).

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
| 25 | ACCOUNT_DELETE_PAYOUT | Balance handed to the player as items when a user and their account were deleted |
| 26 | ACCOUNT_FREEZE | An account was frozen or unfrozen; amount 0, item 0, note `frozen` / `unfrozen`, actor = admin, console or the API caller |
| 27 | ADMIN_DISALLOW_ITEM | An admin disallowed an item; one row per holder with the removed (non-refunded) balance |
| 28 | ACCOUNT_DELETE_LOSS | Balance of a deleted user's account that could not be handed out and was destroyed; the note ends with `not delivered: <reason>`, where the reason is `player offline`, `outcome unknown, transfer <id>` (a slave's DROP stayed unknown; `ref` = that transfer id) or `outcome unknown: <cause>` |
| 29 | RAW_CHANGE | Change of a slot's total balance that no other row covers: raw bank calls of other mods or slave requests, a slot created with a start balance, a slot removed with a balance. Signed; the note names the call (`deposit`, `setBalance`, `bank created`, `bank removed`, ...) |
| 30 | EXTERNAL_BIND | A slot was bound to an external currency account; signed change of the slot's total (the external balance joining), note `bound to <provider>:<account>` |
| 31 | EXTERNAL_UNBIND | A slot was unbound; signed change of the slot's total, note says whether the funds were kept on BankSystem or left on the external account |
| 32 | REFUND_LOST | Marker, no balance change: banknotes withdrawn for a player (WITHDRAW row, same `ref`) were not handed out, and their refund could not be booked back (the account or its money bank is gone, or the deposit was refused), so the value is lost. Account = the account the refund was meant for (it may no longer exist), item = money, amount = the undelivered raw money, `ref` = the slave's DROP transfer id (same as the WITHDRAW row), note = the reason, actor = the player, `balance_after` null when the account or money bank is gone |

Amounts are unsigned, except for TRANSFER, RAW_CHANGE, EXTERNAL_BIND and EXTERNAL_UNBIND, where the sign gives the direction.

## Columns

| Column | Meaning |
|---|---|
| `id` | Row id (insert order) |
| `account_number` | Account the row belongs to |
| `actor_uuid` | Player or admin who caused the change; null for console/system |
| `actor_name` | Actor name at the time of the change |
| `source_kind` | `PLAYER`, `ADMIN`, `CONSOLE`, `SYSTEM`, `STOCKMARKET` or `UNKNOWN` (old rows, and raw changes whose caller is not known) |
| `type_code` | Type code (table above) |
| `kind` | Type name (kept for older readers) |
| `item_id` | Item of the bank that changed |
| `amount` | Raw amount (see [Raw vs Real Amounts](API.md#raw-vs-real-amounts)) |
| `other_account` | Counterparty account (transfers) |
| `company_id` | Company (payouts, dividends, share rows) |
| `ts` | Time in epoch milliseconds |
| `note` | Free text (details such as `old->new`, block position, item count) |
| `ref` | External reference: StockMarket order id, or the slave's transfer id for slave deposits/withdrawals and user-deletion payouts |
| `tag` | Trade row kind: `PERIODIC`, `SHUTDOWN` or `CLOSE` |
| `money_amount` | Trade: money moved by this slice |
| `price` | Trade: average price of this slice |
| `currency_item` | Trade: currency item id |
| `virtual_qty` | Trade: quantity filled against virtual (bot) liquidity, with no counterparty account |
| `balance_after` | Bank total (free + locked) right after the change; null for trade rows |
| `origin_server` | Server whose request caused the row: the slave id the master authenticated the slave's connection with (the `slave_id` in the slave's `server_identity.json`). Null for rows caused on the master, and for rows written before 2.2.0 |
| `dimension`, `x`, `y`, `z` | Block where the action happened: bank terminal (deposit, withdraw, crafting), upload and download block, the ATM a converter commit or banknote withdrawal was made at, share stamper. A slave sends its block with the request. Null for rows not caused at a block (commands, screens, transfers, ATM opened by the ATM software), for banknote withdrawals made on a slave, and for rows written before 2.2.0 |

## Reads (master)

`TransactionLogManager`: `getByAccount`, `getByAccountAndType(account, typeCode, sinceMs, limit)`, `getByRef(ref)` (oldest first), `getByActor(uuid, sinceMs, limit)`, plus the company reads. All return newest first unless noted.

`getPage(account, typeCode, sinceMs, offset, limit)` (newest first, `typeCode < 0` = all) backs the admin audit screen (`AuditPageRequest`, 100 rows per page, admin-checked on the master).

## Admin audit and suspicion flags

`TransactionLedger.write` passes every master row to `AdminAudit.onLedgerRow`. Admin rows (codes 7–11, 26, 27, and 12 / 28 when the source is ADMIN) are appended to `<world>/data/BankSystem/audit/<yyyy-mm-dd>.log` and sent to BankSystem admins with alerts on (see [Administration](../user-guide/Administration.md#audit-and-admin-alerts)).

Admin changes that write no ledger row get one line each in the same file (master only, no chat alert), written where the state changes and only when it changed. Every line starts with `yyyy-MM-dd HH:mm:ss` (server time zone); names are in double quotes, with inner double quotes replaced by single ones; the actor is a player name with ` (<uuid>)`, `Console`, `LuckPerms`, `LuckPerms via slave <id>`, or `SYSTEM` when the caller set no actor:

| Line | Written when |
|---|---|
| `ADMIN RIGHTS_OP player <uuid> "<name>" state admin by <actor>` | the BankSystem admin flag is turned on (command, API request, LuckPerms sync) |
| `ADMIN RIGHTS_DEOP player <uuid> "<name>" state not_admin by <actor>` | the flag is turned off |
| `ADMIN SLAVE_TRUST slave "<id>" state trusted by <actor>` | a slave becomes trusted |
| `ADMIN SLAVE_UNTRUST slave "<id>" state untrusted by <actor>` | a slave is no longer trusted |
| `ADMIN ITEM_ALLOW item <itemId> "<item name>" state allowed by <actor>` | an admin (or the console) allows an item that was not allowed; automatic allows are not written |
| `ADMIN ITEM_DISALLOW item <itemId> "<item name>" state disallowed by <actor>` | an item is disallowed; the cleared balances stay as one `ADMIN_DISALLOW_ITEM` line per account |
| `ADMIN FLAG_ACK company <companyId> "<name>" account <account> flag SHARE_EXCESS excess <shares> state acknowledged by <actor>` | an open share-excess alert is acknowledged |

BankSystem's own checks raise flags internally (`banking.ledger.AdminAudit.raise`, not part of the API). Each flag writes one row to the `SuspicionFlag` table (`id, ts, account_number, flag_type, severity, amount, ref, details, acknowledged`), the audit-file line `FLAG <type> ...` and the admin alert, then fires [`getSuspicionFlaggedEvent()`](EventsAndSignals.md#getsuspicionflaggedevent). Master only; it never throws. Admins read flags with `SuspicionFlagManager.getRecent(account, sinceMs, limit)` and mark them handled with `acknowledge(id)`.

> Add-ons cannot raise flags yet: there is no public call and no code range reserved for add-ons. Add-ons can **listen** to flags with `getSuspicionFlaggedEvent()`. Ask for a public call if you need one.

Flag codes in use (`SuspicionChecks`, append-only):

| Code | Constant | Raised by |
|---:|---|---|
| 1 | `BANKNOTE_LAUNDERING` | ledger hook on DEPOSIT / UPLOAD_DEPOSIT money rows whose note contains `banknote ` |
| 2 | `LARGE_NOTE_WITHDRAWAL` | ledger hook on WITHDRAW money rows: per actor + account burst (closed after 30 s without a new row), sum of WITHDRAW minus WITHDRAW_REFUND rows in the burst, one flag per burst |
| 3 | `WEALTH_JUMP` | periodic sweep over the BalanceHistory WEALTH series (item id 32767) |
| 4 | `TRANSFER_TO_NEW_ACCOUNT` | ledger hook on incoming TRANSFER (+), PAYOUT and DIVIDEND money rows |
| 5 | `MONEY_SUPPLY_GROWTH` | wealth sweep tick; account 0 (server-wide), baseline from balance snapshots an hour ago |
| 6 | `TRANSFER_PARKED` | a value DROP to a slave (banknote withdraw, user-deletion payout) whose outcome stayed unknown after the status check; not refunded, `ref` = transfer id, always on |
| 7 | `EXTERNAL_BINDING_SUSPENDED` | start-up check: a currency binding to an external account the account's managers may not bind was suspended; `ref` = `providerId:accountKey` |

The ledger hooks (codes 1, 2, 4) run from `TransactionLedger.write` and `TransactionLedger.writeAll` (payout and dividend batches), on the master only, also for rows written by add-ons. `WEALTH_JUMP` uses the WEALTH series, which is only recorded while an [item price provider](ItemPriceProviderAPI.md) is registered. Bank accounts store their creation time as NBT `createdAt` (absent for accounts created before v2.2.0). Missing columns are added on start-up like for `TransactionLog`.

## Upgrading old databases

On start-up, the missing columns and indexes are added. Old rows get a type code from their `kind` text (`LedgerType.fromLegacyKind(String)`, which returns `UNKNOWN_LEGACY` for anything it cannot map). `TRANSFER_OUT` rows become TRANSFER with a negated amount, and `TRANSFER_IN` rows become TRANSFER with a positive amount. The update only touches rows that are still at code 0, so running it again never negates a row twice. Old rows that cannot be mapped stay at code 0 and are still readable.
