package net.kroia.banksystem.util;

import net.kroia.modutilities.PermissionCompat;

/**
 * Permission nodes for BankSystem admin functions (Task #102). Permission mods such as LuckPerms can grant
 * or deny them; without one every node falls back to vanilla op level {@link #FALLBACK_LEVEL}.
 */
public final class BankSystemPermissions {
    private BankSystemPermissions() {}

    public static final int FALLBACK_LEVEL = 2;

    public static final String COMMAND_TRUST = "banksystem.admin.command.trust";               // trust + untrust
    public static final String COMMAND_OP = "banksystem.admin.command.op";                     // op + deop
    public static final String COMMAND_BACKUP = "banksystem.admin.command.backup";
    public static final String COMMAND_SYMBOLS = "banksystem.admin.command.symbols";
    public static final String COMMAND_EXPORTRECIPES = "banksystem.admin.command.exportrecipes";
    public static final String ADMIN_MODE = "banksystem.admin.adminmode";
    public static final String SOFTWARE = "banksystem.admin.software";

    /** Registers all nodes with ModUtilities. Must run during mod init, before the server starts (NeoForge). */
    public static void register() {
        for (String node : new String[]{COMMAND_TRUST, COMMAND_OP, COMMAND_BACKUP, COMMAND_SYMBOLS,
                COMMAND_EXPORTRECIPES, ADMIN_MODE, SOFTWARE}) {
            PermissionCompat.registerNode(node, FALLBACK_LEVEL);
        }
    }
}
