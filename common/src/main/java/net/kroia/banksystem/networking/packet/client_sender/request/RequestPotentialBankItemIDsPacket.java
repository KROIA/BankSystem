package net.kroia.banksystem.networking.packet.client_sender.request;

import net.kroia.banksystem.banking.ServerBankManager;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncPotentialBankItemIDsPacket;
import net.kroia.modutilities.networking.NetworkPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class RequestPotentialBankItemIDsPacket extends NetworkPacket {

    public static void sendRequest()
    {
        RequestPotentialBankItemIDsPacket packet = new RequestPotentialBankItemIDsPacket();
        BankSystemNetworking.sendToServer(packet);
    }

    public RequestPotentialBankItemIDsPacket()
    {

    }
    public RequestPotentialBankItemIDsPacket(FriendlyByteBuf buf)
    {
        this.fromBytes(buf);
    }


    public void toBytes(FriendlyByteBuf buf) {

    }

    public void fromBytes(FriendlyByteBuf buf)
    {

    }

    protected void handleOnServer(ServerPlayer sender)
    {
        SyncPotentialBankItemIDsPacket.sendPacket(sender, ServerBankManager.getPotentialBankItemIDs());
    }
}
