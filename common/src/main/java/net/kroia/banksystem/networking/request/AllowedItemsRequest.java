package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
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
    public void encodeInput(RegistryFriendlyByteBuf buf, Integer input) {
        ByteBufCodecs.INT.encode(buf, input);
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, List<ItemID> output) {
        ExtraCodecUtils.listStreamCodec(ItemID.STREAM_CODEC).encode(buf,  output);
    }

    @Override
    public Integer decodeInput(RegistryFriendlyByteBuf buf) {
        return ByteBufCodecs.INT.decode(buf);
    }

    @Override
    public List<ItemID> decodeOutput(RegistryFriendlyByteBuf buf) {
        return ExtraCodecUtils.listStreamCodec(ItemID.STREAM_CODEC).decode(buf);
    }
}
