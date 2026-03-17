package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.banking.clientdata.BankManagerData;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.CompletableFuture;

public class BankManagerDataRequest extends BankSystemGenericRequest<Integer, BankManagerData> {
    @Override
    public String getRequestTypeID() {
        return BankManagerDataRequest.class.getName();
    }

    //@Override
    //public BankManagerData handleOnClient(Integer input) {
    //    return null;
    //}

    @Override
    public CompletableFuture<BankManagerData> handleOnServer(Integer input, ServerPlayer sender) {
        CompletableFuture<BankManagerData>  future = new CompletableFuture<>();
        future.complete(BACKEND_INSTANCES.SERVER_BANK_MANAGER.getBankManagerData());
        return future;
    }

    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, Integer input) {
        // No input to encode for this request
        // If needed, you can encode some identifier or parameters here
        ByteBufCodecs.INT.encode(buf, input);
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, BankManagerData output) {
        BankManagerData.STREAM_CODEC.encode(buf, output);
    }

    @Override
    public Integer decodeInput(RegistryFriendlyByteBuf buf) {
        return ByteBufCodecs.INT.decode(buf);
    }

    @Override
    public BankManagerData decodeOutput(RegistryFriendlyByteBuf buf) {
        return BankManagerData.STREAM_CODEC.decode(buf);
    }
}
