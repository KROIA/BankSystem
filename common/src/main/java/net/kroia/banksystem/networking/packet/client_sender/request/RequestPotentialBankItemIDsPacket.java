package net.kroia.banksystem.networking.packet.client_sender.request;

import dev.architectury.networking.simple.MessageType;
import net.kroia.banksystem.banking.ServerBankManager;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncPotentialBankItemIDsPacket;
import net.kroia.modutilities.networking.NetworkPacketC2S;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class RequestPotentialBankItemIDsPacket extends NetworkPacketC2S {

    public static void sendRequest()
    {
        RequestPotentialBankItemIDsPacket packet = new RequestPotentialBankItemIDsPacket();
        packet.sendToServer();
    }

    public RequestPotentialBankItemIDsPacket()
    {

    }

    @Override
    public MessageType getType() {
        return BankSystemNetworking.REQUEST_POTENTIAL_BANK_ITEM_IDS;
    }

    public RequestPotentialBankItemIDsPacket(RegistryFriendlyByteBuf buf)
    {
        this.fromBytes(buf);
    }

    protected void handleOnServer(ServerPlayer sender)
    {
        SyncPotentialBankItemIDsPacket.sendPacket(sender, ServerBankManager.getPotentialBankItemIDs());
    }
}
