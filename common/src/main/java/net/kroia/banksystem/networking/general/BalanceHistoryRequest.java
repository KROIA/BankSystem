package net.kroia.banksystem.networking.general;

import net.kroia.banksystem.data.filter.EqualityFilter;
import net.kroia.banksystem.data.table.record.BalanceHistoryRecord;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class BalanceHistoryRequest extends BankSystemGenericRequest<Integer, List<BalanceHistoryRecord>> {

    @Override
    public String getRequestTypeID() {
        return BalanceHistoryRequest.class.getSimpleName();
    }

    @Override
    public CompletableFuture<List<BalanceHistoryRecord>> handleOnServer(Integer input, ServerPlayer sender) {
        return handleOnMasterServer(input, "", sender.getUUID());
    }

    @Override
    public CompletableFuture<List<BalanceHistoryRecord>> handleOnMasterServer(Integer input, String slaveID, UUID sender) {
        if (BACKEND_INSTANCES.BALANCE_HISTORY_MANAGER == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return BACKEND_INSTANCES.BALANCE_HISTORY_MANAGER.getHistory(
                Optional.empty(),
                Optional.of(new EqualityFilter(input)),
                Optional.empty(),
                0
        );
    }

    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, Integer input) {
        ByteBufCodecs.INT.encode(buf, input);
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, List<BalanceHistoryRecord> output) {
        ExtraCodecUtils.listStreamCodec(BalanceHistoryRecord.STREAM_CODEC).encode(buf, output);
    }

    @Override
    public Integer decodeInput(RegistryFriendlyByteBuf buf) {
        return ByteBufCodecs.INT.decode(buf);
    }

    @Override
    public List<BalanceHistoryRecord> decodeOutput(RegistryFriendlyByteBuf buf) {
        return ExtraCodecUtils.listStreamCodec(BalanceHistoryRecord.STREAM_CODEC).decode(buf);
    }
}
