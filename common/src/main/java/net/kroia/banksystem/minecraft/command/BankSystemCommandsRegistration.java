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
import net.kroia.banksystem.util.BankSystemPermissions;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankmanager.IServerBankManager;
import net.kroia.banksystem.api.command.IAsyncBankSystemCommandHandler;
import net.kroia.banksystem.api.command.IServerBankSystemCommandHandler;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.banking.company.CompanyManager;
import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.networking.ui.SyncOpenGUIPacket;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.kroia.modutilities.discord.DiscordWebhooks;
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
                        .requires(source -> net.kroia.modutilities.PermissionCompat.hasPermission(source, BankSystemPermissions.COMMAND_TRUST, BankSystemPermissions.FALLBACK_LEVEL)) // Admin-only
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
                        .requires(source -> net.kroia.modutilities.PermissionCompat.hasPermission(source, BankSystemPermissions.COMMAND_TRUST, BankSystemPermissions.FALLBACK_LEVEL)) // Admin-only
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
                        .requires(source -> net.kroia.modutilities.PermissionCompat.hasPermission(source, BankSystemPermissions.COMMAND_OP, BankSystemPermissions.FALLBACK_LEVEL)) // Admin-only
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
                        .requires(source -> net.kroia.modutilities.PermissionCompat.hasPermission(source, BankSystemPermissions.COMMAND_OP, BankSystemPermissions.FALLBACK_LEVEL)) // Admin-only
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
                // Task #117: /banksystem freeze <account> [on|off] (no state = toggle). Admin check on the master.
                .then(Commands.literal("freeze")
                        .then(Commands.argument("account", IntegerArgumentType.integer(0))
                                .executes(ctx -> freezeCommand(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "account"), 2))
                                .then(Commands.literal("on")
                                        .executes(ctx -> freezeCommand(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "account"), 1)))
                                .then(Commands.literal("off")
                                        .executes(ctx -> freezeCommand(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "account"), 0)))
                        )
                )
                // Task #110: /banksystem audit <player|account>, /banksystem alerts on|off
                .then(Commands.literal("audit")
                        .then(Commands.argument("target", StringArgumentType.word()).suggests(BankSystemCommandsRegistration::getTargetSuggestion)
                                .executes(ctx -> auditCommand(ctx.getSource(), StringArgumentType.getString(ctx, "target")))))
                .then(Commands.literal("alerts")
                        .then(Commands.literal("on").executes(ctx -> alertsCommand(ctx.getSource(), true)))
                        .then(Commands.literal("off").executes(ctx -> alertsCommand(ctx.getSource(), false))))
                // Task #113: /banksystem auditreport <hours>; Task #116: /banksystem history <player|account>
                .then(Commands.literal("auditreport")
                        .then(Commands.argument("hours", IntegerArgumentType.integer(1, net.kroia.banksystem.banking.ledger.AuditDigest.MAX_REPORT_HOURS))
                                .executes(ctx -> auditReportCommand(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "hours")))))
                .then(Commands.literal("history")
                        .then(Commands.argument("target", StringArgumentType.word()).suggests(BankSystemCommandsRegistration::getTargetSuggestion)
                                .executes(ctx -> historyCommand(ctx.getSource(), StringArgumentType.getString(ctx, "target")))))
                // Task #146: /banksystem restorebinding <account> [item] — lift suspended external bindings
                .then(Commands.literal("restorebinding")
                        .requires(source -> BankSystemPermissions.canUseFlagGatedCommand(source, BankSystemPermissions.COMMAND_RESTORE_BINDING)) // BS-196: admin flag checked in runAsAdmin
                        .then(Commands.argument("account", IntegerArgumentType.integer(0))
                                .executes(ctx -> adminOnly(ctx.getSource(), () -> restoreBindingCommand(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "account"), null)))
                                .then(Commands.argument("item", StringArgumentType.greedyString())
                                        .executes(ctx -> adminOnly(ctx.getSource(), () -> restoreBindingCommand(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "account"),
                                                StringArgumentType.getString(ctx, "item")))))))
                // BS-235: /banksystem shares acknowledge <companyId> — accept a share excess, resume dividends
                .then(Commands.literal("shares")
                        .requires(source -> BankSystemPermissions.canUseFlagGatedCommand(source, BankSystemPermissions.COMMAND_SHARES))
                        .then(Commands.literal("acknowledge")
                                .then(Commands.argument("companyId", IntegerArgumentType.integer(0))
                                        .executes(ctx -> adminOnly(ctx.getSource(), () -> sharesAcknowledgeCommand(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "companyId")))))))
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
                        .requires(source -> net.kroia.modutilities.PermissionCompat.hasPermission(source, BankSystemPermissions.COMMAND_EXPORTRECIPES, BankSystemPermissions.FALLBACK_LEVEL) && BankSystemMod.ENABLE_DEV_FEATURES)
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
                        .requires(source -> net.kroia.modutilities.PermissionCompat.hasPermission(source, BankSystemPermissions.COMMAND_BACKUP, BankSystemPermissions.FALLBACK_LEVEL)) // op-only, matches vanilla save-off
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
                // /banksystem discord status|reload: ModUtilities' node, gated by its own permission (modutilities.command.discord, level 3)
                .then(DiscordWebhooks.command())
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
                                                    }).exceptionally(ex -> { AsyncBankSystemCommandHandler.reportFailure(playerUUID, ex); return null; });
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
    /**
     * Issue #165 — audit/history targets: the bank's known users (incl. offline; a slave asks the master
     * asynchronously) plus the online players. Unquoted: the argument is a single word.
     */
    private static CompletableFuture<Suggestions> getTargetSuggestion(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder)
    {
        java.util.Set<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(List.of(ctx.getSource().getServer().getPlayerNames()));
        return BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync().getBankManagerUserMapDataAsync()
                .completeOnTimeout(null, 2, java.util.concurrent.TimeUnit.SECONDS) // master unreachable: online players only
                .exceptionally(ex -> null)
                .thenCompose(data -> {
                    if (data != null)
                        data.userMap().values().forEach(u -> { if (u.userName() != null) names.add(u.userName()); });
                    return net.minecraft.commands.SharedSuggestionProvider.suggest(names, builder);
                });
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
                    .requires(src -> BankSystemPermissions.canUseFlagGatedCommand(src, BankSystemPermissions.COMMAND_SYMBOLS)) // BS-196: admin flag checked in adminOnly
                    // list
                    .then(Commands.literal("list")
                        .executes(ctx -> adminOnly(ctx.getSource(), () -> {
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
                        }))
                    )
                    // add <id>
                    .then(Commands.literal("add")
                        .then(Commands.argument("id", StringArgumentType.word())
                            .executes(ctx -> adminOnly(ctx.getSource(), () -> {
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
                            }))
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
                            .executes(ctx -> adminOnly(ctx.getSource(), () -> {
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
                            }))
                        )
                    )
                    // reload
                    .then(Commands.literal("reload")
                        .executes(ctx -> adminOnly(ctx.getSource(), () -> {
                            if (!isMasterSymbols(ctx.getSource())) return 0;
                            String result = BACKEND_INSTANCES.SHARE_SYMBOL_STORE.adminReload();
                            String msg = result != null ? result : "Reloaded — no changes.";
                            ctx.getSource().sendSuccess(() -> Component.literal("[symbols] " + msg), true);
                            return Command.SINGLE_SUCCESS;
                        }))
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

    /**
     * BS-196: the single BankSystem-admin gate of the flag-gated commands ({@code symbols}, {@code restorebinding}, {@code shares};
     * see {@link BankSystemPermissions#FLAG_GATED_NODES}).
     * <ul>
     *   <li>Non-player sources (console, command blocks) already passed the op-level {@code requires} check and run
     *       {@code action} right away.</li>
     *   <li>A player runs {@code action} only with the BankSystem admin flag ({@code /banksystem op}), server op or not.
     *       The flag lives on the master, so a slave asks it ({@code isBanksystemAdminAsync}); a slave without a master
     *       connection fails fast with the "not connected" message. Without the flag the player gets the admin-only
     *       message; if the master gives no answer, the "request failed or timed out" message.</li>
     * </ul>
     *
     * @param action the command's work; always runs on the server main thread
     * @return future resolving to {@code true} if {@code action} ran
     */
    public static CompletableFuture<Boolean> runAsAdmin(CommandSourceStack source, Runnable action) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            action.run();
            return CompletableFuture.completedFuture(true);
        }
        UUID uuid = player.getUUID();
        if (!net.kroia.banksystem.util.MultiServerUtils.canInteractWithBankSystem(uuid))
            return CompletableFuture.completedFuture(false);
        MinecraftServer server = source.getServer();
        CompletableFuture<Boolean> ran = new CompletableFuture<>();
        // On the master the future is already complete and server.execute runs inline on the main thread.
        BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync().isBanksystemAdminAsync(uuid)
                .whenComplete((isAdmin, failure) -> server.execute(() -> {
                    if (failure != null) {
                        AsyncBankSystemCommandHandler.reportFailure(uuid, failure); // logs + tells the player
                        ran.complete(false);
                    } else if (player.hasDisconnected()) {
                        ran.complete(false); // logged off while waiting for the master
                    } else if (!Boolean.TRUE.equals(isAdmin)) {
                        source.sendFailure(Component.literal(ServerBankSystemCommandHandler.NO_ADMIN_MESSAGE));
                        ran.complete(false);
                    } else {
                        action.run();
                        ran.complete(true);
                    }
                }));
        return ran;
    }

    /** {@link #runAsAdmin} for a Brigadier {@code executes}: the action's result when it ran inline, else 1. */
    private static int adminOnly(CommandSourceStack source, java.util.function.IntSupplier action) {
        int[] result = {Command.SINGLE_SUCCESS};
        runAsAdmin(source, () -> result[0] = action.getAsInt());
        return result[0];
    }

    /**
     * Task #117 — players go through the command handler (admin check on the master); the
     * console / command blocks act directly on the master with the console actor.
     */
    private static int freezeCommand(CommandSourceStack source, int accountNumber, int mode) {
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            handler().banksystem_freeze_async(player.getUUID(), accountNumber, mode);
            return Command.SINGLE_SUCCESS;
        }
        net.kroia.banksystem.api.bankmanager.ISyncServerBankManager bankManager =
                BACKEND_INSTANCES.SERVER_BANK_MANAGER == null ? null : BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
        if (bankManager == null || !net.kroia.modutilities.PermissionCompat.hasPermission(source, BankSystemPermissions.COMMAND_OP, BankSystemPermissions.FALLBACK_LEVEL)) {
            source.sendFailure(Component.literal("[BankSystem] freeze from the console works on the master server only"));
            return 0;
        }
        if (bankManager.getBankAccount(accountNumber) == null) {
            source.sendFailure(Component.literal(BankSystemTextMessages.getAccountFreezeFailedMessage(accountNumber)));
            return 0;
        }
        boolean frozen = mode == 2 ? !bankManager.isAccountFrozen(accountNumber) : mode == 1;
        bankManager.setAccountFrozen(accountNumber, frozen, net.kroia.banksystem.api.ledger.LedgerActor.console());
        String msg = frozen ? BankSystemTextMessages.getAccountFreezeSetMessage(accountNumber)
                : BankSystemTextMessages.getAccountFreezeClearedMessage(accountNumber);
        source.sendSuccess(() -> Component.literal(msg), true);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Task #110 — opens the audit screen for an account number or a player's personal account.
     * The admin check here is a courtesy; every page is checked again on the master.
     */
    private static int auditCommand(CommandSourceStack source, String target) {
        return openForTarget(source, target, "audit", SyncOpenGUIPacket::send_openAuditScreen);
    }

    /** Task #116 — balance history of any account with flag markers. Admin check as for audit. */
    private static int historyCommand(CommandSourceStack source, String target) {
        return openForTarget(source, target, "history", SyncOpenGUIPacket::send_openHistoryScreen);
    }

    private static int openForTarget(CommandSourceStack source, String target, String name,
                                     java.util.function.BiConsumer<ServerPlayer, Integer> open) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[BankSystem] " + name + " needs a player"));
            return 0;
        }
        var bankManager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync();
        bankManager.isBanksystemAdminAsync(player.getUUID()).thenCompose(isAdmin -> {
            if (!Boolean.TRUE.equals(isAdmin)) return java.util.concurrent.CompletableFuture.completedFuture(-1);
            try {
                return java.util.concurrent.CompletableFuture.completedFuture(Integer.parseInt(target));
            } catch (NumberFormatException e) {
                return bankManager.getPersonalBankAccountNrAsync(target);
            }
        }).thenAccept(nr -> player.level().getServer().execute(() -> {
            if (nr == null || nr == -1) {
                player.sendSystemMessage(Component.literal(ServerBankSystemCommandHandler.NO_ADMIN_MESSAGE));
            } else if (nr <= 0) {
                player.sendSystemMessage(Component.literal(BankSystemTextMessages.getAuditTargetNotFoundMessage(target)));
            } else {
                open.accept(player, nr);
            }
        })).exceptionally(ex -> { AsyncBankSystemCommandHandler.reportFailure(player.getUUID(), ex); return null; });
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Task #113 — writes an audit digest of the last {@code hours} hours on the master and tells
     * the admin its absolute path. From a slave the request is forwarded; the path is on the master.
     */
    private static int auditReportCommand(CommandSourceStack source, int hours) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            if (BACKEND_INSTANCES.isSlaveServer) {
                source.sendFailure(Component.literal("[BankSystem] auditreport from the console works on the master server only"));
                return 0;
            }
            ServerBankSystemCommandHandler.reportOnServerThread(hours).thenAccept(path -> {
                if (path.isEmpty()) source.sendFailure(Component.literal(BankSystemTextMessages.getAuditReportFailedMessage()));
                else source.sendSuccess(() -> Component.literal(BankSystemTextMessages.getAuditReportWrittenMessage(path, false)), false);
            });
            return Command.SINGLE_SUCCESS;
        }
        boolean onMaster = BACKEND_INSTANCES.isSlaveServer;
        handler().banksystem_auditreport_async(player.getUUID(), hours).thenAccept(path -> player.level().getServer().execute(() -> {
            if (!path.isEmpty())
                player.sendSystemMessage(Component.literal(BankSystemTextMessages.getAuditReportWrittenMessage(path, onMaster)));
            else
                player.sendSystemMessage(Component.literal(BankSystemTextMessages.getAuditReportFailedMessage()));
        }));
        return Command.SINGLE_SUCCESS;
    }

    private static int alertsCommand(CommandSourceStack source, boolean enabled) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[BankSystem] alerts needs a player"));
            return 0;
        }
        handler().banksystem_alerts_async(player.getUUID(), enabled);
        return Command.SINGLE_SUCCESS;
    }

    /** Task #146: lifts suspended external bindings of an account (one slot when {@code item} is given). Master only. */
    /** BS-235: master only; acknowledges the company's share excess alert and resumes its dividends. */
    private static int sharesAcknowledgeCommand(CommandSourceStack source, int companyId) {
        if (BACKEND_INSTANCES.SERVER_BANK_MANAGER == null
                || !(BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync() instanceof net.kroia.banksystem.banking.bankmanager.ServerBankManager)) {
            source.sendFailure(Component.literal("[BankSystem] shares acknowledge works on the master server only"));
            return 0;
        }
        long excess = net.kroia.banksystem.banking.company.ShareIntegrity.acknowledge(companyId);
        if (excess < 0) {
            source.sendFailure(Component.literal("[BankSystem] no company with id " + companyId));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[BankSystem] company " + companyId + ": share excess of " + excess
                + " acknowledged, dividends resume. A new alert comes only if the excess grows."), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int restoreBindingCommand(CommandSourceStack source, int account, @org.jetbrains.annotations.Nullable String item) {
        if (BACKEND_INSTANCES.SERVER_BANK_MANAGER == null
                || !(BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync() instanceof net.kroia.banksystem.banking.bankmanager.ServerBankManager bm)) {
            source.sendFailure(Component.literal("[BankSystem] restorebinding works on the master server only"));
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        net.kroia.banksystem.api.ledger.LedgerActor actor = player == null
                ? net.kroia.banksystem.api.ledger.LedgerActor.console()
                : net.kroia.banksystem.banking.ledger.TransactionLedger.admin(player.getUUID());
        int restored = bm.restoreSuspendedBindings(account, item, actor);
        if (restored == 0) {
            source.sendFailure(Component.literal("[BankSystem] no suspended external binding on account " + account
                    + (item != null ? " for " + item : "")));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[BankSystem] restored " + restored
                + " external binding(s) on account " + account), true);
        return restored;
    }
}
