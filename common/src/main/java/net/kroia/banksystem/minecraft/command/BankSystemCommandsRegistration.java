package net.kroia.banksystem.minecraft.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankmanager.IServerBankManager;
import net.kroia.banksystem.api.command.IAsyncBankSystemCommandHandler;
import net.kroia.banksystem.api.command.IServerBankSystemCommandHandler;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.banking.company.CompanyManager;
import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.networking.ui.SyncOpenGUIPacket;
import net.kroia.modutilities.testing.TestCommandRegistration;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.kroia.modutilities.networking.multi_server.MultiServerManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.nio.file.InvalidPathException;
import java.util.List;
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
    private static IServerBankSystemCommandHandler masterHandler()
    {
        return BACKEND_INSTANCES.COMMAND_HANDLER.getSync();
    }
    private static boolean isMaster()
    {
        return BACKEND_INSTANCES.COMMAND_HANDLER.getSync() != null;
    }
    
    // Method to register commands
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {

        // /banksystem manage                                         - Open bank settings GUI to manage the bankable items
        // /banksystem trust <slaveServerID>                          - Ads the slave server ID to the trusted list
        // /banksystem untrust <slaveServerID>                        - Removes the slave ID from the trusted list
        // /banksystem setBankSystemAdminMode <ON/OFF>
        // /banksystem setBankSystemAdminMode <playerName> <ON/OFF>
        // /banksystem allowItem <itemID>                             - Makes the itemID available for bank accounts
        // /banksystem allowItemInHand                                - Makes the item in the player's hand available for bank accounts
        // /banksystem disallowItem <itemID>                          - Makes the itemID unavailable for bank accounts
        // /banksystem disallowItemInHand                             - Makes the item in the player's hand unavailable for bank accounts
        // /banksystem exportrecipes                                  - Export all crafting recipes as PNG images
        dispatcher.register(
                Commands.literal("banksystem")
                .then(Commands.literal("manage")
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            ServerPlayer player = source.getPlayerOrException();
                            handler().banksystem_manage_async(player.getUUID());
                            return Command.SINGLE_SUCCESS;
                        })
                )
                .then(Commands.literal("trust")
                        .requires(source -> source.hasPermission(2)) // Admin-only
                        .then(Commands.argument("slaveID", StringArgumentType.string()).suggests((context, builder) -> getSlaveServerIDSuggestion(builder))
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    ServerPlayer player = source.getPlayerOrException();
                                    String slaveID = StringArgumentType.getString(context, "slaveID");
                                    if(isMaster())
                                        masterHandler().banksystem_setSlaveServerTrusted(player.getUUID(), slaveID, true);
                                    else
                                        ServerPlayerUtilities.printToClientConsole(player, "This command can only be used on the master server!");

                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                )
                .then(Commands.literal("untrust")
                        .requires(source -> source.hasPermission(2)) // Admin-only
                        .then(Commands.argument("slaveID", StringArgumentType.string()).suggests((context, builder) -> getSlaveServerIDSuggestion(builder))
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    ServerPlayer player = source.getPlayerOrException();
                                    String slaveID = StringArgumentType.getString(context, "slaveID");
                                    if(isMaster())
                                        masterHandler().banksystem_setSlaveServerTrusted(player.getUUID(), slaveID, false);
                                    else
                                        ServerPlayerUtilities.printToClientConsole(player, "This command can only be used on the master server!");

                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                )
                .then(Commands.literal("op")
                        .requires(source -> source.hasPermission(2)) // Admin-only
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            ServerPlayer player = source.getPlayerOrException();
                            if(isMaster())
                                masterHandler().banksystem_setBankSystemAdminMode(player.getUUID(), true);
                            else
                                ServerPlayerUtilities.printToClientConsole(player, "This command can only be used on the master server!");
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("username", StringArgumentType.string()).suggests((context, builder) -> getPlayerNamesSuggestion(builder))
                                        .executes(context -> {
                                            CommandSourceStack source = context.getSource();
                                            ServerPlayer player = source.getPlayerOrException();
                                            String toPlayer = StringArgumentType.getString(context, "username");
                                            if(isMaster())
                                                masterHandler().banksystem_setBankSystemAdminMode_user(player.getUUID(), toPlayer, true);
                                            else
                                                ServerPlayerUtilities.printToClientConsole(player, "This command can only be used on the master server!");

                                            return Command.SINGLE_SUCCESS;
                                        })
                        )
                )
                .then(Commands.literal("deop")
                        .requires(source -> source.hasPermission(2)) // Admin-only
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            ServerPlayer player = source.getPlayerOrException();
                            if(isMaster())
                                masterHandler().banksystem_setBankSystemAdminMode(player.getUUID(), false);
                            else
                                ServerPlayerUtilities.printToClientConsole(player, "This command can only be used on the master server!");

                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("username", StringArgumentType.string()).suggests((context, builder) -> getPlayerNamesSuggestion(builder))
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    ServerPlayer player = source.getPlayerOrException();
                                    String toPlayer = StringArgumentType.getString(context, "username");
                                    if(isMaster())
                                        masterHandler().banksystem_setBankSystemAdminMode_user(player.getUUID(), toPlayer, false);
                                    else
                                        ServerPlayerUtilities.printToClientConsole(player, "This command can only be used on the master server!");
                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                )
                .then(Commands.literal("allowItem")
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
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            handler().banksystem_serverInfo_async(player.getUUID());
                            return Command.SINGLE_SUCCESS;
                        })
                )
                .then(Commands.literal("serverNetworkInfo")
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            handler().banksystem_serverNetworkInfo_async(player.getUUID());
                            return Command.SINGLE_SUCCESS;
                        })
                )
                // Dev-only: export all BankSystem crafting recipes as PNG images
                .then(Commands.literal("exportrecipes")
                        .requires(source -> source.hasPermission(2) && BankSystemMod.ENABLE_DEV_FEATURES)
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            ServerPlayer player = source.getPlayerOrException();
                            SyncOpenGUIPacket.send_exportRecipes(player);
                            return Command.SINGLE_SUCCESS;
                        })
                )
                // Task #42 (v2.0.7) — op-only backup coordination for external tar-based backups.
                // /banksystem backup pause                                     - Block the db-worker so tar sees a stable file
                // /banksystem backup resume                                    - Release the db-worker
                // /banksystem backup status                                    - Report IDLE / PAUSED (for Xs)
                // /banksystem backup snapshot <path>                           - Write a consistent DB copy to <path>
                .then(Commands.literal("backup")
                        .requires(source -> source.hasPermission(2)) // op-only, matches vanilla save-off
                        .then(Commands.literal("pause")
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    DatabaseManager db = BACKEND_INSTANCES.DATABASE_MANAGER;
                                    if (db == null) {
                                        source.sendFailure(Component.literal("[BankSystem] database not initialized (slave server or master pre-init)"));
                                        return 0;
                                    }
                                    MinecraftServer server = source.getServer();
                                    db.beginBackupPause().thenAccept(ok -> {
                                        Runnable feedback = () -> source.sendSuccess(() -> Component.literal(ok
                                                ? "[BankSystem] db-worker paused for backup"
                                                : "[BankSystem] db-worker was already paused"), true);
                                        if (server != null) server.execute(feedback);
                                        else feedback.run();
                                    });
                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                        .then(Commands.literal("resume")
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    DatabaseManager db = BACKEND_INSTANCES.DATABASE_MANAGER;
                                    if (db == null) {
                                        source.sendFailure(Component.literal("[BankSystem] database not initialized (slave server or master pre-init)"));
                                        return 0;
                                    }
                                    boolean ok = db.endBackupPause();
                                    source.sendSuccess(() -> Component.literal(ok
                                            ? "[BankSystem] db-worker resumed"
                                            : "[BankSystem] db-worker was not paused"), true);
                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                        .then(Commands.literal("status")
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    DatabaseManager db = BACKEND_INSTANCES.DATABASE_MANAGER;
                                    if (db == null) {
                                        source.sendFailure(Component.literal("[BankSystem] database not initialized (slave server or master pre-init)"));
                                        return 0;
                                    }
                                    DatabaseManager.BackupState state = db.getBackupState();
                                    String line = "[BankSystem] backup state: " + state;
                                    if (state == DatabaseManager.BackupState.PAUSED) {
                                        line += " (for " + (db.getPausedForMs() / 1000L) + "s)";
                                    }
                                    final String finalLine = line;
                                    source.sendSuccess(() -> Component.literal(finalLine), false);
                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                        .then(Commands.literal("snapshot")
                                .then(Commands.argument("path", StringArgumentType.string())
                                        .executes(context -> {
                                            CommandSourceStack source = context.getSource();
                                            DatabaseManager db = BACKEND_INSTANCES.DATABASE_MANAGER;
                                            if (db == null) {
                                                source.sendFailure(Component.literal("[BankSystem] database not initialized (slave server or master pre-init)"));
                                                return 0;
                                            }
                                            String p = StringArgumentType.getString(context, "path");
                                            Path target;
                                            try {
                                                target = Path.of(p);
                                            } catch (InvalidPathException e) {
                                                source.sendFailure(Component.literal("[BankSystem] invalid snapshot path: " + e.getMessage()));
                                                return 0;
                                            }
                                            MinecraftServer server = source.getServer();
                                            db.snapshotTo(target).thenAccept(ok -> {
                                                Runnable feedback = () -> source.sendSuccess(() -> Component.literal(ok
                                                        ? "[BankSystem] snapshot written to " + p
                                                        : "[BankSystem] snapshot failed (see server log)"), true);
                                                if (server != null) server.execute(feedback);
                                                else feedback.run();
                                            });
                                            return Command.SINGLE_SUCCESS;
                                        })
                                )
                        )
                )
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
                                .then(Commands.argument("amount", FloatArgumentType.floatArg(0, Long.MAX_VALUE))
                                        .executes(context -> {
                                            CommandSourceStack source = context.getSource();
                                            ServerPlayer player = source.getPlayerOrException();

                                            // Get arguments
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
                        )
        );

        // Task #43 (v2.1.0) — /company command tree (Phase 1: Company foundation).
        registerCompanyCommands(dispatcher);

        // Task #54 (v2.1.0) — /banksystem symbols admin commands (master-gated, op-only).
        registerSymbolCommands(dispatcher);

        boolean isSlave = BACKEND_INSTANCES != null && BACKEND_INSTANCES.isSlaveServer;
        if (BankSystemMod.ENABLE_DEV_FEATURES)
            TestCommandRegistration.register(dispatcher, "banksystem", "BankSystem", "banksystem", isSlave);
    }

    /**
     * Task #43 (v2.1.0). Registers the {@code /company} command tree — Phase 1 subset:
     * {@code create}, {@code transfer}, {@code dissolve}, {@code description}, {@code info}.
     * <p>
     * Master-only in this phase. Slaves print a "master only" notice — full slave-side ARRS
     * forwarding is a follow-up task (deferred from Task #43 spec item 8).
     */
    private static void registerCompanyCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("company")
                    .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.string())
                            .then(Commands.argument("maxSupply", LongArgumentType.longArg(1L, 1_000_000_000L))
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String name = StringArgumentType.getString(ctx, "name");
                                    long maxSupply = LongArgumentType.getLong(ctx, "maxSupply");
                                    CompanyCommandLogic.create(player, name, maxSupply);
                                    return Command.SINGLE_SUCCESS;
                                })
                            )
                        )
                    )
                    // transfer / dissolve / description live in the Company Management screen
                    // (Danger Zone and Overview tabs) — no chat equivalent, so there is exactly
                    // one place each of them is done.
                    .then(Commands.literal("info")
                        .then(Commands.argument("companyName", StringArgumentType.string())
                                .suggests((c, b) -> getCompanyNameSuggestion(c, b, AsyncCompanyManager.FILTER_ALL))
                            .executes(ctx -> {
                                ServerPlayer player = ctx.getSource().getPlayerOrException();
                                String companyName = StringArgumentType.getString(ctx, "companyName");
                                CompanyCommandLogic.info(player, companyName);
                                return Command.SINGLE_SUCCESS;
                            })
                        )
                    )
                    // Task #51 (v2.1.0) — /company manage <companyName>: opens the
                    // CompanyManagementScreen on the caller's client if they have MANAGE.
                    .then(Commands.literal("manage")
                        .then(Commands.argument("companyName", StringArgumentType.string())
                                .suggests((c, b) -> getCompanyNameSuggestion(c, b, AsyncCompanyManager.FILTER_MANAGE))
                            .executes(ctx -> {
                                ServerPlayer player = ctx.getSource().getPlayerOrException();
                                String companyName = StringArgumentType.getString(ctx, "companyName");
                                CompanyCommandLogic.manage(player, companyName);
                                return Command.SINGLE_SUCCESS;
                            })
                        )
                    )
                    // Stamper binding is done at the block: right-click an unbound Share Stamper
                    // to pick a company, and use Unbind in its screen or the Shares tab.
        );
    }

    /**
     * Task #43h — company-name suggestion provider, filtered by caller's rights.
     * On master, iterates {@link CompanyManager} directly. On slave, forwards to master
     * via {@link AsyncCompanyManager} — ARRS response completes the returned future.
     */
    private static CompletableFuture<Suggestions> getCompanyNameSuggestion(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
            SuggestionsBuilder builder,
            byte filterKind) {
        CommandSourceStack source = ctx.getSource();
        UUID callerUUID;
        try {
            callerUUID = source.getPlayerOrException().getUUID();
        } catch (Exception e) {
            return CompletableFuture.completedFuture(builder.build());
        }

        CompanyManager cm = CompanyManager.get();
        IServerBankSystemCommandHandler master = BACKEND_INSTANCES == null ? null
                : BACKEND_INSTANCES.COMMAND_HANDLER.getSync();
        if (cm != null && master != null) {
            // Master side — walk indices directly, no ARRS.
            java.util.Set<net.kroia.banksystem.banking.company.Company> set = switch (filterKind) {
                case AsyncCompanyManager.FILTER_FOUNDER -> cm.listCompaniesFounderedBy(callerUUID);
                case AsyncCompanyManager.FILTER_MANAGE  -> cm.listCompaniesManagedBy(callerUUID);
                default                                 -> cm.listAllCompanies();
            };
            for (net.kroia.banksystem.banking.company.Company c : set) {
                builder.suggest("\"" + c.getName() + "\"");
            }
            return CompletableFuture.completedFuture(builder.build());
        }
        // Slave — ARRS forward.
        CompletableFuture<Suggestions> future = new CompletableFuture<>();
        AsyncCompanyManager.listCompanyNamesForCallerAsync(callerUUID, filterKind)
                .thenAccept(names -> {
                    for (String n : names) builder.suggest("\"" + n + "\"");
                    future.complete(builder.build());
                });
        return future;
    }

    private static CompletableFuture<Suggestions> getPlayerNamesSuggestion(SuggestionsBuilder builder)
    {
        CompletableFuture<Suggestions> future = new CompletableFuture<>();
        BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync().getBankManagerUserMapDataAsync().thenAccept(userMapData ->
        {
            userMapData.userMap().values().forEach(userData -> {
                builder.suggest("\""+ userData.userName() +"\"");
            });
            future.complete(builder.build());
        });
        return future;
    }
    private static CompletableFuture<Suggestions> getSlaveServerIDSuggestion(SuggestionsBuilder builder)
    {
        IServerBankManager bankManager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
        if(bankManager == null)
            return CompletableFuture.completedFuture(builder.build());

        List<String> slaves = MultiServerManager.getConnectedSlaveIDs();
        for(String slave : slaves)
        {
            builder.suggest("\""+ slave +"\"");
        }
        return CompletableFuture.completedFuture(builder.build());
    }

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

    // ── Task #54 (v2.1.0) — /banksystem symbols ──────────────────────────────

    private static void registerSymbolCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("banksystem")
                .then(Commands.literal("symbols")
                    .requires(src -> src.hasPermission(2))
                    // list
                    .then(Commands.literal("list")
                        .executes(ctx -> {
                            if (!isMasterSymbols(ctx.getSource())) return 0;
                            net.kroia.banksystem.banking.company.ShareSymbolStore store =
                                    BACKEND_INSTANCES.SHARE_SYMBOL_STORE;
                            List<net.kroia.banksystem.banking.company.ShareSymbolStore.SymbolEntry> es =
                                    store.getEntries();
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "Symbols (revision " + store.getRevision() + "): " + es.size() + " total"), false);
                            for (net.kroia.banksystem.banking.company.ShareSymbolStore.SymbolEntry e : es) {
                                String hex8 = e.sha256Hex().substring(0, 8);
                                ctx.getSource().sendSuccess(() -> Component.literal(
                                        "  " + e.id() + "  ordinal=" + e.ordinal()
                                        + "  sha256=" + hex8 + "…  " + e.size() + "b"), false);
                            }
                            return Command.SINGLE_SUCCESS;
                        })
                    )
                    // add <id>
                    .then(Commands.literal("add")
                        .then(Commands.argument("id", StringArgumentType.word())
                            .executes(ctx -> {
                                if (!isMasterSymbols(ctx.getSource())) return 0;
                                String id = StringArgumentType.getString(ctx, "id");
                                String err = BACKEND_INSTANCES.SHARE_SYMBOL_STORE.adminAdd(id);
                                if (err != null) {
                                    ctx.getSource().sendFailure(Component.literal("[symbols] " + err));
                                    return 0;
                                }
                                ctx.getSource().sendSuccess(() -> Component.literal(
                                        "[symbols] Added symbol '" + id + "'."), true);
                                return Command.SINGLE_SUCCESS;
                            })
                        )
                    )
                    // remove <id>
                    .then(Commands.literal("remove")
                        .then(Commands.argument("id", StringArgumentType.word())
                            .suggests((ctx, builder) -> {
                                if (BACKEND_INSTANCES == null || BACKEND_INSTANCES.SHARE_SYMBOL_STORE == null)
                                    return builder.buildFuture();
                                BACKEND_INSTANCES.SHARE_SYMBOL_STORE.getEntries()
                                        .forEach(e -> builder.suggest(e.id()));
                                return builder.buildFuture();
                            })
                            .executes(ctx -> {
                                if (!isMasterSymbols(ctx.getSource())) return 0;
                                String id = StringArgumentType.getString(ctx, "id");
                                String err = BACKEND_INSTANCES.SHARE_SYMBOL_STORE.adminRemove(id);
                                if (err != null) {
                                    ctx.getSource().sendFailure(Component.literal("[symbols] " + err));
                                    return 0;
                                }
                                ctx.getSource().sendSuccess(() -> Component.literal(
                                        "[symbols] Removed symbol '" + id + "'. Ordinals compacted."), true);
                                return Command.SINGLE_SUCCESS;
                            })
                        )
                    )
                    // reload
                    .then(Commands.literal("reload")
                        .executes(ctx -> {
                            if (!isMasterSymbols(ctx.getSource())) return 0;
                            String result = BACKEND_INSTANCES.SHARE_SYMBOL_STORE.adminReload();
                            String msg = result != null ? result : "Reloaded — no changes.";
                            ctx.getSource().sendSuccess(() -> Component.literal("[symbols] " + msg), true);
                            return Command.SINGLE_SUCCESS;
                        })
                    )
                )
        );
    }

    private static boolean isMasterSymbols(CommandSourceStack src) {
        if (BACKEND_INSTANCES == null || BACKEND_INSTANCES.SHARE_SYMBOL_STORE == null) {
            src.sendFailure(Component.literal("[symbols] Store not initialized."));
            return false;
        }
        if (BACKEND_INSTANCES.isSlaveServer) {
            src.sendFailure(Component.literal("[symbols] Must run on the master server."));
            return false;
        }
        return true;
    }
}
