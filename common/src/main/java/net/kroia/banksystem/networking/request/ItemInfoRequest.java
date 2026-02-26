package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.banking.clientdata.ItemInfoData;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class ItemInfoRequest extends BankSystemGenericRequest<ItemID, ItemInfoData> {



    @Override
    public String getRequestTypeID() {
        return ItemInfoRequest.class.getName();
    }

    @Override
    public ItemInfoData handleOnClient(ItemID input) {
        return null;
    }

    @Override
    public ItemInfoData handleOnServer(ItemID itemID, ServerPlayer sender) {
        if(!playerIsAdmin(sender)) {
            return null; // If the player is not an admin, return null
        }
        ItemInfoData data = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getItemInfoData(itemID);
        return data;
    }

    @Override
    public void encodeInput(FriendlyByteBuf buf, ItemID input) {
        buf.writeItem(input.getStack()); // Encode the ItemID as an ItemStack
    }

    @Override
    public void encodeOutput(FriendlyByteBuf buf, ItemInfoData output) {
        if(output == null)
            return; // Handle null output gracefully
        output.encode(buf); // Encode the ItemInfo
    }

    @Override
    public ItemID decodeInput(FriendlyByteBuf buf) {
        return new ItemID(buf.readItem()); // Decode the ItemID from an ItemStack
    }

    @Override
    public ItemInfoData decodeOutput(FriendlyByteBuf buf) {
        if(buf.readableBytes() == 0) {
            return null; // Handle empty output
        }
        return ItemInfoData.decode(buf); // Decode the ItemInfo
    }
}
