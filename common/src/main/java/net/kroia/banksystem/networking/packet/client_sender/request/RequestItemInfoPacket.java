package net.kroia.banksystem.networking.packet.client_sender.request;

import net.kroia.banksystem.networking.BankSystemNetworkPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncItemInfoPacket;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class RequestItemInfoPacket extends BankSystemNetworkPacket {

    ItemID itemID;
    public RequestItemInfoPacket(ItemID itemID) {
        super();
        this.itemID = itemID;
    }

    public RequestItemInfoPacket(FriendlyByteBuf buf) {
        super(buf);
    }

    public static void sendRequest(ItemID itemID) {
        new RequestItemInfoPacket(itemID).sendToServer();
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeItem(itemID.getStack());
    }

    @Override
    public void decode(FriendlyByteBuf buf) {
        itemID = new ItemID(buf.readItem());
    }

    @Override
    protected void handleOnServer(ServerPlayer sender) {
        SyncItemInfoPacket.sendResponse(sender, itemID);
    }
}
