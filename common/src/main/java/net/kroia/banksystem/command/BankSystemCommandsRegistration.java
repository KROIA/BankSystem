package net.kroia.banksystem.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.command.IAsyncBankSystemCommandHandler;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class BankSystemCommandsRegistration {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BankSystemCommandsRegistration.BACKEND_INSTANCES = backend;
    }


    private static IAsyncBankSystemCommandHandler handler()
    {
        return BACKEND_INSTANCES.COMMAND_HANDLER.getAsync();
    }
    
    // Method to register commands
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        //BACKEND_INSTANCES.LOGGER.info("Registering commands");

        // /bankSystem manage                                    - Open bank settings GUI to manage the bankable items
        // /bankSystem testScreen                                    - Open thest screen for development
        // /bankSystem setBankSystemAdminMode <ON/OFF>
        // /bankSystem setBankSystemAdminMode <playerName> <ON/OFF>
        // /bankSystem allowItem <itemID>                             - Makes the itemID available for bank accounts
        // /bankSystem allowItemInHand                                - Makes the item in the player's hand available for bank accounts
        // /bankSystem disallowItem <itemID>                          - Makes the itemID unavailable for bank accounts
        // /bankSystem disallowItemInHand                             - Makes the item in the player's hand unavailable for bank accounts
        //// /bankSystem setStartingBalance                             - Set the starting money balance for new players
        //// /bankSystem setItemTransferTickInterval                    - Set the amount of ticks it uses for a item to be transferred in the bank terminal block. If set to 0, it will be instant.
        //// /bankSystem save                                           - Save all bank data and settings
        //// /bankSystem saveBankaccounts                               - Save all bank data
        //// /bankSystem saveSettings                                   - Save BankSystemMod settings to file
        //// /bankSystem loadBankaccounts                               - Load all bank data from file
        //// /bankSystem loadSettings                                   - Load BankSystemMod settings from file
        dispatcher.register(
                Commands.literal("banksystem")
                .then(Commands.literal("testScreen")
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            ServerPlayer player = source.getPlayerOrException();
                            handler().banksystem_testScreen_async(player.getUUID());
                            return Command.SINGLE_SUCCESS;
                        })
                )
                .then(Commands.literal("manage")
                        //.requires(source -> source.hasPermission(2))
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            ServerPlayer player = source.getPlayerOrException();
                            handler().banksystem_manage_async(player.getUUID());
                            return Command.SINGLE_SUCCESS;
                        })
                )
                .then(Commands.literal("op")
                        .requires(source -> source.hasPermission(2)) // Admin-only
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            ServerPlayer player = source.getPlayerOrException();
                            handler().banksystem_setBankSystemAdminMode_async(player.getUUID(), true);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("username", StringArgumentType.string()).suggests((context, builder) -> getPlayerNamesSuggestion(builder))
                                        .executes(context -> {
                                            CommandSourceStack source = context.getSource();
                                            ServerPlayer player = source.getPlayerOrException();
                                            String toPlayer = StringArgumentType.getString(context, "username");
                                            handler().banksystem_setBankSystemAdminMode_user_async(player.getUUID(), toPlayer, true);
                                            return Command.SINGLE_SUCCESS;
                                        })
                        )
                )
                .then(Commands.literal("deop")
                        .requires(source -> source.hasPermission(2)) // Admin-only
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            ServerPlayer player = source.getPlayerOrException();
                            handler().banksystem_setBankSystemAdminMode_async(player.getUUID(), false);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("username", StringArgumentType.string()).suggests((context, builder) -> getPlayerNamesSuggestion(builder))
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    ServerPlayer player = source.getPlayerOrException();
                                    String toPlayer = StringArgumentType.getString(context, "username");
                                    handler().banksystem_setBankSystemAdminMode_user_async(player.getUUID(), toPlayer, false);
                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                )
                .then(Commands.literal("allowItem")
                        //.requires(source -> source.hasPermission(2))
                        .then(Commands.argument("itemID", StringArgumentType.string())
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                    // Get arguments
                                    String itemIDStr = StringArgumentType.getString(context, "itemID");
                                    ItemStack itemStack = ItemUtilities.createItemStackFromId(itemIDStr);
                                    if (itemStack == ItemStack.EMPTY) {
                                        ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getInvalidItemIDMessage(itemIDStr));
                                        return Command.SINGLE_SUCCESS;
                                    }
                                    CompletableFuture<ItemID> itemIDFuture = ItemID.getOrRegisterFromItemStackServerSide(itemStack);
                                    itemIDFuture.thenAccept(id -> {
                                        handler().banksystem_allowItem_async(player.getUUID(), id);
                                    });
                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                )
                .then(Commands.literal("allowItemInHand")
                        //.requires(source -> source.hasPermission(2))
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();

                            // Get arguments
                            ItemStack itemStack = player.getMainHandItem();
                            if (itemStack.isEmpty()) {
                                ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getNoItemInHandMessage());
                                return Command.SINGLE_SUCCESS;
                            }
                            CompletableFuture<ItemID> itemIDFuture = ItemID.getOrRegisterFromItemStackServerSide(itemStack);
                            itemIDFuture.thenAccept(id -> {
                                handler().banksystem_allowItem_async(player.getUUID(), id);
                            });
                            return Command.SINGLE_SUCCESS;
                        })

                )
                .then(Commands.literal("disallowItem")
                        //.requires(source -> source.hasPermission(2))
                        .then(Commands.argument("itemID", StringArgumentType.string())
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayerOrException();

                                    // Get arguments
                                    String itemIDStr = StringArgumentType.getString(context, "itemID");
                                    ItemStack itemStack = ItemUtilities.createItemStackFromId(itemIDStr);
                                    if (itemStack == ItemStack.EMPTY) {
                                        ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getInvalidItemIDMessage(itemIDStr));
                                        return Command.SINGLE_SUCCESS;
                                    }
                                    CompletableFuture<ItemID> itemIDFuture = ItemID.getOrRegisterFromItemStackServerSide(itemStack);
                                    itemIDFuture.thenAccept(id -> {
                                        handler().banksystem_disallowItem_async(player.getUUID(), id);
                                    });

                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                )
                .then(Commands.literal("disallowItemInHand")
                        //.requires(source -> source.hasPermission(2))
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();

                            // Get arguments
                            ItemStack itemStack = player.getMainHandItem();
                            if (itemStack.isEmpty()) {
                                ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getNoItemInHandMessage());
                                return Command.SINGLE_SUCCESS;
                            }
                            CompletableFuture<ItemID> itemIDFuture = ItemID.getOrRegisterFromItemStackServerSide(itemStack);
                            itemIDFuture.thenAccept(id -> {
                                handler().banksystem_disallowItem_async(player.getUUID(), id);
                            });
                            return Command.SINGLE_SUCCESS;
                        })
                )
                .then(Commands.literal("serverInfo")
                        //.requires(source -> source.hasPermission(2))
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            handler().banksystem_serverInfo_async(player.getUUID());
                            return Command.SINGLE_SUCCESS;
                        })
                )
                .then(Commands.literal("serverNetworkInfo")
                        //.requires(source -> source.hasPermission(2))
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            handler().banksystem_serverNetworkInfo_async(player.getUUID());
                            return Command.SINGLE_SUCCESS;
                        })
                )
                /*.then(Commands.literal("setStartingBalance")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("amount", FloatArgumentType.floatArg(0))
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    ServerPlayer player = source.getPlayerOrException();
                                    CompletableFuture<Boolean> isBanksystemAdmin = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync().isBanksystemAdminAsync(player.getUUID());
                                    isBanksystemAdmin.thenAccept(isAdmin -> {
                                        if (!isAdmin) {
                                            ServerPlayerUtilities.printToClientConsole("This command is only for BankSystem admins!");
                                            return;
                                        }

                                        // Get arguments
                                        float amount = FloatArgumentType.getFloat(context, "amount");
                                        long rawAmount = ServerBank.convertToRawAmountStatic(amount);
                                        BACKEND_INSTANCES.SERVER_SETTINGS.PLAYER.STARTING_BALANCE.set(rawAmount);
                                        ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankSettingStartBalanceSetMessage(ServerBank.getFormattedAmountStatic(amount)));
                                    });
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

                                    CompletableFuture<Boolean> isBanksystemAdmin = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync().isBanksystemAdminAsync(player.getUUID());
                                    isBanksystemAdmin.thenAccept(isAdmin -> {
                                        if (!isAdmin) {
                                            ServerPlayerUtilities.printToClientConsole("This command is only for BankSystem admins!");
                                            return;
                                        }

                                        // Get arguments
                                        int amount = IntegerArgumentType.getInteger(context, "ticks");
                                        BACKEND_INSTANCES.SERVER_SETTINGS.BANK.ITEM_TRANSFER_TICK_INTERVAL.set(amount);
                                        ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankSettingItemTransferTickIntervalMessage(amount));
                                    });
                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                )
                .then(Commands.literal("save")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            ServerPlayer player = source.getPlayerOrException();

                            CompletableFuture<Boolean> isBanksystemAdmin = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync().isBanksystemAdminAsync(player.getUUID());
                            isBanksystemAdmin.thenAccept(isAdmin -> {
                                if (!isAdmin) {
                                    ServerPlayerUtilities.printToClientConsole("This command is only for BankSystem admins!");
                                    return;
                                }

                                if (BACKEND_INSTANCES.SERVER_DATA_HANDLER.saveAll())
                                    ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankDataSavedMessage());
                                else
                                    ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankDataSaveFailedMessage());
                            });
                            return Command.SINGLE_SUCCESS;
                        })
                )
                .then(Commands.literal("saveBankaccounts")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            ServerPlayer player = source.getPlayerOrException();

                            CompletableFuture<Boolean> isBanksystemAdmin = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync().isBanksystemAdminAsync(player.getUUID());
                            isBanksystemAdmin.thenAccept(isAdmin -> {
                                if (!isAdmin) {
                                    ServerPlayerUtilities.printToClientConsole("This command is only for BankSystem admins!");
                                    return;
                                }

                                if(!BACKEND_INSTANCES.isSlaveServer)
                                {
                                    if (BACKEND_INSTANCES.SERVER_DATA_HANDLER.save_bank())
                                        ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankDataSavedMessage());
                                    else
                                        ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankDataSaveFailedMessage());
                                }
                            });
                            return Command.SINGLE_SUCCESS;
                        })
                )
                .then(Commands.literal("saveSettings")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            ServerPlayer player = source.getPlayerOrException();

                            CompletableFuture<Boolean> isBanksystemAdmin = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync().isBanksystemAdminAsync(player.getUUID());
                            isBanksystemAdmin.thenAccept(isAdmin -> {
                                if (!isAdmin) {
                                    ServerPlayerUtilities.printToClientConsole("This command is only for BankSystem admins!");
                                    return;
                                }
                                if (BACKEND_INSTANCES.SERVER_DATA_HANDLER.save_globalSettings())
                                    ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankDataSavedMessage());
                                else
                                    ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankDataSaveFailedMessage());
                            });
                            return Command.SINGLE_SUCCESS;
                        })
                )
                .then(Commands.literal("loadBankaccounts")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            ServerPlayer player = source.getPlayerOrException();

                            CompletableFuture<Boolean> isBanksystemAdmin = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync().isBanksystemAdminAsync(player.getUUID());
                            isBanksystemAdmin.thenAccept(isAdmin -> {
                                if (!isAdmin) {
                                    ServerPlayerUtilities.printToClientConsole("This command is only for BankSystem admins!");
                                    return;
                                }
                                if (BACKEND_INSTANCES.SERVER_DATA_HANDLER.load_bank())
                                    ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankDataLoadedMessage());
                                else
                                    ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankDataLoadFailedMessage());
                            });
                            return Command.SINGLE_SUCCESS;
                        })
                )
                .then(Commands.literal("loadSettings")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            ServerPlayer player = source.getPlayerOrException();
                            CompletableFuture<Boolean> isBanksystemAdmin = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync().isBanksystemAdminAsync(player.getUUID());
                            isBanksystemAdmin.thenAccept(isAdmin -> {
                                if (!isAdmin) {
                                    ServerPlayerUtilities.printToClientConsole("This command is only for BankSystem admins!");
                                    return;
                                }
                                if (BACKEND_INSTANCES.SERVER_DATA_HANDLER.load_globalSettings())
                                    ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankDataLoadedMessage());
                                else
                                    ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankDataLoadFailedMessage());
                            });
                            return Command.SINGLE_SUCCESS;
                        })
                )*/
        );

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
                            handler().money_async(player.getUUID());
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("add")
                                //.requires(source -> source.hasPermission(2)) // Admin-only for adding money
                                .then(Commands.argument("amount", FloatArgumentType.floatArg(0, Long.MAX_VALUE))
                                        .executes(context -> {
                                            CommandSourceStack source = context.getSource();
                                            ServerPlayer player = source.getPlayerOrException();

                                            // Get arguments
                                            String username = player.getName().getString();
                                            float amount = FloatArgumentType.getFloat(context, "amount");
                                            handler().money_add_async(player.getUUID(), amount);
                                            return Command.SINGLE_SUCCESS;
                                        })) // Add to self
                                .then(Commands.argument("username", StringArgumentType.string()).suggests((context, builder) -> getPlayerNamesSuggestion(builder))
                                                .then(Commands.argument("amount", FloatArgumentType.floatArg(0, Long.MAX_VALUE))
                                                        .executes(context -> {
                                                            CommandSourceStack source = context.getSource();
                                                            ServerPlayer player = source.getPlayerOrException();

                                                            // Get arguments
                                                            String username = StringArgumentType.getString(context, "username");
                                                            float amount = FloatArgumentType.getFloat(context, "amount");
                                                            handler().money_add_user_async(player.getUUID(), username, amount);
                                                            return Command.SINGLE_SUCCESS;
                                                        })
                                                )
                                )
                        )
                        .then(Commands.literal("set")
                                //.requires(source -> source.hasPermission(2)) // Admin-only for adding money
                                .then(Commands.argument("amount", FloatArgumentType.floatArg(0, Long.MAX_VALUE))
                                        .executes(context -> {
                                            ServerPlayer player = context.getSource().getPlayerOrException();

                                            // Get arguments
                                            float amount = FloatArgumentType.getFloat(context, "amount");
                                            handler().money_set_async(player.getUUID(), amount);
                                            return Command.SINGLE_SUCCESS;
                                        })) // Add to self
                                .then(Commands.argument("username", StringArgumentType.string()).suggests((context, builder) -> getPlayerNamesSuggestion(builder))
                                                .then(Commands.argument("amount", FloatArgumentType.floatArg(0, Long.MAX_VALUE))
                                                        .executes(context -> {
                                                            ServerPlayer player = context.getSource().getPlayerOrException();

                                                            // Get arguments
                                                            String username = StringArgumentType.getString(context, "username");
                                                            float amount = FloatArgumentType.getFloat(context, "amount");

                                                            handler().money_set_user_async(player.getUUID(), username, amount);
                                                            return Command.SINGLE_SUCCESS;
                                                        })
                                                )
                                )
                        )
                        .then(Commands.literal("remove")
                                //.requires(source -> source.hasPermission(2)) // Admin-only for adding money
                                .then(Commands.argument("amount", FloatArgumentType.floatArg(0, Long.MAX_VALUE))
                                        .executes(context -> {
                                            ServerPlayer player = context.getSource().getPlayerOrException();

                                            // Get arguments
                                            float amount = FloatArgumentType.getFloat(context, "amount");

                                            // Execute the command on the server_sender
                                            handler().money_remove_async(player.getUUID(), amount);
                                            return Command.SINGLE_SUCCESS;
                                        })) // Add to self
                                .then(Commands.argument("username", StringArgumentType.string()).suggests((context, builder) -> getPlayerNamesSuggestion(builder))
                                                .then(Commands.argument("amount", FloatArgumentType.floatArg(0, Long.MAX_VALUE))
                                                        .executes(context -> {
                                                            CommandSourceStack source = context.getSource();
                                                            ServerPlayer player = source.getPlayerOrException();

                                                            // Get arguments
                                                            String username = StringArgumentType.getString(context, "username");
                                                            float amount = FloatArgumentType.getFloat(context, "amount");

                                                            // Execute the command on the server_sender
                                                            handler().money_remove_user_async(player.getUUID(), username, amount);
                                                            return Command.SINGLE_SUCCESS;
                                                        })
                                                )
                                )
                        )
                        .then(Commands.literal("send")
                                .then(Commands.argument("username", StringArgumentType.string()).suggests((context, builder) -> getPlayerNamesSuggestion(builder))
                                                .then(Commands.argument("amount", FloatArgumentType.floatArg(0, Long.MAX_VALUE))
                                                        .executes(context -> {
                                                            CommandSourceStack source = context.getSource();
                                                            ServerPlayer player = source.getPlayerOrException();

                                                            // Get arguments
                                                            String toPlayer = StringArgumentType.getString(context, "username");
                                                            float amount = FloatArgumentType.getFloat(context, "amount");

                                                            // Execute the command on the server_sender
                                                            handler().money_send_user_async(player.getUUID(), toPlayer, amount);
                                                            return Command.SINGLE_SUCCESS;
                                                        })
                                                )
                                )
                        )
                        .then(Commands.literal("circulation")
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                    handler().money_circulation_async(player.getUUID());
                                    return Command.SINGLE_SUCCESS;
                                })
                        )


        );


        // /bank                                                - Show bank balance (money and items)
        // /bank enableNotifications                            - Enables bank notifications on transactions
        // /bank disableNotifications                           - Disables bank notifications on transactions
        // /bank manage                                         - Opens the management window to manage own bank accounts
        // /bank manage <accountname>                           - Opens the management window to manage the specific bank account
        // /bank create <accountname>                           - Create a new bank account with the given name
        // /bank <username> manage                              - Opens the management window to manage personal bank of the specific user
        // /bank <username> show                                - Show bank balance of a player
        //// /bank <username> create <itemID> <amount>            - Create a bank for another player
        //// /bank <username> setBalance <itemID> <amount>        - Set balance of a bank for another player
        //// /bank <username> delete <itemID>                     - Delete a bank for another player

        dispatcher.register(
                Commands.literal("bank")
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            ServerPlayer player = source.getPlayerOrException();

                            // Execute the balance command on the server_sender
                            handler().bank_show_user_async(player.getUUID(), player.getName().getString());
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("enableNotifications")
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    ServerPlayer player = source.getPlayerOrException();
                                    handler().bank_enableNotifications_async(player.getUUID());
                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                        .then(Commands.literal("disableNotifications")
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    ServerPlayer player = source.getPlayerOrException();
                                    handler().bank_disableNotifications_async(player.getUUID());
                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                        .then(Commands.literal("manage")
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    ServerPlayer player = source.getPlayerOrException();
                                    handler().bank_manage_async(player.getUUID());
                                    return Command.SINGLE_SUCCESS;
                                })
                                .then(Commands.argument("accountname", StringArgumentType.string())
                                        .executes(context -> {
                                            CommandSourceStack source = context.getSource();
                                            ServerPlayer player = source.getPlayerOrException();
                                            String accountName = StringArgumentType.getString(context, "accountname");
                                            handler().bank_manage_account_async(player.getUUID(), accountName);
                                            return Command.SINGLE_SUCCESS;
                                        })
                                )
                        )
                        .then(Commands.literal("create")
                                .then(Commands.argument("accountname", StringArgumentType.string())
                                        .executes(context -> {
                                            CommandSourceStack source = context.getSource();
                                            ServerPlayer player = source.getPlayerOrException();
                                            String accountName = StringArgumentType.getString(context, "accountname");
                                            handler().bank_create_async(player.getUUID(), accountName);
                                            return Command.SINGLE_SUCCESS;
                                        })
                                )
                        )
                        .then(Commands.argument("username", StringArgumentType.string()).suggests((context, builder) -> getPlayerNamesSuggestion(builder))
                                        //.requires(source -> source.hasPermission(2))
                                        .then(Commands.literal("manage")
                                                .executes(context -> {
                                                    UUID playerUUID = context.getSource().getPlayerOrException().getUUID();
                                                    String username = StringArgumentType.getString(context, "username");
                                                    CompletableFuture<Integer> personalBankAccountNr = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync().getPersonalBankAccountNrAsync(username);
                                                    personalBankAccountNr.thenAccept(bankAccountNr -> {
                                                        handler().bank_manage_account_async(playerUUID, bankAccountNr);
                                                    });
                                                    return Command.SINGLE_SUCCESS;
                                                })
                                        )
                                        .then(Commands.literal("show")
                                                .executes(context -> {
                                                    CommandSourceStack source = context.getSource();
                                                    ServerPlayer player = source.getPlayerOrException();
                                                    String username = StringArgumentType.getString(context, "username");
                                                    handler().bank_show_user_async(player.getUUID(), username);
                                                    return Command.SINGLE_SUCCESS;
                                                })
                                        )
                                        /*.then(Commands.literal("create")
                                                .then(Commands.argument("itemID", StringArgumentType.string())
                                                        .then(Commands.argument("balance", FloatArgumentType.floatArg(0))
                                                                .executes(context -> {
                                                                    CommandSourceStack source = context.getSource();
                                                                    ServerPlayer player = source.getPlayerOrException();

                                                                    // Get arguments
                                                                    String itemID = StringArgumentType.getString(context, "itemID");
                                                                    float balance = FloatArgumentType.getFloat(context, "balance");
                                                                    String username = StringArgumentType.getString(context, "username");

                                                                    CompletableFuture<Boolean> isBanksystemAdmin = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync().isBanksystemAdminAsync(player.getUUID());
                                                                    isBanksystemAdmin.thenAccept(isAdmin -> {
                                                                        if (!isAdmin) {
                                                                            ServerPlayerUtilities.printToClientConsole("This command is only for BankSystem admins!");
                                                                            return;
                                                                        }

                                                                        ItemStack itemStack = ItemUtilities.createItemStackFromId(itemID);
                                                                        if (itemStack == ItemStack.EMPTY) {
                                                                            ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getInvalidItemIDMessage(itemID));
                                                                            return;
                                                                        }
                                                                        ItemID itemIDObj = ItemID.getFromItemStack(itemStack);

                                                                        // Execute the command on the server_sender
                                                                        long rawAmount = ServerBank.convertToRawAmountStatic(balance);
                                                                        bank_create(player, username, itemIDObj, rawAmount);
                                                                    });
                                                                    return Command.SINGLE_SUCCESS;
                                                                })
                                                        )
                                                )
                                        )
                                        .then(Commands.literal("setBalance")
                                                .then(Commands.argument("itemID", StringArgumentType.string())
                                                        .then(Commands.argument("balance", FloatArgumentType.floatArg(0, Long.MAX_VALUE))
                                                                .executes(context -> {
                                                                    CommandSourceStack source = context.getSource();
                                                                    ServerPlayer player = source.getPlayerOrException();

                                                                    // Get arguments
                                                                    String itemID = StringArgumentType.getString(context, "itemID");
                                                                    float balance = FloatArgumentType.getFloat(context, "balance");
                                                                    String username = StringArgumentType.getString(context, "username");

                                                                    CompletableFuture<Boolean> isBanksystemAdmin = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync().isBanksystemAdminAsync(player.getUUID());
                                                                    isBanksystemAdmin.thenAccept(isAdmin -> {
                                                                        if (!isAdmin) {
                                                                            ServerPlayerUtilities.printToClientConsole("This command is only for BankSystem admins!");
                                                                            return;
                                                                        }


                                                                        ItemStack itemStack = ItemUtilities.createItemStackFromId(itemID);
                                                                        if (itemStack == ItemStack.EMPTY) {
                                                                            ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getInvalidItemIDMessage(itemID));
                                                                            return;
                                                                        }
                                                                        ItemID itemIDObj = ItemID.getFromItemStack(itemStack);

                                                                        // Execute the command on the server_sender
                                                                        long realBalance = ServerBank.convertToRawAmountStatic(balance);
                                                                        bank_setBalance(player, username, itemIDObj, realBalance);
                                                                    });
                                                                    return Command.SINGLE_SUCCESS;
                                                                })
                                                        )
                                                )
                                        )
                                        .then(Commands.literal("delete")
                                                .then(Commands.argument("itemID", StringArgumentType.string())
                                                        .executes(context -> {
                                                            CommandSourceStack source = context.getSource();
                                                            ServerPlayer player = source.getPlayerOrException();

                                                            // Get arguments
                                                            String itemID = StringArgumentType.getString(context, "itemID");
                                                            String username = StringArgumentType.getString(context, "username");

                                                            CompletableFuture<Boolean> isBanksystemAdmin = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync().isBanksystemAdminAsync(player.getUUID());
                                                            isBanksystemAdmin.thenAccept(isAdmin -> {
                                                                if (!isAdmin) {
                                                                    ServerPlayerUtilities.printToClientConsole("This command is only for BankSystem admins!");
                                                                    return;
                                                                }

                                                                // Execute the command on the server_sender
                                                                ItemStack itemStack = ItemUtilities.createItemStackFromId(itemID);
                                                                if (itemStack == ItemStack.EMPTY) {
                                                                    ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getInvalidItemIDMessage(itemID));
                                                                    return;
                                                                }
                                                                ItemID itemIDObj = ItemID.getFromItemStack(itemStack);

                                                                bank_delete(player, username, itemIDObj);
                                                            });
                                                            return Command.SINGLE_SUCCESS;
                                                        })
                                                )
                                        )*/
                        )




        );
    }


    private static CompletableFuture<Suggestions> getPlayerNamesSuggestion(SuggestionsBuilder builder)
    {
        CompletableFuture<Suggestions> future = new CompletableFuture<>();
        BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync().getBankManagerUserMapDataAsync().thenAccept(userMapData ->
        {
            userMapData.userMap.values().forEach(userData -> {
                builder.suggest("\""+userData.userName+"\"");
            });
            future.complete(builder.build());
        });
        return future;
    }

    /*private static int showBalance(ServerPlayer player) {
        ISyncServerBankManager bankManager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
        ISyncServerBank bank = bankManager.getOrCreatePersonalBank(player.getUUID(), MoneyItem.getItemID());
        if(bank == null)
        {
            ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankNotFoundMessage(player.getName().getString(), MoneyItem.getName()));
            return Command.SINGLE_SUCCESS;
        }
        long balance = bank.getBalance();
        ServerPlayerUtilities.printToClientConsole(player,BankSystemTextMessages.getYourBalanceMessage(ServerBank.getFormattedAmountStatic(balance)));
        return Command.SINGLE_SUCCESS;
    }

    private static int bank_show(ServerPlayer player, String targetPlayer) {
        IAsyncBankManager bankManager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync();
        CompletableFuture<@Nullable IAsyncBankAccount> account = bankManager.getOrCreatePersonalBankAccountAsync(targetPlayer);
        if(account == null)
        {
            ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankNotFoundMessage(targetPlayer, MoneyItem.getName()));
            return Command.SINGLE_SUCCESS;
        }
        ServerPlayerUtilities.printToClientConsole(player, account.toString());
        return Command.SINGLE_SUCCESS;
    }*/



    private static void info(String msg)
    {
        BACKEND_INSTANCES.LOGGER.info("[Commands] " + msg);
    }
    private static void error(String msg)
    {
        BACKEND_INSTANCES.LOGGER.error("[Commands] " + msg);
    }
    private static void error(String msg, Throwable e)
    {
        BACKEND_INSTANCES.LOGGER.error("[Commands] " + msg, e);
    }
    private static void warn(String msg)
    {
        BACKEND_INSTANCES.LOGGER.warn("[Commands] " + msg);
    }
    private static void debug(String msg)
    {
        BACKEND_INSTANCES.LOGGER.debug("[Commands] " + msg);
    }
}
