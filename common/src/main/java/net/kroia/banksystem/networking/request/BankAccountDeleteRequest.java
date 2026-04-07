package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.api.IBankAccount;
import net.kroia.banksystem.api.ISyncServerBankManager;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class BankAccountDeleteRequest extends BankSystemGenericRequest<Integer, Boolean> {
    @Override
    public String getRequestTypeID() {
        return BankAccountDeleteRequest.class.getName();
    }
    @Override
    public boolean needsRoutingToMaster() { return true; }

    @Override
    public CompletableFuture<Boolean> handleOnServer(Integer input, ServerPlayer sender) {
        return handleOnMasterServer(input, sender.getUUID());
    }
    @Override
    public CompletableFuture<Boolean> handleOnMasterServer(Integer input, UUID sender) {
        ISyncServerBankManager bankManager = getSyncBankManager();
        CompletableFuture<Boolean>  future = new CompletableFuture<>();
        boolean isAdmin = playerIsAdmin(sender);
        IBankAccount account = bankManager.getBankAccount(input);
        if(account == null)
        {
            future.complete(false);
            return future;
        }
        boolean canManage = account.hasPermission(sender, BankPermission.MANAGE.getValue()) || isAdmin;
        if(canManage)
        {
            future.complete(bankManager.deleteBankAccount(input));
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
