package net.kroia.banksystem.banking.clientdata;

import net.kroia.modutilities.networking.INetworkPayloadEncoder;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

public class UserData implements INetworkPayloadEncoder {

    public final UUID userUUID;
    public final String userName;
    public final boolean enableBankNotifications;

    public UserData(UUID userUUID, String userName, boolean enableBankNotifications) {
        this.userUUID = userUUID;
        this.userName = userName;
        this.enableBankNotifications = enableBankNotifications;
    }


    @Override
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
    }
}
