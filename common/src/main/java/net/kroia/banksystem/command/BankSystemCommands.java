package net.kroia.banksystem.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.kroia.banksystem.banking.BankUser;
import net.kroia.banksystem.banking.ServerBankManager;
import net.kroia.banksystem.banking.bank.Bank;
import net.kroia.banksystem.banking.bank.MoneyBank;
import net.kroia.banksystem.item.custom.money.MoneyItem;
import net.kroia.banksystem.networking.packet.server_sender.SyncOpenBankSystemSettingsGUIPacket;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.PlayerUtilities;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
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
                                                    //builder.suggest("\""+ BankSystemModSettings.MarketBot.USER_NAME +"\"");
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
                                                    //builder.suggest("\""+ BankSystemModSettings.MarketBot.USER_NAME +"\"");
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
                                                    //builder.suggest("\""+ BankSystemModSettings.MarketBot.USER_NAME +"\"");
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
                                                    //builder.suggest("\""+ BankSystemModSettings.MarketBot.USER_NAME +"\"");
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
                                    PlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getCirculationMessage(circulation, MoneyItem.getName()));
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
                                            //builder.suggest("\""+ BankSystemModSettings.MarketBot.USER_NAME +"\"");
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
                                                                            return bank_create(player, username,  itemID, balance);
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
                                                                            return bank_setBalance(player, username,  itemID, balance);
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
                                                                    return bank_delete(player, username, itemID);
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


                                            if(ServerBankManager.allowItemID(itemID))
                                                PlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getItemNowAllowedMessage(itemID));
                                            else
                                                PlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getItemNowAllowedFailedMessage(itemID));
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
        Bank bank = ServerBankManager.getMoneyBank(username);
        if(bank == null)
        {
            PlayerUtilities.printToClientConsole(executor, BankSystemTextMessages.getBankNotFoundMessage(username, MoneyItem.getName()));
            return Command.SINGLE_SUCCESS;
        }
        bank.deposit(amount);
        PlayerUtilities.printToClientConsole(executor, BankSystemTextMessages.getAddedMessage(amount, MoneyItem.getName(), username));
        return Command.SINGLE_SUCCESS;
    }
    private static int executeSetMoney(ServerPlayer executor, String username, int amount) {
        Bank bank = ServerBankManager.getMoneyBank(username);
        if(bank == null)
        {
            PlayerUtilities.printToClientConsole(executor, BankSystemTextMessages.getBankNotFoundMessage(username, MoneyItem.getName()));
            return Command.SINGLE_SUCCESS;
        }
        bank.setBalance(amount);
        if(!executor.getName().getString().equals(username))
            PlayerUtilities.printToClientConsole(executor, BankSystemTextMessages.getSetBalanceMessage(amount, MoneyItem.getName(), username));
        return Command.SINGLE_SUCCESS;
    }
    private static int executeRemoveMoney(ServerPlayer executor, String username, int amount) {
        Bank bank = ServerBankManager.getMoneyBank(username);
        if(bank == null)
        {
            PlayerUtilities.printToClientConsole(executor, BankSystemTextMessages.getBankNotFoundMessage(username, MoneyItem.getName()));
            return Command.SINGLE_SUCCESS;
        }
        if(bank.getBalance() >= amount) {
            bank.withdraw(amount);

            PlayerUtilities.printToClientConsole(executor, BankSystemTextMessages.getRemovedMessage(amount, MoneyItem.getName(), username));
        }
        else {
            PlayerUtilities.printToClientConsole(executor, BankSystemTextMessages.getNotEnoughInAccountMessage(username, MoneyItem.getName()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int executeSendMoney(ServerPlayer executor, String fromUser, String toUser, int amount) {
        Bank fromBank = ServerBankManager.getMoneyBank(fromUser);
        Bank toBank = ServerBankManager.getMoneyBank(toUser);

        if(fromBank == null)
        {
            PlayerUtilities.printToClientConsole(executor, BankSystemTextMessages.getBankNotFoundMessage(fromUser, MoneyItem.getName()));
            return Command.SINGLE_SUCCESS;
        }
        if(toBank == null)
        {
            PlayerUtilities.printToClientConsole(executor, BankSystemTextMessages.getBankNotFoundMessage(toUser, MoneyItem.getName()));
            return Command.SINGLE_SUCCESS;
        }
        if(fromBank == toBank)
        {
            PlayerUtilities.printToClientConsole(executor, BankSystemTextMessages.getTransferToSameAccountMessage(MoneyItem.getName()));
            return Command.SINGLE_SUCCESS;
        }
        if(!fromBank.transfer(amount, toBank)) {
            if (fromBank.getBalance() < amount)
               PlayerUtilities.printToClientConsole(executor, BankSystemTextMessages.getNotEnoughMoneyForTransfer(fromUser, toUser, amount, MoneyItem.getName()));
            else
               PlayerUtilities.printToClientConsole(executor, BankSystemTextMessages.getTransferFailedMessage(fromUser, toUser, amount, MoneyItem.getName()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int showBalance(ServerPlayer player) {
        Bank bank = ServerBankManager.getMoneyBank(player.getUUID());
        if(bank == null) {
            PlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankNotFoundMessage(player.getName().getString(), MoneyItem.getName()));
            return Command.SINGLE_SUCCESS;
        }
        long balance = bank.getBalance();
        PlayerUtilities.printToClientConsole(player,BankSystemTextMessages.getYourBalanceMessage(balance));
        return Command.SINGLE_SUCCESS;
    }

    private static int bank_show(ServerPlayer player, String targetPlayer) {
        BankUser user = ServerBankManager.getUser(targetPlayer);
        if(user == null) {
            PlayerUtilities.printToClientConsole(player,BankSystemTextMessages.getUserNotFoundMessage(targetPlayer));
            return Command.SINGLE_SUCCESS;
        }
        PlayerUtilities.printToClientConsole(player,user.toString());
        return Command.SINGLE_SUCCESS;
    }

    private static int bank_create(ServerPlayer player,String targetPlayer, String itemID, long balance) {
        String orgItemID = itemID;
        itemID = ItemUtilities.getNormalizedItemID(itemID);
        if(itemID == null)
        {
            PlayerUtilities.printToClientConsole(player,BankSystemTextMessages.getInvalidItemIDMessage(orgItemID));
            return Command.SINGLE_SUCCESS;
        }
        BankUser user = ServerBankManager.getUser(targetPlayer);
        if(user == null) {
            PlayerUtilities.printToClientConsole(player,BankSystemTextMessages.getUserNotFoundMessage(targetPlayer));
            return Command.SINGLE_SUCCESS;
        }
        Bank bank = user.getBank(itemID);
        boolean created = bank == null;

        bank = user.createItemBank(itemID, balance);
        if(bank == null)
        {
            PlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getCantCreateBankMessage(targetPlayer, itemID));
            return Command.SINGLE_SUCCESS;
        }
        if(created)
            PlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankCreatedMessage(player.getName().getString(), itemID)+"\n"+bank.toStringNoOwner());
        else
            PlayerUtilities.printToClientConsole(player,BankSystemTextMessages.getBankAlreadyExistsMessage(player.getName().getString(), itemID)+"\n"+bank.toStringNoOwner());
        return Command.SINGLE_SUCCESS;
    }
    private static int bank_setBalance(ServerPlayer player,String targetPlayer, String itemID, long balance) {
        String orgItemID = itemID;
        itemID = ItemUtilities.getNormalizedItemID(itemID);
        if(itemID == null)
        {
            PlayerUtilities.printToClientConsole(player,BankSystemTextMessages.getInvalidItemIDMessage(orgItemID));
            return Command.SINGLE_SUCCESS;
        }
        BankUser user = ServerBankManager.getUser(targetPlayer);
        if(user == null) {
            PlayerUtilities.printToClientConsole(player,BankSystemTextMessages.getUserNotFoundMessage(targetPlayer));
            return Command.SINGLE_SUCCESS;
        }
        Bank bank = user.getBank(itemID);
        if(bank == null) {
            PlayerUtilities.printToClientConsole(player,BankSystemTextMessages.getBankNotFoundMessage(player.getName().getString(),itemID));
            return Command.SINGLE_SUCCESS;
        }
        bank.setBalance(balance);
        //PlayerUtilities.printToClientConsole(player,BankSystemTextMessages.getSetBalanceMessage(balance, player.getName().getString(), itemID) + "\n"+bank.toStringNoOwner());
        return Command.SINGLE_SUCCESS;
    }

    private static int bank_delete(ServerPlayer player,String targetPlayer, String itemID)
    {
        String orgItemID = itemID;
        itemID = ItemUtilities.getNormalizedItemID(itemID);
        if(itemID == null)
        {
            PlayerUtilities.printToClientConsole(player,BankSystemTextMessages.getInvalidItemIDMessage(orgItemID));
            return Command.SINGLE_SUCCESS;
        }
        BankUser user = ServerBankManager.getUser(targetPlayer);
        if(user == null) {
            PlayerUtilities.printToClientConsole(player,BankSystemTextMessages.getUserNotFoundMessage(targetPlayer));
            return Command.SINGLE_SUCCESS;
        }
        Bank bank = user.getBank(itemID);
        if(bank == null) {
            PlayerUtilities.printToClientConsole(player,BankSystemTextMessages.getBankNotFoundMessage(player.getName().getString(),itemID));
            return Command.SINGLE_SUCCESS;
        }

        UUID playerUUID = user.getPlayerUUID();
        if(ServerBankManager.closeBankAccount(playerUUID, itemID)) {
            PlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankDeletedMessage(player.getName().getString(), itemID));
        }
        else {
            PlayerUtilities.printToClientConsole(player,BankSystemTextMessages.getBankNotFoundMessage(player.getName().getString(),itemID));
        }
        return Command.SINGLE_SUCCESS;
    }
}
