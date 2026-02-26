package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.api.IBankAccount;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
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
        IBankAccount account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getBankAccount(input);
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
    public void encodeInput(RegistryFriendlyByteBuf buf, Integer input) {
        ByteBufCodecs.INT.encode(buf, input);
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, Boolean output) {
        ByteBufCodecs.BOOL.encode(buf, output);
    }

    @Override
    public Integer decodeInput(RegistryFriendlyByteBuf buf) {
        return ByteBufCodecs.INT.decode(buf);
    }

    @Override
    public Boolean decodeOutput(RegistryFriendlyByteBuf buf) {
        return ByteBufCodecs.BOOL.decode(buf);
    }
}
