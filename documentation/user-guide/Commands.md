# Command Reference

## BankSystem Commands

| Command | Description | Admin only | BS Admin only |
|---------|-------------|:----------:|:-------------:|
| `/banksystem manage` | Opens the settings window for the mod | | X |
| `/banksystem op <user>` | Makes the specified player a BankSystem Admin | X | |
| `/banksystem deop <user>` | Removes the BankSystem Admin status from a player | X | |
| `/banksystem allowItem <itemID>` | Adds the item to the list of bankable items | | X |
| `/banksystem allowItemInHand` | Adds the item currently in your main hand to the bankable items list | | X |
| `/banksystem disallowItem <itemID>` | Removes the item from the bankable items list (removes from all players) | | X |
| `/banksystem disallowItemInHand` | Removes the item in your main hand from the bankable items list | | X |
| `/banksystem trust <slaveID>` | Adds a slave server to the trusted list (master only) | X | |
| `/banksystem untrust <slaveID>` | Removes a slave server from the trusted list (master only) | X | |
| `/banksystem serverInfo` | Shows information about this server | | |
| `/banksystem serverNetworkInfo` | Shows information about the server network and trust status | | |

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
| `/bank create <accountname>` | Create a new bank account with the given name | | |
| `/bank <username> manage` | Open the management GUI for a specific player's account | | X |
| `/bank <username> show` | Show another player's bank balance | | X |
