package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.banking.clientdata.MinimalBankUserData;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class MinimalBankUserDataRequest extends BankSystemGenericRequest<UUID, MinimalBankUserData> {
    @Override
    public String getRequestTypeID() {
        return MinimalBankUserDataRequest.class.getName();
    }

    @Override
    public MinimalBankUserData handleOnClient(UUID input) {
        return null;
    }

    @Override
    public MinimalBankUserData handleOnServer(UUID input, ServerPlayer sender) {
        if(input.compareTo(sender.getUUID()) == 0) {
            if(!sender.hasPermissions(BACKEND_INSTANCES.SERVER_SETTINGS.UTILITIES.ADMIN_PERMISSION_LEVEL.get())) {
                return null; // If the player is not an admin, return null
            }
        }
        return BACKEND_INSTANCES.SERVER_BANK_MANAGER.getMinimalBankUserData(input);
    }

    @Override
    public void encodeInput(FriendlyByteBuf buf, UUID input) {
        buf.writeUUID(input); // Encode the UUID of the bank user
    }

    @Override
    public void encodeOutput(FriendlyByteBuf buf, MinimalBankUserData output) {
        buf.writeBoolean(output != null);
        if(output != null) {
            output.encode(buf); // Encode the MinimalBankUserData
        }
    }

    @Override
    public UUID decodeInput(FriendlyByteBuf buf) {
        return buf.readUUID(); // Decode the UUID of the bank user
    }

    @Override
    public MinimalBankUserData decodeOutput(FriendlyByteBuf buf) {
        if(buf.readBoolean()) {
            return MinimalBankUserData.decode(buf); // Decode the MinimalBankUserData
        }
        return null; // If no data was encoded, return null
    }
}
