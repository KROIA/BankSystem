package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.banking.clientdata.ItemInfoData;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.network.RegistryFriendlyByteBuf;
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
        return BACKEND_INSTANCES.SERVER_BANK_MANAGER.getItemInfoData(itemID);
    }

    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, ItemID input) {
        ItemID.STREAM_CODEC.encode(buf, input);
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, ItemInfoData output) {
        if(output == null)
            return; // Handle null output gracefully
        ItemInfoData.STREAM_CODEC.encode(buf, output);
    }

    @Override
    public ItemID decodeInput(RegistryFriendlyByteBuf buf) {
        return ItemID.STREAM_CODEC.decode(buf);
    }

    @Override
    public ItemInfoData decodeOutput(RegistryFriendlyByteBuf buf) {
        if(buf.readableBytes() == 0) {
            return null; // Handle empty output
        }
        return ItemInfoData.STREAM_CODEC.decode(buf); // Decode the ItemInfo
    }
}
