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
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.MoneyFormat;
import net.minecraft.network.chat.Component;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Task #110 (v2.2.0) — admin audit trail, master only.
 * <ul>
 *     <li>Every admin ledger row (ADMIN_SET..ADMIN_ACCOUNT_EDIT, ACCOUNT_FREEZE, ADMIN_DISALLOW_ITEM,
 *     and BANK_CLOSED / ACCOUNT_DELETE_LOSS done by an admin) goes to {@code <world>/data/BankSystem/audit/<yyyy-mm-dd>.log}
 *     and to every BankSystem admin with alerts on. Hooked in {@link TransactionLedger#write}.</li>
 *     <li>{@link #raise} writes a SuspicionFlag row plus the same file line and alert.</li>
 * </ul>
 * Chat alerts reach admins on slaves too ({@link ClientConsoleMessagePacket} broadcasts to slaves).
 */
public final class AdminAudit {

    /** User customData key of the per-admin chat alert toggle. */
    public static final String ALERTS_KEY = "alertsEnabled";

    /** Test hook: when set, the alert chat text (without the prefix) goes here instead of chat. */
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
            case BANK_CLOSED, ACCOUNT_DELETE_LOSS -> r.source() == LedgerActor.Source.ADMIN;
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
            alert(() -> adminChat(r)); // BS-241: readable chat text; the file line above stays machine-readable
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }

    /**
     * BS-257: one audit-file line for an admin change that writes no ledger row (admin rights, slave
     * trust, allowed items, flag acknowledge): {@code ADMIN <type> <subject> state <state> by <actor>[ (<uuid>)]}.
     * Called where the state changes, only when it changed. Master only (no-op elsewhere), no chat alert. Never throws.
     */
    public static void adminChange(String type, String subject, String state, LedgerActor actor) {
        try {
            if (BACKEND_INSTANCES == null || BACKEND_INSTANCES.isSlaveServer) return;
            appendLine(System.currentTimeMillis(), "ADMIN " + type + " " + subject + " state " + state + " by "
                    + (actor.name() != null ? actor.name() : actor.source().name())
                    + (actor.uuid() != null ? " (" + actor.uuid() + ")" : ""));
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }

    /** @return {@code s} in double quotes (inner double quotes become single ones), for names in audit lines. */
    public static String quote(@Nullable String s) {
        return "\"" + (s == null ? "" : s.replace('"', '\'')) + "\"";
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
            alert(() -> flagChat(accountNumber, flagType, severity, amount, details)); // BS-241
            // Task #119: last, so a throwing listener cannot skip the file line or the alert
            if (BACKEND_INSTANCES.SERVER_EVENTS != null)
                BACKEND_INSTANCES.SERVER_EVENTS.SUSPICION_FLAGGED.notifyListeners(new SuspicionFlaggedEvent(accountNumber,
                        flagType, SuspicionChecks.typeName(flagType), severity, amount, ref, details, now));
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }

    /**
     * BS-267: a value transfer whose outcome stayed unknown after the slave's status check is parked: treated as
     * applied by the master, nothing handed or taken back, no lock released. The slave journal already has TIMED_OUT
     * (AsyncBank). This adds the error log, a TRANSFER_PARKED flag (ref = transfer id; amount = raw money, 0 for other
     * items, which the details name) and one chat line to {@code player} if online here. On a slave the flag goes to the
     * master through {@link net.kroia.banksystem.networking.multi_server.TransferParkedRequest} (trusted slaves only,
     * retried while the link is down). Never throws.
     */
    public static void parkTransfer(int accountNumber, ItemID item, long raw, @Nullable String transferId,
                                    @Nullable UUID player, String what, @Nullable Throwable cause) {
        try {
            String details = what + " outcome unknown, parked (treated as applied, lock kept): " + itemAmount(item.getShort(), raw);
            if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
                String msg = "[AdminAudit] " + details + " (account " + accountNumber + ", player " + player + ", transfer " + transferId + ")";
                if (cause == null) BACKEND_INSTANCES.LOGGER.error(msg);
                else BACKEND_INSTANCES.LOGGER.error(msg, net.kroia.banksystem.networking.multi_server.TransferStatusRequest.cause(cause));
            }
            if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.isSlaveServer)
                net.kroia.banksystem.networking.multi_server.TransferParkedRequest.report(accountNumber, item, raw, transferId, details);
            else raiseParked(accountNumber, item, raw, transferId, details);
            if (player != null)
                net.kroia.modutilities.ServerPlayerUtilities.printToClientConsole(player,
                        BankSystemTextMessages.getTransferUnconfirmedMessage(String.valueOf(transferId)));
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }

    /** BS-267: the TRANSFER_PARKED flag of a parked transfer (amount = raw money, 0 for other items). Master only. */
    public static void raiseParked(int accountNumber, ItemID item, long raw, @Nullable String transferId, String details) {
        raise(accountNumber, SuspicionChecks.TRANSFER_PARKED, 2,
                net.kroia.banksystem.minecraft.item.custom.money.MoneyItem.isMoney(item) ? raw : 0, transferId, details);
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
     * Sends the chat text built by {@code chat} to every BankSystem admin with alerts on, wherever they
     * are online. Ledger rows arrive on DB / network threads; the text is built and the user map and chat
     * broadcast are only touched on the server main thread (deferred via {@code execute} when called elsewhere).
     */
    public static void alert(Supplier<String> chat) {
        net.minecraft.server.MinecraftServer srv = dev.architectury.utils.GameInstance.getServer();
        if (srv != null && !srv.isSameThread()) {
            srv.execute(() -> {
                try { alert(chat); } catch (RuntimeException ignored) { /* best-effort */ }
            });
            return;
        }
        String text = chat.get();
        Consumer<String> capture = alertCapture;
        if (capture != null) {
            capture.accept(text);
            return;
        }
        if (BACKEND_INSTANCES == null || BACKEND_INSTANCES.SERVER_BANK_MANAGER == null
                || !(BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync() instanceof ServerBankManager manager)) return;
        String msg = BankSystemTextMessages.getAuditAlertMessage(text);
        for (User admin : manager.getAdminUsers())
            if (alertsEnabled(admin))
                ClientConsoleMessagePacket.sendMessageFromMaster(admin.getUUID(), msg);
    }

    // ------------------------------------------------------------------
    // BS-241 — chat text of an alert: readable names, real amounts, no codes / UUIDs / refs
    // ------------------------------------------------------------------

    private static final String KEY = "message.banksystem.";
    private static final int MAX_DETAIL_LINES = 4;
    private static final Pattern UUID_TEXT = Pattern.compile(
            "\\s*\\(?\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b\\)?");

    /** Flag alert: severity + flag name, account, amount (scaled), up to 4 detail lines. */
    public static String flagChat(int account, int flagType, int severity, long amount, @Nullable String details) {
        StringBuilder sb = new StringBuilder(severityText(severity)).append("\u00a7r: ").append(flagTitle(flagType))
                .append("\n  ").append(accountText(account));
        String amt = flagAmount(flagType, amount);
        if (amt != null) sb.append("\n  ").append(text("alert_amount").replace("{amount}", amt));
        if (details != null) {
            List<String> lines = new ArrayList<>();
            for (String l : clean(details).split("\n")) if (!l.isBlank()) lines.add(l.strip());
            for (int i = 0; i < lines.size() && i < MAX_DETAIL_LINES; i++) sb.append("\n  \u00a77").append(lines.get(i));
            if (lines.size() > MAX_DETAIL_LINES)
                sb.append("\n  \u00a77").append(text("alert_more").replace("{account_number}", String.valueOf(account)));
        }
        return sb.toString();
    }

    /** Admin action alert: one readable line (who did what on which account), the note below it. */
    public static String adminChat(TransactionLogRecord r) {
        String actor = r.actorName() != null ? r.actorName()
                : r.source().name().charAt(0) + r.source().name().substring(1).toLowerCase(Locale.ROOT);
        String key = r.type() == net.kroia.banksystem.api.ledger.LedgerType.ACCOUNT_FREEZE && "unfrozen".equals(r.note())
                ? "account_unfreeze" : r.type().name().toLowerCase(Locale.ROOT);
        String line = text("admin_action." + key);
        if (line.equals(KEY + "admin_action." + key)) line = "{actor}: " + key.replace('_', ' ') + ", {account}";
        line = line.replace("{actor}", actor).replace("{account}", accountText(r.accountNumber()))
                .replace("{amount}", itemAmount(r.itemId(), Math.abs(r.amount())));
        boolean freeze = r.type() == net.kroia.banksystem.api.ledger.LedgerType.ACCOUNT_FREEZE;
        if (r.note() != null && !r.note().isBlank() && !freeze) line += "\n  \u00a77" + clean(r.note()).strip();
        return line;
    }

    /** @return the readable name of a flag type ("Flag N" for an unknown one). */
    public static String flagTitle(int flagType) {
        String key = KEY + "flag_type." + flagType;
        String t = Component.translatable(key).getString();
        return t.equals(key) ? "Flag " + flagType : t;
    }

    private static String severityText(int severity) {
        if (severity >= 3) return "\u00a7cHIGH";
        return severity == 2 ? "\u00a76MEDIUM" : "\u00a7eLOW";
    }

    private static @Nullable String flagAmount(int flagType, long amount) {
        if (amount == 0) return null;
        if (flagType == SuspicionChecks.SHARE_EXCESS || flagType == SuspicionChecks.SHARE_CREATED)
            return text("alert_shares").replace("{count}", String.valueOf(amount / BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR));
        if (flagType == SuspicionChecks.EXTERNAL_BINDING_SUSPENDED) return MoneyFormat.format(amount); // units of the bound slot
        return net.kroia.banksystem.api.MoneyFormat.formatMoney(amount);
    }

    /** "Account #4 "Name"", or the whole server for account 0. Server thread. */
    private static String accountText(int nr) {
        if (nr == 0) return text("alert_server");
        String name = null;
        try {
            if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.SERVER_BANK_MANAGER != null
                    && BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync() instanceof ServerBankManager bm) {
                var acct = bm.getBankAccount(nr);
                if (acct != null) name = acct.getAccountName();
            }
        } catch (RuntimeException ignored) { /* name is optional */ }
        return text("alert_account").replace("{account}",
                "#" + nr + (name == null || name.isBlank() ? "" : " \"" + clean(name).strip() + "\""));
    }

    /** Real amount with its item: money with the currency, shares as a count, other items with their name. */
    public static String itemAmount(short itemId, long raw) {
        if (itemId == 0) return MoneyFormat.format(raw);
        try {
            if (itemId == net.kroia.banksystem.minecraft.item.custom.money.MoneyItem.getItemID().getShort())
                return net.kroia.banksystem.api.MoneyFormat.formatMoney(raw);
            ItemID id = new ItemID(itemId);
            Integer cid = net.kroia.banksystem.minecraft.item.custom.share.StampedShareItem.getCompanyIdForItemID(id);
            if (cid != null) {
                var cm = net.kroia.banksystem.banking.company.CompanyManager.get();
                var c = cm == null ? null : cm.getById(cid);
                return text("alert_shares_of").replace("{count}", String.valueOf(raw / BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR))
                        .replace("{company}", c != null ? c.getName() : "#" + cid);
            }
            return MoneyFormat.format(raw) + " " + id.getStack().getHoverName().getString();
        } catch (RuntimeException e) {
            return MoneyFormat.format(raw);
        }
    }

    /** Drops UUIDs (and their brackets) from text meant for chat. */
    static String clean(String s) {
        return UUID_TEXT.matcher(s).replaceAll("");
    }

    private static String text(String key) {
        return Component.translatable(KEY + key).getString();
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
