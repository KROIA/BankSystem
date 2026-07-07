# Administration Guide

This guide is for server administrators and single-player worlds.

## Managing Banking Items

By default only money items can be stored in a bank. To change which items can be used for banking, an admin can open the settings GUI:
```
/banksystem manage
```

<div align="center">
    <img src="../images/settingsGUI.png" > 
</div>
<br>

In this window you can add/remove items which can be stored in the bank.

- **Left side**: A list of all items that can be stored inside a bank. Click on an item to see details.
- **Right side**: Overview of the selected item showing the total supply (sum of all players' balances) and the total locked amount across all players.
- **Manage button**: Opens the [player account management](#managing-player-bank-accounts) window for the selected player.

Items can also be added/removed via commands:
```
/banksystem allowItem <itemID>
/banksystem allowItemInHand
/banksystem disallowItem <itemID>
/banksystem disallowItemInHand
```

> [!WARNING]
> Disallowing an item removes the item bank from **all** player accounts. This action cannot be undone.

---
## Managing Player Bank Accounts

To manage a specific player's bank account, use one of the following methods:

- Command: `/bank <player_name> manage`
- Click the **Manage** button in the [settings GUI](#managing-banking-items).
- Right-click on a player with the **Banking Software** item.

<div align="center">
    <img src="../images/bankManagementGUI.png" > 
</div>
<br>

The window shows all items currently stored in the player's bank account.

<details>
<summary><b>Add new item</b></summary>
Click on the <b>Create new bank</b> button and select the item you want to add to the player's account.<br>
The item bank will be created instantly, no need to press the save button.
</details>

<details>
<summary><b>Remove an item</b></summary>
If you want to delete an item from the user's bank, click on <b>Close account</b>. A deleted account is marked red.<br>
Click the <b>Save changes</b> button to apply your changes.
</details>

<details>
<summary><b>Change balance</b></summary>
Change the balance of a specific item using the text field.<br>
Click the <b>Save changes</b> button to apply your changes.
</details>

<details>
<summary><b>Release locked amount</b></summary>
Check the checkbox if you want to release the locked amount for a specific item. This may affect other mods which have locked the money/item in the first place.<br>
Click the <b>Save changes</b> button to apply your changes.
</details>

---
## Locked Amounts

Other mods that access a player's bank account may reserve some amount for later use.

The [Stock Market Mod](https://github.com/KROIA/StockMarket) for example uses this feature to reserve the amount (money/items) for pending trades. If a trade is not executed immediately, the player must wait until the transaction is processed. To prevent double spending during this time, the amount gets locked.

> [!CAUTION]
> Releasing locked amounts without knowing which mod reserved them may cause problems with pending transactions in those mods.

---
## Volatile Item Components

Some mods attach **time-varying data components** to item stacks — for example TerraFirmaCraft's food-decay timestamp (`tfc:food`) or heat state (`tfc:heat`). Because BankSystem identifies items by their full component data, such components would make identical items look different over time (e.g. every freshly caught fish would count as a "new" item, and stored food templates could rot inside the bank's item registry).

BankSystem therefore strips a configurable set of **volatile component types** from all items before they take part in item identification. Items are stored and compared *without* these components; when an item is handed back to a player, the owning mod re-attaches a fresh component automatically (e.g. TFC food withdrawn from a bank starts with a fresh creation date).

The volatile set is the union of two sources:

1. **Datapack tag** `banksystem:volatile_item_components` over the data-component-type registry. BankSystem ships this tag with optional entries for `tfc:food` and `tfc:heat` (harmless when TFC is not installed). Modpack authors can extend it with their own datapack:
   ```json
   // data/<your_namespace>/tags/data_component_type/volatile_item_components.json
   {
     "values": [
       { "id": "somemod:some_component", "required": false }
     ]
   }
   ```
2. **Server config list** `ADDITIONAL_VOLATILE_COMPONENTS` in the `ServerBank` section of `<world>/data/BankSystem/settings.json`, for admins who do not want to ship a datapack:
   ```json
   "ADDITIONAL_VOLATILE_COMPONENTS": ["somemod:some_component"]
   ```

Both lists merge. The config list is synced to clients and forwarded to slave servers automatically, so all sides identify items identically.

When a world whose item registry already contains duplicates (created before this feature existed) is loaded, the duplicates are merged automatically: the lowest ID is kept and the others become aliases, so existing bank balances and market references stay valid. A log message reports how many IDs were merged.

> [!NOTE]
> Stripping a whole component also removes its non-time-varying parts (e.g. TFC food *traits* such as "salted"). Items differing only in such traits are treated as the same item by the bank.

### Deposit-Gated Components

Ignoring a state-carrying component for identification makes differently-aged items fungible inside the bank — which would allow *state laundering*: deposit a rotten TFC food, withdraw a fresh one. To prevent this, a second component set marks components as **deposit-gated**.

Gated components are ignored for item identification just like volatile ones, but at **deposit time** an extra rule applies: *an item may only be deposited if it is equivalent to what the bank would hand back for it.* BankSystem rebuilds the withdrawal-equivalent item (the owning mod stamps fresh state onto it, exactly like on a real withdrawal) and compares it with the incoming item using Minecraft's stack-equality check. If they are not equivalent, the deposit is rejected with a message ("The condition of ... prevents banking it") and the item stays where it is — in the terminal inventory or the Bank Upload block. Items that carry no gated component are entirely unaffected.

The gated set is configured exactly like the volatile set:

1. **Datapack tag** `banksystem:deposit_gated_components` over the data-component-type registry. BankSystem ships this tag with an optional entry for `tfc:food`:
   ```json
   // data/<your_namespace>/tags/data_component_type/deposit_gated_components.json
   {
     "values": [
       { "id": "somemod:some_component", "required": false }
     ]
   }
   ```
2. **Server config list** `ADDITIONAL_DEPOSIT_GATED_COMPONENTS` in the `ServerBank` section of `<world>/data/BankSystem/settings.json`:
   ```json
   "ADDITIONAL_DEPOSIT_GATED_COMPONENTS": ["somemod:some_component"]
   ```

Both lists merge and are synced to clients and slave servers automatically.

Because the source mod's own stack-equality semantics decide what counts as "equivalent", the check needs no mod-specific code. For TerraFirmaCraft this means: two foods compare equal only when their creation dates fall into the same decay-stacking window (6 in-game hours by default), and rotten food never equals fresh food.

> [!NOTE]
> With TFC defaults, food older than the 6-in-game-hour stacking window is rejected even if it is still perfectly edible. This is consistent with TFC's own stack-merging rules — such food would not stack with fresh food in a chest either. The window size is configurable in TFC's own config if you want to allow older food into the bank.

---
## BankSystem Admin Role

BankSystem has its own admin role separate from Minecraft's operator system. Server operators can promote/demote BankSystem admins:

```
/banksystem op <player>
/banksystem deop <player>
```

BankSystem admins can manage banking items and access other players' bank accounts without needing Minecraft operator status.
