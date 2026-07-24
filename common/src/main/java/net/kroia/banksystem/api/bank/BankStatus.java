package net.kroia.banksystem.api.bank;

import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

public enum BankStatus {
    SUCCESS,
    FAILED_NOT_ENOUGH_FUNDS,
    FAILED_OVERFLOW,
    FAILED_NEGATIVE_VALUE,
    FAILED_WRONG_INSTANCE_TYPE,
    FAILED_INVALID_ITEM_ID,
    FAILED_NO_BANK,
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
    FAILED_EXTERNAL_UNAVAILABLE;

    public static final StreamCodec<RegistryFriendlyByteBuf, BankStatus> STREAM_CODEC = ExtraCodecUtils.enumStreamCodec(BankStatus.class);
}
