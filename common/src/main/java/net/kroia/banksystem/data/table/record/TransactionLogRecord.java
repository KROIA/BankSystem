package net.kroia.banksystem.data.table.record;

import net.kroia.banksystem.api.ledger.LedgerActor;
import net.kroia.banksystem.api.ledger.LedgerType;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Task #44 / #109 (v2.1.0) — Transaction Ledger row.
 * <p>
 * Persisted to the SQLite {@code TransactionLog} table (see {@code
 * common/src/main/resources/sql/TransactionLog.sql}). One row per money-or-item movement.
 * {@link #type()} carries the stable numeric code ({@code type_code}); the {@code kind} text
 * column keeps the type name for readability.
 * <p>
 * Trade rows ({@link LedgerType#TRADE_BUY}/{@link LedgerType#TRADE_SELL}) use
 * {@code itemId}/{@code amount} for the item slice and {@code moneyAmount}/{@code price}/
 * {@code currencyItem} for the money side. {@code tag} holds the trade row kind.
 * {@code balanceAfter} is the bank balance of {@code itemId} after the operation, when known.
 * {@code originServer} (BS-256) is the slave id of the server whose request caused the row; null means the master.
 */
public record TransactionLogRecord(
        long id,
        int accountNumber,
        @Nullable UUID actorUuid,
        @Nullable String actorName,
        LedgerActor.Source source,
        LedgerType type,
        short itemId,
        long amount,
        @Nullable Integer otherAccount,
        @Nullable Integer companyId,
        long time,
        @Nullable String note,
        @Nullable String ref,
        @Nullable Long moneyAmount,
        @Nullable Long price,
        @Nullable Short currencyItem,
        @Nullable Long balanceAfter,
        @Nullable Long virtualQty,
        @Nullable String tag,
        @Nullable String originServer
) {
    /** Sentinel used when the row's DB id is not yet known (pre-insert construction). */
    public static final long UNSAVED_ID = -1L;

    /** Short form for non-trade rows. Source is PLAYER when {@code actor} is set, else SYSTEM. */
    public TransactionLogRecord(long id, int accountNumber, @Nullable UUID actorUuid, LedgerType type,
                                short itemId, long amount, @Nullable Integer otherAccount,
                                @Nullable Integer companyId, long time, @Nullable String note) {
        this(id, accountNumber, actorUuid, null,
                actorUuid == null ? LedgerActor.Source.SYSTEM : LedgerActor.Source.PLAYER,
                type, itemId, amount, otherAccount, companyId, time, note,
                null, null, null, null, null, null, null, null);
    }

    /** Non-trade row with a full actor. */
    public static TransactionLogRecord of(int accountNumber, LedgerType type, short itemId, long amount,
                                          LedgerActor actor, @Nullable Integer otherAccount,
                                          @Nullable Integer companyId, @Nullable String ref,
                                          @Nullable String note, @Nullable Long balanceAfter) {
        return new TransactionLogRecord(UNSAVED_ID, accountNumber, actor.uuid(), actor.name(), actor.source(),
                type, itemId, amount, otherAccount, companyId, System.currentTimeMillis(), note,
                ref, null, null, null, balanceAfter, null, null, null);
    }

    /** Like {@link #of} but with an explicit time (payout / dividend legs share the run's timestamp). */
    public static TransactionLogRecord ofAt(int accountNumber, LedgerType type, short itemId, long amount,
                                            LedgerActor actor, @Nullable Integer otherAccount,
                                            @Nullable Integer companyId, long time, @Nullable String ref,
                                            @Nullable String note, @Nullable Long balanceAfter) {
        return new TransactionLogRecord(UNSAVED_ID, accountNumber, actor.uuid(), actor.name(), actor.source(),
                type, itemId, amount, otherAccount, companyId, time, note,
                ref, null, null, null, balanceAfter, null, null, null);
    }

    /** Convenience factory for a plain row (no counterparty, no company). */
    public static TransactionLogRecord simple(int accountNumber, @Nullable UUID actor,
                                              LedgerType type, short itemId, long amount, long time) {
        return new TransactionLogRecord(UNSAVED_ID, accountNumber, actor, type, itemId,
                amount, null, null, time, null);
    }

    /** Convenience factory for a transfer leg ({@link LedgerType#TRANSFER}, signed amount). */
    public static TransactionLogRecord transfer(int accountNumber, @Nullable UUID actor,
                                                short itemId, long signedAmount,
                                                int otherAccount, long time) {
        return new TransactionLogRecord(UNSAVED_ID, accountNumber, actor, LedgerType.TRANSFER, itemId,
                signedAmount, otherAccount, null, time, null);
    }

    /** Copy with a different actor. */
    public TransactionLogRecord withActor(LedgerActor actor) {
        return new TransactionLogRecord(id, accountNumber, actor.uuid(), actor.name(), actor.source(), type,
                itemId, amount, otherAccount, companyId, time, note, ref, moneyAmount, price,
                currencyItem, balanceAfter, virtualQty, tag, originServer);
    }

    /** BS-256: copy with the origin server (slave id, null = master). */
    public TransactionLogRecord withOrigin(@Nullable String origin) {
        return new TransactionLogRecord(id, accountNumber, actorUuid, actorName, source, type, itemId, amount,
                otherAccount, companyId, time, note, ref, moneyAmount, price, currencyItem, balanceAfter, virtualQty,
                tag, origin);
    }
}
