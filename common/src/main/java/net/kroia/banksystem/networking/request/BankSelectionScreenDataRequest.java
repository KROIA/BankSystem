package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.banking.ServerBankManager;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class BankSelectionScreenDataRequest extends BankSystemGenericRequest<UUID, BankSelectionScreenDataRequest.Output> {
    public record Output(List<BankAccountData> bankAccounts)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, Output> STREAM_CODEC = StreamCodec.composite(
                ExtraCodecUtils.listStreamCodec(BankAccountData.STREAM_CODEC), Output::bankAccounts,
                Output::new
        );
    }


    @Override
    public String getRequestTypeID() {
        return BankSelectionScreenDataRequest.class.getSimpleName();
    }
    @Override
    public boolean needsRoutingToMaster() { return true; }

    @Override
    public CompletableFuture<Output> handleOnServer(UUID input, ServerPlayer sender) {
        return handleOnMasterServer(input, sender.getUUID());
    }
    @Override
    public CompletableFuture<Output> handleOnMasterServer(UUID input, UUID sender) {
        CompletableFuture<Output>  future = new CompletableFuture<>();
        ServerBankManager bankManager = (ServerBankManager)BACKEND_INSTANCES.SERVER_BANK_MANAGER;
        var accounts = bankManager.getBankAccounts_direct(input);
        Output output = new Output(new ArrayList<>());
        for (var account : accounts) {
            output.bankAccounts.add(account.getAccountData());
        }
        future.complete(output);
        return future;
    }

    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, UUID input) {
        UUIDUtil.STREAM_CODEC.encode(buf, input);
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, Output output) {
        Output.STREAM_CODEC.encode(buf, output);
    }

    @Override
    public UUID decodeInput(RegistryFriendlyByteBuf buf) {
        return UUIDUtil.STREAM_CODEC.decode(buf);
    }

    @Override
    public Output decodeOutput(RegistryFriendlyByteBuf buf) {
        return Output.STREAM_CODEC.decode(buf);
    }


}
