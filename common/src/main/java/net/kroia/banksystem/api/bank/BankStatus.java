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
    FAILED_NO_BANK;

    public static final StreamCodec<RegistryFriendlyByteBuf, BankStatus> STREAM_CODEC = ExtraCodecUtils.enumStreamCodec(BankStatus.class);
}
