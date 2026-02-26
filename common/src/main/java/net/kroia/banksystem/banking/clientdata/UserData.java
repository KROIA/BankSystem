package net.kroia.banksystem.banking.clientdata;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.UUID;

public class UserData {


    public static final StreamCodec<RegistryFriendlyByteBuf, UserData> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, p -> p.userUUID,
            ByteBufCodecs.STRING_UTF8, p -> p.userName,
            ByteBufCodecs.BOOL, p -> p.enableBankNotifications,
            UserData::new
    );

    public final UUID userUUID;
    public final String userName;
    public final boolean enableBankNotifications;

    public UserData(UUID userUUID, String userName, boolean enableBankNotifications) {
        this.userUUID = userUUID;
        this.userName = userName;
        this.enableBankNotifications = enableBankNotifications;
    }


    /*@Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(userUUID);
        buf.writeUtf(userName);
        buf.writeBoolean(enableBankNotifications);
    }

    public static UserData decode(FriendlyByteBuf buf) {
        UUID userUUID = buf.readUUID();
        String userName = buf.readUtf();
        boolean enableBankNotifications = buf.readBoolean();
        return new UserData(userUUID, userName, enableBankNotifications);
    }*/
}
