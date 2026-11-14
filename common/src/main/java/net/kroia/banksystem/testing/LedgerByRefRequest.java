package net.kroia.banksystem.testing;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.data.table.TransactionLogManager;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Task #177 — test only: a slave asks the master for its TransactionLog rows with {@code ref} = a transfer_id.
 * Lives in {@code testing/} (not compiled in release builds) and is registered by {@link DevBootstrap}, so a release
 * server never knows it. Read-only; answered only for a trusted slave (no player sender).
 */
public class LedgerByRefRequest extends BankSystemGenericRequest<String, List<LedgerByRefRequest.Row>> {

    public static final LedgerByRefRequest INSTANCE = new LedgerByRefRequest();

    /** {@code origin}: the slave id of the row's origin server (BS-256), "" for the master. */
    public record Row(String ref, int account, LedgerType type, short itemId, long amount, String origin) {
        static final StreamCodec<RegistryFriendlyByteBuf, Row> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Row::ref,
                ByteBufCodecs.VAR_INT, Row::account,
                ByteBufCodecs.VAR_INT.map(LedgerType::fromCode, LedgerType::code), Row::type,
                ByteBufCodecs.SHORT, Row::itemId,
                ByteBufCodecs.VAR_LONG, Row::amount,
                ByteBufCodecs.STRING_UTF8, Row::origin,
                Row::new);
    }

    private static final StreamCodec<RegistryFriendlyByteBuf, List<Row>> LIST_CODEC = Row.CODEC.apply(ByteBufCodecs.list());

    @Override
    public String getRequestTypeID() {
        return LedgerByRefRequest.class.getName();
    }

    @Override
    public long getTimeoutMs() { return SLOW_READ_TIMEOUT_MS; }

    @Override
    public CompletableFuture<List<Row>> handleOnMasterServer(String ref, String slaveID, @Nullable UUID playerSender) {
        TransactionLogManager log = BankSystemModBackend.getTransactionLogManager();
        if (playerSender != null || slaveID == null || slaveID.isEmpty() || isFromUntrustedSlave(slaveID) || log == null)
            return CompletableFuture.completedFuture(List.of());
        if (ref.startsWith(FLAGS)) return flags(ref.substring(FLAGS.length()));
        return log.getByRef(ref).thenApply(rows -> {
            List<Row> out = new ArrayList<>();
            for (var r : rows) out.add(new Row(r.ref(), r.accountNumber(), r.type(), r.itemId(), r.amount(),
                    r.originServer() == null ? "" : r.originServer()));
            return out;
        });
    }

    /**
     * BS-267: input {@code "flags:<ref>"} returns the master's SuspicionFlag rows with that ref instead, as rows with
     * type UNKNOWN_LEGACY, itemId = flag type, amount = flag amount, origin = "flag".
     */
    public static final String FLAGS = "flags:";

    private static CompletableFuture<List<Row>> flags(String ref) {
        var mgr = BankSystemModBackend.getInstances_forTesting().SUSPICION_FLAG_MANAGER;
        if (mgr == null) return CompletableFuture.completedFuture(List.of());
        return mgr.getRecent(-1, 0, 0).thenApply(all -> {
            List<Row> out = new ArrayList<>();
            for (var f : all) if (ref.equals(f.ref()))
                out.add(new Row(ref, f.accountNumber(), LedgerType.UNKNOWN_LEGACY, (short) f.flagType(), f.amount(), "flag"));
            return out;
        });
    }

    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, String input) { ByteBufCodecs.STRING_UTF8.encode(buf, input); }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, List<Row> output) { LIST_CODEC.encode(buf, output); }

    @Override
    public String decodeInput(RegistryFriendlyByteBuf buf) { return ByteBufCodecs.STRING_UTF8.decode(buf); }

    @Override
    public List<Row> decodeOutput(RegistryFriendlyByteBuf buf) { return LIST_CODEC.decode(buf); }
}
