# BankSystem Documentation

## For Mod Users

Guides for players and server administrators on how to use the BankSystem mod.

| Guide | Description |
|-------|-------------|
| [Usage](user-guide/Usage.md) | How to use the Bank Terminal (including its crafting grid), ATM, automation blocks, bank displays, and money stockpiles |
| [Bank Accounts](user-guide/BankAccounts.md) | Shared bank accounts, creating accounts, permissions |
| [Currency Bindings](user-guide/CurrencyBindings.md) | Link a bank slot to a Numismatics or Lightman's Currency account |
| [Companies](user-guide/Companies.md) | Found a company, manage workers, issue shares with the Share Stamper, pay dividends and recurring payouts |
| [Administration](user-guide/Administration.md) | Server admin guide: bankable items, player accounts, account freeze, locked amounts, backups, transaction ledger, audit screen and files, suspicious-activity flags, suspended currency bindings, admin role |
| [Configuration](user-guide/Configuration.md) | Per-world `settings.json` reference; volatile & deposit-gated item components |
| [Placeholders](user-guide/Placeholders.md) | Show balances, money in circulation and a richest-players list via TAB or Placeholder API |
| [Multi-Server Setup](user-guide/MultiserverSetup.md) | Master-slave setup, what runs where, trusted and untrusted slaves, failed requests and transfers, troubleshooting |
| [Commands](user-guide/Commands.md) | Full command reference |

## For Mod Developers

Technical documentation for developers integrating with or extending BankSystem.

| Guide | Description |
|-------|-------------|
| [API Reference](developer-guide/API.md) | Public API overview, entry points, and usage examples |
| [Events & Signals](developer-guide/EventsAndSignals.md) | Every event/signal for dependent mods: user/account changes, ItemID merges, multi-server connection lifecycle, slave-trust changes, payouts and dividends, transaction logged (ledger), suspicion flagged, account freeze changed |
| [Diagnostics](developer-guide/API.md#note-flow-log-inoteflowlog) | `getNoteFlowLog()`: record money notes created or destroyed outside the bank in the diagnostics database (`banksystem_diagnostics.db`) for external audit tools |
| [Currency Integration](developer-guide/CurrencyIntegration.md) | `ExternalCurrencyProvider` SPI for currency-mod authors who want their mod in the binding picker |
| [Item Price Provider API](developer-guide/ItemPriceProviderAPI.md) | Supply item prices for total-wealth tracking |
| [Transaction Ledger](developer-guide/TransactionLedger.md) | Ledger type codes, columns, and writing rows from other mods |
| [Async Forwarding Architecture](developer-guide/AsyncForwardingArchitecture.md) | Internal RPC system for multi-server function forwarding |

## Development & Testing

BankSystem ships in-game tests and a few dev-only commands. They are compiled only when the Gradle property `enable_dev_features` is `true` (default in `gradle.properties`: `false`). Build a dev jar with:

```
./gradlew build -Penable_dev_features=true
```

A dev build of BankSystem also needs a ModUtilities jar built with `-Penable_dev_features=true`.

In a dev build, operators (permission level 2) get:

| Command | Description |
|---------|-------------|
| `/banksystem test` | Run all in-game tests |
| `/banksystem test list` | List the test categories |
| `/banksystem test <category>` | Run one category |
| `/banksystem exportrecipes` | Export all BankSystem crafting recipes as PNG images |

> [!WARNING]
> The in-game tests change real bank data on the running world. Some tests disallow items, which removes those banks from **every** bank account. Never run them on a real world or server — use a throwaway test world.

To run the tests automatically, use the autotest harness in the shared `Tools/autotest` folder next to the mod repositories. It builds the mods with dev features enabled, starts the game on Fabric and NeoForge in singleplayer, master/client and master/slave setups, runs every registered in-game test suite (ModUtilities, BankSystem, StockMarket) in a fresh world, and prints a pass/fail table.
