package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.banking.bank.SyncServerBank;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class AllowItemRequest extends BankSystemGenericRequest<AllowItemRequest.Data, Boolean> {

    public record Data(ItemID itemID)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, Data> STREAM_CODEC =
                StreamCodec.composite(
                        ItemID.STREAM_CODEC, Data::itemID,
                        Data::new
                );
    }

    @Override
    public String getRequestTypeID() {
        return AllowItemRequest.class.getName();
    }
    @Override
    public boolean needsRoutingToMaster() { return true; }

    @Override
    public CompletableFuture<Boolean> handleOnServer(AllowItemRequest.Data data, ServerPlayer sender) {
        // Check if sender has permission to allow the item
        CompletableFuture<Boolean>  future = new CompletableFuture<>();
        if(playerIsAdmin(sender)) {
            ISyncServerBankManager bankManager = getSyncBankManager();
            if(data != null)
            {
                if(bankManager.isItemIDAllowed(data.itemID))
                {
                    ServerPlayerUtilities.printToClientConsole(sender, BankSystemTextMessages.getItemAlreadyAllowedMessage(data.itemID.getName()));
                    future.complete(true);
                    return future;
                }

                if(bankManager.allowItemID(data.itemID))
                {
                    String smallestAmountStr = SyncServerBank.getFormattedAmountStatic(1);
                    ServerPlayerUtilities.printToClientConsole(sender, BankSystemTextMessages.getItemNowAllowedMessage(data.itemID.getName(), smallestAmountStr));
                    future.complete(true);
                    return future;
                }
                else
                {
                    ServerPlayerUtilities.printToClientConsole(sender, BankSystemTextMessages.getItemNowAllowedFailedMessage(data.itemID.getName()));
                }
            }
            else
            {
                ServerPlayerUtilities.printToClientConsole(sender, BankSystemTextMessages.getInvalidItemIDMessage("null"));
            }
        }
        future.complete(false);
        return future;
    }
    @Override
    public CompletableFuture<Boolean> handleOnMasterServer(AllowItemRequest.Data data, UUID sender) {
        // Check if sender has permission to allow the item

        CompletableFuture<Boolean>  future = new CompletableFuture<>();
        if(playerIsAdmin(sender)) {
            ISyncServerBankManager bankManager = getSyncBankManager();
            if(data != null)
            {
                if(bankManager.isItemIDAllowed(data.itemID))
                {
                    // todo: replace by sending a custom packet to the slaves client back to print the message
                    ServerPlayerUtilities.printToClientConsole(sender, BankSystemTextMessages.getItemAlreadyAllowedMessage(data.itemID.getName()));
                    future.complete(true);
                    return future;
                }

                if(bankManager.allowItemID(data.itemID))
                {
                    String smallestAmountStr = SyncServerBank.getFormattedAmountStatic(1);
                    // todo: replace by sending a custom packet to the slaves client back to print the message
                    ServerPlayerUtilities.printToClientConsole(sender, BankSystemTextMessages.getItemNowAllowedMessage(data.itemID.getName(), smallestAmountStr));
                    future.complete(true);
                    return future;
                }
                else
                {
                    // todo: replace by sending a custom packet to the slaves client back to print the message
                    ServerPlayerUtilities.printToClientConsole(sender, BankSystemTextMessages.getItemNowAllowedFailedMessage(data.itemID.getName()));
                }
            }
            else
            {
                // todo: replace by sending a custom packet to the slaves client back to print the message
                ServerPlayerUtilities.printToClientConsole(sender, BankSystemTextMessages.getInvalidItemIDMessage("null"));
            }
        }
        future.complete(false);
        return future;
    }

    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, AllowItemRequest.Data input) {
        Data.STREAM_CODEC.encode(buf, input);
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, Boolean output) {
        ByteBufCodecs.BOOL.encode(buf, output);
    }

    @Override
    public AllowItemRequest.Data decodeInput(RegistryFriendlyByteBuf buf) {
        return Data.STREAM_CODEC.decode(buf);
    }

    @Override
    public Boolean decodeOutput(RegistryFriendlyByteBuf buf) {
        return ByteBufCodecs.BOOL.decode(buf);
    }
}
