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
## BankSystem Admin Role

BankSystem has its own admin role separate from Minecraft's operator system. Server operators can promote/demote BankSystem admins:

```
/banksystem op <player>
/banksystem deop <player>
```

BankSystem admins can manage banking items and access other players' bank accounts without needing Minecraft operator status.
