package net.kroia.banksystem.banking.clientdata;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.UUID;

public record UserData(UUID userUUID, String userName, boolean enableBankNotifications) {


    public static final StreamCodec<RegistryFriendlyByteBuf, UserData> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, p -> p.userUUID,
            ByteBufCodecs.STRING_UTF8, p -> p.userName,
            ByteBufCodecs.BOOL, p -> p.enableBankNotifications,
            UserData::new
    );

}
