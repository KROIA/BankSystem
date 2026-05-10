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
| `getServerBankManager()` | `IBankManager` | Server-side bank manager (sync + async access) |
| `getClientBankManager()` | `IClientBankManager` | Client-side bank manager |
| `getEvents()` | `IBankSystemEvents` | Event/signal subscriptions |
| `getDataHandler()` | `IBankSystemDataHandler` | Save/load operations |
| `isSlave()` | `boolean` | Whether this server is a slave in multi-server mode |

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

**Rule of thumb**: Always use async unless you have a specific reason to use sync. Async works everywhere.

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

// Get a bank account by account number
IAsyncBankAccount account = asyncManager.getBankAccountAsync(accountNumber);

// Get a specific item bank within the account
CompletableFuture<IAsyncBank> bankFuture = account.getBankAsync(itemID);

// Or get all banks in the account
CompletableFuture<Map<ItemID, IAsyncBank>> allBanks = account.getAllBanksAsync();
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
| `withdrawLockedAsync(long amount)` → `BankStatus` | Withdraw only from locked balance |
| `withdrawLockedPreferedAsync(long amount)` → `BankStatus` | Withdraw from locked first, then available |
| `setBalanceAsync(long balance)` → `Boolean` | Set balance directly (admin use) |
| `setRealBalanceAsync(double balance)` → `Boolean` | Set balance using scaled value |
| `hasSufficientFundsAsync(long amount)` → `Boolean` | Check if available balance covers amount |

#### Transfers

| Method | Description |
|--------|-------------|
| `transferAsync(long amount, int toAccount)` → `BankStatus` | Transfer from available balance to another account |
| `transferRealAsync(double amount, int toAccount)` → `BankStatus` | Transfer scaled amount |
| `transferFromLockedAsync(long amount, int toAccount)` → `BankStatus` | Transfer from locked balance |
| `transferFromLockedPreferedAsync(long amount, int toAccount)` → `BankStatus` | Transfer from locked first, then available |

#### Locking

| Method | Description |
|--------|-------------|
| `lockAmountAsync(long amount)` → `BankStatus` | Reserve amount for a pending transaction |
| `unlockAmountAsync(long amount)` → `BankStatus` | Release a previously locked amount |
| `unlockAllAsync()` → `void` | Release all locked amounts |

#### Formatting & Conversion

| Method | Description |
|--------|-------------|
| `convertToRawAmountAsync(double realAmount)` → `Long` | Convert display value to internal representation |
| `convertToRealAmountAsync(long rawAmount)` → `Double` | Convert internal value to display representation |
| `getNormalizedBalanceAsync()` → `String` | Balance with SI suffix (1k, 1M, etc.) |
| `getFormattedBalanceAsync()` → `String` | Balance with thousand separators (15'649'864) |
| `getItemNameAsync()` → `String` | Display name of the item this bank holds |
| `toJsonAsync()` → `JsonElement` | Bank data as JSON |

#### Raw vs Real Amounts

The bank stores amounts as `long` values using fixed-point arithmetic. For money with cents, `150` represents `1.50`:

```java
bank.depositAsync(150L);         // deposits 1.50 (raw)
bank.depositRealAsync(1.50);     // deposits 1.50 (scaled)
```

#### BankStatus

All mutation operations return `BankStatus`:

```java
bank.depositAsync(amount).thenAccept(status -> {
    if (status == BankStatus.SUCCESS) {
        // operation completed
    } else {
        // handle failure (FAILED, INSUFFICIENT_FUNDS, etc.)
    }
});
```

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
| `getMasterServerSlaveConnected()` → `Signal` | Fires when a slave connects to the master |
| `removeListeners()` → `void` | Unsubscribe all listeners |

## API Package Structure

All public interfaces are in `net.kroia.banksystem.api`:

```
api/
├── BankSystemAPI.java              — Main entry point
├── IBankSystemEvents.java          — Event subscriptions
├── IBankSystemDataHandler.java     — Data persistence
├── bank/
│   ├── IAsyncBank.java             — Async bank operations (48 methods)
│   ├── ISyncServerBank.java        — Sync bank operations (master only)
│   ├── IClientBank.java            — Client-side read-only bank
│   ├── BankStatus.java             — Operation result enum
│   └── BankType.java               — Bank type enum
├── bankaccount/
│   ├── IAsyncBankAccount.java      — Async account operations (35+ methods)
│   ├── ISyncServerBankAccount.java — Sync account operations (master only)
│   └── IServerBankAccount.java     — Server-side account interface
├── bankmanager/
│   ├── IBankManager.java           — Access point (sync/async routing)
│   ├── IAsyncBankManager.java      — Async manager operations (60+ methods)
│   ├── ISyncServerBankManager.java — Sync manager operations (master only)
│   ├── IClientBankManager.java     — Client-side manager
│   └── IServerBankManager.java     — Server-side manager interface
└── command/
    ├── IBankSystemCommands.java     — Command registration
    ├── IAsyncBankSystemCommandHandler.java  — Async command handlers
    └── IServerBankSystemCommandHandler.java — Server command handlers
```

## Further Reading

- [Async Forwarding Architecture](AsyncForwardingArchitecture.md) — How that system works internally
