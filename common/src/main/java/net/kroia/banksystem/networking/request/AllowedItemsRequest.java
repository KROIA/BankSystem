package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public class AllowedItemsRequest extends BankSystemGenericRequest<Integer, List<ItemID>> {
    @Override
    public String getRequestTypeID() {
        return AllowedItemsRequest.class.getName();
    }

    @Override
    public List<ItemID> handleOnServer(Integer input, ServerPlayer sender)
    {
        return BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAllowedItems();

    }

    @Override
    public void encodeInput(FriendlyByteBuf buf, Integer input) {
        buf.writeInt(input);
    }

    @Override
    public void encodeOutput(FriendlyByteBuf buf, List<ItemID> output) {
        buf.writeInt(output.size());
        for(ItemID itemID : output)
        {
            itemID.encode(buf);
        }
    }

    @Override
    public Integer decodeInput(FriendlyByteBuf buf) {
        return buf.readInt();
    }

    @Override
    public List<ItemID> decodeOutput(FriendlyByteBuf buf) {
        int size = buf.readInt();
        List<ItemID> itemIDs = new java.util.ArrayList<>(size);
        for(int i = 0; i < size; i++)
        {
            ItemID itemID = new ItemID(buf);
            itemIDs.add(itemID);
        }
        return itemIDs;
    }
}
