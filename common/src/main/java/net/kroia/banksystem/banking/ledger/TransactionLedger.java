package net.kroia.banksystem.banking.ledger;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bank.ISyncServerBank;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.api.event.TransactionLoggedEvent;
import net.kroia.banksystem.api.ledger.ITransactionLedger;
import net.kroia.banksystem.api.ledger.LedgerActor;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.api.ledger.TradeLogEntry;
import net.kroia.banksystem.banking.bank.ServerBank;
import net.kroia.banksystem.data.table.TransactionLogManager;
import net.kroia.banksystem.data.table.record.TransactionLogRecord;
import net.kroia.banksystem.minecraft.command.ServerBankSystemCommandHandler;
import net.kroia.banksystem.util.ItemID;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Task #109 (v2.1.0) — single write path for the transaction ledger.
 * <p>
 * Best-effort everywhere: no method throws, a failed write never touches the banking
 * operation it describes. Rows are written on the master only ({@link TransactionLogManager},
 * async DB worker). On a slave every write is a no-op: the slave's request to the master
 * carries the ledger type code (see {@link #tagged}) and the master writes the row.
 * <p>
 * Call sites that only know the actor further up the stack (the {@code ServerBank.transfer}
 * auto-log, account deletion) read it from {@link #currentActor()}, set with
 * {@link #withActor(LedgerActor, Supplier)}.
 */
public final class TransactionLedger implements ITransactionLedger {

    public static final TransactionLedger INSTANCE = new TransactionLedger();

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    private static final ThreadLocal<LedgerActor> ACTOR = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> SUPPRESS_TRANSFER_LOG = new ThreadLocal<>();
    private static final ThreadLocal<SlaveTag> TAG = new ThreadLocal<>();
    private static final ThreadLocal<Resolved> TRANSFER_TYPE = new ThreadLocal<>();

    /**
     * Wire-only code: the request moves value but writes no row. Used by the raw async bank
     * calls (StockMarket settlement, which logs through {@link #logTrade}). Never stored.
     */
    public static final int NO_LOG = -1;

    /** Allow-list: codes a slave may send with a deposit (DepositItemsInBankRequest, AsyncBank deposit). */
    public static final Set<LedgerType> SLAVE_DEPOSIT_CODES = EnumSet.of(LedgerType.DEPOSIT,
            LedgerType.CONVERTER_DEPOSIT, LedgerType.UPLOAD_DEPOSIT, LedgerType.CRAFT_OUTPUT, LedgerType.WITHDRAW_REFUND);
    /** Allow-list: codes a slave may send with a withdrawal (WithdrawItemsFromBankRequest, AsyncBank withdraw*). */
    public static final Set<LedgerType> SLAVE_WITHDRAW_CODES = EnumSet.of(LedgerType.WITHDRAW,
            LedgerType.CONVERTER_WITHDRAW, LedgerType.DOWNLOAD_WITHDRAW, LedgerType.CRAFT_CONSUME);
    /** Allow-list: codes a slave may send with an AsyncBank transfer*. */
    public static final Set<LedgerType> SLAVE_TRANSFER_CODES = EnumSet.of(LedgerType.TRANSFER);

    /**
     * Ledger fields a slave attaches to a value-moving request.
     * @param journal Task #124: TransferJournal id + context for the slave's journal rows; Task #125: the id also
     *                goes on the wire and becomes the master's ledger ref
     */
    public record SlaveTag(int code, @Nullable UUID actor, @Nullable String note,
                           @Nullable net.kroia.banksystem.data.TransferJournal.Ref journal) {}

    /** Type and note the master writes for a slave request after validation. */
    public record Resolved(LedgerType type, @Nullable String note) {}

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    private TransactionLedger() {}

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    @Override
    public void logTrade(TradeLogEntry e) {
        if (e == null) return;
        try {
            LedgerActor a = LedgerActor.stockmarket();
            String name = e.actorUuid() == null ? a.name() : nameOf(e.actorUuid());
            write(new TransactionLogRecord(TransactionLogRecord.UNSAVED_ID, e.accountNumber(), e.actorUuid(), name,
                    LedgerActor.Source.STOCKMARKET, e.side().type(), e.item().getShort(), e.qtyRaw(),
                    null, null, e.timeMs(), e.note(), e.ref(), e.moneyRaw(), e.avgPriceRaw(),
                    e.currency().getShort(), null, e.virtualQty(), e.rowKind().name()));
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }

    @Override
    public void log(int accountNumber, LedgerType type, ItemID item, long amount, LedgerActor actor,
                    @Nullable String ref, @Nullable String note) {
        if (type == null || item == null || actor == null) return;
        try {
            write(TransactionLogRecord.of(accountNumber, type, item.getShort(), amount, actor, null, null,
                    ref, note, balanceAfter(accountNumber, type, item)));
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /** Generic row; {@code balance_after} is looked up on the master. */
    public static void logRow(int accountNumber, LedgerType type, ItemID item, long amount,
                           LedgerActor actor, @Nullable Integer otherAccount, @Nullable String note) {
        logRow(accountNumber, type, item, amount, actor, otherAccount, note, null);
    }

    /** Same, with a {@code ref} (Task #136: a user-deletion payout leg's transfer_id). */
    public static void logRow(int accountNumber, LedgerType type, ItemID item, long amount,
                           LedgerActor actor, @Nullable Integer otherAccount, @Nullable String note, @Nullable String ref) {
        try {
            write(TransactionLogRecord.of(accountNumber, type, item.getShort(), amount, actor, otherAccount,
                    null, ref, note, balanceAfter(accountNumber, type, item)));
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }

    /** Row for a sync master bank; account and balance_after come from the bank itself. */
    public static void logBank(ISyncServerBank bank, LedgerType type, long amount,
                               LedgerActor actor, @Nullable String note) {
        logBank(bank, type, amount, actor, note, null);
    }

    /** Same as {@link #logBank(ISyncServerBank, LedgerType, long, LedgerActor, String)} with a {@code ref} (Task #125: transfer_id). */
    public static void logBank(ISyncServerBank bank, LedgerType type, long amount,
                               LedgerActor actor, @Nullable String note, @Nullable String ref) {
        try {
            if (!(bank instanceof ServerBank sb) || sb.getAccountId() == 0) return;
            write(TransactionLogRecord.of(sb.getAccountId(), type, bank.getItemID().getShort(), amount, actor,
                    null, null, ref, note, bank.getTotalBalance()));
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }

    /** Persists a ready row on the master; no-op on a slave or without a DB. Never throws. */
    public static void write(TransactionLogRecord row) {
        try {
            if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.isSlaveServer) return;
            TransactionLogManager mgr = BankSystemModBackend.getTransactionLogManager();
            if (mgr != null) {
                mgr.save(row).exceptionally(t -> {
                    warn("Ledger write failed: " + t.getMessage());
                    return null;
                });
                afterWrite(row);
            }
        } catch (RuntimeException e) {
            warn("Ledger write failed: " + e.getMessage());
        }
    }

    /**
     * Batch form of {@link #write} (payout / dividend runs): one DB batch, then the same
     * per-row audit and suspicion hooks. Master only; never throws.
     */
    public static void writeAll(java.util.List<TransactionLogRecord> rows) {
        try {
            if (rows.isEmpty() || (BACKEND_INSTANCES != null && BACKEND_INSTANCES.isSlaveServer)) return;
            TransactionLogManager mgr = BankSystemModBackend.getTransactionLogManager();
            if (mgr == null) return;
            mgr.save(rows).exceptionally(t -> {
                warn("Ledger write failed: " + t.getMessage());
                return null;
            });
            for (TransactionLogRecord row : rows) afterWrite(row);
        } catch (RuntimeException e) {
            warn("Ledger write failed: " + e.getMessage());
        }
    }

    /** Hooks run after a row was queued for saving (queries on the DB worker see it). */
    private static void afterWrite(TransactionLogRecord row) {
        // Task #119: API event first (a row is announced before any flag it triggers); a throwing
        // listener must not abort the writeAll loop or skip the hooks below.
        try {
            if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.SERVER_EVENTS != null)
                BACKEND_INSTANCES.SERVER_EVENTS.TRANSACTION_LOGGED.notifyListeners(new TransactionLoggedEvent(
                        row.accountNumber(), row.type(), row.itemId(), row.amount(),
                        new LedgerActor(row.actorUuid(), row.actorName(), row.source()),
                        row.otherAccount(), row.companyId(), row.time(), row.note()));
        } catch (RuntimeException e) {
            warn("TransactionLogged listener failed: " + e);
        }
        AdminAudit.onLedgerRow(row);      // Task #110: audit file + admin alerts
        SuspicionChecks.onLedgerRow(row); // Tasks #111 / #115: suspicion flags
    }

    // ------------------------------------------------------------------
    // Slave -> master type codes
    // ------------------------------------------------------------------

    /**
     * Runs {@code body} (one async bank call) with a ledger tag. On a slave the tag rides along
     * in the {@code AsyncBank} request and the master writes the row; on the master the tag is
     * ignored and the caller logs itself after success.
     */
    public static <T> T tagged(LedgerType type, @Nullable UUID actor, @Nullable String note, Supplier<T> body) {
        return tagged(type, actor, note, null, body);
    }

    /**
     * Same as {@link #tagged(LedgerType, UUID, String, Supplier)}, plus the Task #124 journal id + context.
     * <p>
     * Invariant (Task #126): one transfer_id = one value-moving call. {@code body} must make exactly one
     * deposit / withdraw: the master stores each (slave, transfer_id) reply and answers a second call with
     * the same id from that store without applying it, so a second call in one scope would be lost.
     */
    public static <T> T tagged(LedgerType type, @Nullable UUID actor, @Nullable String note,
                               @Nullable net.kroia.banksystem.data.TransferJournal.Ref journal, Supplier<T> body) {
        SlaveTag previous = TAG.get();
        TAG.set(new SlaveTag(type.code(), actor, note, journal));
        try {
            return body.get();
        } finally {
            if (previous == null) TAG.remove();
            else TAG.set(previous);
        }
    }

    /** @return the tag set by {@link #tagged}, or a NO_LOG tag. */
    public static SlaveTag currentTag() {
        SlaveTag t = TAG.get();
        return t == null ? new SlaveTag(NO_LOG, null, null, null) : t;
    }

    /**
     * Master-side check of a code sent by a slave. An allowed code is kept; NO_LOG returns
     * null (write nothing) when {@code noLogAllowed}; anything else is written as
     * UNKNOWN_LEGACY with a "rejected code" note and a warning. Never throws.
     */
    public static @Nullable Resolved resolveSlaveCode(int code, Set<LedgerType> allowed, boolean noLogAllowed,
                                                      @Nullable String note, String slaveID, String requestKind) {
        if (code == NO_LOG && noLogAllowed) return null;
        LedgerType type = LedgerType.fromCode(code);
        if (type.code() == code && allowed.contains(type)) return new Resolved(type, note);
        String reject = "rejected code " + code + " from slave " + slaveID;
        warn(reject + " on " + requestKind);
        return new Resolved(LedgerType.UNKNOWN_LEGACY, note == null ? reject : reject + "; " + note);
    }

    /** Actor for a slave-sent uuid: the player, or SYSTEM without one. */
    public static LedgerActor slaveActor(@Nullable UUID uuid) {
        return uuid == null ? LedgerActor.system() : player(uuid);
    }

    /** Runs {@code body} with the type/note the {@code ServerBank.transfer} auto-log should use. */
    public static <T> T withTransferType(@Nullable Resolved resolved, Supplier<T> body) {
        if (resolved == null) return body.get();
        Resolved previous = TRANSFER_TYPE.get();
        TRANSFER_TYPE.set(resolved);
        try {
            return body.get();
        } finally {
            if (previous == null) TRANSFER_TYPE.remove();
            else TRANSFER_TYPE.set(previous);
        }
    }

    /** @return the override set by {@link #withTransferType}, or TRANSFER without a note. */
    public static Resolved currentTransferType() {
        Resolved r = TRANSFER_TYPE.get();
        return r == null ? new Resolved(LedgerType.TRANSFER, null) : r;
    }

    /** Actor for a player, name resolved from the online player list or the user table. */
    public static LedgerActor player(@Nullable UUID uuid) {
        return LedgerActor.player(uuid, uuid == null ? null : nameOf(uuid));
    }

    /** Actor for a BankSystem admin acting with admin rights. */
    public static LedgerActor admin(@Nullable UUID uuid) {
        return LedgerActor.admin(uuid, uuid == null ? null : nameOf(uuid));
    }

    /** Runs {@code body} with {@code actor} visible to {@link #currentActor()} on this thread. */
    public static <T> T withActor(LedgerActor actor, Supplier<T> body) {
        LedgerActor previous = ACTOR.get();
        ACTOR.set(actor);
        try {
            return body.get();
        } finally {
            if (previous == null) ACTOR.remove();
            else ACTOR.set(previous);
        }
    }

    /**
     * Runs {@code body} without the {@code ServerBank.transfer} auto-log on this thread. For
     * callers that write their own typed rows for the transfer (PAYOUT, DIVIDEND), so one
     * movement is not logged twice.
     */
    public static <T> T withoutTransferLog(Supplier<T> body) {
        Boolean previous = SUPPRESS_TRANSFER_LOG.get();
        SUPPRESS_TRANSFER_LOG.set(Boolean.TRUE);
        try {
            return body.get();
        } finally {
            if (previous == null) SUPPRESS_TRANSFER_LOG.remove();
            else SUPPRESS_TRANSFER_LOG.set(previous);
        }
    }

    /** @return true inside {@link #withoutTransferLog}. */
    public static boolean isTransferLogSuppressed() {
        return SUPPRESS_TRANSFER_LOG.get() != null;
    }

    /** @return the actor set by {@link #withActor}, or SYSTEM. */
    public static LedgerActor currentActor() {
        LedgerActor a = ACTOR.get();
        return a == null ? LedgerActor.system() : a;
    }

    private static @Nullable Long balanceAfter(int accountNumber, LedgerType type, ItemID item) {
        if (type == LedgerType.TRADE_BUY || type == LedgerType.TRADE_SELL) return null;
        try {
            if (BACKEND_INSTANCES == null || BACKEND_INSTANCES.isSlaveServer
                    || BACKEND_INSTANCES.SERVER_BANK_MANAGER == null) return null;
            ISyncServerBankManager manager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
            IServerBankAccount account = manager == null ? null : manager.getBankAccount(accountNumber);
            ISyncServerBank bank = account == null ? null : account.getBank(item);
            return bank == null ? null : bank.getTotalBalance();
        } catch (RuntimeException e) {
            return null; // off-thread map read raced a mutation; balance_after is optional
        }
    }

    private static @Nullable String nameOf(UUID uuid) {
        try {
            return ServerBankSystemCommandHandler.tryGetPlayerName(uuid);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void warn(String msg) {
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null)
            BACKEND_INSTANCES.LOGGER.warn("[TransactionLedger] " + msg);
    }
}
