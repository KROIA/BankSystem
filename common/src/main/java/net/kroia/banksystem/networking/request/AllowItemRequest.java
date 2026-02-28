package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.banking.bank.Bank;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;

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
    public Boolean handleOnClient(AllowItemRequest.Data input) {
        return null;
    }

    @Override
    public Boolean handleOnServer(AllowItemRequest.Data data, ServerPlayer sender) {
        // Check if sender has permission to allow the item
        if(playerIsAdmin(sender)) {
            if(data != null)
            {
                if(BACKEND_INSTANCES.SERVER_BANK_MANAGER.isItemIDAllowed(data.itemID))
                {
                    ServerPlayerUtilities.printToClientConsole(sender, BankSystemTextMessages.getItemAlreadyAllowedMessage(data.itemID.getName()));
                    return true;
                }

                if(BACKEND_INSTANCES.SERVER_BANK_MANAGER.allowItemID(data.itemID))
                {
                    String smallestAmountStr = Bank.getFormattedAmountStatic(1);
                    ServerPlayerUtilities.printToClientConsole(sender, BankSystemTextMessages.getItemNowAllowedMessage(data.itemID.getName(), smallestAmountStr));
                    return true;
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
        return false;
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
