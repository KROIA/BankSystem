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
            ByteBufCodecs.BOOL, p -> p.enableBankNotifications,
            ByteBufCodecs.INT, p -> p.permissions,
            BankUserData::new
    );

    public final UUID userUUID;
    public final String userName;
    public boolean enableBankNotifications;
    public int permissions;

    public BankUserData(UUID userUUID, String userName, boolean enableBankNotifications, int permissions) {
        this.userUUID = userUUID;
        this.userName = userName;
        this.enableBankNotifications = enableBankNotifications;
        this.permissions = permissions;
    }


    /*@Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(userUUID);
        buf.writeUtf(userName);
        buf.writeBoolean(enableBankNotifications);
        buf.writeInt(permissions);
    }

    public static BankUserData decode(FriendlyByteBuf buf) {
        UUID userUUID = buf.readUUID();
        String userName = buf.readUtf();
        boolean enableBankNotifications = buf.readBoolean();
        int permissions = buf.readInt();
        return new BankUserData(userUUID, userName, enableBankNotifications, permissions);
    }*/
}
