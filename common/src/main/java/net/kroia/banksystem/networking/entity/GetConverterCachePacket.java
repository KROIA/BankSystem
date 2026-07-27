package net.kroia.banksystem.networking.entity;

import net.kroia.banksystem.banking.converter.ConverterCacheManager;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Client&rarr;server request for the current converter-cache balance of the
 * calling player (Task #39, v2.0.7). The ATM Money Converter tab polls this at
 * ~1 Hz to refresh the cache display without needing an explicit push channel.
 *
 * <p>Read-only, no permission gate: the cache is per-player and the caller is
 * always asking about their own cache. {@code needsRoutingToMaster()} returns
 * {@code false} because the cache lives on whichever server the player is
 * connected to.
 *
 * <p><i>File name retained for consistency with the four sibling packets; this
 * class extends {@link BankSystemGenericRequest} rather than
 * {@code BankSystemNetworkPacket} because a request/response round-trip is the
 * cleaner API for a polling read.</i>
 */
public class GetConverterCachePacket extends BankSystemGenericRequest<GetConverterCachePacket.InputData, GetConverterCachePacket.OutputData> {

    public record InputData(boolean dummy) {
        public static final StreamCodec<RegistryFriendlyByteBuf, InputData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, InputData::dummy,
                InputData::new
        );
    }

    public record OutputData(long cacheCents) {
        public static final StreamCodec<RegistryFriendlyByteBuf, OutputData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, OutputData::cacheCents,
                OutputData::new
        );
    }

    /**
     * Client-side entry point. Returns a future that completes with the current
     * cache in cents; on transport failure the future completes with {@code 0}
     * to keep the client UI defensive (a stale positive cache displayed while
     * the server is down would be misleading).
     */
    public static CompletableFuture<Long> sendRequest() {
        CompletableFuture<Long> future = new CompletableFuture<>();
        BankSystemNetworking.GET_CONVERTER_CACHE_REQUEST
                .sendRequestToServer(new InputData(false))
                .whenComplete((response, ex) -> {
                    if (ex != null || response == null) {
                        future.complete(0L);
                    } else {
                        future.complete(response.cacheCents);
                    }
                });
        return future;
    }

    @Override
    public String getRequestTypeID() {
        return GetConverterCachePacket.class.getSimpleName();
    }

    @Override
    public boolean needsRoutingToMaster() { return false; }

    @Override
    public CompletableFuture<OutputData> handleOnServer(InputData input, ServerPlayer sender) {
        if (sender == null) {
            return CompletableFuture.completedFuture(new OutputData(0L));
        }
        long cache = ConverterCacheManager.get().getCache(sender.getUUID());
        return CompletableFuture.completedFuture(new OutputData(cache));
    }

    @Override
    public CompletableFuture<OutputData> handleOnMasterServer(InputData input, String slaveID, @Nullable UUID playerSender) {
        // Not reached — needsRoutingToMaster returns false. Defensive default.
        long cache = playerSender == null ? 0L : ConverterCacheManager.get().getCache(playerSender);
        return CompletableFuture.completedFuture(new OutputData(cache));
    }

    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, InputData input) {
        InputData.STREAM_CODEC.encode(buf, input);
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, OutputData output) {
        OutputData.STREAM_CODEC.encode(buf, output);
    }

    @Override
    public InputData decodeInput(RegistryFriendlyByteBuf buf) {
        return InputData.STREAM_CODEC.decode(buf);
    }

    @Override
    public OutputData decodeOutput(RegistryFriendlyByteBuf buf) {
        return OutputData.STREAM_CODEC.decode(buf);
    }
}
