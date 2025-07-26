package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.banking.clientdata.MinimalServerBankManagerData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class MinimalServerBankManagerDataRequest extends BankSystemGenericRequest<Integer, MinimalServerBankManagerData> {
    @Override
    public String getRequestTypeID() {
        return MinimalServerBankManagerDataRequest.class.getName();
    }

    @Override
    public MinimalServerBankManagerData handleOnClient(Integer input) {
        return null;
    }

    @Override
    public MinimalServerBankManagerData handleOnServer(Integer input, ServerPlayer sender) {
        // Check if sender has admin permissions
        if(sender.hasPermissions(2)) {
            // If the player has admin permissions, return the minimal data
            return BACKEND_INSTANCES.SERVER_BANK_MANAGER.getMinimalData();
        }
        return null; // If not, return null or handle accordingly
    }

    @Override
    public void encodeInput(FriendlyByteBuf buf, Integer input) {
        // No input to encode for this request
        // If needed, you can encode some identifier or parameters here
    }

    @Override
    public void encodeOutput(FriendlyByteBuf buf, MinimalServerBankManagerData output) {
        buf.writeBoolean(output != null);
        if(output != null) {
            output.encode(buf); // Encode the MinimalServerBankManagerData
        }
    }

    @Override
    public Integer decodeInput(FriendlyByteBuf buf) {
        return 0; // No input to decode for this request, return a default value
    }

    @Override
    public MinimalServerBankManagerData decodeOutput(FriendlyByteBuf buf) {
        if(buf.readBoolean()) {
            return MinimalServerBankManagerData.decode(buf); // Decode the MinimalServerBankManagerData
        }
        return null; // If no data was encoded, return null
    }
}
