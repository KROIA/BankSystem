# Bank System Mod

## About
BankSystem is a Minecraft mod that adds a full banking system to the game. Players get a bank account that can hold money and items. Create shared accounts with permissions, automate item transfers with redstone, and monitor balances on in-world displays. Connect multiple servers to share bank accounts across your network.

<div align="center">
    <img src="documentation/images/overview.png"> 
</div>

You want to support me?<br>
[!["Buy Me A Coffee"](https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png)](https://buymeacoffee.com/alexkrieg)

---

## Chapters 
* [Features](#features)
* [Installation](#installation)
* [Downloads](#downloads)
* [Usage](#usage)
* [Blocks](#blocks)
* [Items](#items)
* [Commands](#commands)
* [Crafting Recipes](#crafting-recipes)
* [Documentation](#documentation)
* [Changelog](#changelog)


---
## Features
- Bank accounts for money and items with deposit, withdrawal, and transfer support
- Built-in crafting grid in the Bank Terminal that can pull missing ingredients from the bank and deposit crafted items back automatically
- Shared bank accounts with per-player permissions (deposit, withdraw, manage)
- ATM block for withdrawing specific bank note denominations
- Bank Upload / Download blocks for redstone-powered automation with hoppers and pipes
- Bank Display blocks that show live balance overviews or history charts
- Money system with coins and bills that can be placed as decorative blocks
- Multi-server support to share bank accounts across connected servers
- Mod API for developers to integrate with the banking system

---
## Installation

1. Install [Architectury API](https://www.curseforge.com/minecraft/mc-mods/architectury-api) and the mod loader API for your platform:
   - **Fabric**: [Fabric API](https://www.curseforge.com/minecraft/mc-mods/fabric-api)
   - **Quilt**: [Quilted Fabric API](https://www.curseforge.com/minecraft/mc-mods/qsl) + [Mod Utilities](https://www.curseforge.com/minecraft/mc-mods/modutilities)
   - **NeoForge / Forge**: No additional API needed
2. Download the BankSystem jar for your Minecraft version and mod loader from [CurseForge](https://www.curseforge.com/minecraft/mc-mods/banksystem) or the [Downloads](#downloads) table below.
3. Place the jar in your `mods/` folder and launch the game.

---
## Downloads
<!--
[CurseForge](https://www.curseforge.com/minecraft/mc-mods/stockmarket)


| Version | Download |
|---------|----------|
|1.3.0    | [![Minecraft Version](https://img.shields.io/badge/Minecraft-1.21.1-green)](https://www.curseforge.com/minecraft/mc-mods/stockmarket/download/6002691)<br>[![Minecraft Version](https://img.shields.io/badge/Minecraft-1.20.6-blue)](https://www.curseforge.com/minecraft/mc-mods/stockmarket/download/6002684)<br>[![Minecraft Version](https://img.shields.io/badge/Minecraft-1.20.4-blue)](https://www.curseforge.com/minecraft/mc-mods/stockmarket/download/6002682)<br>[![Minecraft Version](https://img.shields.io/badge/Minecraft-1.20.3-blue)](https://www.curseforge.com/minecraft/mc-mods/stockmarket/download/6002681)<br>[![Minecraft Version](https://img.shields.io/badge/Minecraft-1.20.2-blue)](https://www.curseforge.com/minecraft/mc-mods/stockmarket/download/6002679)<br>[![Minecraft Version](https://img.shields.io/badge/Minecraft-1.20.1-blue)](https://www.curseforge.com/minecraft/mc-mods/stockmarket/download/6002676)<br>[![Minecraft Version](https://img.shields.io/badge/Minecraft-1.19.4-blue)](https://www.curseforge.com/minecraft/mc-mods/stockmarket/download/6004639)<br>[![Minecraft Version](https://img.shields.io/badge/Minecraft-1.19.3-blue)](https://www.curseforge.com/minecraft/mc-mods/stockmarket/download/6004641)<br>[![Minecraft Version](https://img.shields.io/badge/Minecraft-1.19.2-blue)](https://www.curseforge.com/minecraft/mc-mods/stockmarket/download/6004643) |

-->
[CurseForge](https://www.curseforge.com/minecraft/mc-mods/banksystem)
| Minecraft | Fabric | Forge | Quilt | Neoforge |
|-----------|--------|-------|-------|----------|
| ![Minecraft Version](https://img.shields.io/badge/Minecraft-1.21.1-green)    |  |                                                                            |                                                                            | [![Version](https://img.shields.io/badge/v2.0.1--alpha-orange)][2.0.1-neoforge-1.21.1] |
| ![Minecraft Version](https://img.shields.io/badge/Minecraft-1.21.1-green)    | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-fabric-1.21.1] |                                                                            |                                                                            | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-neoforge-1.21.1] |
| ![Minecraft Version](https://img.shields.io/badge/Minecraft-1.21-green)      | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-fabric-1.21]   |                                                                            |                                                                            | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-neoforge-1.21]   |
| ![Minecraft Version](https://img.shields.io/badge/Minecraft-1.20.6-green)    | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-fabric-1.20.6] |                                                                            |                                                                            | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-neoforge-1.20.6] |
| ![Minecraft Version](https://img.shields.io/badge/Minecraft-1.20.4-green)    | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-fabric-1.20.4] | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-forge-1.20.4] | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-quilt-1.20.4] |                                                                               |
| ![Minecraft Version](https://img.shields.io/badge/Minecraft-1.20.2-green)    | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-fabric-1.20.2] | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-forge-1.20.2] | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-quilt-1.20.2] |                                                                               |
| ![Minecraft Version](https://img.shields.io/badge/Minecraft-1.20.1-green)    | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-fabric-1.20.1] | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-forge-1.20.1] | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-quilt-1.20.1] |                                                                               |
| ![Minecraft Version](https://img.shields.io/badge/Minecraft-1.19.4-green)    | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-fabric-1.19.4] | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-forge-1.19.4] |                                                                            |                                                                               |
| ![Minecraft Version](https://img.shields.io/badge/Minecraft-1.19.3-green)    | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-fabric-1.19.3] | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-forge-1.19.3] | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-quilt-1.19.3] |                                                                               |
| ![Minecraft Version](https://img.shields.io/badge/Minecraft-1.19.2-green)    | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-fabric-1.19.2] | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-forge-1.19.2] | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-quilt-1.19.2] |                                                                               |


<!--	Links to curseforge:	-->
[2.0.1-neoforge-1.21.1]:https://www.curseforge.com/minecraft/mc-mods/banksystem/files/8122940

[1.4.1-fabric-1.21.1]:https://www.curseforge.com/minecraft/mc-mods/banksystem/download/6200604
[1.4.1-fabric-1.21]:https://www.curseforge.com/minecraft/mc-mods/banksystem/download/6200596
[1.4.1-fabric-1.20.6]:https://www.curseforge.com/minecraft/mc-mods/banksystem/download/6200590
[1.4.1-fabric-1.20.4]:https://www.curseforge.com/minecraft/mc-mods/banksystem/download/6200584
[1.4.1-fabric-1.20.2]:https://www.curseforge.com/minecraft/mc-mods/banksystem/download/6200573
[1.4.1-fabric-1.20.1]:https://www.curseforge.com/minecraft/mc-mods/banksystem/download/6200133
[1.4.1-fabric-1.19.4]:https://www.curseforge.com/minecraft/mc-mods/banksystem/download/6200127
[1.4.1-fabric-1.19.3]:https://www.curseforge.com/minecraft/mc-mods/banksystem/download/6200121
[1.4.1-fabric-1.19.2]:https://www.curseforge.com/minecraft/mc-mods/banksystem/download/6200108

[1.4.1-forge-1.20.4]:https://www.curseforge.com/minecraft/mc-mods/banksystem/download/6200586
[1.4.1-forge-1.20.2]:https://www.curseforge.com/minecraft/mc-mods/banksystem/download/6200578
[1.4.1-forge-1.20.1]:https://www.curseforge.com/minecraft/mc-mods/banksystem/download/6200135
[1.4.1-forge-1.19.4]:https://www.curseforge.com/minecraft/mc-mods/banksystem/download/6200130
[1.4.1-forge-1.19.3]:https://www.curseforge.com/minecraft/mc-mods/banksystem/download/6200124
[1.4.1-forge-1.19.2]:https://www.curseforge.com/minecraft/mc-mods/banksystem/download/6200111

[1.4.1-quilt-1.20.4]:https://www.curseforge.com/minecraft/mc-mods/banksystem/download/6200588
[1.4.1-quilt-1.20.2]:https://www.curseforge.com/minecraft/mc-mods/banksystem/download/6200581
[1.4.1-quilt-1.20.1]:https://www.curseforge.com/minecraft/mc-mods/banksystem/download/6200137
[1.4.1-quilt-1.19.3]:https://www.curseforge.com/minecraft/mc-mods/banksystem/download/6200126
[1.4.1-quilt-1.19.2]:https://www.curseforge.com/minecraft/mc-mods/banksystem/download/6200112

[1.4.1-neoforge-1.21.1]:https://www.curseforge.com/minecraft/mc-mods/banksystem/download/6200607
[1.4.1-neoforge-1.21]:https://www.curseforge.com/minecraft/mc-mods/banksystem/download/6200602
[1.4.1-neoforge-1.20.6]:https://www.curseforge.com/minecraft/mc-mods/banksystem/download/6200595


---
## Usage

### Bank Terminal Block

<div align="center">
    <img src="documentation/images/BankTerminalBlock.gif"> 
</div>

The Bank Terminal Block is used to deposit/withdraw items to/from the bank account.
A **Filter** box above the bank item list helps you find items quickly.

The terminal also features a built-in 3×3 crafting grid that supports all standard crafting recipes.
Enable **Use Bank Items** to pull missing ingredients straight from the selected bank account,
and **Auto-deposit output** to send crafted items directly back into the bank.
With JEI installed, recipes can be filled into the grid with one click.
See [Block Usage](documentation/user-guide/Usage.md#bank-terminal-block) for details.

> [!NOTE]  
> The block contains an inventory which is unique for every player. 
> Like an ender chest, but when the block gets destroyed, 
> items not stored in the bank account will be dropped.

---
### ATM Block

<div align="center">
    <img src="documentation/images/ATMBlock.gif"> 
</div>

The ATM Block lets you withdraw money as specific bank notes.

---
### Automation Blocks

The **Bank Upload Block** and **Bank Download Block** connect to your bank account and allow automated item transfers via redstone signals. They can be connected to pipes and hoppers.

<div align="center">
    <img src="documentation/images/bank_upDownload_block.png"> 
</div>

For detailed usage of all blocks, bank accounts, and administration, see the [Documentation](#documentation) section.

---
### Money Stockpile

<div align="center">
    <img src="documentation/images/money_block.png"> 
</div>

Money items can be placed in the world as decorative blocks. Each denomination has its own block model. The blocks can also be used for physical storage of money outside the banking system.

---
### Bank Displays

The **Bank Display** block shows live bank account data on its screen. Right-click to open the configuration screen, where you select a bank account and one of two display modes:

<div align="center">
    <img src="documentation/images/DisplayConfigScreen.png"> 
</div>

<div align="center">
    <img src="documentation/images/BankDisplays.png"> 
</div>

| Display Mode | Description |
|--------------|-------------|
| **Balance History** | A line chart tracking balance changes over time for all items in the account. Each item is color-coded with a legend on the right. Updates every 60 seconds. |
| **Balance Overview** | A compact grid showing the current balances of the highest-value items in the account. Displays item icons with their amounts. Updates every second. |

### Mod Settings Screen

Server admins can edit the mod's `settings.json` in-game, without touching the file:

1. Run `/banksystem manage` (admin only) to open the banking settings window.
2. Click **Mod Settings**.

The screen lists every setting of the mod, grouped by category (Utilities, Player, Bank, TAB Placeholders), with a matching editor per setting type (checkboxes, number fields, text fields). **Apply** validates and saves the values on the server (out-of-range values are clamped and the corrected values are shown), **Reload** re-fetches the current server state and **Defaults** resets the fields to the built-in defaults (nothing is saved until Apply is pressed).

Notes:

- **Master-only:** the button is only shown when you are connected to the master server (or a regular single server). Only the master's `settings.json` can be edited; the server additionally rejects requests from non-admins regardless of the UI.
- **Permission:** requires BankSystem admin status (the same permission as the other `/banksystem` admin commands).
- **Restart markers:** settings that are only read once at server startup show an orange "⟳ Restart required" marker — the change is saved immediately but only takes effect after a server restart. This includes the volatile/deposit-gated component lists and the one-shot `CONFIRM_ITEMID_MERGE` / `CONFIRM_ITEMID_REPAIR` flags, which can be armed from this screen to approve a pending ItemID merge or world repair on the next restart.

---
## Blocks

| Block | Description |
|-------|-------------|
| **Metal Case Block** | Casing for the Terminal block. |
| **Terminal Block** | Unprogrammed terminal. Can be programmed using a software item. |
| **Bank Terminal Block** | Used to access the bank account. Right click on a **Terminal Block** using a **Banking Software** to create this block. |
| **ATM Block** | Used to withdraw money. Right click on a **Terminal Block** using an **ATM Software** to create this block. |
| **Bank Upload Block** | Sends items to a bank account. Can be connected to pipes and hoppers. Needs redstone power. |
| **Bank Download Block** | Receives items from a bank account. Can be connected to pipes and hoppers. Needs redstone power. |
| **Bank Display** | Displays bank account information on its screen. Can show a balance overview or balance history chart. Right-click to configure. |

---
## Items

| Item | Description |
|------|-------------|
| **Circuit Board** | Electronics component for other items. |
| **Display** | Display for the Terminal Block. |
| **Empty Software** | Base software, used to create specific software variants. |
| **Banking Software** | Programs a Terminal Block into a Bank Terminal Block. |
| **ATM Software** | Programs a Terminal Block into an ATM Block. |
| **Bank Transmitter Module** | Component for the Bank Upload Block. |
| **Bank Receiver Module** | Component for the Bank Download Block. |

### Money

<div align="center">
    <img src="documentation/images/moneyCollection.png"> 
</div>

Money comes in coins ($1-$50) and bills ($5-$1000). All denominations can be converted between each other using the crafting table. Money can also be placed in the world as decorative blocks.
The server administrator controls how money enters circulation.
See [Money Conversions](#money-conversions) for all crafting recipes.

---

## Commands

Key commands for players and admins:

| Command | Description |
|---------|-------------|
| `/money` | Show your money balance |
| `/money send <user> <amount>` | Send money to another player |
| `/bank` | Show your full bank balance (money and items) |
| `/bank manage` | Open the bank account management GUI |
| `/bank create <accountname>` | Create a new bank account |
| `/banksystem manage` | Open the banking settings (admin only) |

For the full command reference, see [Commands](documentation/user-guide/Commands.md).

---

## Documentation

Detailed guides are available in the [documentation](documentation/README.md) folder:

**For Mod Users:**
- [Block Usage](documentation/user-guide/Usage.md) — How to use the Bank Terminal (including its crafting grid), ATM, automation blocks, bank displays, and money stockpiles
- [Bank Accounts](documentation/user-guide/BankAccounts.md) — Shared accounts, creating accounts, permissions
- [Administration](documentation/user-guide/Administration.md) — Managing banking items, player accounts, locked amounts
- [Multi-Server Setup](documentation/user-guide/MultiserverSetup.md) — Master-slave architecture for cross-server banking
- [Commands](documentation/user-guide/Commands.md) — Full command reference

**For Mod Developers:**
- [API Reference](documentation/developer-guide/API.md) — Public API overview and usage examples
- [Async Forwarding Architecture](documentation/developer-guide/AsyncForwardingArchitecture.md) — Internal RPC system documentation

---

## Crafting Recipes

### Components
<table>
<tr>
<td><b>Empty Software</b></td>
<td><b>Circuit Board</b></td>
<td><b>Display</b></td>
</tr>
<tr>
<td><img src="documentation/images/recipes/recipe_software.png" width="350"></td>
<td><img src="documentation/images/recipes/recipe_circuit_board.png" width="350"></td>
<td><img src="documentation/images/recipes/recipe_display.png" width="350"></td>
</tr>
</table>

### Software
<table>
<tr>
<td><b>Banking Software</b></td>
<td><b>ATM Software</b></td>
</tr>
<tr>
<td><img src="documentation/images/recipes/recipe_banking_software.png" width="350"></td>
<td><img src="documentation/images/recipes/recipe_atm_software.png" width="350"></td>
</tr>
</table>

### Modules
<table>
<tr>
<td><b>Bank Transmitter Module</b></td>
<td><b>Bank Receiver Module</b></td>
</tr>
<tr>
<td><img src="documentation/images/recipes/recipe_bank_transmitter_module.png" width="350"></td>
<td><img src="documentation/images/recipes/recipe_bank_receiver_module.png" width="350"></td>
</tr>
</table>

### Blocks
<table>
<tr>
<td><b>Metal Case Block</b></td>
<td><b>Terminal Block</b></td>
</tr>
<tr>
<td><img src="documentation/images/recipes/recipe_metal_case_block.png" width="350"></td>
<td><img src="documentation/images/recipes/recipe_terminal_block.png" width="350"></td>
</tr>
<tr>
<td><b>BankSystem Display Block</b></td>
<td><b>Bank Upload Block</b></td>
</tr>
<tr>
<td><img src="documentation/images/recipes/recipe_banksystem_display_block.png" width="350"></td>
<td><img src="documentation/images/recipes/recipe_bank_upload_block.png" width="350"></td>
</tr>
<tr>
<td><b>Bank Download Block</b></td>
<td></td>
</tr>
<tr>
<td><img src="documentation/images/recipes/recipe_bank_download_block.png" width="350"></td>
<td></td>
</tr>
</table>

### Money Conversions

#### $1
<table>
<tr>
<td><img src="documentation/images/recipes/recipe_money_1.png" width="350"></td>
<td><img src="documentation/images/recipes/recipe_money_2.png" width="350"></td>
</tr>
<tr>
<td><img src="documentation/images/recipes/recipe_money_3.png" width="350"></td>
<td><img src="documentation/images/recipes/recipe_money_4.png" width="350"></td>
</tr>
</table>

#### $5
<table>
<tr>
<td><img src="documentation/images/recipes/recipe_money5_1.png" width="350"></td>
<td><img src="documentation/images/recipes/recipe_money5_2.png" width="350"></td>
</tr>
<tr>
<td><img src="documentation/images/recipes/recipe_money5_3.png" width="350"></td>
<td><img src="documentation/images/recipes/recipe_money5_4.png" width="350"></td>
</tr>
<tr>
<td><img src="documentation/images/recipes/recipe_money5_5.png" width="350"></td>
<td><img src="documentation/images/recipes/recipe_money5_6.png" width="350"></td>
</tr>
</table>

#### $10
<table>
<tr>
<td><img src="documentation/images/recipes/recipe_money10_1.png" width="350"></td>
<td><img src="documentation/images/recipes/recipe_money10_2.png" width="350"></td>
</tr>
<tr>
<td><img src="documentation/images/recipes/recipe_money10_3.png" width="350"></td>
<td><img src="documentation/images/recipes/recipe_money10_4.png" width="350"></td>
</tr>
<tr>
<td><img src="documentation/images/recipes/recipe_money10_5.png" width="350"></td>
<td><img src="documentation/images/recipes/recipe_money10_6.png" width="350"></td>
</tr>
</table>

#### $20
<table>
<tr>
<td><img src="documentation/images/recipes/recipe_money20_1.png" width="350"></td>
<td><img src="documentation/images/recipes/recipe_money20_2.png" width="350"></td>
</tr>
<tr>
<td><img src="documentation/images/recipes/recipe_money20_3.png" width="350"></td>
<td><img src="documentation/images/recipes/recipe_money20_4.png" width="350"></td>
</tr>
<tr>
<td><img src="documentation/images/recipes/recipe_money20_5.png" width="350"></td>
<td></td>
</tr>
</table>

#### $50
<table>
<tr>
<td><img src="documentation/images/recipes/recipe_money50_1.png" width="350"></td>
<td><img src="documentation/images/recipes/recipe_money50_2.png" width="350"></td>
</tr>
<tr>
<td><img src="documentation/images/recipes/recipe_money50_3.png" width="350"></td>
<td><img src="documentation/images/recipes/recipe_money50_4.png" width="350"></td>
</tr>
<tr>
<td><img src="documentation/images/recipes/recipe_money50_5.png" width="350"></td>
<td></td>
</tr>
</table>

#### $100
<table>
<tr>
<td><img src="documentation/images/recipes/recipe_money100_1.png" width="350"></td>
<td><img src="documentation/images/recipes/recipe_money100_2.png" width="350"></td>
</tr>
<tr>
<td><img src="documentation/images/recipes/recipe_money100_3.png" width="350"></td>
<td><img src="documentation/images/recipes/recipe_money100_4.png" width="350"></td>
</tr>
</table>

#### $200
<table>
<tr>
<td><img src="documentation/images/recipes/recipe_money200_1.png" width="350"></td>
<td><img src="documentation/images/recipes/recipe_money200_2.png" width="350"></td>
</tr>
<tr>
<td><img src="documentation/images/recipes/recipe_money200_3.png" width="350"></td>
<td></td>
</tr>
</table>

#### $500
<table>
<tr>
<td><img src="documentation/images/recipes/recipe_money500_1.png" width="350"></td>
<td><img src="documentation/images/recipes/recipe_money500_2.png" width="350"></td>
</tr>
<tr>
<td><img src="documentation/images/recipes/recipe_money500_3.png" width="350"></td>
<td></td>
</tr>
</table>

#### $1000
<table>
<tr>
<td><img src="documentation/images/recipes/recipe_money1000_1.png" width="350"></td>
<td><img src="documentation/images/recipes/recipe_money1000_2.png" width="350"></td>
</tr>
</table>

---

## Changelog

All notable changes are documented in version-specific files under `changelog/`.

### Current

- [2.0.5](changelog/v2.0.5.md) — In Development

### Previous

- [2.0.4](changelog/v2.0.4.md) — Released 2026-07-21
- [2.0.3](changelog/v2.0.3.md) — Released 2026-07-14
- [2.0.2](changelog/v2.0.2.md) — Released 2026-06-07
- [2.0.1](changelog/v2.0.1.md) — Released 2026-05-21
- [1.5.0_ALPHA_3](changelog/v1.5.0_ALPHA_3.md) — Security & stability: 50 fixes across permissions, async, concurrency, block entities
- [1.5.0_ALPHA_2](changelog/v1.5.0_ALPHA_2.md) — Trusted slave servers, package reorganization, UI improvements
- 1.5.0_ALPHA_1 — Initial alpha

### Stable

- [1.4.1](changelog/v1.4.1.md) — Stable (CurseForge)
- 1.4.0
- 1.3.0
- 1.2.0
- 1.1.0
- 1.0.0 — Initial release
