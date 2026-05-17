# Bank System Mod

## About
BankSystem is a Minecraft mod that brings money in to the game. Players can have a bankacount that can not only hold money but items too.
Admins can define which items can be stored in the bank in order to prevent players from using the bank as infinite storage.


<tr>
<td>
<div align="center">
    <img src="documentation/images/overview.png" > 
</div>
</td>

You want to support me?<br>
[!["Buy Me A Coffee"](https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png)](https://buymeacoffee.com/alexkrieg)
---


## Chapters 
* [Features](#features)
* [Downloads](#downloads)
* [Blocks](#blocks)
* [Items](#items)
* [Crafting Recipes](#crafting-recipes)
* [Usage](#usage)
* [Commands](#commands)
* [Documentation](#documentation)
* [Changelog](#changelog)


---
## Features
- Adds a banking system to the game for money and items.
- Adds new [blocks](#blocks) to interact with the bank account.
- Money can be placed as a block for decorative and storage purposes.
- Connect multiple servers to share bank accounts across multiple servers.

## Dependencies
- [Architectury](https://www.curseforge.com/minecraft/mc-mods/architectury-api)
- [Quilted Fabric API](https://www.curseforge.com/minecraft/mc-mods/qsl) (Only needed for Quilt)
- [Mod Utilities](https://www.curseforge.com/minecraft/mc-mods/modutilities) (Only needed for Quilt)
- [Fabric API](https://www.curseforge.com/minecraft/mc-mods/fabric-api) (Only needed for Fabric)


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
| ![Minecraft Version](https://img.shields.io/badge/Minecraft-1.21.1-green)    | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-fabric-1.21.1] |                                                                            |                                                                            | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-neoforge-1.21.1] |
| ![Minecraft Version](https://img.shields.io/badge/Minecraft-1.21-green)      | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-fabric-1.21]   |                                                                            |                                                                            | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-neoforge-1.21]   |
| ![Minecraft Version](https://img.shields.io/badge/Minecraft-1.20.6-green)    | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-fabric-1.20.6] |                                                                            |                                                                            | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-neoforge-1.20.6] |
| ![Minecraft Version](https://img.shields.io/badge/Minecraft-1.20.4-green)    | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-fabric-1.20.4] | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-forge-1.20.4] | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-quilt-1.20.4] |                                                                               |
| ![Minecraft Version](https://img.shields.io/badge/Minecraft-1.20.2-green)    | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-fabric-1.20.2] | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-forge-1.20.2] | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-quilt-1.20.2] |                                                                               |
| ![Minecraft Version](https://img.shields.io/badge/Minecraft-1.20.1-green)    | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-fabric-1.20.1] | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-forge-1.20.1] | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-quilt-1.20.1] |                                                                               |
| ![Minecraft Version](https://img.shields.io/badge/Minecraft-1.19.4-green)    | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-fabric-1.19.4] | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-forge-1.19.4] |                                                                            |                                                                               |
| ![Minecraft Version](https://img.shields.io/badge/Minecraft-1.19.3-green)    | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-fabric-1.19.3] | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-forge-1.19.3] | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-quilt-1.19.3] |                                                                               |
| ![Minecraft Version](https://img.shields.io/badge/Minecraft-1.19.2-green)    | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-fabric-1.19.2] | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-forge-1.19.2] | [![Version](https://img.shields.io/badge/v1.4.1-green)][1.4.1-quilt-1.19.2] |                                                                               |





<!--	Links to Curse1.4.1-forge:	-->
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
## Blocks
<table>
<tr>
<td>
<b>Metal Case Block</b><br>
Casing for the Terminal block.<br>
8 Iron ingots
</td>
<td>
<div align="center">
    <img src="documentation/images/metalCaseBlock.png" width="300"> 
</div>
</td>
</tr>


<tr>
<td>
<b>Terminal Block</b><br>
Unprogrammed terminal.<br>
Can be programmed using a software item.<br>
4 Iron nuggets<br>
1 Metal Case Block<br>
1 Display<br>
1 Circuit Board<br>
2 Redstone
</td>
<td>
<div align="center">
    <img src="documentation/images/terminalBlock.png" width="300"> 
</div>
</td>
</tr>


<tr>
<td>
<b>Bank Terminal Block</b><br>
Used to get access to the bank account.<br>
Interaction using right click.<br>
Right click on a <b>Terminal Block</b> using a <b>Banking Software</b> to create this block.
</td>
<td>
<div align="center">
    <img src="documentation/images/bankingTerminalBlock.png" width="100"> 
</div>
</td>
</tr>



<tr>
<td>
<b>ATM Block</b><br>
Used to withdraw money.<br>
Interaction using right click.<br>
Right click on a <b>Terminal Block</b> using a <b>ATM Software</b> to create this block.
</td>
<td>
<div align="center">
    <img src="documentation/images/atmBlock.png" width="100"> 
</div>
</td>
</tr>


<tr>
<td>
<b>Bank Upload Block</b><br>
Used to send items to a specified bank account.<br>
Change its settings by right clicking on it<br>
Once the block is connected to a bank account, other players can't access it any more.<br>
Can be connected to Pipes and hoppers.<br>
Needs to be powered by redstone to send items to the bank.<br>
6 Iron nuggets<br>
1 Metal Case Block<br>
1 Comparator<br>
1 Bank Transmitter Module<br>
</td>
<td>
<div align="center">
    <img src="documentation/images/bank_upload_block.png" width="300"> 
</div>
</td>
</tr>

<tr>
<td>
<b>Bank Download Block</b><br>
Used to receive items from a specified bank account.<br>
Change its settings by right clicking on it<br>
Once the block is connected to a bank account, other players can't access it any more.<br>
Can be connected to Pipes and hoppers.<br>
Needs to be powered by redstone to receive items to the bank.<br>
6 Iron nuggets<br>
1 Metal Case Block<br>
1 Comparator<br>
1 Bank Receiver Module<br>
</td>
<td>
<div align="center">
    <img src="documentation/images/bank_download_block.png" width="300"> 
</div>
</td>
</tr>
</table>

---
## Items
<table>
<tr>
<td>
<b>Circuit Board</b><br>
Electronics for other Items.<br>
1 Nether Quartz<br>
3 Copper Ingots<br>
3 Paper<br>
</td>
<td>
<div align="center">
    <img src="documentation/images/circuitBoard.png" width="300"> 
</div>
</td>
</tr>


<tr>
<td>
<b>Display</b><br>
Display for the <b>Terminal Block</b><br>
6 Glass Planes<br>
2 Iron Ingots<br>
1 Ciruit Board
</td>
<td>
<div align="center">
    <img src="documentation/images/display.png" width="300"> 
</div>
</td>
</tr>


<tr>
<td>
<b>Empty Software</b><br>
Used to create a specific software<br>
4 Iron nuggets<br>
2 Ink Sacs<br>
3 Paper
</td>
<td>
<div align="center">
    <img src="documentation/images/emptySoftware.png" width="300"> 
</div>
</td>
</tr>


<tr>
<td>
<b>Banking Software</b><br>
Used to programm the <b>Terminal Block</b> to be a <b>Bank Terminal Block</b><br>
1 Empty Software<br>
1 Gold Ingot<br>
</td>
<td>
<div align="center">
    <img src="documentation/images/bankingSoftware.png" width="300"> 
</div>
</td>
</tr>



<tr>
<td>
<b>ATM Software</b><br>
Used to programm the <b>Terminal Block</b> to be a <b>ATM Block</b><br>
1 Empty Software<br>
1 Dispenser<br>
</td>
<td>
<div align="center">
    <img src="documentation/images/atmSoftware.png" width="300"> 
</div>
</td>
</tr>


<tr>
<td>
<b>Bank Transmitter Module</b><br>
Used to craft the <b>Bank Upload Block</b>.<br>
1 Iron ingot<br>
1 circuit Board<br>
</td>
<td>
<div align="center">
    <img src="documentation/images/bank_transmitter_module.png" width="300"> 
</div>
</td>
</tr>

<tr>
<td>
<b>Bank Receiver Module</b><br>
Used to craft the <b>Bank Download Block</b>.<br>
1 Iron ingot<br>
1 circuit Board<br>
</td>
<td>
<div align="center">
    <img src="documentation/images/bank_receiver_module.png" width="300"> 
</div>
</td>
</tr>


<tr>
<td>
<b>1 Dollar</b><br>
A 1 Dollar coin can only be crafted, using other type of money items such as the 5 Dollar bank note.<br>
This puts control over inflation in the hands of the server administrator.<br>
The admin is responsible to bring money in to circulation.<br>
</td>
<td>
<div align="center">
    <img src="documentation/images/1Dollar.png" width="300"> 
</div>
</td>
</tr>


<tr>

<td>
<div align="center">
    <img src="documentation/images/Money.png" width="500"> 
</div>
</td>
</tr>

</table>

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

---

## Usage

### Bank Terminal Block
<table>
<tr>
<td width = 500 valign="top">
The Bank Terminal Block is used to deposit/withdraw items to/from the bank account.

> [!NOTE]  
> The block contains an inventory which is unique for every player. 
> Like an ender chest, but when the block gets destroyed, 
> items not stored in the bank account will be dropped.

</td>
<td width = 600>
<div align="center">
    <img src="documentation/images/BankTerminalBlock.gif" width=600> 
</div>
</td>
</tr>
</table>

### ATM Block
<table>
<tr>
<td width = 500 valign="top">
The ATM Block lets you withdraw money as specific bank notes.
</td>
<td width = 600>
<div align="center">
    <img src="documentation/images/ATMBlock.gif" width=600> 
</div>
</td>
</tr>
</table>

### Automation Blocks

The **Bank Upload Block** and **Bank Download Block** connect to your bank account and allow automated item transfers via redstone signals. They can be connected to pipes and hoppers.

<div align="center">
    <img src="documentation/images/bank_upDownload_block.png" > 
</div>

For detailed usage of all blocks, bank accounts, and administration, see the [Documentation](#documentation) section.

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
- [Block Usage](documentation/user-guide/Usage.md) — How to use the Bank Terminal, ATM, and automation blocks
- [Bank Accounts](documentation/user-guide/BankAccounts.md) — Shared accounts, creating accounts, permissions
- [Administration](documentation/user-guide/Administration.md) — Managing banking items, player accounts, locked amounts
- [Multi-Server Setup](documentation/user-guide/MultiserverSetup.md) — Master-slave architecture for cross-server banking
- [Commands](documentation/user-guide/Commands.md) — Full command reference

**For Mod Developers:**
- [API Reference](documentation/developer-guide/API.md) — Public API overview and usage examples
- [Async Forwarding Architecture](documentation/developer-guide/AsyncForwardingArchitecture.md) — Internal RPC system documentation

---

## Changelog

All notable changes are documented in version-specific files under `changelog/`.

### Current

- [2.0.1](changelog/v2.0.1.md) — In Development

### Previous

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
