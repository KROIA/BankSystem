package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.api.IBankAccount;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

public class RemoveEmptyBanksRequest extends BankSystemGenericRequest<Integer, List<ItemID>> {
    @Override
    public String getRequestTypeID() {
        return RemoveEmptyBanksRequest.class.getSimpleName();
    }

    @Override
    public List<ItemID> handleOnClient(Integer input) {
        return null;
    }

    @Override
    public List<ItemID> handleOnServer(Integer input, ServerPlayer sender) {
        IBankAccount account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getBankAccount(input);
        if(account == null)
            return List.of();

        UUID senderUUID = sender.getUUID();
        boolean canEdit = playerIsAdmin(sender) || account.hasPermission(senderUUID, BankPermission.MANAGE.getValue());

        if(canEdit) {
            return account.removeEmptyBanks();
        }
        return List.of();
    }

    @Override
    public void encodeInput(FriendlyByteBuf buf, Integer input) {
        buf.writeInt(input);
    }

    @Override
    public void encodeOutput(FriendlyByteBuf buf, List<ItemID> output) {
        buf.writeInt(output.size());
        for (ItemID itemID : output) {
            itemID.encode(buf);
        }
    }

    @Override
    public Integer decodeInput(FriendlyByteBuf buf) {
        return buf.readInt();
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
