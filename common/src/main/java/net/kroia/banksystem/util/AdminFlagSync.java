package net.kroia.banksystem.util;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankmanager.IServerBankManager;
import net.kroia.banksystem.minecraft.command.ServerBankSystemCommandHandler;
import net.kroia.banksystem.util.async_function_forwarding.AsyncForwardingRequest;
import net.kroia.modutilities.UtilitiesPlatform;
import net.kroia.modutilities.permission.LuckPermsBridge;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Task #107: opt-in two-way sync between the BankSystem admin flag and the LuckPerms node
 * {@link BankSystemPermissions#ADMIN} (setting {@code Integrations.SYNC_ADMIN_FLAG_WITH_PERMISSION}).
 * Without the setting or without LuckPerms nothing here does anything.
 * <ul>
 *   <li>{@code /banksystem op|deop}: sets the flag, then writes the node ({@link #pushToLuckPerms}). Deop only
 *       removes the user's own node; if a LuckPerms group still grants it, the group wins: the flag is set back
 *       at once and the executor is told.</li>
 *   <li>New BankSystem user on the master ({@link #onUserAdded}): an effective grant sets the flag.</li>
 *   <li>LuckPerms node change ({@link #onNodeChanged}, server thread): master writes the flag directly;
 *       a trusted slave forwards {@code setBanksystemAdminModeAsync} to the master with a null player sender,
 *       which the master accepts only from a trusted slave while its own setting is on (AsyncBankManager);
 *       an untrusted slave ignores it. The listener never calls setNode, so there is no loop.</li>
 *   <li>One-time migration on the master ({@link #migrateIfNeeded}): pushes node=true for every flagged admin,
 *       then writes {@link #MIGRATION_MARKER} into the world's BankSystem data folder. Never pushes false.</li>
 * </ul>
 */
public final class AdminFlagSync {
    private AdminFlagSync() {}

    public enum ListenerAction { WRITE_LOCAL, FORWARD, IGNORE }

    public static final String MIGRATION_MARKER = "AdminFlagLuckPermsMigrated.marker";

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) { BACKEND_INSTANCES = backend; }

    private static volatile boolean migrating = false;
    private static final net.kroia.banksystem.api.ledger.LedgerActor LUCKPERMS =
            new net.kroia.banksystem.api.ledger.LedgerActor(null, "LuckPerms", net.kroia.banksystem.api.ledger.LedgerActor.Source.SYSTEM);

    // ---- pure decisions ----

    /** op/deop mirrors the flag into LuckPerms only with the setting on and LuckPerms present. */
    public static boolean shouldPushToLuckPerms(boolean settingOn, boolean lpAvailable) {
        return settingOn && lpAvailable;
    }

    /** What a LuckPerms node change does on this server. */
    public static ListenerAction listenerAction(boolean settingOn, boolean isMaster, boolean isTrustedSlave) {
        if (!settingOn)
            return ListenerAction.IGNORE;
        if (isMaster)
            return ListenerAction.WRITE_LOCAL;
        return isTrustedSlave ? ListenerAction.FORWARD : ListenerAction.IGNORE;
    }

    public enum DeopFollowUp { RESTORE_AND_NOTE, NONE }

    /** After deop removed the user's node: a still-effective grant (LuckPerms group) wins, so restore the flag. */
    public static DeopFollowUp deopFollowUp(Optional<Boolean> effective) {
        return effective.orElse(false) ? DeopFollowUp.RESTORE_AND_NOTE : DeopFollowUp.NONE;
    }

    /**
     * For a brand-new user (flag false): true if the flag must be set. Only an effective {@code true} changes
     * anything; {@code false} or unknown leaves the new user's flag as it is (already false).
     */
    public static boolean newUserApply(Optional<Boolean> effective) {
        return effective.orElse(false);
    }

    /** The one-time admin push runs once: setting on, LuckPerms present, not yet migrated. */
    public static boolean shouldMigrate(boolean settingOn, boolean lpAvailable, boolean migrated) {
        return settingOn && lpAvailable && !migrated;
    }

    // ---- runtime ----

    /** True if this server's own {@code SYNC_ADMIN_FLAG_WITH_PERMISSION} setting is on. */
    public static boolean isEnabled() {
        return BACKEND_INSTANCES != null && BACKEND_INSTANCES.SERVER_SETTINGS != null
                && Boolean.TRUE.equals(BACKEND_INSTANCES.SERVER_SETTINGS.INTEGRATIONS.SYNC_ADMIN_FLAG_WITH_PERMISSION.get());
    }

    /**
     * Registers the LuckPerms listener once at mod init. No-op without LuckPerms. The setting is checked inside
     * the callback, so turning it on or off at runtime needs no re-registration.
     */
    public static void init() {
        LuckPermsBridge.addNodeChangeListener(BankSystemPermissions.ADMIN, AdminFlagSync::onNodeChanged);
    }

    private static void onNodeChanged(UUID uuid, boolean admin) {
        if (!isEnabled() || BACKEND_INSTANCES.SERVER_BANK_MANAGER == null)
            return;
        applyFlag(uuid, admin, "from LuckPerms");
    }

    /**
     * Writes the flag without touching LuckPerms (loop-safe). Master: directly. Slave: forwarded to the master only
     * if the master trusts this slave (asked each time; these events are rare). Call on the server thread.
     */
    private static void applyFlag(UUID uuid, boolean admin, String reason) {
        if (!BACKEND_INSTANCES.isSlaveServer) {
            if (listenerAction(isEnabled(), true, false) == ListenerAction.WRITE_LOCAL)
                writeLocal(uuid, admin, reason);
            return;
        }
        BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync().isSlaveServerTrustedAsync("").thenAccept(trusted -> {
            if (listenerAction(isEnabled(), false, Boolean.TRUE.equals(trusted)) != ListenerAction.FORWARD) {
                BACKEND_INSTANCES.LOGGER.debug("[AdminFlagSync] Ignored admin flag change for " + uuid
                        + " (" + reason + "): sync off or this slave is not trusted by the master");
                return;
            }
            BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync().setBanksystemAdminModeAsync(uuid, admin).thenAccept(ok -> {
                if (ok)
                    BACKEND_INSTANCES.LOGGER.info("[AdminFlagSync] Forwarded BankSystem admin flag of "
                            + AsyncForwardingRequest.tryGetPlayerName(uuid) + " = " + admin + " (" + reason + ") to the master");
                else
                    BACKEND_INSTANCES.LOGGER.warn("[AdminFlagSync] Master rejected admin flag sync for " + uuid
                            + " (setting off on the master, or unknown user)");
            });
        });
    }

    private static void writeLocal(UUID uuid, boolean admin, String reason) {
        IServerBankManager manager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
        if (manager == null || manager.isBanksystemAdmin(uuid) == admin)
            return; // also the echo of our own setNode: already equal, loop-safe
        // BS-257: the audit line names LuckPerms as the actor
        if (net.kroia.banksystem.banking.ledger.TransactionLedger.withActor(LUCKPERMS, () -> manager.setBanksystemAdminMode(uuid, admin)))
            BACKEND_INSTANCES.LOGGER.info("[AdminFlagSync] BankSystem admin flag of "
                    + AsyncForwardingRequest.tryGetPlayerName(uuid) + " set to " + admin + " (" + reason + ")");
    }

    /**
     * After {@code /banksystem op|deop} changed the flag: mirror it into LuckPerms (setting on + LuckPerms only)
     * and report a failure to the executor. Deop removes only the user's own node; if a LuckPerms group still
     * grants it ("group wins"), the flag is set back at once and the executor is told.
     */
    public static void pushToLuckPerms(UUID executor, UUID player, String playerName, boolean admin) {
        if (!shouldPushToLuckPerms(isEnabled(), LuckPermsBridge.isAvailable()))
            return;
        CompletableFuture<Boolean> write = LuckPermsBridge.setNode(player, BankSystemPermissions.ADMIN, admin);
        write.thenAccept(ok -> {
            if (!ok)
                onServerThread(() -> ServerBankSystemCommandHandler.sendMessage(executor, "Failed to write the LuckPerms node '"
                        + BankSystemPermissions.ADMIN + "' for " + playerName + " (see the server log). "
                        + "The BankSystem flag was changed, but the next LuckPerms change of that node overwrites it."));
        });
        if (admin)
            return;
        // setNode invalidates the user's cache before completing, so this lookup sees the post-write state.
        write.thenCompose(ok -> ok ? LuckPermsBridge.getEffective(player, BankSystemPermissions.ADMIN)
                        : CompletableFuture.completedFuture(Optional.<Boolean>empty()))
                .thenAccept(effective -> onServerThread(() -> {
                    if (deopFollowUp(effective) != DeopFollowUp.RESTORE_AND_NOTE)
                        return;
                    applyFlag(player, true, "still granted by a LuckPerms group after deop");
                    ServerBankSystemCommandHandler.sendMessage(executor,
                            BankSystemTextMessages.getStillAdminViaLuckPermsGroupMessage(playerName));
                }));
    }

    /**
     * Master only, called when BankSystem creates a user: a player who already has the node in LuckPerms (e.g. via
     * a group) gets the flag. Closes the gap where the listener fired before the user existed.
     */
    public static void onUserAdded(UUID uuid) {
        if (BACKEND_INSTANCES == null || BACKEND_INSTANCES.isSlaveServer || !isEnabled() || !LuckPermsBridge.isAvailable())
            return;
        LuckPermsBridge.getEffective(uuid, BankSystemPermissions.ADMIN).thenAccept(effective -> onServerThread(() -> {
            if (newUserApply(effective))
                applyFlag(uuid, true, "LuckPerms grant of new user");
        }));
    }

    /** Master only: one-time push of every flagged admin to LuckPerms. Safe to call repeatedly. */
    public static void migrateIfNeeded() {
        if (BACKEND_INSTANCES == null || BACKEND_INSTANCES.isSlaveServer || migrating
                || BACKEND_INSTANCES.SERVER_DATA_HANDLER == null || BACKEND_INSTANCES.SERVER_BANK_MANAGER == null)
            return;
        IServerBankManager manager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
        if (manager == null)
            return;
        Path marker = BACKEND_INSTANCES.SERVER_DATA_HANDLER.getAbsoluteSavePath(MIGRATION_MARKER);
        if (!shouldMigrate(isEnabled(), LuckPermsBridge.isAvailable(), Files.exists(marker)))
            return;
        migrating = true;
        List<CompletableFuture<Boolean>> pushes = new ArrayList<>();
        for (UUID uuid : manager.getBankManagerUserMapData().userMap().keySet())
            if (manager.isBanksystemAdmin(uuid))
                pushes.add(LuckPermsBridge.setNode(uuid, BankSystemPermissions.ADMIN, true));
        CompletableFuture.allOf(pushes.toArray(new CompletableFuture[0])).whenComplete((v, e) -> {
            migrating = false;
            if (e != null || !pushes.stream().allMatch(CompletableFuture::join)) {
                BACKEND_INSTANCES.LOGGER.warn("[AdminFlagSync] Migration of BankSystem admins to LuckPerms node '"
                        + BankSystemPermissions.ADMIN + "' failed for some players; retried at the next server start");
                return;
            }
            try {
                Files.createDirectories(marker.getParent());
                Files.writeString(marker, "BankSystem admins pushed to LuckPerms node " + BankSystemPermissions.ADMIN
                        + ". Delete this file to run the migration again.\n");
                BACKEND_INSTANCES.LOGGER.info("[AdminFlagSync] Migrated " + pushes.size()
                        + " BankSystem admin(s) to LuckPerms node '" + BankSystemPermissions.ADMIN + "'");
            } catch (Exception ex) {
                BACKEND_INSTANCES.LOGGER.error("[AdminFlagSync] Could not write migration marker " + marker, ex);
            }
        });
    }

    private static void onServerThread(Runnable r) {
        MinecraftServer server = UtilitiesPlatform.getServer();
        if (server != null)
            server.execute(r);
    }
}
