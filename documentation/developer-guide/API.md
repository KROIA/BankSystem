# BankSystem API Reference

This guide is for mod developers who want to integrate with BankSystem as a dependency.

## Getting Started

Add BankSystem as a dependency in your `build.gradle` and access the API through `BankSystemAPI`.

### Entry Point

```java
BankSystemAPI api = BankSystemMod.getAPI();
```

The `BankSystemAPI` interface provides access to all subsystems:

| Method | Returns | Description |
|--------|---------|-------------|
| `getModID()` | `String` | BankSystem's mod id |
| `getModVersion()` | `String` | The loaded BankSystem version |
| `getServerBankManager()` | `IBankManager` | Server-side bank manager (sync + async access) |
| `getClientBankManager()` | `IClientBankManager` | Client-side bank manager |
| `getEvents()` | `IBankSystemEvents` | Event/signal subscriptions |
| `getDataHandler()` | `IBankSystemDataHandler` | Save/load operations |
| `isSlave()` | `boolean` | Whether this server is a slave in multi-server mode |
| `setItemPriceProvider(ItemPriceProvider)` / `getItemPriceProvider()` | `void` / `ItemPriceProvider` *(nullable)* | Supply market prices for total-wealth tracking — see [Item Price Provider API](ItemPriceProviderAPI.md) |
| `getLedger()` | `ITransactionLedger` | Write transaction-ledger rows (generic `log` and StockMarket `logTrade`); master only, a no-op on slave servers — see [Transaction Ledger](TransactionLedger.md) (since 2.2.0) |
| `getNoteFlowLog()` | `INoteFlowLog` | Record money items created or destroyed outside the bank; works on every server, slaves included — see [Note Flow Log](#note-flow-log-inoteflowlog) (since 2.2.0) |
| `setPriceCurrencyItem(short)` / `getPriceCurrencyItem()` | `void` / `short` | The item prices are denominated in |
| `registerCurrencyProvider(ExternalCurrencyProvider)` | `void` | Register a currency-mod adapter — see [Currency Integration](CurrencyIntegration.md) |
| `unregisterCurrencyProvider(String providerId)` | `boolean` | Remove a previously registered provider |
| `getCurrencyProviders()` | `Collection<ExternalCurrencyProvider>` | Every registered currency provider, except built-in adapters switched off in the server's Integrations settings |
| `getCurrencyProvider(String providerId)` | `ExternalCurrencyProvider` *(nullable)* | One provider by id; `null` if none is registered or it is switched off in the Integrations settings |
| `getPayoutManager()` | `IPayoutManager` | Company payout schedules (create / update / pause / delete / history) |
| `getDividendPayer()` | `IDividendPayer` | One-shot dividend runs to every share holder |
| `getVisualLookup()` | `IBankSystemVisualLookup` | Client-side share-visual lookup per `ItemID` (never null; each query returns `null` on a dedicated server) |
| `getShareIconRenderer()` | `IShareIconRenderer` *(nullable)* | Paints a company's share badge; `null` on dedicated servers |

> Payout and dividend operations are **master-only**. On a slave, `getPayoutManager()` and `getDividendPayer()` return fail-closed shims (`NOT_MASTER`). There is no public async variant yet: call them from code that runs on the master. See [Company Payouts and Dividends](#company-payouts-and-dividends).
>
> `IBankSystemCommands` (in `api/command/`) has no accessor on `BankSystemAPI`; it is used by BankSystem itself.

## Sync vs Async Access

BankSystem exposes two access patterns through `IBankManager`:

```java
IBankManager bankManager = api.getServerBankManager();

// Check what's available
bankManager.hasSyncAccess();   // true on master, false on slave
bankManager.hasAsyncAccess();  // true if connected (master always, slave when connected)

// Get the appropriate interface
ISyncServerBankManager sync = bankManager.getSync();   // null on slave
IAsyncBankManager async = bankManager.getAsync();       // always available
```

| | Sync (`getSync()`) | Async (`getAsync()`) |
|---|---|---|
| **Available on** | Master only | Master + connected slaves |
| **Return type** | Direct values | `CompletableFuture<T>` |
| **Use when** | You are certain you're on master | You need cross-server compatibility |
| **Can fail** | No (returns a status) | On slaves and clients, yes — see [When an async call fails](#when-an-async-call-fails) |
| **Read access (since 2.2.0)** | Everything | Account data goes to account members, BankSystem admins and server-internal calls (no player sender). A request that arrives through an untrusted slave gets member data only: the admin flag and server-internal calls don't count there, because a slave can forge the sender. Refused reads complete with `null`, an empty list or a names-only copy |

**Rule of thumb**: Always use async unless you have a specific reason to use sync. Async works everywhere.

### When an async call fails

On the master, async calls run locally and complete at once. On a slave (or a client) they are sent to the master, and the answer can go missing. Since 2.2.0, `BankSystemMod.init` sets ModUtilities' JVM-wide `RequestManager.ENABLE_REQUEST_FAILURES = true`. This applies to **every** mod's ARRS requests in that JVM, not only BankSystem's: a request without an answer now fails its future instead of staying pending forever.

| What happened | What you get |
|---|---|
| Slave not connected to the master when you call | The future completes at once with a fallback: `FAILED_NO_MASTER_CONNECTION` for `BankStatus` calls, `false`, `0`, `""`, an empty list or `null` for the others. Nothing was sent |
| Request could not be sent | Fails with `RequestNotSentException` (ModUtilities). Nothing reached the master: **refunding is safe** |
| No answer within the timeout, or the connection dropped while waiting | Fails with `RequestOutcomeUnknownException` (a `TimeoutException`). The master **may have applied it**: do **not** refund or retry blindly |
| The master refused the request (not allowed for a client or an untrusted slave) | Usually completes with `null`. Treat `null` like a failure |

The default timeout is 30 s (`RequestManager.DEFAULT_TIMEOUT_MS`). A request class can override `getTimeoutMs()`; BankSystem's slow reads (audit pages, balance history, flag markers, network overview, company requests, slave admin commands) use `BankSystemGenericRequest.SLOW_READ_TIMEOUT_MS` (150 s: 120 s backup pause + 30 s).

**Deposits and withdrawals from a slave** (`depositAsync`, `withdrawAsync`, `withdrawLocked*Async` and their `Real` variants) carry a transfer id. If their answer goes missing, the slave asks the master whether the call was applied before it gives up:

- applied: completes with `SUCCESS`;
- not applied (or never sent): completes with `FAILED_NO_MASTER_CONNECTION` — since 2.2.0 this status always means **nothing changed**, so rolling back is safe;
- still unknown: fails with `RequestOutcomeUnknownException` — do not refund.

The master applies each transfer id at most once, so a repeated request gets the first answer. **Transfers, locks, unlocks and set-balance calls get no status check**: when their answer is missing they just fail with `RequestOutcomeUnknownException`.

```java
// The player's items were already taken out of the world; put them in the bank.
bank.depositAsync(amount).whenComplete((status, ex) -> {
    if (ex != null) {
        Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
        if (cause instanceof RequestNotSentException) giveItemsBack(); // never reached the master
        else log("deposit outcome unknown, items kept: " + cause);    // may have been applied
        return;
    }
    if (status != BankStatus.SUCCESS) giveItemsBack();                // any other status: nothing changed
});
```

## Working with Bank Accounts

### Data Hierarchy

```
BankManager
 └─ BankAccount (N accounts, identified by account number)
     ├─ Users (linked players with permissions)
     │   ├─ Owner (personal bank owner — always has full access)
     │   ├─ Player A (deposit + withdraw)
     │   └─ Player B (deposit only)
     └─ Banks (one per item type)
         ├─ Bank<Money>    — balance: 1500, locked: 200
         ├─ Bank<Diamond>  — balance: 64, locked: 0
         └─ Bank<Iron>     — balance: 320, locked: 50
```

- **BankManager** — The top-level manager. Holds all bank accounts on the server.
- **BankAccount** — A named account identified by a unique account number. Every player gets one personal account automatically, but players can also create additional accounts. Multiple players can be linked to the same account with per-player permissions (deposit, withdraw, manage).
- **Bank** — A single item ledger within an account. Each bank holds the balance for exactly one item type (e.g. money, diamonds, iron). A bank account can contain multiple banks — one for each item type the admin has allowed for banking.

When working with the API, you first get a `BankAccount`, then get (or create) a `Bank` for the specific item you want to operate on.

### Getting a Bank Account (Async)

```java
IAsyncBankManager asyncManager = bankManager.getAsync();

// Get a bank account by account number (completes with null if there is no such account)
asyncManager.getBankAccountAsync(accountNumber).thenCompose(account -> {
    if (account == null)
        return CompletableFuture.completedFuture(null);
    // Get a specific item bank within the account (null if the account has no bank for this item)
    return account.getBankAsync(itemID);
    // Or get all banks in the account: account.getAllBanksAsync() → Map<ItemID, IAsyncBank>
}).thenAccept(bank -> {
    if (bank != null) { /* use the IAsyncBank */ }
});
```

### IAsyncBank

All operations return `CompletableFuture` and work identically on master and slave servers. Obtained from `IAsyncBankAccount` via `getBankAsync(itemID)`.

#### Balance Queries

| Method | Description |
|--------|-------------|
| `getBalanceAsync()` → `Long` | Free available balance (raw) |
| `getLockedBalanceAsync()` → `Long` | Locked balance reserved for pending transactions |
| `getTotalBalanceAsync()` → `Long` | Sum of available + locked balance |
| `getRealBalanceAsync()` → `Double` | Free available balance (scaled for display) |
| `getRealLockedBalanceAsync()` → `Double` | Locked balance (scaled) |
| `getRealTotalBalanceAsync()` → `Double` | Total balance (scaled) |

#### Mutations

| Method | Description |
|--------|-------------|
| `depositAsync(long amount)` → `BankStatus` | Deposit raw amount |
| `depositRealAsync(double amount)` → `BankStatus` | Deposit scaled amount |
| `withdrawAsync(long amount)` → `BankStatus` | Withdraw from available balance |
| `withdrawRealAsync(double amount)` → `BankStatus` | Withdraw scaled amount |
| `withdrawLockedAsync(long amount)` / `withdrawLockedRealAsync(double)` → `BankStatus` | Withdraw only from locked balance |
| `withdrawLockedPreferedAsync(long amount)` / `withdrawLockedPreferedRealAsync(double)` → `BankStatus` | Withdraw from locked first, then available |
| `setBalanceAsync(long balance)` → `Boolean` | Set balance directly (admin use) |
| `setRealBalanceAsync(double balance)` → `Boolean` | Set balance using scaled value |
| `hasSufficientFundsAsync(long amount)` → `Boolean` | Check if available balance covers amount |

#### Transfers

| Method | Description |
|--------|-------------|
| `transferAsync(long amount, int toAccount)` → `BankStatus` | Transfer from available balance to another account |
| `transferRealAsync(double amount, int toAccount)` → `BankStatus` | Transfer scaled amount |
| `transferFromLockedAsync(long amount, int toAccount)` / `transferFromLockedRealAsync(double, int)` → `BankStatus` | Transfer from locked balance |
| `transferFromLockedPreferedAsync(long amount, int toAccount)` / `transferFromLockedPreferedRealAsync(double, int)` → `BankStatus` | Transfer from locked first, then available |

#### Locking

| Method | Description |
|--------|-------------|
| `lockAmountAsync(long amount)` / `lockAmountRealAsync(double)` → `BankStatus` | Reserve amount for a pending transaction |
| `unlockAmountAsync(long amount)` / `unlockAmountRealAsync(double)` → `BankStatus` | Release a previously locked amount |
| `unlockAllAsync()` → `void` | Release all locked amounts |

#### Formatting & Conversion

| Method | Description |
|--------|-------------|
| `convertToRawAmountAsync(double realAmount)` → `Long` | Convert display value to internal representation |
| `convertToRealAmountAsync(long rawAmount)` → `Double` | Convert internal value to display representation |
| `getNormalizedBalanceAsync()` / `getNormalizedLockedBalanceAsync()` / `getNormalizedTotalBalanceAsync()` → `String` | Balance with SI suffix (1k, 1M, etc.) |
| `getFormattedBalanceAsync()` / `getFormattedLockedBalanceAsync()` / `getFormattedTotalBalanceAsync()` → `String` | Balance with thousand separators (15'649'864) |
| `getNormalizedAmountAsync(double real \| long raw)` / `getFormattedAmountAsync(double real \| long raw)` → `String` | Format any amount the same way |
| `getItemNameAsync()` → `String` | Display name of the item this bank holds |
| `getMinimalDataAsync()` → `BankData` | Item id, balance and locked balance in one snapshot |
| `toJsonAsync()` / `toJsonStringAsync()` → `JsonElement` / `String` | Bank data as JSON |
| `toStringAsync()` / `toStringNoOwnerAsync()` → `String` | Debug text |

#### Raw vs Real Amounts

The bank stores amounts as `long` values using fixed-point arithmetic. For money with cents, `150` represents `1.50`:

```java
bank.depositAsync(150L);         // deposits 1.50 (raw)
bank.depositRealAsync(1.50);     // deposits 1.50 (scaled)
```

#### BankStatus

All mutation operations return `BankStatus`. Any value other than `SUCCESS` means nothing changed. On a slave the future can also fail instead — see [When an async call fails](#when-an-async-call-fails).

```java
bank.depositAsync(amount).thenAccept(status -> {
    if (status == BankStatus.SUCCESS) {
        // operation completed
    } else {
        // nothing changed (e.g. FAILED_NOT_ENOUGH_FUNDS, FAILED_ACCOUNT_FROZEN)
    }
});
```

| Value | Meaning |
|---|---|
| `SUCCESS` | Done |
| `FAILED_NOT_ENOUGH_FUNDS` | The (free or locked) balance does not cover the amount |
| `FAILED_OVERFLOW` | The target balance would overflow |
| `FAILED_NEGATIVE_VALUE` | A negative amount was passed |
| `FAILED_WRONG_INSTANCE_TYPE` | Internal: the target bank is not a server bank |
| `FAILED_INVALID_ITEM_ID` | The item id is not valid |
| `FAILED_NO_BANK` | The account has no bank for this item (e.g. the transfer target) |
| `FAILED_NO_MASTER_CONNECTION` | Slave only: the master was not reached, or (since 2.2.0) its status check says the call was not applied. Nothing changed |
| `FAILED_EXTERNAL_UNAVAILABLE` | The slot is bound to a currency mod that is not available right now (since 2.0.5) |
| `FAILED_ACCOUNT_FROZEN` | The account is frozen and the call was a player action (since 2.2.0, see below) |

### Account Freeze (since 2.2.0)

Admins can freeze an account (`/banksystem freeze`, management screen, or the API):

```java
// ISyncServerBankManager (master)
boolean isAccountFrozen(int accountNumber);
boolean setAccountFrozen(int accountNumber, boolean frozen, LedgerActor actor); // false = no such account
// IAsyncBankManager (master + slaves)
CompletableFuture<Boolean> isAccountFrozenAsync(int accountNumber);
// IBankSystemEvents (master)
DataEvent<AccountFreezeChangedEvent> getAccountFreezeChangedEvent();
record AccountFreezeChangedEvent(int accountNumber, boolean frozen, LedgerActor actor) {}
```

A real change writes one `ACCOUNT_FREEZE` ledger row and fires the event once; setting the current state again does nothing.

The master refuses player movements on a frozen account by their ledger type (DEPOSIT, WITHDRAW, TRANSFER, CONVERTER_*, UPLOAD_DEPOSIT, DOWNLOAD_WITHDRAW, CRAFT_*, SHARE_TRADE, and rejected slave codes) with `FAILED_ACCOUNT_FROZEN`; nothing changes. Payouts and dividends from a frozen company account fail with `ACCOUNT_FROZEN`.

**Not blocked:** untagged raw bank calls (no ledger type, e.g. StockMarket settlement, locks/unlocks) and admin edits. Add-ons that move player funds should check `isAccountFrozen(Async)` themselves (StockMarket refuses new orders on a frozen account).

### Locking Mechanism

The locking system prevents double-spending for pending transactions. This is critical for mods like the StockMarket that need to reserve funds.

```java
// 1. Lock the amount when creating a pending order
bank.lockAmountAsync(amount);

// 2. When the order is fulfilled, withdraw from locked balance
bank.withdrawLockedPreferedAsync(amount);

// 3. If the order is cancelled, unlock the amount
bank.unlockAmountAsync(amount);
```

Use `withdrawLockedPreferedAsync` over `withdrawLockedAsync` because it gracefully handles cases where the locked amount was partially freed by an admin using `setBalance`.

### IAsyncBankAccount

#### Account Info

| Method | Description |
|--------|-------------|
| `getAccountNumberAsync()` → `int` | Unique account number |
| `getAccountNameAsync()` → `String` | Display name of the account |
| `setAccountNameAsync(String name)` → `void` | Change the account name |
| `getAccountIconAsync()` → `ItemID` | Account icon (nullable) |
| `setAccountIconAsync(ItemID icon)` → `void` | Change the account icon |
| `getAccountDataAsync()` → `BankAccountData` | Full account data snapshot |

#### User Management

| Method | Description |
|--------|-------------|
| `addUserAsync(User user, int permission)` → `void` | Add a player with permissions |
| `removeUserAsync(UUID userUUID)` → `void` | Remove a player from the account |
| `setUsersAsync(Map<User, Integer> userList)` → `void` | Replace the entire user list |
| `hasUserAsync(UUID userUUID)` → `Boolean` | Check if a player has access |
| `hasAnyUserAsync()` → `Boolean` | Check if any users are linked |
| `getPersonalBankOwnerAsync()` → `User` | Get the account owner (nullable) |
| `getPermissionAsync(UUID userUUID)` → `Integer` | Get permission flags for a player |
| `hasPermissionAsync(UUID userUUID, BankPermission perm)` → `Boolean` | Check a specific permission |
| `setPermissionAsync(UUID userUUID, int permission)` → `void` | Set permission flags for a player |

#### Bank Management

| Method | Description |
|--------|-------------|
| `createBankAsync(ItemID itemID, long startBalance)` → `IAsyncBank` | Create a new item bank (or return existing) |
| `removeBankAsync(ItemID itemID)` → `void` | Delete an item bank (items are lost) |
| `removeAllBanksAsync()` → `void` | Delete all item banks |
| `removeEmptyBanksAsync()` → `List<ItemID>` | Remove banks with zero balance |
| `getBankAsync(ItemID itemID)` → `IAsyncBank` | Get a specific item bank (nullable) |
| `getOrCreateBankAsync(ItemID itemID)` → `IAsyncBank` | Get or create an item bank |
| `getAllBanksAsync()` → `Map<ItemID, IAsyncBank>` | Get all item banks in this account |
| `hasBankAsync(ItemID itemID)` → `Boolean` | Check if an item bank exists |
| `hasAnyBankAsync()` → `Boolean` | Check if the account has any banks |

### IBankSystemEvents

Subscribe to events for reactive integration via `api.getEvents()`:

| Method | Description |
|--------|-------------|
| `getUserAddedEvent()` → `DataEvent<User>` | Fires when a new player is registered |
| `getUserRemovedEvent()` → `DataEvent<User>` | Fires when a player is removed |
| `getBankAccountCreatedEvent()` → `DataEvent<ISyncServerBankAccount>` | Fires when an account is created |
| `getBankAccountDeletedEvent()` → `DataEvent<ISyncServerBankAccount>` | Fires when an account is deleted |
| `getBankDataSavedToFileSignal()` → `Signal` | Fires after bank data is saved |
| `getBankDataLoadedFromFileSignal()` → `Signal` | Fires after bank data is loaded |
| `getSettingsSavedToFileSignal()` → `Signal` | Fires after settings are saved |
| `getSettingsLoadedFromFileSignal()` → `Signal` | Fires after settings are loaded |
| `getBanksystemSetupCompleteSignal()` → `Signal` | Fires when the bank system is ready to use |
| `getItemIDsMergedEvent()` → `DataEvent<Map<ItemID,ItemID>>` | Fires after an ItemID merge (alias → canonical) |
| `getMasterServerSlaveConnected()` → `Signal` | Fires when a slave connects to the master |
| `getMasterServerSlaveDisconnected()` → `DataEvent<String>` | Fires when a slave disconnects (payload: slaveID) |
| `getSlaveConnectionAcceptedSignal()` → `Signal` | Slave side: this server's handshake with its master completed |
| `getSlaveConnectionLostSignal()` → `Signal` | Slave side: the master link dropped (edge-triggered) |
| `getTrustChangedSignal()` → `DataEvent<TrustChangeInfo>` | Fires when a slave is trusted/untrusted |
| `getPayoutExecutedEvent()` → `DataEvent<PayoutExecutedInfo>` | Fires on every payout-schedule evaluation, success or failure |
| `getDividendPaidEvent()` → `DataEvent<DividendPaidEvent>` | Fires after a successful dividend run |
| `getAccountFreezeChangedEvent()` → `DataEvent<AccountFreezeChangedEvent>` | Fires once per real freeze/unfreeze of an account (since 2.2.0) |
| `getTransactionLoggedEvent()` → `DataEvent<TransactionLoggedEvent>` | Fires once per transaction-ledger row on the master, on the writer's thread (since 2.2.0) |
| `getSuspicionFlaggedEvent()` → `DataEvent<SuspicionFlaggedEvent>` | Fires once per raised suspicion flag on the master, on the raising thread (since 2.2.0) |
| `removeListeners()` → `void` | Unsubscribe all listeners |

Threading, which JVM each one fires on, and full payload contracts: [Events & Signals](EventsAndSignals.md).

## Money Formatting (`MoneyFormat`)

`net.kroia.banksystem.api.MoneyFormat` formats money the same way BankSystem's [placeholders](../user-guide/Placeholders.md#number-format) do, so dependent mods (e.g. StockMarket) can show money values in the same style. All methods are static and thread-safe.

| Method | Description |
|--------|-------------|
| `static String formatMoney(long rawUnits)` | Formatted with the server's `MONEY_FORMAT` setting (default `$%s`): `123456` → `"$1'234.56"` |
| `static String formatMoney(long rawUnits, String pattern)` | Formatted with your own pattern; `%s` is replaced by the number. A pattern without `%s` gives the bare number (`"1'234.56"`). |
| `static String formatMoneyRaw(long rawUnits)` | Plain decimal without separator or symbol: `123456` → `"1234.56"` |
| `static String getPattern()` | The current `MONEY_FORMAT` pattern |
| `static void setPattern(String pattern)` | Set by BankSystem from the `MONEY_FORMAT` setting; dependent mods should not call it |

Amounts are **raw** bank units (`100` raw = `1.00` money, see [Raw vs Real Amounts](#raw-vs-real-amounts)). The number always uses `'` as thousands separator and 2 decimals (rounded down). Negative amounts get a `-` in front of the number, inside the pattern: `-123456` with the default pattern gives `"$-1'234.56"`.

```java
import net.kroia.banksystem.api.MoneyFormat;

long raw = 123456;                                   // 1234.56 money
String shown = MoneyFormat.formatMoney(raw);         // "$1'234.56" with the default MONEY_FORMAT
String euro  = MoneyFormat.formatMoney(raw, "%s €"); // "1'234.56 €"
String plain = MoneyFormat.formatMoneyRaw(raw);      // "1234.56"
```

> [!NOTE]
> The pattern is set from the server's `MONEY_FORMAT` setting when the server starts. On a client that is connected to a remote server, `formatMoney(long)` uses the default pattern `$%s`.

## Removing a User

`ISyncServerBankManager.removeUser(uuid)` and `IAsyncBankManager.removeUserAsync(uuid)` delete the user and their personal account, plus accounts left without users (company accounts are kept). The balances are handed to the player as items: this server first, then each slave. What cannot be delivered (player offline, outcome unknown, or a rest below one item) is destroyed and logged as `ACCOUNT_DELETE_LOSS`; the rest is logged as `ACCOUNT_DELETE_PAYOUT`.

Both return **before delivery is known**: `true` only means the user existed and was removed. The payout and its ledger rows finish later. There is no public API that reports what was delivered.

## Note Flow Log (`INoteFlowLog`)

`api.getNoteFlowLog()` (since 2.2.0) records money items that are created or destroyed outside the bank, e.g. money a villager pays out or takes in. Rows go to **this server's** diagnostics database (`banksystem_diagnostics.db`), so it works on every server including slaves, without a round trip to the master. It never blocks and never throws; it does nothing when the diagnostics database is missing or switched off.

```java
INoteFlowLog flow = api.getNoteFlowLog();
// on the server thread: the villager is read right away
flow.record("mymod:villager_pay", player.getUUID(), moneyItemId, +count, villager, "trade #12");
flow.record("mymod:burn", player.getUUID(), moneyItemId, -count, null); // no villager
```

| Parameter | Meaning |
|---|---|
| `source` | Namespaced kind, e.g. `stockmarket:villager_pay` |
| `player` | Player involved, or `null` |
| `item` | The money `ItemID` |
| `amount` | Signed **item count**: positive = created, negative = destroyed |
| `villager` | The trading villager, or `null`. BankSystem stores its UUID, dimension id and block position (`villager_uuid`, `dimension`, `x`, `y`, `z` columns) |
| `context` | Other free text, or `null` |

### Your own diagnostics file (`DiagnosticsDatabase`)

An add-on can reuse BankSystem's write-only SQLite writer for its own file (since 2.2.0). The class is `net.kroia.banksystem.data.DiagnosticsDatabase`, outside `api/`, so it may change in a later version.

```java
DiagnosticsDatabase db = new DiagnosticsDatabase(
        worldDir.resolve("mymod_diagnostics.db"),   // file
        "mymod-diag-db",                              // name of its own daemon thread
        "1",                                          // schema version, stored in Meta.schema_version
        List.of("CREATE TABLE IF NOT EXISTS MyRows (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, info TEXT)"));
db.execute("INSERT INTO MyRows (ts, info) VALUES (?, ?)", System.currentTimeMillis(), "hello");
db.close(); // on server stop: drains the queue for up to 2 s
```

- The file gets a `Meta (key, value)` table (`created_ms`, `schema_version`), then your `schema` statements, run in order on every open (use `CREATE ... IF NOT EXISTS`). It never gets BankSystem's tables.
- `execute` and `setMeta` queue the write and return at once. A full queue (10 000 writes) drops the write; `dropped()` counts them. `flush()` completes once every earlier write is committed.
- Opened on the first write. A corrupt file is renamed `.corrupt-<ms>` and recreated; any other open failure disables the instance for the session (`isDisabled()`). Nothing throws to callers.

## Company Payouts and Dividends

Both interfaces are **master-only**; on a slave they are shims that refuse everything with `NOT_MASTER` (reads give an empty list or future).

### `IPayoutManager` (`api.getPayoutManager()`)

| Method | Returns |
|---|---|
| `createSchedule(companyId, target, amount, intervalTicks, nowTick, createdBy)` | `CreateOutcome` |
| `createSchedule(..., targetAccountNr, targetPlayerName, targetAccountName, Mode mode, short currencyItem)` | `CreateOutcome` |
| `updateSchedule(companyId, scheduleId, newAmount, newIntervalTicks)` | `OpResult` |
| `updateScheduleEx(companyId, scheduleId, newAmount, newIntervalTicks, nowTick, newTarget, newTargetAccountNr, newTargetPlayerName, newTargetAccountName, newMode, newCurrencyItem)` | `OpResult` |
| `pauseSchedule(companyId, scheduleId, paused)` / `deleteSchedule(companyId, scheduleId)` | `OpResult` |
| `listSchedulesFor(companyId)` | `List<PayoutSchedule>` |
| `getHistory(scheduleId, limit)` | `CompletableFuture<List<PayoutHistoryRecord>>`, newest first |
| `getTotalPaidForSchedule(scheduleId)` | `CompletableFuture<Long>`, sum of OK payouts |

- `OpResult`: `OK`, `COMPANY_MISSING`, `SCHEDULE_MISSING`, `INVALID_INPUT`, `NOT_MASTER`.
- `CreateOutcome(OpResult result, long scheduleId)`: `scheduleId` is set when `result == OK`.
- `amount` is in raw units (`> 0`); `intervalTicks` has a floor of 20 (1 s).
- `PayoutSchedule.Mode`: `FIXED_PAYOUT`, `DIVIDEND`, `ONE_TIME` (runs once at the scheduled time, then removes itself).
- `currencyItem`: short `ItemID` of the paid item; `0` = money.

Every evaluation fires `getPayoutExecutedEvent()` — see [Events & Signals](EventsAndSignals.md#getpayoutexecutedevent) for the status values.

### `IDividendPayer` (`api.getDividendPayer()`)

```java
PayDividendResult r = api.getDividendPayer().payDividend(companyId, amountPerShare,
        /* includeCompanyAccount */ false, actorUuidOrNull, /* currencyItem, 0 = money */ (short) 0);
if (r.success()) { long paid = r.totalPaid(); int holders = r.holderCount(); }
```

All or nothing: the run pays every holder or changes nothing. `amountPerShare` is in raw units of `currencyItem`. On any reason other than `OK`, `totalPaid` and `holderCount` are 0.

| `PayDividendResult.Reason` | Meaning |
|---|---|
| `OK` | Every holder paid |
| `NOT_MASTER` | Called on a slave or before start-up finished |
| `COMPANY_MISSING` | No such company |
| `INVALID_INPUT` | `amountPerShare` ≤ 0 |
| `NO_SHARES` | No account holds the company's shares |
| `INSUFFICIENT_FUNDS` | The company account cannot cover the total |
| `NO_PERMISSION` | The caller lacks MANAGE on the company |
| `INTERNAL` | Internal error (bank manager or source account missing) |
| `CURRENCY_ITEM_MISSING` | The company account has no bank for `currencyItem` |
| `ACCOUNT_FROZEN` | The company account is frozen (since 2.2.0) |

## API Package Structure

All public interfaces are in `net.kroia.banksystem.api`:

```
api/
├── BankSystemAPI.java              — Main entry point
├── IBankSystemEvents.java          — Event subscriptions
├── IBankSystemDataHandler.java     — Data persistence
├── ItemPriceProvider.java          — Price source for total-wealth tracking
├── PayDividendResult.java          — Dividend run result + typed reason
├── MoneyFormat.java                — Shared money display format
├── bank/
│   ├── IAsyncBank.java             — Async bank operations
│   ├── ISyncServerBank.java        — Sync bank operations (master only)
│   ├── IServerBank.java            — Server-side bank interface
│   ├── IClientBank.java            — Client-side read-only bank
│   ├── BankStatus.java             — Operation result enum
│   └── BankType.java               — Bank type enum
├── bankaccount/
│   ├── IAsyncBankAccount.java      — Async account operations
│   ├── ISyncServerBankAccount.java — Sync account operations (master only)
│   └── IServerBankAccount.java     — Server-side account interface
├── bankmanager/
│   ├── IBankManager.java           — Access point (sync/async routing)
│   ├── IAsyncBankManager.java      — Async manager operations
│   ├── ISyncServerBankManager.java — Sync manager operations (master only)
│   ├── IClientBankManager.java     — Client-side manager
│   └── IServerBankManager.java     — Server-side manager interface
├── company/
│   ├── ShareVisuals.java           — A share's visual identity (layers, name, supply)
│   ├── IBankSystemVisualLookup.java— Client-side per-ItemID visual lookup
│   └── IShareIconRenderer.java     — Optional share badge painter
├── payout/
│   └── IPayoutManager.java         — Company payout schedules (master only)
├── dividend/
│   └── IDividendPayer.java         — One-shot dividend runs (master only)
├── currency/
│   ├── ExternalCurrencyProvider.java — SPI for currency mods
│   ├── ExternalAccount.java        — An account inside a currency mod
│   ├── ExternalAccountRef.java     — Stable reference to such an account
│   └── ProviderFeature.java        — Optional capability flags
├── ledger/
│   ├── ITransactionLedger.java     — Write ledger rows (getLedger())
│   ├── LedgerType.java             — Stable type codes
│   ├── LedgerActor.java            — Who caused a row
│   └── TradeLogEntry.java          — StockMarket trade slice (+ TradeSide, TradeRowKind)
├── diagnostics/
│   └── INoteFlowLog.java           — Money items created/destroyed outside the bank (getNoteFlowLog())
├── event/
│   ├── TrustChangeInfo.java        — Payload of getTrustChangedSignal()
│   ├── PayoutExecutedInfo.java     — Payload of getPayoutExecutedEvent()
│   ├── DividendPaidEvent.java      — Payload of getDividendPaidEvent()
│   ├── AccountFreezeChangedEvent.java — Payload of getAccountFreezeChangedEvent()
│   ├── TransactionLoggedEvent.java — Payload of getTransactionLoggedEvent()
│   └── SuspicionFlaggedEvent.java  — Payload of getSuspicionFlaggedEvent()
└── command/
    ├── IBankSystemCommands.java     — Command registration (internal; no BankSystemAPI accessor)
    ├── IAsyncBankSystemCommandHandler.java  — Async command handlers
    └── IServerBankSystemCommandHandler.java — Server command handlers
```

## Further Reading

- [Events & Signals](EventsAndSignals.md) — Full event catalog with threading and side contracts
- [Currency Integration](CurrencyIntegration.md) — Implementing `ExternalCurrencyProvider`
- [Item Price Provider API](ItemPriceProviderAPI.md) — Supplying prices for total-wealth tracking
- [Transaction Ledger](TransactionLedger.md) — `getLedger()`, type codes and columns
- [Async Forwarding Architecture](AsyncForwardingArchitecture.md) — How that system works internally
