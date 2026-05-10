# Block Usage

## Bank Terminal Block

<table>
<tr>
<td width = 500 valign="top">
The Bank Terminal Block is used to deposit/withdraw items to/from the bank account.

> [!NOTE]  
> The block contains an inventory which is unique for every player. 
> Like a ender chest but when the block gets destroyed, 
> the items which are not stored in the bank account will be dropped.
> 
<br>
</td>
<td width = 600>
<div align="center">
    <img src="../images/BankTerminalBlock.gif" width=600> 
</div>
</td>
</tr>
</table>


---
## ATM Block

<table>
<tr>
<td width = 500 valign="top">
The ATM Block lets you withdraw money as specific bank notes.<br>
</td>
<td width = 600>
<div align="center">
    <img src="../images/ATMBlock.gif" width=600> 
</div>
</td>
</tr>
</table>

---
## Automation Blocks

<div align="center">
    <img src="../images/bank_upDownload_block.png" > 
</div>

### Bank Upload Block

<table>
<tr>
<td width = 500 valign="top">
To use the Bank Upload Block, it has to be connected to your bank account.<br>
Open the block and press on the <b>Connect to Bank</b> button.<br>
- <b>Drop items if not bankable:</b><br>
   This setting specifies if the block drops items that can not be stored in the bank or not.<br>
<br>
Once the block is connected to your bank account, items can be placed in it.<br>
To send the items to the bank account, the block must be powered by a redstone signal.<br>
</td>
<td width = 600>
<div align="center">
    <img src="../images/BankUploadBlock.gif" width=600> 
</div>
</td>
</tr>
</table>

### Bank Download Block

<table>
<tr>
<td width = 500 valign="top">
To use the Bank Download Block, it has to be connected to your bank account.<br>
Open the block and press on the <b>Connect to Bank</b> button.<br>

- <b>Balance:</b><br>
   Shows the current balance in the connected bank account.<br>

- <b>Amount:</b><br>
   Define how many items the block should try to hold in its inventory.
   If items get removed from the inventory, the block tries to download new items until the specified amount is reached.<br>

- <b>Condition:</b><br>
   Set a condition for when items should be downloaded from the bank:
   - **No condition** - Keep the target amount in the inventory at all times.
   - **More than** - Only download items if the bank balance exceeds the specified value.
   - **Less than** - Only download items if the bank balance is below the specified value.

Press the <b>Save</b> button to apply the changes.<br>
Once the block is configured, a redstone signal triggers the block to work.<br>
</td>
<td width = 600>
<div align="center">
    <img src="../images/BankDownloadBlock.gif" width=600> 
</div>
</td>
</tr>
</table>
