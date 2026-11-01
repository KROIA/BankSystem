package net.kroia.banksystem.banking.clientdata;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.UUID;

public class BankUserData  {

    public static final StreamCodec<RegistryFriendlyByteBuf, BankUserData> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, p -> p.userUUID,
            ByteBufCodecs.STRING_UTF8, p -> p.userName,
            ByteBufCodecs.INT, p -> p.permissions,
            BankUserData::new
    );

    public final UUID userUUID;
    public final String userName;
    public int permissions;

    public BankUserData(UUID userUUID, String userName, int permissions) {
        this.userUUID = userUUID;
        this.userName = userName;
        this.permissions = permissions;
    }
}
