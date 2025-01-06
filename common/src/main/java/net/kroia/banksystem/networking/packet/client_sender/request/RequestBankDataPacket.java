package net.kroia.banksystem.networking.packet.client_sender.request;

import dev.architectury.networking.simple.MessageType;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankDataPacket;
import net.kroia.modutilities.networking.NetworkPacketC2S;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class RequestBankDataPacket extends NetworkPacketC2S {
    UUID playerUUID;

    @Override
    public MessageType getType() {
        return BankSystemNetworking.REQUEST_BANK_DATA;
    }

    public RequestBankDataPacket(UUID playerUUID) {
        super();
        this.playerUUID = playerUUID;
    }
    public RequestBankDataPacket(RegistryFriendlyByteBuf buf) {
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
    public void toBytes(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(playerUUID);

    }
    @Override
    public void fromBytes(RegistryFriendlyByteBuf buf) {
        playerUUID = buf.readUUID();
    }

    @Override
    protected void handleOnServer(ServerPlayer sender)
    {
        SyncBankDataPacket.sendPacket(sender, playerUUID);
    }


}
