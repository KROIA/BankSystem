package net.kroia.banksystem.networking.entity;


import net.kroia.banksystem.minecraft.entity.custom.BankTerminalBlockEntity;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.CompletableFuture;

public class BankTerminalBlockDataRequest extends BankSystemGenericRequest<BlockPos, BankTerminalBlockDataRequest.Output> {

    public record Output(int selectedBankAccount)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, Output> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.INT, Output::selectedBankAccount,
                Output::new
        );
    }

    @Override
    public String getRequestTypeID() {
        return BankTerminalBlockDataRequest.class.getSimpleName();
    }

    @Override
    public boolean needsRoutingToMaster() { return false; }

    @Override
    public CompletableFuture<Output> handleOnServer(BlockPos input, ServerPlayer sender) {
        CompletableFuture<Output> future = new CompletableFuture<>();

        BankTerminalBlockEntity blockEntity = (BankTerminalBlockEntity) sender.level().getBlockEntity(input);
        if(blockEntity == null) {
            future.complete(new Output(0));
            return future;
        }

        Output output = new Output(blockEntity.getSelectedBankAccount(sender.getUUID()));
        future.complete(output);
        return future;
    }

    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, BlockPos input) {
        BlockPos.STREAM_CODEC.encode(buf, input);
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, Output output) {
        Output.STREAM_CODEC.encode(buf, output);
    }

    @Override
    public BlockPos decodeInput(RegistryFriendlyByteBuf buf) {
        return BlockPos.STREAM_CODEC.decode(buf);
    }

    @Override
    public Output decodeOutput(RegistryFriendlyByteBuf buf) {
        return Output.STREAM_CODEC.decode(buf);
    }


}
