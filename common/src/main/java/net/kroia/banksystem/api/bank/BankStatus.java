package net.kroia.banksystem.api.bank;

import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * Result of a bank mutation. Every value other than {@link #SUCCESS} means nothing changed.
 * The wire format uses the ordinal: new values are only appended.
 */
public enum BankStatus {
    /** Done. */
    SUCCESS,
    /** The free (or locked) balance does not cover the amount. */
    FAILED_NOT_ENOUGH_FUNDS,
    /** The target balance would overflow. */
    FAILED_OVERFLOW,
    /** A negative amount was passed. */
    FAILED_NEGATIVE_VALUE,
    /** Internal: the target bank is not a server bank. */
    FAILED_WRONG_INSTANCE_TYPE,
    /** The item id is not valid. */
    FAILED_INVALID_ITEM_ID,
    /** The account (e.g. a transfer target) does not exist or has no bank for this item. */
    FAILED_NO_BANK,
    /**
     * Slave only: the master was not reached, or (since 2.2.0) the transfer status check says the call was not
     * applied. Nothing changed, so rolling back is safe. An outcome that stays unknown fails the future instead.
     */
    FAILED_NO_MASTER_CONNECTION,
    /**
     * The slot is bound to an external currency-mod account, but the provider is not
     * currently available (mod uninstalled, adapter failed to initialize, or the
     * referenced account can no longer be opened). Writes fail cleanly with this
     * status and no state change; reads report a degraded balance.
     * <p>
     * Existing consumers treat this like any other non-{@link #SUCCESS} status —
     * the transaction is aborted, nothing changed.
     * <p>
     * Added in v2.0.5 (Task #33). Appended to the enum tail so the wire-format
     * ordinal used by {@link #STREAM_CODEC} stays stable for existing values.
     */
    FAILED_EXTERNAL_UNAVAILABLE,
    /**
     * Task #117 — the account is frozen by an admin and the call was a player-initiated
     * movement. Nothing changed. Appended to keep existing ordinals stable.
     *
     * @since 2.2.0
     */
    FAILED_ACCOUNT_FROZEN;

    public static final StreamCodec<RegistryFriendlyByteBuf, BankStatus> STREAM_CODEC = ExtraCodecUtils.enumStreamCodec(BankStatus.class);
}
