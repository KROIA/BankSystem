# Command Reference

What the columns mean:

- **Admin only** — the command needs a permission node (listed in the description). Without a permission mod such as LuckPerms, each node falls back to operator level 2; `/banksystem discord` needs level 3. The server console always passes. All nodes are listed in the [main README](../../README.md#permissions-luckperms).
- **BS Admin only** — the command needs the [BankSystem admin flag](Administration.md#banksystem-admin-role), set with `/banksystem op`. Minecraft operator status alone is not enough.

On a slave server that the master does not trust, only `/money` and `/money circulation` work; see [Multi-Server Setup](MultiserverSetup.md#what-an-untrusted-slave-can-do).

## BankSystem Commands

| Command | Description | Admin only | BS Admin only |
|---------|-------------|:----------:|:-------------:|
| `/banksystem manage` | Opens the bankable-items management window; from there admins reach the player accounts, the server history and the **Mod Settings** screen | | X |
| `/banksystem op [user]` | Makes the specified player a BankSystem Admin, or yourself without a name. Master server only. Node `banksystem.admin.command.op` | X | |
| `/banksystem deop [user]` | Removes the BankSystem Admin status from a player, or from yourself without a name. Master server only. Node `banksystem.admin.command.op` | X | |
| `/banksystem allowItem <itemID>` | Adds the item to the list of bankable items | | X |
| `/banksystem allowItemInHand` | Adds the item currently in your main hand to the bankable items list | | X |
| `/banksystem disallowItem <itemID>` | Removes the item from the bankable items list (removes from all players) | | X |
| `/banksystem disallowItemInHand` | Removes the item in your main hand from the bankable items list | | X |
| `/banksystem freeze <account> [on\|off]` | Freezes or unfreezes a bank account; without `on`/`off` it toggles. Also works from the server console (master only) | | X |
| `/banksystem audit <player\|account>` | Opens the audit screen with the ledger entries of a player's personal account or of an account number. Needs a player (not the console) | | X |
| `/banksystem auditreport <hours>` | Writes an audit digest of the last 1 to 720 hours on the master and tells you the file path. Also works from the master's console | | X |
| `/banksystem history <player\|account>` | Opens the balance history of a player's personal account or of an account number, with suspicion flags as chart markers. Needs a player | | X |
| `/banksystem alerts on\|off` | Turns your chat alerts for admin actions and suspicion flags on or off; the audit files are written either way. Needs a player | | X |
| `/banksystem restorebinding <account> [item]` | Lifts the suspension of a [suspended currency binding](Administration.md#suspended-currency-bindings) on that account (one slot when `item` is given). Master server or its console only. Node `banksystem.admin.command.restorebinding` | X | |
| `/banksystem trust <slaveID>` | Adds a slave server to the trusted list (master only). Node `banksystem.admin.command.trust` | X | |
| `/banksystem untrust <slaveID>` | Removes a slave server from the trusted list (master only). Node `banksystem.admin.command.trust` | X | |
| `/banksystem serverInfo` | Shows information about this server | | |
| `/banksystem serverNetworkInfo` | Shows information about the server network and trust status. Refused on an untrusted slave | | X |
| `/banksystem backup pause` | Pauses database writes so the world files can be copied safely (master only). Node `banksystem.admin.command.backup` | X | |
| `/banksystem backup resume` | Resumes database writes after a pause (master only). Node `banksystem.admin.command.backup` | X | |
| `/banksystem backup status` | Shows whether database writes are currently paused (master only). Node `banksystem.admin.command.backup` | X | |
| `/banksystem backup snapshot <path>` | Writes a consistent database snapshot to the given path (master only). Node `banksystem.admin.command.backup` | X | |
| `/banksystem symbols list` | Lists every share symbol in the server's symbol library (master only). Node `banksystem.admin.command.symbols` (all `symbols` commands) | X | |
| `/banksystem symbols add <id>` | Imports a PNG from the server inbox folder as a new share symbol (master only) | X | |
| `/banksystem symbols remove <id>` | Deletes a share symbol; remaining ids are compacted automatically (master only) | X | |
| `/banksystem symbols reload` | Re-reads the symbol manifest from disk and pushes it to all clients (master only) | X | |
| `/banksystem discord status` | Shows the [Discord feed](Configuration.md#discord-feed) state of all mods: webhook names, enabled types, `minAmount`; never the webhook URLs. Permission `modutilities.command.discord` (operator level 3 without a permission mod) | X | |
| `/banksystem discord reload` | Re-reads the Discord feed config files; same as `/modutilities discord reload`. Permission `modutilities.command.discord` | X | |

## Money Commands

| Command | Description | Admin only | BS Admin only |
|---------|-------------|:----------:|:-------------:|
| `/money` | Show your money balance | | |
| `/money add <amount>` | Add money to yourself | | X |
| `/money add <user> <amount>` | Add money to another player | | X |
| `/money set <amount>` | Set your money balance | | X |
| `/money set <user> <amount>` | Set another player's money balance | | X |
| `/money remove <amount>` | Remove money from yourself | | X |
| `/money remove <user> <amount>` | Remove money from another player | | X |
| `/money send <user> <amount>` | Send money to another player | | |
| `/money circulation` | Show total money circulation across all players | | |

## Bank Commands

| Command | Description | Admin only | BS Admin only |
|---------|-------------|:----------:|:-------------:|
| `/bank` | Show your bank balance (money and items) | | |
| `/bank enableNotifications` | Enable bank transaction notifications | | |
| `/bank disableNotifications` | Disable bank transaction notifications | | |
| `/bank manage` | Open the management GUI for your bank account | | |
| `/bank manage <accountname>` | Open the management GUI for the specified account | | |
| `/bank create <accountname>` | Create a new bank account with the given name. Limited per player by `MAX_BANK_ACCOUNTS_PER_PLAYER` (default 3); at the limit you get *"You have reached your limit of 3 bank account(s). You cannot create another one."* | | |
| `/bank <username> manage` | Open the management GUI for a specific player's account | | X |
| `/bank <username> show` | Show another player's bank balance | | X |

## Company Commands

| Command | Description | Admin only | BS Admin only |
|---------|-------------|:----------:|:-------------:|
| `/company create <name> <maxSupply>` | Founds a company with a bound bank account, with you as founder. `maxSupply` is the share cap, between 1 and 1,000,000,000. Limited by `MAX_COMPANIES_PER_PLAYER` and needs a free bank-account slot ([details](Companies.md#founding-a-company)) | | |
| `/company info <companyName>` | Prints company metadata to chat — founders, max supply, issued shares and description | | |
| `/company manage <companyName>` | Opens the Company Management screen (requires MANAGE on the company's account) | | |

Company names are case-insensitive, and tab-completion is scoped to your rights: `info` suggests every company on the server, `manage` only those where you hold MANAGE.

Everything else is done in the game world rather than in chat:

| Action | Where |
|--------|-------|
| Edit the description | Company Management → Overview |
| Hire workers, change their permissions | Company Management → Workers |
| Create payouts, pay dividends | Company Management → Payouts |
| Design the share card, unbind stampers | Company Management → Shares |
| Open, pause or close a share market | Company Management → Market (needs StockMarket) |
| Transfer the founder role, dissolve the company | Company Management → Danger Zone (founder only) |
| Bind a Share Stamper to a company | Right-click an unbound Share Stamper |
