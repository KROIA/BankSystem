package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.banking.clientdata.MinimalBankData;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.INetworkPayloadEncoder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class MinimalBankDataRequest extends BankSystemGenericRequest<MinimalBankDataRequest.InputType, MinimalBankData> {

    public static class InputType implements INetworkPayloadEncoder
    {
        public final UUID userUUID;
        public final ItemID itemID;


        public InputType(UUID userUUID, ItemID itemID) {
            this.userUUID = userUUID;
            this.itemID = itemID;
        }

        @Override
        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(userUUID);
            buf.writeItem(itemID.getStack());
        }

        public static InputType decode(FriendlyByteBuf buf) {
            UUID userUUID = buf.readUUID();
            ItemID itemID = new ItemID(buf.readItem());
            return new InputType(userUUID, itemID);
        }
    }


    @Override
    public String getRequestTypeID() {
        return MinimalBankDataRequest.class.getName();
    }

    @Override
    public MinimalBankData handleOnClient(InputType input) {
        return null;
    }

    @Override
    public MinimalBankData handleOnServer(InputType input, ServerPlayer sender) {
        if(input.userUUID.compareTo(sender.getUUID()) == 0) {
            // If the request is for the sender's own data, check if they are an admin
            if(!playerIsAdmin(sender)) {
                return null; // If the player is not an admin, return null
            }
        }
        return BACKEND_INSTANCES.SERVER_BANK_MANAGER.getMinimalBankData(input.userUUID, input.itemID);
    }

    @Override
    public void encodeInput(FriendlyByteBuf buf, InputType input) {
        input.encode(buf); // Encode the InputType which contains userUUID and itemID
    }

    @Override
    public void encodeOutput(FriendlyByteBuf buf, MinimalBankData output) {
        buf.writeBoolean(output != null);
        if(output != null) {
            output.encode(buf); // Encode the MinimalBankData
        }
    }

    @Override
    public InputType decodeInput(FriendlyByteBuf buf) {
        return InputType.decode(buf); // Decode the InputType which contains userUUID and itemID
    }

    @Override
    public MinimalBankData decodeOutput(FriendlyByteBuf buf) {
        if(buf.readBoolean()) {
            return MinimalBankData.decode(buf); // Decode the MinimalBankData
        }
        return null; // If no data was encoded, return null
    }
}
