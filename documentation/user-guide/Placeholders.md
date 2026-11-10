# Placeholders

BankSystem provides placeholders that show bank data in tab lists, scoreboards, chat formats and anything else that supports placeholders: a player's balance, the money in circulation, account counts and a list of the richest players.

The placeholders are only active when one of these mods is installed on the server:

| Mod | Platforms | Placeholder format |
|-----|-----------|--------------------|
| [TAB](https://github.com/NEZNAMY/TAB) | Fabric, NeoForge | `%banksystem_<name>%`, e.g. `%banksystem_balance%` |
| [Placeholder API](https://placeholders.pb4.eu/) | Fabric | `%banksystem:<name>%`, e.g. `%banksystem:balance%` |

The placeholder names are fixed and listed below. Nothing has to be registered or configured to use them.

TAB and Placeholder API are **server-side only**: players don't need to install them. In singleplayer the game runs its own integrated server, so TAB (or Placeholder API) must be in the client's `mods` folder there.

> [!WARNING]
> **Known TAB issue (TAB 5.5.0, also happens with only TAB installed):** opening a second singleplayer world in the same game session disconnects you with *"An objective with the name TAB-PlayerList already exists"*. Restart the game between singleplayer worlds. Dedicated servers are not affected.

---
## How Values Are Updated

Placeholder values are cached. Player values are refreshed every `PLAYER_REFRESH_MS` milliseconds and server values every `SERVER_REFRESH_MS` milliseconds (see [Settings](#settings)), so a displayed value may lag a little behind the real balance.

Placeholders also work on [multi-server setups](MultiserverSetup.md): a slave server asks the master for the bank data, so players see the same values on every server.

---
## Number Format

Money values use `'` as the thousands separator and 2 decimals, wrapped in the `MONEY_FORMAT` setting (default `$%s`), e.g. `$1'234.56`.

Every money placeholder also has a `_raw` variant without separator or symbol, e.g. `1234.56` — use it when another plugin has to calculate with the value.

---
## Player Placeholders

| Name | Example | Description |
|------|---------|-------------|
| `balance` | `$1'234.56` | Available money in the player's personal account. |
| `balance_raw` | `1234.56` | Same as `balance`, unformatted. |
| `locked` | `$100.00` | Locked money in the player's personal account (see [Administration](Administration.md)). |
| `locked_raw` | `100.00` | Same as `locked`, unformatted. |
| `total` | `$1'334.56` | Available plus locked money. |
| `total_raw` | `1334.56` | Same as `total`, unformatted. |
| `account_number` | `12` | Number of the player's personal bank account, empty if the player has none. |
| `rank` | `3` | The player's position in the richest-players list (by total money, personal accounts only), empty if the player has no personal account. |

---
## Server Placeholders

| Name | Example | Description |
|------|---------|-------------|
| `circulation` | `$1'250'000.00` | All available money in all bank accounts. |
| `circulation_raw` | `1250000.00` | Same as `circulation`, unformatted. |
| `circulation_locked` | `$20'000.00` | All locked money in all bank accounts. |
| `circulation_locked_raw` | `20000.00` | Same as `circulation_locked`, unformatted. |
| `account_count` | `57` | Number of all bank accounts. |
| `player_count` | `42` | Number of players with a personal bank account. |
| `average_balance` | `$29'761.90` | Average total money of the personal accounts. |
| `average_balance_raw` | `29761.90` | Same as `average_balance`, unformatted. |
| `top_<n>_name` | `Steve` | Name of the player at position `n` of the richest-players list. |
| `top_<n>_balance` | `$250'000.00` | Total money of the player at position `n`. |
| `top_<n>_balance_raw` | `250000.00` | Same as `top_<n>_balance`, unformatted. |

`n` runs from `1` to `TOP_COUNT`, e.g. `%banksystem_top_1_name%` for the richest player. The list ranks personal accounts by total money (available plus locked). If there are fewer players than slots, the empty slots show an empty name and a zero balance.

---
## Settings

The placeholder settings are in the `Integrations` section of [settings.json](Configuration.md#integrations-section) and can also be edited in-game in the **Mod Settings** screen, under **Integrations → Placeholders enabled**. Settings files from versions before 2.2.0 had them in a `Placeholder` section; they are moved automatically on the first start.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `PLACEHOLDERS_ENABLED` | boolean | `true` | Provides the placeholders. When off, BankSystem does not provide them. **Requires a server restart.** |
| `PLAYER_REFRESH_MS` | number | `1000` | How often the player placeholder values are refreshed, in milliseconds. Valid range `100`–`86400000`. |
| `SERVER_REFRESH_MS` | number | `30000` | How often the server placeholder values (circulation, counts, average, richest-players list) are refreshed, in milliseconds. Valid range `100`–`86400000`. |
| `TOP_COUNT` | number | `10` | Number of `top_<n>` slots in the richest-players list. Valid range `0`–`100`. **Requires a server restart.** |
| `MONEY_FORMAT` | string | `"$%s"` | Format of the money placeholders. `%s` is replaced by the formatted number, e.g. `"%s €"` gives `1'234.56 €`. A value without `%s` is reset to the default. |

---
## Migrating from Older Versions

Before 2.2.0, the TAB placeholder identifiers were configurable in `settings.json` (`PLAYER_BALANCE`, `PLAYER_LOCKED_BALANCE`, `PLAYER_TOTAL_BALANCE`, `PLAYER_BANKUSER_JSON`, `SERVER_CIRCULATION_JSON`). These settings were removed: old entries in `settings.json` are ignored, and the placeholder names are fixed. Update your TAB configuration:

| Old default identifier | Replacement |
|------------------------|-------------|
| `%banksystem_player_balance%` | `%banksystem_balance%` |
| `%banksystem_player_locked_balance%` | `%banksystem_locked%` |
| `%banksystem_player_total_balance%` | `%banksystem_total%` |
| `%banksystem_bankuser_json%` | No JSON placeholder anymore; use the individual player placeholders. |
| `%banksystem_server_circulation_json%` | No JSON placeholder anymore; use `%banksystem_circulation%` and the other server placeholders. |
