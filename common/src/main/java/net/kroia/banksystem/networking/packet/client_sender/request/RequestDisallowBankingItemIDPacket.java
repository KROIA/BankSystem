package net.kroia.banksystem.networking.packet.client_sender.request;

import net.kroia.banksystem.networking.BankSystemNetworkPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankDataPacket;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class RequestDisallowBankingItemIDPacket extends BankSystemNetworkPacket {

    private ItemID itemID;

    public static void sendRequest(ItemID itemID)
    {
        new RequestDisallowBankingItemIDPacket(itemID).sendToServer();
    }
    public RequestDisallowBankingItemIDPacket(ItemID itemID)
    {
        this.itemID = itemID;
    }
    public RequestDisallowBankingItemIDPacket(FriendlyByteBuf buf)
    {
        super(buf);
    }

    public ItemID getItemID()
    {
        return itemID;
    }


    public void encode(FriendlyByteBuf buf) {
        buf.writeItem(itemID.getStack());
    }

    public void decode(FriendlyByteBuf buf)
    {
        itemID = new ItemID(buf.readItem());
    }

    protected void handleOnServer(ServerPlayer sender)
    {
        BACKEND_INSTANCES.SERVER_BANK_MANAGER.disallowItemID(itemID);
        SyncBankDataPacket.sendPacket(sender);
    }
}
