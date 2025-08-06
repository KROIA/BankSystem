package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class AllowItemRequest extends BankSystemGenericRequest<ItemID, Boolean> {
    @Override
    public String getRequestTypeID() {
        return AllowItemRequest.class.getName();
    }

    @Override
    public Boolean handleOnClient(ItemID input) {
        return null;
    }

    @Override
    public Boolean handleOnServer(ItemID itemID, ServerPlayer sender) {
        // Check if sender has permission to allow the item
        if(playerIsAdmin(sender)) {
            if(itemID != null)
            {
                if(BACKEND_INSTANCES.SERVER_BANK_MANAGER.isItemIDAllowed(itemID))
                {
                    ServerPlayerUtilities.printToClientConsole(sender, BankSystemTextMessages.getItemAlreadyAllowedMessage(itemID.getName()));
                    return true;
                }
                if(BACKEND_INSTANCES.SERVER_BANK_MANAGER.allowItemID(itemID))
                {
                    ServerPlayerUtilities.printToClientConsole(sender, BankSystemTextMessages.getItemNowAllowedMessage(itemID.getName()));
                    return true;
                }
                else
                {
                    ServerPlayerUtilities.printToClientConsole(sender, BankSystemTextMessages.getItemNowAllowedFailedMessage(itemID.getName()));
                }
            }
            else
            {
                ServerPlayerUtilities.printToClientConsole(sender, BankSystemTextMessages.getInvalidItemIDMessage("null"));
            }
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
