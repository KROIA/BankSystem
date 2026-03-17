package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.api.IBankAccount;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.CompletableFuture;

public class BankAccountDeleteRequest extends BankSystemGenericRequest<Integer, Boolean> {
    @Override
    public String getRequestTypeID() {
        return BankAccountDeleteRequest.class.getName();
    }

    //@Override
    //public Boolean handleOnClient(Integer input) {
    //    return null;
    //}

    @Override
    public CompletableFuture<Boolean> handleOnServer(Integer input, ServerPlayer sender) {
        CompletableFuture<Boolean>  future = new CompletableFuture<>();
        boolean isAdmin = playerIsAdmin(sender);
        IBankAccount account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getBankAccount(input);
        if(account == null)
        {
            future.complete(false);
            return future;
        }
        boolean canManage = account.hasPermission(sender.getUUID(), BankPermission.MANAGE.getValue()) || isAdmin;
        if(canManage)
        {
            future.complete(BACKEND_INSTANCES.SERVER_BANK_MANAGER.deleteBankAccount(input));
            return future;
        }
        future.complete(false);
        return future; // The player does not have permission to delete the bank account
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
