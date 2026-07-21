# BankSystem Documentation

## For Mod Users

Guides for players and server administrators on how to use the BankSystem mod.

| Guide | Description |
|-------|-------------|
| [Usage](user-guide/Usage.md) | How to use the Bank Terminal (including its crafting grid), ATM, automation blocks, bank displays, and money stockpiles |
| [Bank Accounts](user-guide/BankAccounts.md) | Shared bank accounts, creating accounts, permissions |
| [Administration](user-guide/Administration.md) | Server admin guide: managing banking items, player accounts, locked amounts |
| [Configuration](user-guide/Configuration.md) | Per-world `settings.json` reference; volatile & deposit-gated item components |
| [Multi-Server Setup](user-guide/MultiserverSetup.md) | Master-slave architecture setup for cross-server banking |
| [Commands](user-guide/Commands.md) | Full command reference |

## For Mod Developers

Technical documentation for developers integrating with or extending BankSystem.

| Guide | Description |
|-------|-------------|
| [API Reference](developer-guide/API.md) | Public API overview, entry points, and usage examples |
| [Events & Signals](developer-guide/EventsAndSignals.md) | Every event/signal for dependent mods: user/account changes, ItemID merges, multi-server connection lifecycle, slave-trust changes |
| [Async Forwarding Architecture](developer-guide/AsyncForwardingArchitecture.md) | Internal RPC system for multi-server function forwarding |
