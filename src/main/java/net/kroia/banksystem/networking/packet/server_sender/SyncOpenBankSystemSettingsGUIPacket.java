package net.kroia.banksystem.networking.packet.server_sender;

import net.kroia.banksystem.BankSystemClientHooks;
import net.kroia.banksystem.networking.ModMessages;
import net.kroia.banksystem.screen.custom.BankSystemSettingScreen;
import net.kroia.modutilities.networking.NetworkPacket;
import net.minecraft.client.Minecraft;
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
        ModMessages.sendToPlayer(packet, player);
    }

    @Override
    protected void handleOnClient()
    {
        BankSystemClientHooks.openBankSystemSettingScreen();
    }
}
