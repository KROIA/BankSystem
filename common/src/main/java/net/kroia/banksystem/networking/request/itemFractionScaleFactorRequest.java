package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class itemFractionScaleFactorRequest extends BankSystemGenericRequest<ItemID, Integer> {

    @Override
    public String getRequestTypeID() {
        return itemFractionScaleFactorRequest.class.getSimpleName();
    }

    @Override
    public Integer handleOnClient(ItemID input) {
        return null;
    }

    @Override
    public Integer handleOnServer(ItemID input, ServerPlayer sender) {
        // Get the scale factor for the itemID
        return BACKEND_INSTANCES.SERVER_BANK_MANAGER.getItemFractionScaleFactor(input);
    }

    @Override
    public void encodeInput(FriendlyByteBuf buf, ItemID input) {
        input.encode(buf);
    }

    @Override
    public void encodeOutput(FriendlyByteBuf buf, Integer output) {
        buf.writeInt(output);
    }

    @Override
    public ItemID decodeInput(FriendlyByteBuf buf) {
        return new ItemID(buf);
    }

    @Override
    public Integer decodeOutput(FriendlyByteBuf buf) {
        return buf.readInt();
    }
}
