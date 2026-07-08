# Server Configuration (settings.json)

This guide describes the per-world configuration file `settings.json`, every setting it contains, and — in depth — the **volatile** and **deposit-gated item component** lists that control how items with time-varying data (e.g. TerraFirmaCraft food) are handled by the bank.

> [!NOTE]
> This file is **not** the multi-server connection config. Master/slave networking is configured in `<ServerFolder>/config/MultiServerConfig.json` — see [Multi-Server Setup](MultiserverSetup.md).

---
## File Location & Lifecycle

The settings file is stored **per world**:

| Environment | Path |
|-------------|------|
| Singleplayer | `saves/<world name>/data/BankSystem/settings.json` |
| Dedicated server | `<ServerFolder>/<world folder>/data/BankSystem/settings.json` |

Lifecycle:

- **Auto-created** with default values the first time the world is loaded with BankSystem installed (a warning `ServerBank settings file not found, creating default settings file.` is logged).
- **Loaded** every time the world/server starts.
- **Rewritten regularly while the server runs**: BankSystem saves all its data (including this file) on its save interval (`SAVE_INTERVAL_MINUTES`, default every 5 minutes while players are online), whenever the world is saved (autosave, `/save-all`), and on server shutdown.

> [!WARNING]
> Because the server rewrites `settings.json` on its save interval, **only edit the file while the world/server is stopped**. Edits made while the server is running are silently overwritten.
> Also make sure your edits are valid JSON and keep a backup — if the file cannot be loaded, BankSystem writes a fresh file, and your manual changes are lost.

> [!IMPORTANT]
> **Multi-server networks:** every server (master and slaves) has its own `settings.json`, but the item component lists (`ADDITIONAL_VOLATILE_COMPONENTS`, `ADDITIONAL_DEPOSIT_GATED_COMPONENTS`) are overwritten at runtime on slave servers and clients by the master's sync packet. Configure the component lists **on the master server**. See [Multi-Server Behavior](#multi-server-behavior) below.

---
## Default File

A freshly created `settings.json` looks like this:

```json
{
  "Utilities": {
    "SAVE_INTERVAL_MINUTES": 5,
    "BALANCE_SNAPSHOT_INTERVAL_MINUTES": 1,
    "BALANCE_SNAPSHOT_MAX_RECORDS_PER_ITEM": 1440,
    "LOGGING_ENABLE_INFO": true,
    "LOGGING_ENABLE_WARNING": true,
    "LOGGING_ENABLE_ERROR": true,
    "LOGGING_ENABLE_DEBUG": false
  },
  "Player": {
    "STARTING_BALANCE": 0
  },
  "ServerBank": {
    "ITEM_TRANSFER_TICK_INTERVAL": 2,
    "ADDITIONAL_VOLATILE_COMPONENTS": [],
    "ADDITIONAL_DEPOSIT_GATED_COMPONENTS": [],
    "CONFIRM_ITEMID_MERGE": false,
    "BANK_DOWNLOAD_BLOCK_UPDATE_TICK_INTERVAL": 20,
    "BANK_UPLOAD_BLOCK_UPDATE_TICK_INTERVAL": 20
  },
  "Placeholder": {
    "PLAYER_BALANCE": {
      "identifier": "%banksystem_player_balance%",
      "refreshRate": 1000
    },
    "PLAYER_LOCKED_BALANCE": {
      "identifier": "%banksystem_player_locked_balance%",
      "refreshRate": 1000
    },
    "PLAYER_TOTAL_BALANCE": {
      "identifier": "%banksystem_player_total_balance%",
      "refreshRate": 1000
    },
    "PLAYER_BANKUSER_JSON": {
      "identifier": "%banksystem_bankuser_json%",
      "refreshRate": 10000
    },
    "SERVER_CIRCULATION_JSON": {
      "identifier": "%banksystem_server_circulation_json%",
      "refreshRate": 10000
    }
  }
}
```

---
## Utilities Section

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `SAVE_INTERVAL_MINUTES` | number | `5` | Interval in minutes at which BankSystem auto-saves all its data (bank accounts, item registry, and this settings file). Saves only run while at least one player is online, plus one final save after the last player leaves. |
| `BALANCE_SNAPSHOT_INTERVAL_MINUTES` | number | `1` | Interval in minutes between balance snapshots. Snapshots feed the **Balance History** chart of the [Bank Display block](Usage.md#bank-display). One snapshot is also taken at server start. Set to `0` to disable periodic snapshots. |
| `BALANCE_SNAPSHOT_MAX_RECORDS_PER_ITEM` | number | `1440` | Maximum number of snapshot records kept per item per account; the oldest records are pruned when the limit is exceeded. `0` means unlimited — **the history database file can grow extremely large over time** (a warning is logged at startup in that case). |
| `LOGGING_ENABLE_INFO` | boolean | `true` | Enables BankSystem's info-level log messages. |
| `LOGGING_ENABLE_WARNING` | boolean | `true` | Enables BankSystem's warning-level log messages. |
| `LOGGING_ENABLE_ERROR` | boolean | `true` | Enables BankSystem's error-level log messages. |
| `LOGGING_ENABLE_DEBUG` | boolean | `false` | Enables BankSystem's debug-level log messages. |

---
## Player Section

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `STARTING_BALANCE` | number | `0` | Money balance credited to a player's personal bank account when the account is first created (first join). The value is given in the bank's internal fractional unit — balances are tracked in hundredths, so `100` = `1.00` money. |

---
## ServerBank Section

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `ITEM_TRANSFER_TICK_INTERVAL` | number | `2` | Reserved. This setting is present in the file but is not used by any active code path in the current version. |
| `ADDITIONAL_VOLATILE_COMPONENTS` | list of strings | `[]` | Extra **volatile** item component type ids (e.g. `"tfc:food"`) that are ignored for item identification. Extends the datapack tag `banksystem:volatile_item_components`. See the [deep dive](#volatile--deposit-gated-item-components) below. |
| `ADDITIONAL_DEPOSIT_GATED_COMPONENTS` | list of strings | `[]` | Extra **deposit-gated** item component type ids. Gated components are ignored for identification too, but deposits of items carrying them are only accepted in withdrawal-fresh condition. Extends the datapack tag `banksystem:deposit_gated_components`. See the [deep dive](#volatile--deposit-gated-item-components) below. |
| `CONFIRM_ITEMID_MERGE` | boolean | `false` | **One-shot confirmation flag for the ItemID merge guard.** When a change to the component lists would irreversibly merge genuinely distinct bank items, the server refuses to start and logs a report. Setting this to `true` approves that merge on the next startup; the flag automatically resets to `false` afterwards. See [Changing the Lists on an Existing World](#changing-the-lists-on-an-existing-world). |
| `BANK_DOWNLOAD_BLOCK_UPDATE_TICK_INTERVAL` | number | `20` | Interval in game ticks between work cycles of the [Bank Download Block](Usage.md#bank-download-block) (20 ticks = 1 second). |
| `BANK_UPLOAD_BLOCK_UPDATE_TICK_INTERVAL` | number | `20` | Interval in game ticks between work cycles of the [Bank Upload Block](Usage.md#bank-upload-block) (20 ticks = 1 second). |

---
## Placeholder Section

These entries define placeholder identifiers and refresh rates (in milliseconds) for the optional **TAB** plugin integration. Each entry has the shape:

```json
{ "identifier": "%banksystem_player_balance%", "refreshRate": 1000 }
```

An identifier must start and end with `%`, otherwise its registration is skipped.

| Key | Default identifier | Default refresh rate (ms) | Provides |
|-----|--------------------|---------------------------|----------|
| `PLAYER_BALANCE` | `%banksystem_player_balance%` | `1000` | The player's available money balance. |
| `PLAYER_LOCKED_BALANCE` | `%banksystem_player_locked_balance%` | `1000` | The player's locked money balance (see [Locked Amounts](Administration.md#locked-amounts)). |
| `PLAYER_TOTAL_BALANCE` | `%banksystem_player_total_balance%` | `1000` | The player's total money balance (available + locked). |
| `PLAYER_BANKUSER_JSON` | `%banksystem_bankuser_json%` | `10000` | The player's whole bank account as a JSON string. |
| `SERVER_CIRCULATION_JSON` | `%banksystem_server_circulation_json%` | `10000` | Server-wide item circulation data as a JSON string. |

> [!NOTE]
> In the current version the actual placeholder registration is disabled in the mod (pending master/slave compatibility work), so these settings have **no effect yet**. They are documented here because they appear in the file.

---
## Volatile & Deposit-Gated Item Components

### The Problem

BankSystem identifies items by their item type **plus their full data components** — that is what keeps an enchanted pickaxe, a potion, and a renamed sword distinct in your bank. Some mods, however, attach **time-varying data components** to item stacks:

- TerraFirmaCraft stamps every food item with a creation date (`tfc:food`) that drives food decay.
- TerraFirmaCraft tracks the temperature of heated items (`tfc:heat`).

Such components change on their own over time. To a byte-exact identity system this makes identical items look different: every freshly caught fish would register as a brand-new bank item, and stored item entries could "rot in place" inside the bank's registry. BankSystem solves this with two configurable component sets.

### Volatile Components

Component types in the **volatile** set are **ignored for item identification**. They are stripped from every item at the identity boundary (comparing, registering, storing, saving, syncing). Items are stored and compared *without* these components; when an item is handed back to a player, the owning mod re-attaches a fresh component automatically (e.g. TFC food withdrawn from a bank starts with a fresh creation date).

> [!NOTE]
> Stripping a whole component also removes its non-time-varying parts (e.g. TFC food *traits* such as "salted"). Items differing only in such data are treated as the same item by the bank, and the withdrawn item is a copy of the bank's stored template — the stripped data is not preserved.

### Deposit-Gated Components

Ignoring a state-carrying component makes differently-aged items fungible inside the bank — which would allow *state laundering*: deposit a rotten TFC food, withdraw a fresh one. The **deposit-gated** set closes this exploit.

Gated components are ignored for item identification exactly like volatile ones (you do **not** need to list a gated component in the volatile set as well). But at **deposit time** an extra rule applies:

> *An item may only be deposited if it is equivalent to what the bank would hand back for it.*

BankSystem rebuilds the withdrawal-equivalent item (the owning mod stamps fresh state onto it, exactly like on a real withdrawal) and compares it with the incoming item using Minecraft's stack-equality check. If they are not equivalent, the deposit is rejected with a message ("The condition of ... prevents banking it") and the item stays where it is — in the terminal inventory or the Bank Upload Block. Items that carry no gated component are entirely unaffected.

Because the source mod's own stack-equality semantics decide what counts as "equivalent", the check needs no mod-specific code. For TerraFirmaCraft this means: two foods compare equal only when their creation dates fall into the same decay-stacking window (6 in-game hours by default), and rotten food never equals fresh food.

> [!NOTE]
> With TFC defaults, food older than the 6-in-game-hour stacking window is rejected even though it is still perfectly edible. This is consistent with TFC's own stack-merging rules — such food would not stack with fresh food in a chest either. The window size is configurable in TFC's own config if you want to allow older food into the bank.

### Configuring the Component Sets

Each set is the **union of two sources** — both routes always apply and merge:

**Route 1 — server config list** (for server admins; no datapack needed). Add component type ids to the `ServerBank` section of `settings.json`:

```json
"ADDITIONAL_VOLATILE_COMPONENTS": ["somemod:some_component"],
"ADDITIONAL_DEPOSIT_GATED_COMPONENTS": ["somemod:other_component"]
```

Invalid ids (not a valid `namespace:path` resource location) are skipped with a log warning. Ids of components whose owning mod is not installed are kept harmlessly — they simply never match anything.

**Route 2 — datapack tags** (for modpack authors). The sets are backed by two tags over the data component type registry:

- `banksystem:volatile_item_components`
- `banksystem:deposit_gated_components`

To extend them, ship a datapack that adds entries to the tag. Note that the tag file must be placed in the **`banksystem` namespace** of your datapack so that it merges into BankSystem's tag:

```
my_datapack/
├── pack.mcmeta
└── data/
    └── banksystem/
        └── tags/
            └── data_component_type/
                ├── volatile_item_components.json
                └── deposit_gated_components.json
```

`data/banksystem/tags/data_component_type/volatile_item_components.json`:
```json
{
  "values": [
    { "id": "somemod:some_component", "required": false }
  ]
}
```

`pack.mcmeta`:
```json
{
  "pack": {
    "pack_format": 48,
    "description": "Extra volatile item components for BankSystem"
  }
}
```

`"required": false` marks the entry as optional: the datapack still loads when the owning mod is absent. Do not add `"replace": true` — that would discard BankSystem's shipped defaults.

### Shipped Defaults

BankSystem ships the tags pre-filled with known offenders (all as optional entries, harmless when TFC is not installed):

| Tag | Shipped entries |
|-----|-----------------|
| `banksystem:volatile_item_components` | `tfc:food`, `tfc:heat` |
| `banksystem:deposit_gated_components` | `tfc:food` |

**Do not add these ids again** in the config lists or your own datapack — they are already covered out of the box.

### Changing the Lists on an Existing World

When the effective component sets change (edited config list, added datapack, or the first load after updating to a version with this feature), BankSystem **re-normalizes every registered item template** against the new sets and **merges** entries that now collapse to the same identity:

- For each group of colliding entries, the lowest internal ID is kept as the canonical item; the others are removed and recorded as **aliases** of the canonical one.
- Existing references (bank balances, market listings, orders, ...) keyed by a merged ID keep resolving transparently through the alias table, so no data breaks.
- A log message reports how many IDs were merged: `Merged N duplicate ItemID(s) that collapsed to the same identity after removing volatile item components.`

> [!CAUTION]
> This migration is **one-way**. The stripped component data is permanently removed from the stored item templates, and aliases persist. Removing a component id from the lists later does **not** un-merge previously merged items. Back up your world before changing the component lists on a world with existing bank data.

#### The ItemID Merge Guard (`CONFIRM_ITEMID_MERGE`)

Because such a merge is irreversible, BankSystem distinguishes two kinds of merges at server startup:

- **Healing merges** — duplicates collapsing under an **unchanged** component set (e.g. spurious IDs minted by decaying food before the volatile-component fix). These are the intended self-repair and always run **automatically and silently**, exactly as described above. The same applies to the **first load** after updating to a version with the guard (the world has no recorded set yet).
- **Collapse merges** — the effective set **changed** since the world was last normalized, and applying the new set would fuse **genuinely distinct items** (e.g. a damaged and a fresh tool that were separate bank items, each possibly with its own market). These require an explicit admin decision.

To tell the two apart, BankSystem records the effective component set that was last applied in `<world>/data/BankSystem/Meta_data.nbt` and compares it at every master-server startup:

1. **Set unchanged (or no record yet):** normal startup, healing merges run silently.
2. **Set changed, no distinct items collide:** the new set is applied and recorded; a short info message is logged. No action needed.
3. **Set changed, distinct items would merge, `CONFIRM_ITEMID_MERGE` is `false` (default):** the **server refuses to start**. The log / crash report contains a full merge report: every merge group with its canonical ID, the IDs that would become aliases, the item names, and the component values that distinguish the items today (newly stripped components are marked `(NEW)`). Nothing is changed or saved — BankSystem suppresses **all of its data saves** for the remainder of the aborted session (including the automatic "Saving worlds" crash-save), so the world data on disk stays exactly as it was. You then have two options:
   - **Keep the items distinct:** revert the component-list change (config lists and/or datapack tags) and restart, or
   - **Approve the merge:** back up the world, set `"CONFIRM_ITEMID_MERGE": true` in the `ServerBank` section of `settings.json`, and restart.
4. **Set changed, collisions, `CONFIRM_ITEMID_MERGE` is `true`:** the merge report is logged as info, the merge is applied, the new set is recorded — and the flag is **automatically reset to `false`** and saved. The confirmation is strictly one-shot; it can never act as a standing bypass for future changes.

A confirmed merge (and any healing merge) also **consolidates existing bank data under the canonical IDs**: bank balances and locked balances that were stored under a merged ID are added onto the canonical item's bank in every account, the allowed-items list is rewritten to the canonical IDs, and account icons referencing a merged ID are updated. No amounts are lost — the totals per merge group are identical before and after. Dependent mods (e.g. StockMarket) are notified through a post-merge event so they can consolidate their own item-keyed data the same way.

> [!NOTE]
> The guard only runs on the **master server** (or a normal single server). Slave servers and clients always follow the master's item registry unconditionally — the master already made the informed decision.

> [!NOTE]
> **Datapack changes and `/reload`:** changing the `banksystem:*` tags via `/reload` or `/datapack enable` on a **running** server is rejected — item identification keeps using the component set from world load, an error is logged, and the new tag contents are evaluated by the merge guard at the **next restart**. A running server is never aborted.

### Which Components Can Be Listed?

Any data component type can be listed — but for most of them, doing so is a bad idea, because "ignored for identification" also means "not preserved on withdrawal": what you withdraw is a copy of the bank's stored template, not the exact stack you deposited. The table below covers common candidates. **It is not exhaustive** — it is a set of examples plus a [method for finding more](#finding-component-ids-for-other-mods).

| Component | What it stores | Effect of listing it as volatile | Recommendation |
|-----------|----------------|----------------------------------|----------------|
| `tfc:food` | TFC food creation date (decay) and traits | Already shipped as volatile **and** deposit-gated — decaying food works correctly out of the box. | **Shipped default** — do not add again. |
| `tfc:heat` | TFC temperature of heated items | Already shipped as volatile — hot and cold items are the same bank item. | **Shipped default** — do not add again. |
| `minecraft:custom_name` | Anvil rename | Renamed and unnamed items merge into one bank item; the custom name is lost on withdrawal. | **Situational.** Only cosmetic loss, but players may not appreciate their named gear losing its name. |
| `minecraft:lore` | Lore text lines (set by commands/mods) | Items differing only in lore merge; lore is lost on withdrawal. | **Situational.** Same trade-off as `minecraft:custom_name`. Beware of mods that use lore to mark special items. |
| `minecraft:custom_data` | Arbitrary NBT attached by mods, datapacks, and commands | All items differing only in custom data merge; the data is lost on withdrawal. | **Situational — expert use only.** Some mods store transient bookkeeping here, others store the item's entire meaning. Only list it if you know exactly what every mod on your server keeps in there. |
| `minecraft:damage` | Current durability damage of tools/armor | Every damage state becomes the same bank item, and withdrawn items come back in the template's condition — depositing a nearly broken tool and withdrawing it fresh is effectively a free repair. | **Discouraged.** Item-duplication-adjacent value implications. If damaged and fresh tools *must* share one bank entry, prefer listing it as **deposit-gated** instead: damaged tools are then rejected at deposit rather than laundered. |
| `minecraft:repair_cost` | Anvil prior-work penalty | Items differing only in the penalty merge; the penalty is reset to the template's value on withdrawal (penalty laundering). | **Discouraged.** Less severe than `minecraft:damage`, but it still erases a cost the game intentionally accumulates. |
| `minecraft:enchantments` | The item's enchantments | Enchanted and unenchanted items merge into a single bank item; enchantments are not preserved on withdrawal. | **Strongly discouraged.** Destroys the most meaningful distinction between items. There is almost no scenario where this is what you want. |

### Finding Component IDs for Other Mods

Advanced tooltips (`F3+H`) show item ids but **not** data components. To discover which components an item actually carries:

1. Hold the item in your main hand and run:
   ```
   /data get entity @s SelectedItem
   ```
   The output includes a `components` object listing every component id and its current value. Run it twice a few minutes apart — a component whose value changed on its own is a volatility candidate.
2. Browse the registered component types via command autocompletion: type `/give @s minecraft:stick[` and the suggestion list shows all data component type ids known to the game, including modded ones.
3. Check the mod's documentation or wiki — mods that use time-varying components (food decay, temperature, charge, ...) usually document them.

If you are unsure whether a component is safe to strip, test on a copy of your world first and watch the server log for the merge message described above.

### The Monotonic ItemID Counter

Every registered bank item is assigned an internal 16-bit short identifier. Since v2.0.3, BankSystem allocates these shorts from a **persisted monotonic counter** stored in the world's ItemID data (NBT key `nextShortCounter`). The counter has two guarantees you can rely on:

- **Monotonic** — it only ever moves forward. Newly registered items always get a short strictly greater than every short that has ever been issued in this world.
- **No reassignment of dropped shorts** — if a stored item template becomes unresolvable at world load (because the mod that added the item has been removed, or the stored NBT is corrupt), the entry is dropped with a `WARN` log line reporting the short and cached name. The dropped short remains **reserved** — the counter has already advanced past it, so the same short can never be handed out to a different item later. This prevents downstream state that is still keyed by that short (bank balances, StockMarket markets, plugin caches, ...) from silently rebinding to an unrelated new item.

**Legacy worlds** (saved before v2.0.3) migrate automatically on the first load: the counter is seeded to `max(shortsInItemIDMap ∪ shortsInItemIDAliasMap) + 1` and persisted from that point on. No admin action needed.

**Exhaustion** — the counter is a 32-bit int so the exhaustion point is explicit rather than a silent wraparound: once it passes `Short.MAX_VALUE` (32767), the allocator refuses to register new items and returns an invalid ID, logging an `ERROR` with the item's registry name. Existing ItemIDs and their bank balances are unaffected — only new registrations fail. In practice this is only reachable on worlds that have registered tens of thousands of distinct item templates over their lifetime.

### Multi-Server Behavior

In a [master/slave network](MultiserverSetup.md) all sides must identify items identically:

- The **config lists** are read from the **master's** `settings.json` and are automatically synced to all clients and forwarded to all slave servers. Values in a slave's own `settings.json` are overwritten at runtime by the master's sync — so configure the lists on the master.
- The **datapack tags** are distributed to clients by vanilla tag syncing, but they are *not* forwarded between servers. When using the datapack route, install the datapack on the **master and every slave server**.

---
## See Also

- [Administration](Administration.md) — managing banking items and player accounts
- [Multi-Server Setup](MultiserverSetup.md) — master/slave configuration (`MultiServerConfig.json`)
- [Usage](Usage.md) — blocks referenced by the settings above
