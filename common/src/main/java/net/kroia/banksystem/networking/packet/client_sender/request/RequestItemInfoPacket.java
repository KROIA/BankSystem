package net.kroia.banksystem.networking.packet.client_sender.request;

import dev.architectury.networking.simple.MessageType;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncItemInfoPacket;
import net.kroia.modutilities.networking.NetworkPacketC2S;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class RequestItemInfoPacket extends NetworkPacketC2S {

    String itemID;
    @Override
    public MessageType getType() {
        return BankSystemNetworking.REQUEST_ITEM_INFO;
    }
    public RequestItemInfoPacket(String itemID) {
        super();
        this.itemID = itemID;
    }

    public RequestItemInfoPacket(RegistryFriendlyByteBuf buf) {
        super(buf);
    }

    public static void sendRequest(String itemID) {
        new RequestItemInfoPacket(itemID).sendToServer();
    }

    @Override
    public void toBytes(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(itemID);
    }

    @Override
    public void fromBytes(RegistryFriendlyByteBuf buf) {
        itemID = buf.readUtf();
    }

    @Override
    protected void handleOnServer(ServerPlayer sender) {
        SyncItemInfoPacket.sendResponse(sender, itemID);
    }


}
