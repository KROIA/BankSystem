package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.api.IBankAccount;
import net.kroia.banksystem.banking.ServerBankManager;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class BankAccountNumbersRequest extends BankSystemGenericRequest<List<UUID>, List<Integer>> {
    @Override
    public String getRequestTypeID() {
        return BankAccountNumbersRequest.class.getSimpleName();
    }
    @Override
    public boolean needsRoutingToMaster() { return true; }

    @Override
    public CompletableFuture<List<Integer>> handleOnServer(List<UUID> input, ServerPlayer sender) {
        return handleOnMasterServer(input, sender.getUUID());
    }
    @Override
    public CompletableFuture<List<Integer>> handleOnMasterServer(List<UUID> input, UUID sender) {
        CompletableFuture<List<Integer>>  future = new CompletableFuture<>();
        ServerBankManager bankManager = (ServerBankManager)BACKEND_INSTANCES.SERVER_BANK_MANAGER;
        if(input.isEmpty())
            input.add(sender);


        List<Integer> accountNumbers = new ArrayList<>();
        for(UUID uuid : input) {
            List<IBankAccount> accounts = bankManager.getBankAccounts_direct(uuid);
            for(IBankAccount account : accounts) {
                int accountNumber = account.getAccountNumber();
                if(accountNumbers.contains(accountNumber)) {
                    continue; // Skip if the account number is already in the list
                }
                accountNumbers.add(accountNumber); // Add the account number to the list
            }
        }
        future.complete(accountNumbers);
        return future; // Return the list of account numbers
    }

    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, List<UUID> input) {
        ExtraCodecUtils.listStreamCodec(UUIDUtil.STREAM_CODEC).encode(buf, input);
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, List<Integer> output) {
        ExtraCodecUtils.listStreamCodec(ByteBufCodecs.INT).encode(buf, output);
    }

    @Override
    public List<UUID> decodeInput(RegistryFriendlyByteBuf buf) {
        return ExtraCodecUtils.listStreamCodec(UUIDUtil.STREAM_CODEC).decode(buf);
    }

    @Override
    public List<Integer> decodeOutput(RegistryFriendlyByteBuf buf) {
        return ExtraCodecUtils.listStreamCodec(ByteBufCodecs.INT).decode(buf);
    }
}
