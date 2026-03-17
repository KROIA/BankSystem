package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.banking.clientdata.ItemInfoData;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.CompletableFuture;

public class ItemInfoRequest extends BankSystemGenericRequest<ItemID, ItemInfoData> {



    @Override
    public String getRequestTypeID() {
        return ItemInfoRequest.class.getName();
    }

    //@Override
    //public CompletableFuture<ItemInfoData> handleOnClient(ItemID input) {
    //    CompletableFuture<ItemInfoData> future = new CompletableFuture<>();
    //    future.complete(null);
    //    return future;
    //}

    @Override
    public CompletableFuture<ItemInfoData> handleOnServer(ItemID itemID, ServerPlayer sender) {
        if(!playerIsAdmin(sender)) {
            return null; // If the player is not an admin, return null
        }
        CompletableFuture<ItemInfoData> future = new CompletableFuture<>();
        future.complete(BACKEND_INSTANCES.SERVER_BANK_MANAGER.getItemInfoData(itemID));
        return future;
    }

    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, ItemID input) {
        ItemID.STREAM_CODEC.encode(buf, input);
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, ItemInfoData output) {
        ExtraCodecUtils.nullable(ItemInfoData.STREAM_CODEC).encode(buf, output);
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
        return ExtraCodecUtils.nullable(ItemInfoData.STREAM_CODEC).decode(buf); // Decode the ItemInfo
    }
}
