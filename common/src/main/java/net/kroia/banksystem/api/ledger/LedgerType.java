package net.kroia.banksystem.api.ledger;

import org.jetbrains.annotations.Nullable;

/**
 * Task #109 — type of a transaction-ledger row. The numeric {@link #code()} is what
 * gets persisted in the {@code type_code} column.
 * <p>
 * <b>The code table is append-only: never renumber or reuse a code.</b> Persisted rows and
 * downstream tools (audit commands, external readers) depend on the numbers staying stable.
 * New row types get the next free number at the end of the list.
 * <p>
 * Amounts are unsigned except for {@link #TRANSFER}: there the amount is negative on the
 * account the value left and positive on the account it arrived at, so the two rows of one
 * transfer sum to 0.
 *
 * @since 2.2.0
 */
public enum LedgerType {
    /** Legacy row whose old kind could not be mapped, or an unknown code. */
    UNKNOWN_LEGACY(0),
    DEPOSIT(1),
    WITHDRAW(2),
    /** Signed amount: negative = out of this account, positive = in. {@code other_account} = counterparty. */
    TRANSFER(3),
    TRADE_BUY(4),
    TRADE_SELL(5),
    /** Withdrawn items that could not be handed out and went back into the account. */
    WITHDRAW_REFUND(6),
    ADMIN_SET(7),
    ADMIN_ADD(8),
    ADMIN_REMOVE(9),
    ADMIN_UNLOCK(10),
    /** Non-balance account edit (name, icon, users, permissions). Amount 0, note says what changed. */
    ADMIN_ACCOUNT_EDIT(11),
    /** A bank (one item slot) was closed or its account deleted; amount = balance that was destroyed. */
    BANK_CLOSED(12),
    CONVERTER_DEPOSIT(13),
    CONVERTER_WITHDRAW(14),
    UPLOAD_DEPOSIT(15),
    DOWNLOAD_WITHDRAW(16),
    CRAFT_CONSUME(17),
    CRAFT_OUTPUT(18),
    STARTING_BALANCE(19),
    PAYOUT(20),
    DIVIDEND(21),
    SHARE_STAMP(22),
    SHARE_REDEEM(23),
    SHARE_TRADE(24),
    /** Balance handed out as items when a user (and their accounts) is deleted. */
    ACCOUNT_DELETE_PAYOUT(25),
    /** Admin freeze/unfreeze of an account (Task #117). Amount 0, item 0, note "frozen"/"unfrozen". */
    ACCOUNT_FREEZE(26),
    /** Item was disallowed by an admin; every holder's balance of it is cleared (no refund). */
    ADMIN_DISALLOW_ITEM(27),
    /** Balance of a deleted user's account that could not be handed out (player offline / outcome unknown); destroyed. Note gives the reason. */
    ACCOUNT_DELETE_LOSS(28);

    private final int code;

    LedgerType(int code) {
        this.code = code;
    }

    /** @return the stable persisted code. */
    public int code() {
        return code;
    }

    private static final LedgerType[] BY_CODE;
    static {
        int max = 0;
        for (LedgerType t : values()) max = Math.max(max, t.code);
        BY_CODE = new LedgerType[max + 1];
        for (LedgerType t : values()) BY_CODE[t.code] = t;
    }

    /** @return the type for {@code code}, or {@link #UNKNOWN_LEGACY} for an unknown code. Never null. */
    public static LedgerType fromCode(int code) {
        LedgerType t = code >= 0 && code < BY_CODE.length ? BY_CODE[code] : null;
        return t == null ? UNKNOWN_LEGACY : t;
    }

    /**
     * Maps a pre-#109 {@code kind} column value to its type. {@code TRANSFER_OUT} and
     * {@code TRANSFER_IN} both map to {@link #TRANSFER} (the migration negates the OUT amount).
     *
     * @return the mapped type, or {@link #UNKNOWN_LEGACY}. Never null.
     */
    public static LedgerType fromLegacyKind(@Nullable String kind) {
        if (kind == null) return UNKNOWN_LEGACY;
        return switch (kind) {
            case "DEPOSIT" -> DEPOSIT;
            case "WITHDRAW" -> WITHDRAW;
            case "TRANSFER_OUT", "TRANSFER_IN" -> TRANSFER;
            case "PAYOUT" -> PAYOUT;
            case "DIVIDEND" -> DIVIDEND;
            case "SHARE_STAMP" -> SHARE_STAMP;
            case "SHARE_REDEEM" -> SHARE_REDEEM;
            case "SHARE_TRADE" -> SHARE_TRADE;
            default -> UNKNOWN_LEGACY;
        };
    }
}
