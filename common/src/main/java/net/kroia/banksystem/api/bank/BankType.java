package net.kroia.banksystem.api.bank;

import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

public enum BankType {
    MONEY,
    ITEM;

    public static final StreamCodec<RegistryFriendlyByteBuf, BankType> STREAM_CODEC = ExtraCodecUtils.enumStreamCodec(BankType.class);
}
