package net.kroia.banksystem.networking.packet.client_sender.request;

import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankDataPacket;
import net.kroia.modutilities.networking.NetworkPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class RequestBankDataPacket extends NetworkPacket {
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
        BankSystemNetworking.sendToServer(packet);
    }
    public static void sendRequest()
    {
        UUID thisPlayer = Minecraft.getInstance().player.getUUID();
        sendRequest(thisPlayer);
    }

    @Override
    public void toBytes(FriendlyByteBuf buf) {
        buf.writeUUID(playerUUID);

    }
    @Override
    public void fromBytes(FriendlyByteBuf buf) {
        playerUUID = buf.readUUID();
    }

    @Override
    protected void handleOnServer(ServerPlayer sender)
    {
        SyncBankDataPacket.sendPacket(sender, playerUUID);
    }
}
