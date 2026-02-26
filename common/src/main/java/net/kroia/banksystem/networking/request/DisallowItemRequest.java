package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class DisallowItemRequest extends BankSystemGenericRequest<ItemID, Boolean> {
    @Override
    public String getRequestTypeID() {
        return DisallowItemRequest.class.getName();
    }

    @Override
    public Boolean handleOnClient(ItemID input) {
        return null;
    }

    @Override
    public Boolean handleOnServer(ItemID itemID, ServerPlayer sender) {
        // Check if sender has permission to allow the item
        if(playerIsAdmin(sender)) {
            return BACKEND_INSTANCES.SERVER_BANK_MANAGER.disallowItemID(itemID);
        }
        return false;
    }

    @Override
    public void encodeInput(FriendlyByteBuf buf, ItemID input) {
        buf.writeItem(input.getStack()); // Encode the ItemID as an ItemStack
    }

    @Override
    public void encodeOutput(FriendlyByteBuf buf, Boolean output) {
        buf.writeBoolean(output != null && output); // Encode the Boolean output
    }

    @Override
    public ItemID decodeInput(FriendlyByteBuf buf) {
        return new ItemID(buf.readItem()); // Decode the ItemID from an ItemStack
    }

    @Override
    public Boolean decodeOutput(FriendlyByteBuf buf) {
        return buf.readBoolean(); // Decode the Boolean output
    }
}
