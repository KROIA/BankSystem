package net.kroia.banksystem.banking.ledger;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.event.SuspicionFlaggedEvent;
import net.kroia.banksystem.api.ledger.LedgerActor;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.bankmanager.ServerBankManager;
import net.kroia.banksystem.data.table.SuspicionFlagManager;
import net.kroia.banksystem.data.table.record.SuspicionFlagRecord;
import net.kroia.banksystem.data.table.record.TransactionLogRecord;
import net.kroia.banksystem.networking.multi_server.ClientConsoleMessagePacket;
import net.kroia.banksystem.util.BankSystemTextMessages;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Task #110 (v2.2.0) — admin audit trail, master only.
 * <ul>
 *     <li>Every admin ledger row (ADMIN_SET..ADMIN_ACCOUNT_EDIT, ACCOUNT_FREEZE, ADMIN_DISALLOW_ITEM,
 *     and BANK_CLOSED done by an admin) goes to {@code <world>/data/BankSystem/audit/<yyyy-mm-dd>.log}
 *     and to every BankSystem admin with alerts on. Hooked in {@link TransactionLedger#write}.</li>
 *     <li>{@link #raise} writes a SuspicionFlag row plus the same file line and alert.</li>
 * </ul>
 * Chat alerts reach admins on slaves too ({@link ClientConsoleMessagePacket} broadcasts to slaves).
 */
public final class AdminAudit {

    /** User customData key of the per-admin chat alert toggle. */
    public static final String ALERTS_KEY = "alertsEnabled";

    /** Test hook: when set, alerts go here instead of chat. */
    public static volatile @Nullable Consumer<String> alertCapture;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    private AdminAudit() {}

    /** @return true for rows that are admin actions. */
    public static boolean isAdminRow(TransactionLogRecord r) {
        return switch (r.type()) {
            case ADMIN_SET, ADMIN_ADD, ADMIN_REMOVE, ADMIN_UNLOCK, ADMIN_ACCOUNT_EDIT, ACCOUNT_FREEZE, ADMIN_DISALLOW_ITEM -> true;
            case BANK_CLOSED -> r.source() == LedgerActor.Source.ADMIN;
            default -> false;
        };
    }

    /** Called for every ledger row written on the master. Never throws. */
    public static void onLedgerRow(TransactionLogRecord r) {
        try {
            if (!isAdminRow(r)) return;
            String actor = r.actorName() != null ? r.actorName() : r.source().name();
            String line = "ADMIN " + r.type().name() + " account " + r.accountNumber()
                    + (r.itemId() != 0 ? " item " + r.itemId() : "")
                    + (r.amount() != 0 ? " amount " + r.amount() : "")
                    + " by " + actor + (r.actorUuid() != null ? " (" + r.actorUuid() + ")" : "")
                    + (r.note() != null ? " note \"" + r.note() + "\"" : "");
            appendLine(r.time(), line);
            alert(line);
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }

    /**
     * Raises a suspicion flag: one SuspicionFlag row, one audit-file line and an alert to the
     * admins with alerts on. Master only (no-op elsewhere). Never throws.
     *
     * @param accountNumber flagged account
     * @param flagType      append-only code owned by the raising feature
     * @param severity      free-form, higher = worse
     * @param amount        raw amount that triggered the flag (0 if none)
     * @param ref           optional reference (e.g. ledger ref or order ref)
     * @param details       human-readable reason, also shown in the alert
     */
    public static void raise(int accountNumber, int flagType, int severity, long amount,
                             @Nullable String ref, @Nullable String details) {
        try {
            if (BACKEND_INSTANCES == null || BACKEND_INSTANCES.isSlaveServer) return;
            long now = System.currentTimeMillis();
            SuspicionFlagManager mgr = BACKEND_INSTANCES.SUSPICION_FLAG_MANAGER;
            if (mgr != null)
                mgr.save(new SuspicionFlagRecord(SuspicionFlagRecord.UNSAVED_ID, now, accountNumber, flagType,
                        severity, amount, ref, details, false)).exceptionally(t -> null);
            String line = "FLAG " + flagType + " account " + accountNumber + " severity " + severity
                    + (amount != 0 ? " amount " + amount : "") + (ref != null ? " ref " + ref : "")
                    + (details != null ? " \"" + details + "\"" : "");
            appendLine(now, line);
            alert(line);
            // Task #119: last, so a throwing listener cannot skip the file line or the alert
            if (BACKEND_INSTANCES.SERVER_EVENTS != null)
                BACKEND_INSTANCES.SERVER_EVENTS.SUSPICION_FLAGGED.notifyListeners(new SuspicionFlaggedEvent(accountNumber,
                        flagType, SuspicionChecks.typeName(flagType), severity, amount, ref, details, now));
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }

    /** @return the audit file of {@code day} (server time zone), or null without a world. */
    public static @Nullable Path auditFile(LocalDate day) {
        Path dir = auditDir();
        return dir == null ? null : dir.resolve(day + ".log");
    }

    /** @return the audit folder (audit files and digests), or null without a world. */
    public static @Nullable Path auditDir() {
        if (BACKEND_INSTANCES == null || BACKEND_INSTANCES.SERVER_DATA_HANDLER == null) return null;
        return BACKEND_INSTANCES.SERVER_DATA_HANDLER.getAbsoluteSavePath("audit");
    }

    private static synchronized void appendLine(long timeMs, String line) {
        LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochMilli(timeMs), ZoneId.systemDefault());
        Path file = auditFile(t.toLocalDate());
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, TIME.format(t) + " " + line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            if (BACKEND_INSTANCES.LOGGER != null) BACKEND_INSTANCES.LOGGER.warn("[AdminAudit] audit file write failed: " + e.getMessage());
        }
    }

    /**
     * Sends {@code line} to every BankSystem admin with alerts on, wherever they are online.
     * Ledger rows arrive on DB / network threads; the user map and the chat broadcast are only
     * touched on the server main thread (deferred via {@code execute} when called elsewhere).
     */
    public static void alert(String line) {
        net.minecraft.server.MinecraftServer srv = dev.architectury.utils.GameInstance.getServer();
        if (srv != null && !srv.isSameThread()) {
            srv.execute(() -> {
                try { alert(line); } catch (RuntimeException ignored) { /* best-effort */ }
            });
            return;
        }
        Consumer<String> capture = alertCapture;
        if (capture != null) {
            capture.accept(line);
            return;
        }
        if (BACKEND_INSTANCES == null || BACKEND_INSTANCES.SERVER_BANK_MANAGER == null
                || !(BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync() instanceof ServerBankManager manager)) return;
        String msg = BankSystemTextMessages.getAuditAlertMessage(line);
        for (User admin : manager.getAdminUsers())
            if (alertsEnabled(admin))
                ClientConsoleMessagePacket.sendMessageFromMaster(admin.getUUID(), msg);
    }

    /** @return the admin's toggle, or the config default {@code Utilities.ALERTS_DEFAULT_ON}. */
    public static boolean alertsEnabled(User user) {
        if (user.getCustomData().contains(ALERTS_KEY)) return net.kroia.modutilities.NbtCompat.getBoolean(user.getCustomData(), ALERTS_KEY);
        return BACKEND_INSTANCES == null || BACKEND_INSTANCES.SERVER_SETTINGS == null
                || BACKEND_INSTANCES.SERVER_SETTINGS.UTILITIES.ALERTS_DEFAULT_ON.get();
    }

    /** Stores the toggle in the user's customData. @return false if the user is unknown. */
    public static boolean setAlertsEnabled(UUID uuid, boolean enabled) {
        if (BACKEND_INSTANCES == null || BACKEND_INSTANCES.SERVER_BANK_MANAGER == null) return false;
        var manager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
        User user = manager == null ? null : manager.getUserByUUID(uuid);
        if (user == null) return false;
        user.getCustomData().putBoolean(ALERTS_KEY, enabled);
        manager.markPersistDirty();
        return true;
    }
}
