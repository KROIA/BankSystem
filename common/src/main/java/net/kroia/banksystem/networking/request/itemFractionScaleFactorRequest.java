package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
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
    public void encodeInput(RegistryFriendlyByteBuf buf, ItemID input) {
        ItemID.STREAM_CODEC.encode(buf, input);
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, Integer output) {
        ByteBufCodecs.INT.encode(buf, output);
    }

    @Override
    public ItemID decodeInput(RegistryFriendlyByteBuf buf) {
        return ItemID.STREAM_CODEC.decode(buf);
    }

    @Override
    public Integer decodeOutput(RegistryFriendlyByteBuf buf) {
        return ByteBufCodecs.INT.decode(buf);
    }
}
