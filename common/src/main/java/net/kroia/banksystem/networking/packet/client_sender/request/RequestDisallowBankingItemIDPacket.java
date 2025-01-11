package net.kroia.banksystem.networking.packet.client_sender.request;

import net.kroia.banksystem.banking.ServerBankManager;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankDataPacket;
import net.kroia.modutilities.networking.NetworkPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class RequestDisallowBankingItemIDPacket extends NetworkPacket {

    private String itemID;

    public static void sendRequest(String itemID)
    {
        BankSystemNetworking.sendToServer(new RequestDisallowBankingItemIDPacket(itemID));
    }
    public RequestDisallowBankingItemIDPacket(String itemID)
    {
        this.itemID = itemID;
    }
    public RequestDisallowBankingItemIDPacket(FriendlyByteBuf buf)
    {
        this.fromBytes(buf);
    }

    public String getItemID()
    {
        return itemID;
    }


    public void toBytes(FriendlyByteBuf buf) {
        buf.writeUtf(itemID);
    }

    public void fromBytes(FriendlyByteBuf buf)
    {
        itemID = buf.readUtf();
    }

    protected void handleOnServer(ServerPlayer sender)
    {
        ServerBankManager.disallowItemID(itemID);
        SyncBankDataPacket.sendPacket(sender);
    }
}
