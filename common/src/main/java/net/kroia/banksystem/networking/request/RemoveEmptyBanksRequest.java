package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.api.IBankUser;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

public class RemoveEmptyBanksRequest extends BankSystemGenericRequest<UUID, List<ItemID>> {
    @Override
    public String getRequestTypeID() {
        return RemoveEmptyBanksRequest.class.getSimpleName();
    }

    @Override
    public List<ItemID> handleOnClient(UUID input) {
        return null;
    }

    @Override
    public List<ItemID> handleOnServer(UUID input, ServerPlayer sender) {
        if(input.equals(sender.getUUID()) || playerIsAdmin(sender)) {
            IBankUser bankUser = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getUser(input);
            if(bankUser != null)
            {
                return bankUser.removeEmptyBanks();
            }
        }
        return List.of();
    }

    @Override
    public void encodeInput(FriendlyByteBuf buf, UUID input) {
        buf.writeUUID(input);
    }

    @Override
    public void encodeOutput(FriendlyByteBuf buf, List<ItemID> output) {
        buf.writeInt(output.size());
        for (ItemID itemID : output) {
            itemID.encode(buf);
        }
    }

    @Override
    public UUID decodeInput(FriendlyByteBuf buf) {
        return buf.readUUID();
    }

    @Override
    public List<ItemID> decodeOutput(FriendlyByteBuf buf) {
        int size = buf.readInt();
        List<ItemID> itemIDs = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            itemIDs.add(new ItemID(buf));
        }
        return itemIDs;
    }
}
