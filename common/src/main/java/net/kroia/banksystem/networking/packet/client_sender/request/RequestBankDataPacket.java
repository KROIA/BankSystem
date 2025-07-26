package net.kroia.banksystem.networking.packet.client_sender.request;

import net.kroia.banksystem.networking.BankSystemNetworkPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankDataPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class RequestBankDataPacket extends BankSystemNetworkPacket {
    UUID playerUUID;

    public RequestBankDataPacket(UUID playerUUID) {
        super();
        this.playerUUID = playerUUID;
    }
    public RequestBankDataPacket(FriendlyByteBuf buf) {
        super(buf);
    }

    public static void sendRequest(UUID playerUUID)
    {
        RequestBankDataPacket packet = new RequestBankDataPacket(playerUUID);
        packet.sendToServer();
    }
    public static void sendRequest()
    {
        UUID thisPlayer = Minecraft.getInstance().player.getUUID();
        sendRequest(thisPlayer);
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(playerUUID);

    }
    @Override
    public void decode(FriendlyByteBuf buf) {
        playerUUID = buf.readUUID();
    }

    @Override
    protected void handleOnServer(ServerPlayer sender)
    {
        SyncBankDataPacket.sendPacket(sender, playerUUID);
    }
}
