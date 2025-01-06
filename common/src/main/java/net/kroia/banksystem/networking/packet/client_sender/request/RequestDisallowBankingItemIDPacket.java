package net.kroia.banksystem.networking.packet.client_sender.request;

import dev.architectury.networking.simple.MessageType;
import net.kroia.banksystem.banking.ServerBankManager;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankDataPacket;
import net.kroia.modutilities.networking.NetworkPacketC2S;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class RequestDisallowBankingItemIDPacket extends NetworkPacketC2S {

    private String itemID;

    @Override
    public MessageType getType() {
        return BankSystemNetworking.REQUEST_DISALLOW_BANKING_ITEM_ID;
    }

    public static void sendRequest(String itemID)
    {
        new RequestDisallowBankingItemIDPacket(itemID).sendToServer();
    }
    public RequestDisallowBankingItemIDPacket(String itemID)
    {
        this.itemID = itemID;
    }
    public RequestDisallowBankingItemIDPacket(RegistryFriendlyByteBuf buf)
    {
        this.fromBytes(buf);
    }

    public String getItemID()
    {
        return itemID;
    }


    @Override
    public void toBytes(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(itemID);
    }

    @Override
    public void fromBytes(RegistryFriendlyByteBuf buf)
    {
        itemID = buf.readUtf();
    }

    protected void handleOnServer(ServerPlayer sender)
    {
        ServerBankManager.disallowItemID(itemID);
        SyncBankDataPacket.sendPacket(sender);
    }


}
