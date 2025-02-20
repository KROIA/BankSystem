package net.kroia.banksystem.networking.packet.client_sender.request;

import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncItemInfoPacket;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.NetworkPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class RequestItemInfoPacket extends NetworkPacket {

    ItemID itemID;
    public RequestItemInfoPacket(ItemID itemID) {
        super();
        this.itemID = itemID;
    }

    public RequestItemInfoPacket(FriendlyByteBuf buf) {
        super(buf);
    }

    public static void sendRequest(ItemID itemID) {
        BankSystemNetworking.sendToServer(new RequestItemInfoPacket(itemID));
    }

    @Override
    public void toBytes(FriendlyByteBuf buf) {
        buf.writeItem(itemID.getStack());
    }

    @Override
    public void fromBytes(FriendlyByteBuf buf) {
        itemID = new ItemID(buf.readItem());
    }

    @Override
    protected void handleOnServer(ServerPlayer sender) {
        SyncItemInfoPacket.sendResponse(sender, itemID);
    }
}
