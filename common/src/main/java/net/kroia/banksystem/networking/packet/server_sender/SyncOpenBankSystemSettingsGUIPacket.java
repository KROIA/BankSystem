package net.kroia.banksystem.networking.packet.server_sender;

import net.kroia.banksystem.BankSystemClientHooks;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.modutilities.networking.NetworkPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class SyncOpenBankSystemSettingsGUIPacket extends NetworkPacket {

    public SyncOpenBankSystemSettingsGUIPacket() {
        super();
    }
    public SyncOpenBankSystemSettingsGUIPacket(FriendlyByteBuf friendlyByteBuf) {
        super(friendlyByteBuf);
    }

    public static void send(ServerPlayer player)
    {
        SyncOpenBankSystemSettingsGUIPacket packet = new SyncOpenBankSystemSettingsGUIPacket();
        BankSystemNetworking.sendToClient(player, packet);
    }

    @Override
    protected void handleOnClient()
    {
        BankSystemClientHooks.openBankSystemSettingScreen();
    }

    @Override
    public void toBytes(FriendlyByteBuf buf) {
        buf.writeInt(0);
    }

    @Override
    public void fromBytes(FriendlyByteBuf buf) {

    }
}
