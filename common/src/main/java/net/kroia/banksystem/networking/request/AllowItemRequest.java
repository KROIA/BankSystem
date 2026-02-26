package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.banking.bank.Bank;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.kroia.modutilities.networking.INetworkPayloadConverter;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public class AllowItemRequest extends BankSystemGenericRequest<AllowItemRequest.Data, Boolean> {

    public record Data(ItemID itemID, int itemFractionScaleFactor)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, Data> STREAM_CODEC =
                StreamCodec.composite(
                        ItemID.STREAM_CODEC, Data::itemID,
                        ByteBufCodecs.INT, Data::itemFractionScaleFactor,
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

                if(BACKEND_INSTANCES.SERVER_BANK_MANAGER.allowItemID(data.itemID, data.itemFractionScaleFactor))
                {
                    int centScaleFactor = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getItemFractionScaleFactor(data.itemID);
                    String smallestAmountStr = Bank.getFormattedAmount(1, centScaleFactor);
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
    public void encodeInput(FriendlyByteBuf buf, AllowItemRequest.Data input) {
        Data.STREAM_CODEC.encode(buf, input);
        //input.encode(buf); // Encode the ItemID and cent scale factor
    }

    @Override
    public void encodeOutput(FriendlyByteBuf buf, Boolean output) {
        buf.writeBoolean(output != null && output); // Encode the Boolean output
    }

    @Override
    public AllowItemRequest.Data decodeInput(FriendlyByteBuf buf) {
        AllowItemRequest.Data data = new AllowItemRequest.Data();
        data.decode(buf); // Decode the ItemID and cent scale factor
        return data;
    }

    @Override
    public Boolean decodeOutput(FriendlyByteBuf buf) {
        return buf.readBoolean(); // Decode the Boolean output
    }
}
