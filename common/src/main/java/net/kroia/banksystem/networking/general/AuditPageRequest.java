package net.kroia.banksystem.networking.general;

import net.kroia.banksystem.data.table.TransactionLogManager;
import net.kroia.banksystem.data.table.record.TransactionLogRecord;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Task #110 (v2.2.0) — one page of an account's ledger rows for the admin audit screen.
 * <p>
 * The master checks on every page: BankSystem admin, and never for an untrusted slave (it can
 * forge the player UUID). A refused request answers {@link Page#DENIED}.
 */
public class AuditPageRequest extends BankSystemGenericRequest<AuditPageRequest.Query, AuditPageRequest.Page> {

    public static final int MAX_PAGE_SIZE = 100;

    /** @param typeCode ledger type code, or -1 for all; @param sinceMs 0 = no bound */
    public record Query(int accountNumber, int typeCode, long sinceMs, int offset, int limit) {}

    /** One ledger row as shown in the audit list. */
    public record Row(long id, long time, int typeCode, short itemId, long amount, @Nullable Integer otherAccount,
                      String actor, @Nullable String note, @Nullable String ref) {
        static final StreamCodec<RegistryFriendlyByteBuf, Row> CODEC = StreamCodec.of(
                (buf, r) -> {
                    ByteBufCodecs.VAR_LONG.encode(buf, r.id);
                    ByteBufCodecs.VAR_LONG.encode(buf, r.time);
                    ByteBufCodecs.VAR_INT.encode(buf, r.typeCode);
                    ByteBufCodecs.SHORT.encode(buf, r.itemId);
                    ByteBufCodecs.VAR_LONG.encode(buf, r.amount);
                    ExtraCodecUtils.nullable(ByteBufCodecs.INT).encode(buf, r.otherAccount);
                    ByteBufCodecs.STRING_UTF8.encode(buf, r.actor);
                    ExtraCodecUtils.nullable(ByteBufCodecs.STRING_UTF8).encode(buf, r.note);
                    ExtraCodecUtils.nullable(ByteBufCodecs.STRING_UTF8).encode(buf, r.ref);
                },
                buf -> new Row(ByteBufCodecs.VAR_LONG.decode(buf), ByteBufCodecs.VAR_LONG.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.SHORT.decode(buf),
                        ByteBufCodecs.VAR_LONG.decode(buf), ExtraCodecUtils.nullable(ByteBufCodecs.INT).decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf), ExtraCodecUtils.nullable(ByteBufCodecs.STRING_UTF8).decode(buf),
                        ExtraCodecUtils.nullable(ByteBufCodecs.STRING_UTF8).decode(buf)));

        static Row of(TransactionLogRecord r) {
            return new Row(r.id(), r.time(), r.type().code(), r.itemId(), r.amount(), r.otherAccount(),
                    r.actorName() != null ? r.actorName() : r.source().name(), r.note(), r.ref());
        }
    }

    public record Page(boolean denied, boolean hasMore, List<Row> rows) {
        public static final Page DENIED = new Page(true, false, List.of());
    }

    @Override
    public String getRequestTypeID() {
        return AuditPageRequest.class.getSimpleName();
    }

    @Override
    public CompletableFuture<Page> handleOnServer(Query input, ServerPlayer sender) {
        return handleOnMasterServer(input, "", sender.getUUID());
    }

    @Override
    public CompletableFuture<Page> handleOnMasterServer(Query input, String slaveID, UUID sender) {
        TransactionLogManager ledger = BACKEND_INSTANCES.TRANSACTION_LOG_MANAGER;
        if (ledger == null || isFromUntrustedSlave(slaveID) || !playerIsAdmin(sender)) {
            debug("Denied audit page of account " + input.accountNumber() + " to " + sender
                    + (slaveID.isEmpty() ? "" : " via slave " + slaveID));
            return CompletableFuture.completedFuture(Page.DENIED);
        }
        int limit = Math.min(MAX_PAGE_SIZE, Math.max(1, input.limit()));
        // One extra row tells whether a next page exists.
        return ledger.getPage(input.accountNumber(), input.typeCode(), input.sinceMs(), input.offset(), limit + 1)
                .thenApply(rows -> {
                    List<Row> out = new ArrayList<>(Math.min(rows.size(), limit));
                    for (int i = 0; i < rows.size() && i < limit; i++) out.add(Row.of(rows.get(i)));
                    return new Page(false, rows.size() > limit, out);
                })
                .exceptionally(t -> new Page(false, false, List.of()));
    }

    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, Query q) {
        ByteBufCodecs.INT.encode(buf, q.accountNumber());
        ByteBufCodecs.INT.encode(buf, q.typeCode());
        ByteBufCodecs.VAR_LONG.encode(buf, q.sinceMs());
        ByteBufCodecs.VAR_INT.encode(buf, q.offset());
        ByteBufCodecs.VAR_INT.encode(buf, q.limit());
    }

    @Override
    public Query decodeInput(RegistryFriendlyByteBuf buf) {
        return new Query(ByteBufCodecs.INT.decode(buf), ByteBufCodecs.INT.decode(buf), ByteBufCodecs.VAR_LONG.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf));
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, Page p) {
        ByteBufCodecs.BOOL.encode(buf, p.denied());
        ByteBufCodecs.BOOL.encode(buf, p.hasMore());
        ExtraCodecUtils.listStreamCodec(Row.CODEC).encode(buf, p.rows());
    }

    @Override
    public Page decodeOutput(RegistryFriendlyByteBuf buf) {
        boolean denied = ByteBufCodecs.BOOL.decode(buf);
        boolean hasMore = ByteBufCodecs.BOOL.decode(buf);
        return new Page(denied, hasMore, ExtraCodecUtils.listStreamCodec(Row.CODEC).decode(buf));
    }
}
