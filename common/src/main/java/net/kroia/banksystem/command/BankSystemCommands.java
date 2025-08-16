package net.kroia.banksystem.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.IBank;
import net.kroia.banksystem.banking.BankAccount;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.bank.Bank;
import net.kroia.banksystem.item.custom.money.MoneyItem;
import net.kroia.banksystem.networking.packet.server_sender.SyncOpenGUIPacket;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.UUID;

public class BankSystemCommands {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BankSystemCommands.BACKEND_INSTANCES = backend;
    }
    
    // Method to register commands
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        //BACKEND_INSTANCES.LOGGER.info("Registering commands");

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
                                .then(Commands.argument("amount", FloatArgumentType.floatArg(0, Long.MAX_VALUE))
                                        .executes(context -> {
                                            CommandSourceStack source = context.getSource();
                                            ServerPlayer player = source.getPlayerOrException();

                                            // Get arguments
                                            String username = player.getName().getString();
                                            float amount = FloatArgumentType.getFloat(context, "amount");

                                            // Execute the command on the server_sender
                                            return executeAddMoney(player, username, amount);
                                        })) // Add to self
                                .then(Commands.argument("username", StringArgumentType.string()).suggests((context, builder) -> {
                                                    //builder.suggest("\""+ BACKEND_INSTANCESSettings.MarketBot.USER_NAME +"\"");
                                                    Map<UUID, String> uuidToNameMap = ServerPlayerUtilities.getUUIDToNameMap();
                                                    for(String name : uuidToNameMap.values()) {

                                                        builder.suggest("\""+name+"\"");
                                                    }
                                                    return builder.buildFuture();
                                                })
                                                .then(Commands.argument("amount", FloatArgumentType.floatArg(0, Long.MAX_VALUE))
                                                        .executes(context -> {
                                                            CommandSourceStack source = context.getSource();
                                                            ServerPlayer player = source.getPlayerOrException();

                                                            // Get arguments
                                                            String username = StringArgumentType.getString(context, "username");
                                                            float amount = FloatArgumentType.getFloat(context, "amount");

                                                            // Execute the command on the server_sender
                                                            return executeAddMoney(player, username, amount);
                                                        })
                                                )
                                )
                        )
                        .then(Commands.literal("set")
                                .requires(source -> source.hasPermission(2)) // Admin-only for adding money
                                .then(Commands.argument("amount", FloatArgumentType.floatArg(0, Long.MAX_VALUE))
                                        .executes(context -> {
                                            CommandSourceStack source = context.getSource();
                                            ServerPlayer player = source.getPlayerOrException();

                                            // Get arguments
                                            String username = player.getName().getString();
                                            float amount = FloatArgumentType.getFloat(context, "amount");

                                            // Execute the command on the server_sender
                                            return executeSetMoney(player, username, amount);
                                        })) // Add to self
                                .then(Commands.argument("username", StringArgumentType.string()).suggests((context, builder) -> {
                                                    //builder.suggest("\""+ BACKEND_INSTANCESSettings.MarketBot.USER_NAME +"\"");
                                                    Map<UUID, String> uuidToNameMap = ServerPlayerUtilities.getUUIDToNameMap();
                                                    for(String name : uuidToNameMap.values()) {

                                                        builder.suggest("\""+name+"\"");
                                                    }
                                                    return builder.buildFuture();
                                                })
                                                .then(Commands.argument("amount", FloatArgumentType.floatArg(0, Long.MAX_VALUE))
                                                        .executes(context -> {
                                                            CommandSourceStack source = context.getSource();
                                                            ServerPlayer player = source.getPlayerOrException();

                                                            // Get arguments
                                                            String username = StringArgumentType.getString(context, "username");
                                                            float amount = FloatArgumentType.getFloat(context, "amount");

                                                            // Execute the command on the server_sender
                                                            return executeSetMoney(player, username, amount);
                                                        })
                                                )
                                )
                        )
                        .then(Commands.literal("remove")
                                .requires(source -> source.hasPermission(2)) // Admin-only for adding money
                                .then(Commands.argument("amount", FloatArgumentType.floatArg(0, Long.MAX_VALUE))
                                        .executes(context -> {
                                            CommandSourceStack source = context.getSource();
                                            ServerPlayer player = source.getPlayerOrException();

                                            // Get arguments
                                            String username = player.getName().getString();
                                            float amount = FloatArgumentType.getFloat(context, "amount");

                                            // Execute the command on the server_sender
                                            return executeRemoveMoney(player, username, amount);
                                        })) // Add to self
                                .then(Commands.argument("username", StringArgumentType.string()).suggests((context, builder) -> {
                                                    //builder.suggest("\""+ BACKEND_INSTANCESSettings.MarketBot.USER_NAME +"\"");
                                                    Map<UUID, String> uuidToNameMap = ServerPlayerUtilities.getUUIDToNameMap();
                                                    for(String name : uuidToNameMap.values()) {

                                                        builder.suggest("\""+name+"\"");
                                                    }
                                                    return builder.buildFuture();
                                                })
                                                .then(Commands.argument("amount", FloatArgumentType.floatArg(0, Long.MAX_VALUE))
                                                        .executes(context -> {
                                                            CommandSourceStack source = context.getSource();
                                                            ServerPlayer player = source.getPlayerOrException();

                                                            // Get arguments
                                                            String username = StringArgumentType.getString(context, "username");
                                                            float amount = FloatArgumentType.getFloat(context, "amount");

                                                            // Execute the command on the server_sender
                                                            return executeRemoveMoney(player, username, amount);
                                                        })
                                                )
                                )
                        )
                        .then(Commands.literal("send")
                                .then(Commands.argument("username", StringArgumentType.string()).suggests((context, builder) -> {
                                                    //builder.suggest("\""+ BACKEND_INSTANCESSettings.MarketBot.USER_NAME +"\"");
                                                    Map<UUID, String> uuidToNameMap = ServerPlayerUtilities.getUUIDToNameMap();
                                                    for(String name : uuidToNameMap.values()) {

                                                        builder.suggest("\""+name+"\"");
                                                    }
                                                    return builder.buildFuture();
                                                })
                                                .then(Commands.argument("amount", FloatArgumentType.floatArg(0, Long.MAX_VALUE))
                                                        .executes(context -> {
                                                            CommandSourceStack source = context.getSource();
                                                            ServerPlayer player = source.getPlayerOrException();

                                                            // Get arguments
                                                            String fromPlayer = player.getName().getString();
                                                            String toPlayer = StringArgumentType.getString(context, "username");
                                                            float amount = FloatArgumentType.getFloat(context, "amount");

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
                                    double circulation = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getRealMoneyCirculation();
                                    ServerPlayerUtilities.printToClientConsole(player,
                                            BankSystemTextMessages.getCirculationMessage(Bank.getFormattedAmount(circulation,
                                            BACKEND_INSTANCES.SERVER_BANK_MANAGER.getItemFractionScaleFactor(MoneyItem.getItemID())),
                                                    MoneyItem.getName()));
                                    return Command.SINGLE_SUCCESS;
                                })
                        )


        );


        // /bank                                                - Show bank balance (money and items)
        // /bank enableNotifications                            - Enables bank notifications on transactions
        // /bank disableNotifications                           - Disables bank notifications on transactions
        // /bank settingsGUI                                    - Open bank settings GUI to manage the bankable items
        // /bank <username> show                                - Show bank balance of a player
        // /bank <username> create <itemID> <amount>            - Create a bank for another player
        // /bank <username> setBalance <itemID> <amount>        - Set balance of a bank for another player
        // /bank <username> delete <itemID>                     - Delete a bank for another player
        // /bank allowItem <itemID>                             - Makes the itemID available for bank accounts
        // /bank allowItemInHand                                - Makes the item in the player's hand available for bank accounts
        // /bank disallowItem <itemID>                          - Makes the itemID unavailable for bank accounts
        // /bank disallowItemInHand                             - Makes the item in the player's hand unavailable for bank accounts
        // /bank bankManagementGUI                              - Open bankManagement GUI
        // /bank setStartingBalance                             - Set the starting money balance for new players
        // /bank setItemTransferTickInterval                    - Set the amount of ticks it uses for a item to be transferred in the bank terminal block. If set to 0, it will be instant.
        // /bank save                                           - Save all bank data and settings
        // /bank saveBankaccounts                               - Save all bank data
        // /bank saveSettings                                   - Save BankSystemMod settings to file
        // /bank loadBankaccounts                               - Load all bank data from file
        // /bank loadSettings                                   - Load BankSystemMod settings from file
        dispatcher.register(
                Commands.literal("bank")
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            ServerPlayer player = source.getPlayerOrException();

                            // Execute the balance command on the server_sender
                            return bank_show(player, player.getName().getString());
                        })
                        .then(Commands.literal("enableNotifications")
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    ServerPlayer player = source.getPlayerOrException();
                                    User bankUser = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getUserByUUID(player.getUUID());
                                    if(bankUser == null)
                                    {
                                        ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getUserNotFoundMessage(player.getName().getString()));
                                        return Command.SINGLE_SUCCESS;
                                    }
                                    bankUser.setEnableBankNotifications(true);
                                    ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankUserNotificationEnabledMessage());
                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                        .then(Commands.literal("disableNotifications")
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    ServerPlayer player = source.getPlayerOrException();
                                    User bankUser = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getUserByUUID(player.getUUID());
                                    if(bankUser == null)
                                    {
                                        ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getUserNotFoundMessage(player.getName().getString()));
                                        return Command.SINGLE_SUCCESS;
                                    }
                                    bankUser.setEnableBankNotifications(false);
                                    ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankUserNotificationDisabledMessage());
                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                        .then(Commands.argument("username", StringArgumentType.string()).suggests((context, builder) -> {
                                            //builder.suggest("\""+ BACKEND_INSTANCESSettings.MarketBot.USER_NAME +"\"");
                                            Map<UUID, String> uuidToNameMap = ServerPlayerUtilities.getUUIDToNameMap();
                                            for(String name : uuidToNameMap.values()) {

                                                builder.suggest("\""+name+"\"");
                                            }
                                            return builder.buildFuture();
                                        })
                                        .requires(source -> source.hasPermission(2))
                                        .then(Commands.literal("bankManagementGUI")
                                                .executes(context -> {
                                                    CommandSourceStack source = context.getSource();
                                                    ServerPlayer player = source.getPlayerOrException();
                                                    String username = StringArgumentType.getString(context, "username");
                                                    User bankUser = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getUserByName(username);
                                                    if(bankUser == null)
                                                    {
                                                        ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getUserNotFoundMessage(username));
                                                        return Command.SINGLE_SUCCESS;
                                                    }
                                                    UUID playerUUID = bankUser.getUUID();
                                                    BankAccount bankAccount = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getPersonalBankAccount(playerUUID);
                                                    if(bankAccount != null)
                                                        SyncOpenGUIPacket.send_openBankAccountScreen(player, playerUUID, bankAccount.getAccountNumber(), true);


                                                    // Execute the balance command on the server_sender
                                                    return Command.SINGLE_SUCCESS;
                                                })
                                        )
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
                                                                .then(Commands.argument("balance", FloatArgumentType.floatArg(0))
                                                                        .executes(context -> {
                                                                            CommandSourceStack source = context.getSource();
                                                                            ServerPlayer player = source.getPlayerOrException();

                                                                            // Get arguments
                                                                            String itemID = StringArgumentType.getString(context, "itemID");
                                                                            float balance = FloatArgumentType.getFloat(context, "balance");
                                                                            String username = StringArgumentType.getString(context, "username");

                                                                            ItemStack itemStack = ItemUtilities.createItemStackFromId(itemID);
                                                                            if(itemStack == ItemStack.EMPTY)
                                                                            {
                                                                                ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getInvalidItemIDMessage(itemID));
                                                                                return Command.SINGLE_SUCCESS;
                                                                            }
                                                                            ItemID itemIDObj = new ItemID(itemStack);

                                                                            // Execute the command on the server_sender
                                                                            return bank_create(player, username,  itemIDObj, balance);
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
                                                                .then(Commands.argument("balance", FloatArgumentType.floatArg(0, Long.MAX_VALUE))
                                                                        .executes(context -> {
                                                                            CommandSourceStack source = context.getSource();
                                                                            ServerPlayer player = source.getPlayerOrException();

                                                                            // Get arguments
                                                                            String itemID = StringArgumentType.getString(context, "itemID");
                                                                            float balance = FloatArgumentType.getFloat(context, "balance");
                                                                            String username = StringArgumentType.getString(context, "username");


                                                                            ItemStack itemStack = ItemUtilities.createItemStackFromId(itemID);
                                                                            if(itemStack == ItemStack.EMPTY)
                                                                            {
                                                                                ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getInvalidItemIDMessage(itemID));
                                                                                return Command.SINGLE_SUCCESS;
                                                                            }
                                                                            ItemID itemIDObj = new ItemID(itemStack);

                                                                            // Execute the command on the server_sender
                                                                            return bank_setBalance(player, username,  itemIDObj, balance);
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
                                                                    ItemStack itemStack = ItemUtilities.createItemStackFromId(itemID);
                                                                    if(itemStack == ItemStack.EMPTY)
                                                                    {
                                                                        ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getInvalidItemIDMessage(itemID));
                                                                        return Command.SINGLE_SUCCESS;
                                                                    }
                                                                    ItemID itemIDObj = new ItemID(itemStack);

                                                                    return bank_delete(player, username, itemIDObj);
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

                                            ItemStack itemStack = ItemUtilities.createItemStackFromId(itemID);
                                            if(itemStack == ItemStack.EMPTY)
                                            {
                                                ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getInvalidItemIDMessage(itemID));
                                                return Command.SINGLE_SUCCESS;
                                            }
                                            ItemID itemIDObj = new ItemID(itemStack);
                                            int centScaleFactor = 1;
                                            if(BACKEND_INSTANCES.SERVER_BANK_MANAGER.allowItemID(itemIDObj, centScaleFactor)) {
                                                centScaleFactor = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getItemFractionScaleFactor(itemIDObj);
                                                ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getItemNowAllowedMessage(itemID, Bank.getFormattedAmount(1, centScaleFactor)));
                                            }
                                            else
                                                ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getItemNowAllowedFailedMessage(itemID));
                                            return Command.SINGLE_SUCCESS;
                                        })
                                )
                        )
                        .then(Commands.literal("allowItemInHand")
                                .requires(source -> source.hasPermission(2))
                                        .executes(context -> {
                                            CommandSourceStack source = context.getSource();
                                            ServerPlayer player = source.getPlayerOrException();

                                            // Get arguments
                                            ItemStack item = player.getMainHandItem();
                                            if(item.isEmpty())
                                            {
                                                ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getNoItemInHandMessage());
                                                return Command.SINGLE_SUCCESS;
                                            }


                                            ItemID itemIDObj = new ItemID(item);
                                            int centScaleFactor = 1;
                                            if(BACKEND_INSTANCES.SERVER_BANK_MANAGER.allowItemID(itemIDObj, centScaleFactor)) {
                                                centScaleFactor = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getItemFractionScaleFactor(itemIDObj);
                                                ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getItemNowAllowedMessage(itemIDObj.toString(), Bank.getFormattedAmount(1, centScaleFactor)));
                                            }
                                            else
                                                ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getItemNowAllowedFailedMessage(itemIDObj.toString()));
                                            return Command.SINGLE_SUCCESS;
                                        })

                        )
                        .then(Commands.literal("disallowItem")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("itemID", StringArgumentType.string())
                                        .executes(context -> {
                                            CommandSourceStack source = context.getSource();
                                            ServerPlayer player = source.getPlayerOrException();

                                            // Get arguments
                                            String itemID = StringArgumentType.getString(context, "itemID");

                                            ItemStack itemStack = ItemUtilities.createItemStackFromId(itemID);
                                            if(itemStack == ItemStack.EMPTY)
                                            {
                                                ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getInvalidItemIDMessage(itemID));
                                                return Command.SINGLE_SUCCESS;
                                            }
                                            ItemID itemIDObj = new ItemID(itemStack);
                                            if(BACKEND_INSTANCES.SERVER_BANK_MANAGER.disallowItemID(itemIDObj))
                                                ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getItemNotAllowedMessage(itemID));
                                            else
                                                ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getItemNotAllowedFailedMessage(itemID));
                                            return Command.SINGLE_SUCCESS;
                                        })
                                )
                        )
                        .then(Commands.literal("disallowItemInHand")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    ServerPlayer player = source.getPlayerOrException();


                                    // Get arguments
                                    ItemStack item = player.getMainHandItem();
                                    if(item.isEmpty())
                                    {
                                        ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getNoItemInHandMessage());
                                        return Command.SINGLE_SUCCESS;
                                    }


                                    ItemID itemIDObj = new ItemID(item);
                                    if(BACKEND_INSTANCES.SERVER_BANK_MANAGER.disallowItemID(itemIDObj))
                                        ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getItemNotAllowedMessage(itemIDObj.toString()));
                                    else
                                        ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getItemNotAllowedFailedMessage(itemIDObj.toString()));
                                    return Command.SINGLE_SUCCESS;
                                })

                        )
                        .then(Commands.literal("settingsGUI")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    ServerPlayer player = source.getPlayerOrException();

                                    // Open screen for settings GUI
                                    SyncOpenGUIPacket.send_openBankSystemSettingScreen(player);

                                    return Command.SINGLE_SUCCESS;
                                })

                        )
                        .then(Commands.literal("setStartingBalance")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("amount", FloatArgumentType.floatArg(0))
                                        .executes(context -> {
                                            CommandSourceStack source = context.getSource();
                                            ServerPlayer player = source.getPlayerOrException();

                                            // Get arguments
                                            float amount = FloatArgumentType.getFloat(context, "amount");
                                            BACKEND_INSTANCES.SERVER_SETTINGS.PLAYER.STARTING_BALANCE.set(amount);
                                            ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankSettingStartBalanceSetMessage(Bank.getFormattedAmount(amount, BACKEND_INSTANCES.SERVER_BANK_MANAGER.getItemFractionScaleFactor(MoneyItem.getItemID()))));
                                            return Command.SINGLE_SUCCESS;
                                        })
                                )
                        )
                        .then(Commands.literal("setItemTransferTickInterval")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("ticks", IntegerArgumentType.integer(0))
                                        .executes(context -> {
                                            CommandSourceStack source = context.getSource();
                                            ServerPlayer player = source.getPlayerOrException();

                                            // Get arguments
                                            int amount = IntegerArgumentType.getInteger(context, "ticks");
                                            BACKEND_INSTANCES.SERVER_SETTINGS.BANK.ITEM_TRANSFER_TICK_INTERVAL.set(amount);
                                            ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankSettingItemTransferTickIntervalMessage(amount));
                                            return Command.SINGLE_SUCCESS;
                                        })
                                )
                        )
                        .then(Commands.literal("save")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    ServerPlayer player = source.getPlayerOrException();

                                    if(BACKEND_INSTANCES.SERVER_DATA_HANDLER.saveAll())
                                        ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankDataSavedMessage());
                                    else
                                        ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankDataSaveFailedMessage());

                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                        .then(Commands.literal("saveBankaccounts")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    ServerPlayer player = source.getPlayerOrException();

                                    if(BACKEND_INSTANCES.SERVER_DATA_HANDLER.save_bank())
                                        ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankDataSavedMessage());
                                    else
                                        ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankDataSaveFailedMessage());

                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                        .then(Commands.literal("saveSettings")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    ServerPlayer player = source.getPlayerOrException();

                                    if(BACKEND_INSTANCES.SERVER_DATA_HANDLER.save_globalSettings())
                                        ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankDataSavedMessage());
                                    else
                                        ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankDataSaveFailedMessage());

                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                        .then(Commands.literal("loadBankaccounts")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    ServerPlayer player = source.getPlayerOrException();

                                    if(BACKEND_INSTANCES.SERVER_DATA_HANDLER.load_bank())
                                        ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankDataLoadedMessage());
                                    else
                                        ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankDataLoadFailedMessage());

                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                        .then(Commands.literal("loadSettings")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    ServerPlayer player = source.getPlayerOrException();

                                    if(BACKEND_INSTANCES.SERVER_DATA_HANDLER.load_globalSettings())
                                        ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankDataLoadedMessage());
                                    else
                                        ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankDataLoadFailedMessage());

                                    return Command.SINGLE_SUCCESS;
                                })
                        )

        );
    }


    private static int executeAddMoney(ServerPlayer executor, String username, float amount) {
        IBank bank = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getOrCreatePersonalBank(username, MoneyItem.getItemID());
        if(bank == null)
        {
            ServerPlayerUtilities.printToClientConsole(executor, BankSystemTextMessages.getBankNotFoundMessage(username, MoneyItem.getName()));
            return Command.SINGLE_SUCCESS;
        }

        Bank.Status status = bank.depositReal(amount);
        if(status != Bank.Status.SUCCESS){
            ServerPlayerUtilities.printToClientConsole(executor,
                    BankSystemTextMessages.getCantAddMessage(
                            bank.getFormattedAmount(amount),
                            MoneyItem.getName(),
                            username,
                            status.toString()));
        }
        return Command.SINGLE_SUCCESS;
    }
    private static int executeSetMoney(ServerPlayer executor, String username, float amount) {
        IBank bank = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getOrCreatePersonalBank(username, MoneyItem.getItemID());
        if(bank == null)
        {
            ServerPlayerUtilities.printToClientConsole(executor, BankSystemTextMessages.getBankNotFoundMessage(username, MoneyItem.getName()));
            return Command.SINGLE_SUCCESS;
        }
        bank.setRealBalance(amount);
        if(!executor.getName().getString().equals(username))
            ServerPlayerUtilities.printToClientConsole(executor, BankSystemTextMessages.getSetBalanceMessage(bank.getFormattedAmount(amount), MoneyItem.getName(), username));
        return Command.SINGLE_SUCCESS;
    }
    private static int executeRemoveMoney(ServerPlayer executor, String username, float amount) {
        IBank bank = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getOrCreatePersonalBank(username, MoneyItem.getItemID());
        if(bank == null)
        {
            ServerPlayerUtilities.printToClientConsole(executor, BankSystemTextMessages.getBankNotFoundMessage(username, MoneyItem.getName()));
            return Command.SINGLE_SUCCESS;
        }
        if(bank.getBalance() >= amount) {
            bank.withdrawReal(amount);
        }
        else {
            ServerPlayerUtilities.printToClientConsole(executor, BankSystemTextMessages.getNotEnoughInAccountMessage(username, MoneyItem.getName()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int executeSendMoney(ServerPlayer executor, String fromUserName, String toUserName, float amount) {
        IBank fromBank = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getOrCreatePersonalBank(fromUserName, MoneyItem.getItemID());
        if(fromBank == null)
        {
            ServerPlayerUtilities.printToClientConsole(executor, BankSystemTextMessages.getBankNotFoundMessage(fromUserName, MoneyItem.getName()));
            return Command.SINGLE_SUCCESS;
        }
        IBank toBank = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getOrCreatePersonalBank(toUserName, MoneyItem.getItemID());
        if(toBank == null)
        {
            ServerPlayerUtilities.printToClientConsole(executor, BankSystemTextMessages.getBankNotFoundMessage(toUserName, MoneyItem.getName()));
            return Command.SINGLE_SUCCESS;
        }

        if(fromBank == toBank)
        {
            ServerPlayerUtilities.printToClientConsole(executor, BankSystemTextMessages.getTransferToSameAccountMessage(MoneyItem.getName()));
            return Command.SINGLE_SUCCESS;
        }
        Bank.Status status = fromBank.transfer(fromBank.convertToRawAmount(amount), toBank);
        if(status != Bank.Status.SUCCESS) {
            if (fromBank.getBalance() < amount)
               ServerPlayerUtilities.printToClientConsole(executor, BankSystemTextMessages.getNotEnoughMoneyForTransfer(fromUserName, toUserName, fromBank.getFormattedAmount(amount), MoneyItem.getName()));
            else
               ServerPlayerUtilities.printToClientConsole(executor, BankSystemTextMessages.getTransferFailedMessage(fromUserName, toUserName, fromBank.getFormattedAmount(amount), MoneyItem.getName(), status.toString()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int showBalance(ServerPlayer player) {
        IBank bank = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getOrCreatePersonalBank(player.getUUID(), MoneyItem.getItemID());
        if(bank == null)
        {
            ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankNotFoundMessage(player.getName().getString(), MoneyItem.getName()));
            return Command.SINGLE_SUCCESS;
        }
        long balance = bank.getBalance();
        ServerPlayerUtilities.printToClientConsole(player,BankSystemTextMessages.getYourBalanceMessage(Bank.getFormattedAmount(balance, bank.getItemFractionScaleFactor())));
        return Command.SINGLE_SUCCESS;
    }

    private static int bank_show(ServerPlayer player, String targetPlayer) {
        BankAccount account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getOrCreatePersonalBankAccount(targetPlayer);
        if(account == null)
        {
            ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankNotFoundMessage(targetPlayer, MoneyItem.getName()));
            return Command.SINGLE_SUCCESS;
        }
        ServerPlayerUtilities.printToClientConsole(player, account.toString());
        return Command.SINGLE_SUCCESS;
    }

    private static int bank_create(ServerPlayer player, String targetPlayer, ItemID itemID, float balance) {
        if(itemID == null)
        {
            ServerPlayerUtilities.printToClientConsole(player,BankSystemTextMessages.getInvalidItemIDMessage("null"));
            return Command.SINGLE_SUCCESS;
        }

        BankAccount bankAccount = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getOrCreatePersonalBankAccount(targetPlayer);
        if(bankAccount == null)
        {
            ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankNotFoundMessage(player.getName().getString(), MoneyItem.getName()));
            return Command.SINGLE_SUCCESS;
        }


        boolean hasBank = bankAccount.hasBank(itemID);
        if(hasBank)
        {
            ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankAlreadyExistsMessage(player.getName().getString(), itemID.getName()));
            return Command.SINGLE_SUCCESS;
        }

        IBank bank = bankAccount.createBank(itemID, balance);
        if(bank == null)
        {
            ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getCantCreateBankMessage(targetPlayer, itemID.getName()));
            return Command.SINGLE_SUCCESS;
        }
        else
            ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankCreatedMessage(targetPlayer, itemID.getName())+"\n"+bank.toStringNoOwner());
        return Command.SINGLE_SUCCESS;
    }
    private static int bank_setBalance(ServerPlayer player,String targetPlayer, ItemID itemID, float balance) {
        //String orgItemID = MoneyBank.compatibilityMoneyItemIDConvert(itemID);
        //itemID = ItemUtilities.getNormalizedItemID(orgItemID);
        if(itemID == null)
        {
            ServerPlayerUtilities.printToClientConsole(player,BankSystemTextMessages.getInvalidItemIDMessage("null"));
            return Command.SINGLE_SUCCESS;
        }

        BankAccount bankAccount = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getOrCreatePersonalBankAccount(targetPlayer);
        if(bankAccount == null)
        {
            ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankNotFoundMessage(targetPlayer, MoneyItem.getName()));
            return Command.SINGLE_SUCCESS;
        }
        IBank bank = bankAccount.getBank(itemID);
        if(bank == null) {
            ServerPlayerUtilities.printToClientConsole(player,BankSystemTextMessages.getBankNotFoundMessage(targetPlayer,itemID.getName()));
            return Command.SINGLE_SUCCESS;
        }
        bank.setBalance(bank.convertToRawAmount(balance));
        //ServerPlayerUtilities.printToClientConsole(player,BankSystemTextMessages.getSetBalanceMessage(balance, player.getName().getString(), itemID) + "\n"+bank.toStringNoOwner());
        return Command.SINGLE_SUCCESS;
    }

    private static int bank_delete(ServerPlayer player,String targetPlayer, ItemID itemID)
    {
        //String orgItemID = itemID;
        //itemID = ItemUtilities.getNormalizedItemID(itemID);
        if(itemID == null)
        {
            ServerPlayerUtilities.printToClientConsole(player,BankSystemTextMessages.getInvalidItemIDMessage("null"));
            return Command.SINGLE_SUCCESS;
        }
        BankAccount bankAccount = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getPersonalBankAccount(targetPlayer);
        if(bankAccount == null)
        {
            ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankNotFoundMessage(targetPlayer, MoneyItem.getName()));
            return Command.SINGLE_SUCCESS;
        }
        if(!bankAccount.hasBank(itemID)) {
            ServerPlayerUtilities.printToClientConsole(player,BankSystemTextMessages.getBankNotFoundMessage(targetPlayer,itemID.getName()));
            return Command.SINGLE_SUCCESS;
        }

        bankAccount.removeBank(itemID);
        ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankDeletedMessage(targetPlayer, itemID.getName()));
        return Command.SINGLE_SUCCESS;
    }
}
