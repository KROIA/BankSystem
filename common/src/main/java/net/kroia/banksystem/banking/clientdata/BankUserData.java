package net.kroia.banksystem.banking.clientdata;

import net.kroia.modutilities.networking.INetworkPayloadEncoder;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

public class BankUserData implements INetworkPayloadEncoder {

    public final UUID userUUID;
    public final String userName;
    public final boolean enableBankNotifications;
    public final int permissions;

    public BankUserData(UUID userUUID, String userName, boolean enableBankNotifications, int permissions) {
        this.userUUID = userUUID;
        this.userName = userName;
        this.enableBankNotifications = enableBankNotifications;
        this.permissions = permissions;
    }


    @Override
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
    }
}
