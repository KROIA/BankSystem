package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.banking.clientdata.BankManagerData;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class MinimalBankManagerDataRequest extends BankSystemGenericRequest<Integer, BankManagerData> {
    @Override
    public String getRequestTypeID() {
        return MinimalBankManagerDataRequest.class.getName();
    }

    @Override
    public BankManagerData handleOnClient(Integer input) {
        return null;
    }

    @Override
    public BankManagerData handleOnServer(Integer input, ServerPlayer sender) {
        // Check if sender has admin permissions
        if(playerIsAdmin(sender)) {
            // If the player has admin permissions, return the minimal data
            return BACKEND_INSTANCES.SERVER_BANK_MANAGER.getBankManagerData();
        }
        return null; // If not, return null or handle accordingly
    }

    @Override
    public void encodeInput(FriendlyByteBuf buf, Integer input) {
        // No input to encode for this request
        // If needed, you can encode some identifier or parameters here
    }

    @Override
    public void encodeOutput(FriendlyByteBuf buf, BankManagerData output) {
        buf.writeBoolean(output != null);
        if(output != null) {
            output.encode(buf); // Encode the MinimalBankManagerData
        }
    }

    @Override
    public Integer decodeInput(FriendlyByteBuf buf) {
        return 0; // No input to decode for this request, return a default value
    }

    @Override
    public BankManagerData decodeOutput(FriendlyByteBuf buf) {
        if(buf.readBoolean()) {
            return BankManagerData.decode(buf); // Decode the MinimalBankManagerData
        }
        return null; // If no data was encoded, return null
    }
}
