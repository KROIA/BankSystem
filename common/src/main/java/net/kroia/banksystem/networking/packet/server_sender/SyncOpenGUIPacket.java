package net.kroia.banksystem.networking.packet.server_sender;

import dev.architectury.networking.simple.MessageType;
import net.kroia.banksystem.BankSystemClientHooks;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.modutilities.networking.NetworkPacketS2C;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class SyncOpenGUIPacket extends NetworkPacketS2C {

    enum GUIType
    {
        BANK_SYSTEM_SETTING,
        BANK_ACCOUNT
    }

    GUIType guiType;
    UUID targetPlayerUUID;


    public SyncOpenGUIPacket() {
        super();
    }

    @Override
    public MessageType getType() {
        return BankSystemNetworking.SYNC_OPEN_GUI;
    }

    public SyncOpenGUIPacket(RegistryFriendlyByteBuf friendlyByteBuf) {
        super(friendlyByteBuf);
    }

    public static void send_openBankSystemSettingScreen(ServerPlayer player)
    {
        SyncOpenGUIPacket packet = new SyncOpenGUIPacket();
        packet.guiType = GUIType.BANK_SYSTEM_SETTING;
        packet.sendTo(player);
    }
    public static void send_openBankAccountScreen(ServerPlayer player, UUID targetPlayerUUID)
    {
        SyncOpenGUIPacket packet = new SyncOpenGUIPacket();
        packet.guiType = GUIType.BANK_ACCOUNT;
        packet.targetPlayerUUID = targetPlayerUUID;
        packet.sendTo(player);
    }

    @Override
    protected void handleOnClient()
    {
        switch(guiType)
        {
            case BANK_SYSTEM_SETTING:
                BankSystemClientHooks.openBankSystemSettingScreen();
                break;
            case BANK_ACCOUNT:
                BankSystemClientHooks.openBankAccountScreen(targetPlayerUUID);
                break;
        }
    }

    @Override
    public void toBytes(RegistryFriendlyByteBuf buf) {
        buf.writeEnum(guiType);

        if(targetPlayerUUID == null)
            targetPlayerUUID = new UUID(0,0);
        buf.writeUUID(targetPlayerUUID);
    }

    @Override
    public void fromBytes(RegistryFriendlyByteBuf buf) {
        guiType = buf.readEnum(GUIType.class);
        targetPlayerUUID = buf.readUUID();
    }
}
