package net.kroia.banksystem.networking.general;

import net.kroia.banksystem.banking.ledger.SuspicionChecks;
import net.kroia.banksystem.data.table.SuspicionFlagManager;
import net.kroia.banksystem.data.table.record.SuspicionFlagRecord;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Task #116 (v2.2.0) — suspicion flags of one account as chart markers on the balance history
 * screen. Input: account number (0 = server-wide flags). BankSystem admins only, checked on the
 * master; untrusted slaves are refused.
 */
public class FlagMarkersRequest extends BankSystemGenericRequest<Integer, FlagMarkersRequest.Result> {

    public static final int MAX_MARKERS = 500;

    public record Marker(long time, int flagType, String details) {
        static final StreamCodec<RegistryFriendlyByteBuf, Marker> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Marker::time,
                ByteBufCodecs.VAR_INT, Marker::flagType,
                ByteBufCodecs.STRING_UTF8, Marker::details,
                Marker::new);

        public String typeName() { return SuspicionChecks.typeName(flagType); }
    }

    public record Result(boolean denied, List<Marker> markers) {
        public static final Result DENIED = new Result(true, List.of());
    }

    @Override
    public String getRequestTypeID() {
        return FlagMarkersRequest.class.getSimpleName();
    }

    /** Issue #85: flag queries queue on the DB worker. */
    @Override
    public long getTimeoutMs() { return SLOW_READ_TIMEOUT_MS; }

    @Override
    public CompletableFuture<Result> handleOnServer(Integer input, ServerPlayer sender) {
        return handleOnMasterServer(input, "", sender.getUUID());
    }

    @Override
    public CompletableFuture<Result> handleOnMasterServer(Integer accountNumber, String slaveID, UUID sender) {
        SuspicionFlagManager flags = BACKEND_INSTANCES.SUSPICION_FLAG_MANAGER;
        if (flags == null || isFromUntrustedSlave(slaveID) || !playerIsAdmin(sender)) {
            debug("Denied flag markers of account " + accountNumber + " to " + sender
                    + (slaveID.isEmpty() ? "" : " via slave " + slaveID));
            return CompletableFuture.completedFuture(Result.DENIED);
        }
        return flags.getRecent(Math.max(0, accountNumber), 0, MAX_MARKERS)
                .thenApply(rows -> {
                    List<Marker> out = new ArrayList<>(rows.size());
                    for (SuspicionFlagRecord r : rows)
                        out.add(new Marker(r.time(), r.flagType(), r.details() == null ? "" : r.details()));
                    return new Result(false, out);
                })
                .exceptionally(t -> new Result(false, List.of()));
    }

    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, Integer accountNumber) {
        ByteBufCodecs.INT.encode(buf, accountNumber);
    }

    @Override
    public Integer decodeInput(RegistryFriendlyByteBuf buf) {
        return ByteBufCodecs.INT.decode(buf);
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, Result r) {
        ByteBufCodecs.BOOL.encode(buf, r.denied());
        ExtraCodecUtils.listStreamCodec(Marker.CODEC).encode(buf, r.markers());
    }

    @Override
    public Result decodeOutput(RegistryFriendlyByteBuf buf) {
        boolean denied = ByteBufCodecs.BOOL.decode(buf);
        return new Result(denied, ExtraCodecUtils.listStreamCodec(Marker.CODEC).decode(buf));
    }
}
