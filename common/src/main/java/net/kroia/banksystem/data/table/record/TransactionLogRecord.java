package net.kroia.banksystem.data.table.record;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Task #44 (v2.0.8) — Transaction Ledger row.
 * <p>
 * Persisted to the SQLite {@code TransactionLog} table (see {@code
 * common/src/main/resources/sql/TransactionLog.sql}). One row per user-facing money-or-item
 * movement so the future Company screen and audit tooling can render a filterable history
 * that spans an account's or a company's lifetime.
 * <p>
 * Kinds currently populated: {@link Kind#DEPOSIT}, {@link Kind#WITHDRAW},
 * {@link Kind#TRANSFER_OUT}, {@link Kind#TRANSFER_IN}. {@link Kind#PAYOUT} is a stub the
 * scheduler task (#45) will activate. {@link Kind#DIVIDEND}, {@link Kind#SHARE_STAMP},
 * {@link Kind#SHARE_REDEEM}, {@link Kind#SHARE_TRADE} are declared here so future Company
 * phases can write them without a schema migration.
 * <p>
 * {@code actorUuid} is {@code null} for system-initiated writes (e.g. a bank-to-bank
 * transfer performed without a player-scoped executor). {@code otherAccount} is populated
 * for {@code TRANSFER_*} rows; {@code companyId} is populated for share/payout/dividend
 * rows once those phases land.
 */
public record TransactionLogRecord(
        long id,
        int accountNumber,
        @Nullable UUID actorUuid,
        Kind kind,
        short itemId,
        long amount,
        @Nullable Integer otherAccount,
        @Nullable Integer companyId,
        long time,
        @Nullable String note
) {
    /** Sentinel used when the row's DB id is not yet known (pre-insert construction). */
    public static final long UNSAVED_ID = -1L;

    public enum Kind {
        DEPOSIT,
        WITHDRAW,
        TRANSFER_OUT,
        TRANSFER_IN,
        PAYOUT,
        DIVIDEND,
        SHARE_STAMP,
        SHARE_REDEEM,
        SHARE_TRADE
    }

    /** Convenience factory for a plain deposit/withdraw row (no counterparty, no company). */
    public static TransactionLogRecord simple(int accountNumber, @Nullable UUID actor,
                                              Kind kind, short itemId, long amount, long time) {
        return new TransactionLogRecord(UNSAVED_ID, accountNumber, actor, kind, itemId,
                amount, null, null, time, null);
    }

    /** Convenience factory for a transfer leg (source or destination account). */
    public static TransactionLogRecord transfer(int accountNumber, @Nullable UUID actor,
                                                Kind kind, short itemId, long amount,
                                                int otherAccount, long time) {
        return new TransactionLogRecord(UNSAVED_ID, accountNumber, actor, kind, itemId,
                amount, otherAccount, null, time, null);
    }
}
