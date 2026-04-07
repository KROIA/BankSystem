package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.api.ISyncServerBankManager;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class DisallowItemRequest extends BankSystemGenericRequest<ItemID, Boolean> {
    @Override
    public String getRequestTypeID() {
        return DisallowItemRequest.class.getName();
    }
    @Override
    public boolean needsRoutingToMaster() { return true; }

    @Override
    public CompletableFuture<Boolean> handleOnServer(ItemID itemID, ServerPlayer sender) {
        return handleOnMasterServer(itemID, sender.getUUID());
    }
    @Override
    public CompletableFuture<Boolean> handleOnMasterServer(ItemID itemID, UUID sender) {
        ISyncServerBankManager bankManager = getSyncBankManager();
        CompletableFuture<Boolean>  future = new CompletableFuture<>();
        // Check if sender has permission to allow the item
        if(playerIsAdmin(sender)) {
            future.complete(bankManager.disallowItemID(itemID));
            return future;
        }
        future.complete(false);
        return future;
    }

    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, ItemID input) {
        ItemID.STREAM_CODEC.encode(buf, input);
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, Boolean output) {
        ByteBufCodecs.BOOL.encode(buf, output);
    }

    @Override
    public ItemID decodeInput(RegistryFriendlyByteBuf buf) {
        return ItemID.STREAM_CODEC.decode(buf);
    }

    @Override
    public Boolean decodeOutput(RegistryFriendlyByteBuf buf) {
        return ByteBufCodecs.BOOL.decode(buf);
    }
}
