package net.kroia.banksystem.util;

import net.kroia.modutilities.PermissionCompat;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;

/**
 * Permission nodes for BankSystem admin functions (Task #102). Permission mods such as LuckPerms can grant
 * or deny them; without one a node falls back to the vanilla op level from {@link #fallbackLevel(String)}:
 * {@link #FALLBACK_LEVEL} for most nodes, {@link #PLAYER_LEVEL} for the {@link #FLAG_GATED_NODES}.
 * <p>
 * <b>Flag-gated commands (BS-196):</b> {@code symbols} and {@code restorebinding} are guarded by the per-user
 * BankSystem admin flag ({@code /banksystem op}), not by server op. Their nodes default to "every player", so a
 * permission mod can only deny them; a player also needs the admin flag when the command runs. Console and command
 * blocks keep the op-level check. See {@link #canUseFlagGatedCommand(CommandSourceStack, String)}.
 */
public final class BankSystemPermissions {
    private BankSystemPermissions() {}

    public static final int FALLBACK_LEVEL = 2;
    /** Fallback level of the {@link #FLAG_GATED_NODES}: every player passes the node, the admin flag decides. */
    public static final int PLAYER_LEVEL = 0;

    public static final String COMMAND_TRUST = "banksystem.admin.command.trust";               // trust + untrust
    public static final String COMMAND_OP = "banksystem.admin.command.op";                     // op + deop
    public static final String COMMAND_BACKUP = "banksystem.admin.command.backup";
    public static final String COMMAND_SYMBOLS = "banksystem.admin.command.symbols";           // flag-gated (BS-196)
    public static final String COMMAND_EXPORTRECIPES = "banksystem.admin.command.exportrecipes";
    public static final String COMMAND_RESTORE_BINDING = "banksystem.admin.command.restorebinding"; // Task #146, flag-gated (BS-196)
    public static final String ADMIN_MODE = "banksystem.admin.adminmode";
    public static final String SOFTWARE = "banksystem.admin.software";
    /** Task #107: source of the BankSystem admin flag while Integrations.SYNC_ADMIN_FLAG_WITH_PERMISSION is on. */
    public static final String ADMIN = "banksystem.admin";

    public static final String[] ALL_NODES = {COMMAND_TRUST, COMMAND_OP, COMMAND_BACKUP, COMMAND_SYMBOLS,
            COMMAND_EXPORTRECIPES, COMMAND_RESTORE_BINDING, ADMIN_MODE, SOFTWARE, ADMIN};

    /** Nodes of the commands guarded by the BankSystem admin flag instead of server op (BS-196). */
    public static final Set<String> FLAG_GATED_NODES = Set.of(COMMAND_SYMBOLS, COMMAND_RESTORE_BINDING);

    /** @return the vanilla op level {@code node} falls back to without a permission mod */
    public static int fallbackLevel(String node) {
        return FLAG_GATED_NODES.contains(node) ? PLAYER_LEVEL : FALLBACK_LEVEL;
    }

    /**
     * Brigadier {@code requires} check of a flag-gated command (BS-196). Players pass unless a permission mod denies
     * the node; the admin flag is checked when the command runs. Non-player sources need op level {@link #FALLBACK_LEVEL}.
     */
    public static boolean canUseFlagGatedCommand(CommandSourceStack source, String node) {
        if (source.getEntity() instanceof ServerPlayer)
            return PermissionCompat.hasPermission(source, node, PLAYER_LEVEL);
        return PermissionCompat.hasPermission(source, FALLBACK_LEVEL);
    }

    /** Registers all nodes with ModUtilities. Must run during mod init, before the server starts (NeoForge). */
    public static void register() {
        for (String node : ALL_NODES) {
            PermissionCompat.registerNode(node, fallbackLevel(node));
        }
    }
}
