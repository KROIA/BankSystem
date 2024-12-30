package net.kroia.banksystem.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.BankUser;
import net.kroia.banksystem.banking.ServerBankManager;
import net.kroia.banksystem.banking.bank.Bank;
import net.kroia.banksystem.banking.bank.MoneyBank;
import net.kroia.banksystem.item.custom.money.MoneyItem;
import net.kroia.banksystem.networking.packet.server_sender.SyncOpenBankSystemSettingsGUIPacket;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.PlayerUtilities;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;

public class BankSystemCommands {
    // Method to register commands
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        //BankSystemMod.LOGGER.info("Registering commands");

        // /money                               - Show balance
        // /money add <amount>                  - Add money to self
        // /money add <user> <amount>           - Add money to another player
        // /money set <amount>                  - Set money to self
        // /money set <user> <amount>           - Set money to another player
        // /money remove <amount>               - Remove money from self
        // /money remove <user> <amount>        - Remove money from another player
        // /money send <user> <amount>          - Send money to another player
        // /money circulation                   - Show money circulation of all players + bots
        dispatcher.register(
                Commands.literal("money")
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            ServerPlayer player = source.getPlayerOrException();

                            // Execute the balance command on the server_sender
                            return showBalance(player);
                        })
                        .then(Commands.literal("add")
                                .requires(source -> source.hasPermission(2)) // Admin-only for adding money
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(context -> {
                                            CommandSourceStack source = context.getSource();
                                            ServerPlayer player = source.getPlayerOrException();

                                            // Get arguments
                                            String username = player.getName().getString();
                                            int amount = IntegerArgumentType.getInteger(context, "amount");

                                            // Execute the command on the server_sender
                                            return executeAddMoney(player, username, amount);
                                        })) // Add to self
                                .then(Commands.argument("username", StringArgumentType.string()).suggests((context, builder) -> {
                                                    //builder.suggest("\""+ BankSystemSettings.MarketBot.USER_NAME +"\"");
                                                    Map<UUID, String> uuidToNameMap = PlayerUtilities.getUUIDToNameMap();
                                                    for(String name : uuidToNameMap.values()) {

                                                        builder.suggest("\""+name+"\"");
                                                    }
                                                    return builder.buildFuture();
                                                })
                                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                                        .executes(context -> {
                                                            CommandSourceStack source = context.getSource();
                                                            ServerPlayer player = source.getPlayerOrException();

                                                            // Get arguments
                                                            String username = StringArgumentType.getString(context, "username");
                                                            int amount = IntegerArgumentType.getInteger(context, "amount");

                                                            // Execute the command on the server_sender
                                                            return executeAddMoney(player, username, amount);
                                                        })
                                                )
                                )
                        )
                        .then(Commands.literal("set")
                                .requires(source -> source.hasPermission(2)) // Admin-only for adding money
                                .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                        .executes(context -> {
                                            CommandSourceStack source = context.getSource();
                                            ServerPlayer player = source.getPlayerOrException();

                                            // Get arguments
                                            String username = player.getName().getString();
                                            int amount = IntegerArgumentType.getInteger(context, "amount");

                                            // Execute the command on the server_sender
                                            return executeSetMoney(player, username, amount);
                                        })) // Add to self
                                .then(Commands.argument("username", StringArgumentType.string()).suggests((context, builder) -> {
                                                    //builder.suggest("\""+ BankSystemSettings.MarketBot.USER_NAME +"\"");
                                                    Map<UUID, String> uuidToNameMap = PlayerUtilities.getUUIDToNameMap();
                                                    for(String name : uuidToNameMap.values()) {

                                                        builder.suggest("\""+name+"\"");
                                                    }
                                                    return builder.buildFuture();
                                                })
                                                .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                                        .executes(context -> {
                                                            CommandSourceStack source = context.getSource();
                                                            ServerPlayer player = source.getPlayerOrException();

                                                            // Get arguments
                                                            String username = StringArgumentType.getString(context, "username");
                                                            int amount = IntegerArgumentType.getInteger(context, "amount");

                                                            // Execute the command on the server_sender
                                                            return executeSetMoney(player, username, amount);
                                                        })
                                                )
                                )
                        )
                        .then(Commands.literal("remove")
                                .requires(source -> source.hasPermission(2)) // Admin-only for adding money
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(context -> {
                                            CommandSourceStack source = context.getSource();
                                            ServerPlayer player = source.getPlayerOrException();

                                            // Get arguments
                                            String username = player.getName().getString();
                                            int amount = IntegerArgumentType.getInteger(context, "amount");

                                            // Execute the command on the server_sender
                                            return executeRemoveMoney(player, username, amount);
                                        })) // Add to self
                                .then(Commands.argument("username", StringArgumentType.string()).suggests((context, builder) -> {
                                                    //builder.suggest("\""+ BankSystemSettings.MarketBot.USER_NAME +"\"");
                                                    Map<UUID, String> uuidToNameMap = PlayerUtilities.getUUIDToNameMap();
                                                    for(String name : uuidToNameMap.values()) {

                                                        builder.suggest("\""+name+"\"");
                                                    }
                                                    return builder.buildFuture();
                                                })
                                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                                        .executes(context -> {
                                                            CommandSourceStack source = context.getSource();
                                                            ServerPlayer player = source.getPlayerOrException();

                                                            // Get arguments
                                                            String username = StringArgumentType.getString(context, "username");
                                                            int amount = IntegerArgumentType.getInteger(context, "amount");

                                                            // Execute the command on the server_sender
                                                            return executeRemoveMoney(player, username, amount);
                                                        })
                                                )
                                )
                        )
                        .then(Commands.literal("send")
                                .then(Commands.argument("username", StringArgumentType.string()).suggests((context, builder) -> {
                                                    //builder.suggest("\""+ BankSystemSettings.MarketBot.USER_NAME +"\"");
                                                    Map<UUID, String> uuidToNameMap = PlayerUtilities.getUUIDToNameMap();
                                                    for(String name : uuidToNameMap.values()) {

                                                        builder.suggest("\""+name+"\"");
                                                    }
                                                    return builder.buildFuture();
                                                })
                                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                                        .executes(context -> {
                                                            CommandSourceStack source = context.getSource();
                                                            ServerPlayer player = source.getPlayerOrException();

                                                            // Get arguments
                                                            String fromPlayer = player.getName().getString();
                                                            String toPlayer = StringArgumentType.getString(context, "username");
                                                            int amount = IntegerArgumentType.getInteger(context, "amount");

                                                            // Execute the command on the server_sender
                                                            return executeSendMoney(player,fromPlayer, toPlayer, amount);
                                                        })
                                                )
                                )
                        )
                        .then(Commands.literal("circulation")
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    ServerPlayer player = source.getPlayerOrException();
                                    long circulation = ServerBankManager.getMoneyCirculation();
                                    player.sendSystemMessage(Component.literal("Circulation: "+ MoneyBank.ITEM_ID + circulation));
                                    return Command.SINGLE_SUCCESS;
                                })
                        )


        );


        // /bank                                                - Show bank balance (money and items)
        // /bank <username> show                                - Show bank balance of another player
        // /bank <username> create <itemID> <amount>            - Create a bank for another player
        // /bank <username> setBalance <itemID> <amount>        - Set balance of a bank for another player
        // /bank <username> delete <itemID>                     - Delete a bank for another player
        dispatcher.register(
                Commands.literal("bank")
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            ServerPlayer player = source.getPlayerOrException();

                            // Execute the balance command on the server_sender
                            return bank_show(player, player.getName().getString());
                        })
                        .then(Commands.argument("username", StringArgumentType.string()).suggests((context, builder) -> {
                                            //builder.suggest("\""+ BankSystemSettings.MarketBot.USER_NAME +"\"");
                                            Map<UUID, String> uuidToNameMap = PlayerUtilities.getUUIDToNameMap();
                                            for(String name : uuidToNameMap.values()) {

                                                builder.suggest("\""+name+"\"");
                                            }
                                            return builder.buildFuture();
                                        })
                                        .requires(source -> source.hasPermission(2))
                                        .then(Commands.literal("show")
                                                .executes(context -> {
                                                    CommandSourceStack source = context.getSource();
                                                    ServerPlayer player = source.getPlayerOrException();
                                                    String username = StringArgumentType.getString(context, "username");

                                                    // Execute the balance command on the server_sender
                                                    return bank_show(player, username);
                                                })
                                        )
                                        .then(Commands.literal("create")
                                                .then(Commands.argument("itemID", StringArgumentType.string())/*.suggests((context, builder) -> {
                                                                    ArrayList<String> suggestions = ServerMarket.getTradeItemIDs();
                                                                    for(String suggestion : suggestions) {
                                                                        builder.suggest("\""+suggestion+"\"");
                                                                    }
                                                                    return builder.buildFuture();
                                                                })*/
                                                                .then(Commands.argument("balance", LongArgumentType.longArg(0))
                                                                        .executes(context -> {
                                                                            CommandSourceStack source = context.getSource();
                                                                            ServerPlayer player = source.getPlayerOrException();

                                                                            // Get arguments
                                                                            String itemID = StringArgumentType.getString(context, "itemID");
                                                                            long balance = LongArgumentType.getLong(context, "balance");
                                                                            String username = StringArgumentType.getString(context, "username");


                                                                            // Execute the command on the server_sender
                                                                            return bank_create(player, username,  ItemUtilities.getNormalizedItemID(itemID), balance);
                                                                        })
                                                                )
                                                )
                                        )
                                        .then(Commands.literal("setBalance")
                                                .then(Commands.argument("itemID", StringArgumentType.string())/*.suggests((context, builder) -> {
                                                                    ArrayList<String> suggestions = ServerMarket.getTradeItemIDs();
                                                                    builder.suggest("\""+MoneyBank.ITEM_ID+"\"");
                                                                    for(String suggestion : suggestions) {
                                                                        builder.suggest("\""+suggestion+"\"");
                                                                    }
                                                                    return builder.buildFuture();
                                                                })*/
                                                                .then(Commands.argument("balance", LongArgumentType.longArg(0))
                                                                        .executes(context -> {
                                                                            CommandSourceStack source = context.getSource();
                                                                            ServerPlayer player = source.getPlayerOrException();

                                                                            // Get arguments
                                                                            String itemID = StringArgumentType.getString(context, "itemID");
                                                                            long balance = LongArgumentType.getLong(context, "balance");
                                                                            String username = StringArgumentType.getString(context, "username");


                                                                            // Execute the command on the server_sender
                                                                            return bank_setBalance(player, username,  ItemUtilities.getNormalizedItemID(itemID), balance);
                                                                        })
                                                                )
                                                )
                                        )
                                        .then(Commands.literal("delete")
                                                .then(Commands.argument("itemID", StringArgumentType.string())/*.suggests((context, builder) -> {
                                                                    ArrayList<String> suggestions = ServerMarket.getTradeItemIDs();
                                                                    for(String suggestion : suggestions) {
                                                                        builder.suggest("\""+suggestion+"\"");
                                                                    }
                                                                    return builder.buildFuture();
                                                                })*/
                                                                .executes(context -> {
                                                                    CommandSourceStack source = context.getSource();
                                                                    ServerPlayer player = source.getPlayerOrException();

                                                                    // Get arguments
                                                                    String itemID = StringArgumentType.getString(context, "itemID");
                                                                    String username = StringArgumentType.getString(context, "username");


                                                                    // Execute the command on the server_sender
                                                                    return bank_delete(player, username, ItemUtilities.getNormalizedItemID(itemID));
                                                                })
                                                )
                                        )
                        )
                        .then(Commands.literal("allowItem")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("itemID", StringArgumentType.string())
                                        .executes(context -> {
                                            CommandSourceStack source = context.getSource();
                                            ServerPlayer player = source.getPlayerOrException();

                                            // Get arguments
                                            String itemID = StringArgumentType.getString(context, "itemID");


                                            if(ServerBankManager.allowItemID(ItemUtilities.getNormalizedItemID(itemID)))
                                                PlayerUtilities.printToClientConsole(player, "Allowed item ID: " + itemID+ " for banking.");
                                            else
                                                PlayerUtilities.printToClientConsole(player, "Failed to allow item ID: " + itemID+ " for banking.");
                                            return Command.SINGLE_SUCCESS;
                                        })
                                )
                        )
                        .then(Commands.literal("settingsGUI")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    ServerPlayer player = source.getPlayerOrException();

                                    // Open screen for settings GUI
                                    SyncOpenBankSystemSettingsGUIPacket.send(player);

                                    return Command.SINGLE_SUCCESS;
                                })

                        )


        );
    }


    private static int executeAddMoney(ServerPlayer executor, String username, int amount) {
        // Server-side logic for adding money
        //UUID playerUUID = PlayerUtilities.getPlayerUUID(username);
        /*if(playerUUID == null)
        {
            executor.sendSystemMessage(
                    Component.literal("Player " + username + " not found.")
            );
            return Command.SINGLE_SUCCESS;
        }*/
        Bank bank = ServerBankManager.getMoneyBank(username);
        if(bank == null)
        {
            PlayerUtilities.printToClientConsole(executor, "Bank not found for " + username);
            return Command.SINGLE_SUCCESS;
        }
        bank.deposit(amount);
        PlayerUtilities.printToClientConsole(executor, "Added " + amount + " to " + username + "'s account!");
        return Command.SINGLE_SUCCESS;
    }
    private static int executeSetMoney(ServerPlayer executor, String username, int amount) {
        // Server-side logic for adding money
       /* UUID playerUUID = ServerPlayerList.getPlayerUUID(username);
        if(playerUUID == null)
        {
            executor.sendSystemMessage(
                    Component.literal("Player " + username + " not found.")
            );
            return Command.SINGLE_SUCCESS;
        }*/
        Bank bank = ServerBankManager.getMoneyBank(username);
        if(bank == null)
        {
            PlayerUtilities.printToClientConsole(executor, "Bank not found for " + username);
            return Command.SINGLE_SUCCESS;
        }
        bank.setBalance(amount);
        PlayerUtilities.printToClientConsole(executor, "Set " + amount + " to " + username + "'s account!");
        return Command.SINGLE_SUCCESS;
    }
    private static int executeRemoveMoney(ServerPlayer executor, String username, int amount) {
        // Server-side logic for adding money
        /*UUID playerUUID = ServerPlayerList.getPlayerUUID(username);
        if(playerUUID == null)
        {
            executor.sendSystemMessage(
                    Component.literal("Player " + username + " not found.")
            );
            return Command.SINGLE_SUCCESS;
        }*/
        Bank bank = ServerBankManager.getMoneyBank(username);
        if(bank == null)
        {
            PlayerUtilities.printToClientConsole(executor, "Bank not found for " + username);
            return Command.SINGLE_SUCCESS;
        }
        if(bank.getBalance() >= amount) {
            bank.withdraw(amount);

            PlayerUtilities.printToClientConsole(executor, "Removed " + amount + " from " + username + "'s account!");
        }
        else {
            PlayerUtilities.printToClientConsole(executor, "Not enough money in " + username + "'s account!");
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int executeSendMoney(ServerPlayer executor, String fromUser, String toUser, int amount) {
        // Server-side logic for adding money
        /*ServerPlayer fromPlayer = ServerPlayerList.getPlayer(fromUser);
        UUID fromPlayerUUID = ServerPlayerList.getPlayerUUID(fromUser);
        if(fromPlayerUUID == null)
        {
            executor.sendSystemMessage(
                    Component.literal("Player " + fromUser + " not found.")
            );
            return Command.SINGLE_SUCCESS;
        }
        ServerPlayer toPlayer = ServerPlayerList.getPlayer(toUser);
        UUID toPlayerUUID = ServerPlayerList.getPlayerUUID(toUser);
        if(toPlayerUUID == null)
        {
            executor.sendSystemMessage(
                    Component.literal("Player " + toUser + " not found.")
            );
            return Command.SINGLE_SUCCESS;
        }*/

        Bank fromBank = ServerBankManager.getMoneyBank(fromUser);
        Bank toBank = ServerBankManager.getMoneyBank(toUser);

        if(fromBank == null)
        {
            PlayerUtilities.printToClientConsole(fromUser, "You don't have a money bank account.");
            return Command.SINGLE_SUCCESS;
        }
        if(toBank == null)
        {
            PlayerUtilities.printToClientConsole(fromUser, toUser + " doesn't have a money bank account.");
            return Command.SINGLE_SUCCESS;
        }
        if(fromBank.transfer(amount, toBank))
        {
            PlayerUtilities.printToClientConsole(fromUser, "Transfered " + amount + MoneyItem.DISPLAY_NAME+" from " + fromUser + " to " + toUser + "'s account!");
            PlayerUtilities.printToClientConsole(toUser, "Received " + amount + MoneyItem.DISPLAY_NAME+" from " + fromUser + "'s account!");
        }
        else {
            if (fromBank.getBalance() < amount)
               PlayerUtilities.printToClientConsole(fromUser, "You don't have enough money to transfer " + amount + MoneyItem.DISPLAY_NAME+"!");
            else
               PlayerUtilities.printToClientConsole(fromUser, "Failed to transfer " + amount + MoneyItem.DISPLAY_NAME+" to " + toUser + "'s account!");
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int showBalance(ServerPlayer player) {
        // Server-side logic for showing the balance
        long balance = ServerBankManager.getMoneyBank(player.getUUID()).getBalance(); // Example call
        PlayerUtilities.printToClientConsole(player,"Your balance: " + balance);
        return Command.SINGLE_SUCCESS;
    }

    private static int bank_show(ServerPlayer player, String targetPlayer) {
        BankUser user = ServerBankManager.getUser(targetPlayer);
        if(user == null) {
            PlayerUtilities.printToClientConsole(player,"User not found: " + targetPlayer);
            return Command.SINGLE_SUCCESS;
        }
        PlayerUtilities.printToClientConsole(player,user.toString());
        return Command.SINGLE_SUCCESS;
    }

    private static int bank_create(ServerPlayer player,String targetPlayer, String itemID, long balance) {
        BankUser user = ServerBankManager.getUser(targetPlayer);
        Bank bank = user.getBank(itemID);
        boolean created = bank == null;

        bank = user.createItemBank(itemID, balance);
        if(bank == null)
        {
            PlayerUtilities.printToClientConsole(player, "Cannot create bank for " + targetPlayer + " with itemID: " + itemID);
            return Command.SINGLE_SUCCESS;
        }
        if(created)
            PlayerUtilities.printToClientConsole(player, "Bank created for " + player.getName().getString()+"\n"+bank.toStringNoOwner());
        else
            PlayerUtilities.printToClientConsole(player,"Bank already exists for " + player.getName().getString()+"\n"+bank.toStringNoOwner());
        return Command.SINGLE_SUCCESS;
    }
    private static int bank_setBalance(ServerPlayer player,String targetPlayer, String itemID, long balance) {
        BankUser user = ServerBankManager.getUser(targetPlayer);
        Bank bank = user.getBank(itemID);
        if(bank == null) {
            PlayerUtilities.printToClientConsole(player,"Bank not found for " + player.getName().getString()+" ItemID: "+itemID);
            return Command.SINGLE_SUCCESS;
        }
        bank.setBalance(balance);
        PlayerUtilities.printToClientConsole(player,"Bank balance set for " + player.getName().getString()+"\n"+bank.toStringNoOwner());
        return Command.SINGLE_SUCCESS;
    }

    private static int bank_delete(ServerPlayer player,String targetPlayer, String itemID)
    {
        BankUser user = ServerBankManager.getUser(targetPlayer);

        Bank bank = user.getBank(itemID);
        if(bank == null) {
            PlayerUtilities.printToClientConsole(player,"Bank not found for " + player.getName().getString()+" ItemID: "+itemID);
            return Command.SINGLE_SUCCESS;
        }

        UUID playerUUID = user.getPlayerUUID();
        if(ServerBankManager.closeBankAccount(playerUUID, itemID)) {
            PlayerUtilities.printToClientConsole(player, "Bank deleted for " + player.getName().getString() + " ItemID: " + itemID);
        }
        else {
            PlayerUtilities.printToClientConsole(player,"Bank not found for " + player.getName().getString()+" ItemID: "+itemID);
        }
        return Command.SINGLE_SUCCESS;
    }
}
