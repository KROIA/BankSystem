package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.banking.BankAccount;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class BankAccountDeleteRequest extends BankSystemGenericRequest<Integer, Boolean> {
    @Override
    public String getRequestTypeID() {
        return BankAccountDeleteRequest.class.getName();
    }

    @Override
    public Boolean handleOnClient(Integer input) {
        return null;
    }

    @Override
    public Boolean handleOnServer(Integer input, ServerPlayer sender) {
        boolean isAdmin = playerIsAdmin(sender);
        BankAccount account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getBankAccount(input);
        if(account == null)
            return false;
        boolean canManage = account.hasPermission(sender.getUUID(), BankPermission.MANAGE.getValue()) || isAdmin;
        if(canManage)
        {
            return BACKEND_INSTANCES.SERVER_BANK_MANAGER.deleteBankAccount(input);
        }
        return false; // The player does not have permission to delete the bank account
    }

    @Override
    public void encodeInput(FriendlyByteBuf buf, Integer input) {
        buf.writeInt(input);
    }

    @Override
    public void encodeOutput(FriendlyByteBuf buf, Boolean output) {
        buf.writeBoolean(output);
    }

    @Override
    public Integer decodeInput(FriendlyByteBuf buf) {
        return buf.readInt();
    }

    @Override
    public Boolean decodeOutput(FriendlyByteBuf buf) {
        return buf.readBoolean();
    }
}
