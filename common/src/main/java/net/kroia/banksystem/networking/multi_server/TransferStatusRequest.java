package net.kroia.banksystem.networking.multi_server;

import dev.architectury.event.events.common.TickEvent;
import net.kroia.banksystem.data.TransferJournal;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.kroia.modutilities.networking.client_server.arrs.RequestNotSentException;
import net.kroia.modutilities.networking.client_server.arrs.RequestOutcomeUnknownException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Task #126 (B6) — outcome of a value-moving master/slave transfer, by transfer_id.
 * <p>
 * The receiver of a transfer (master: AsyncBank deposit/withdraw, DepositItemsInBankRequest; slave: DROP) stores its reply
 * in {@link #RESULTS} at the moment of the apply, on the server thread. A repeated request with the same id gets the stored
 * reply and nothing is applied twice. When a sender's request fails with an unknown outcome, it asks the receiver with this
 * request before it refunds anything ({@link #resolve}).
 * <p>
 * Answers: {@link #APPLIED} (applied in this session; {@code remainder} = the part not applied), {@link #NOT_APPLIED}
 * (nothing moved and nothing will), {@link #UNKNOWN} (receiver restarted or forgot it: no refund, the transfer is parked).
 */
public class TransferStatusRequest extends BankSystemGenericRequest<TransferStatusRequest.InputData, TransferStatusRequest.OutputData> {

    public static final byte APPLIED = 0, NOT_APPLIED = 1, UNKNOWN = 2;
    /** Peer key on a slave: its only peer is the master. On the master the peer is the slave id. */
    public static final String MASTER = "";

    /** @param ageMs ms since the sender sent the original request (measured on the sender, so clocks need not agree) */
    public record InputData(long ageMs, @Nullable String transferId) {
        public static final StreamCodec<RegistryFriendlyByteBuf, InputData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, InputData::ageMs,
                TransferJournal.ID_CODEC, InputData::transferId,
                InputData::new);
    }

    public record OutputData(byte state, Map<ItemID, Long> remainder) {
        public static final OutputData UNKNOWN_RESULT = new OutputData(UNKNOWN, Map.of());
        /** Task #158: {@link #ask} got no answer (not sent, timeout). Equal to UNKNOWN_RESULT; told apart by identity. */
        public static final OutputData NO_ANSWER = new OutputData(UNKNOWN, Map.of());
        public static final StreamCodec<RegistryFriendlyByteBuf, OutputData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BYTE, o -> o.state,
                ExtraCodecUtils.mapStreamCodec(ItemID.STREAM_CODEC, ByteBufCodecs.VAR_LONG, HashMap<ItemID, Long>::new), OutputData::remainder,
                (s, r) -> new OutputData(s, r));
    }

    /**
     * Replies of applied transfers, in memory only, keyed by peer + transfer_id. Bounded by {@link #CAP} and {@link #TTL_MS};
     * eviction moves the horizon forward, so a forgotten transfer answers UNKNOWN, never a wrong NOT_APPLIED.
     */
    public static final class Results {
        /** {@code reply} null = in-progress marker: the apply started and did not finish (it threw). */
        public record Entry(long atMs, boolean applied, Map<ItemID, Long> remainder, @Nullable Object reply) {}

        public static final int CAP = 20_000;
        public static final long TTL_MS = 10 * 60_000L;
        /** Covers a status request that travels slower than the original did. */
        public static final long MARGIN_MS = 5_000L;

        private final LinkedHashMap<String, Entry> map = new LinkedHashMap<>();
        /** Transfers sent before this time may have been forgotten (session start or newest evicted entry). */
        private long horizonMs = System.currentTimeMillis();

        /** Server start: forget everything. */
        public synchronized void reset(long now) {
            map.clear();
            horizonMs = now;
        }

        public synchronized @Nullable Entry get(String peer, @Nullable String transferId) {
            return transferId == null ? null : map.get(peer + "|" + transferId);
        }

        public synchronized void put(String peer, @Nullable String transferId, Entry e) {
            if (transferId == null) return;
            map.put(peer + "|" + transferId, e);
            for (Iterator<Entry> it = map.values().iterator(); it.hasNext(); ) {
                Entry old = it.next();
                if (map.size() <= CAP && e.atMs() - old.atMs() <= TTL_MS) break;
                horizonMs = Math.max(horizonMs, old.atMs());
                it.remove();
            }
        }

        // ponytail: no tombstone for NOT_APPLIED. The original and the status request use one FIFO connection and run
        // in order on the receiver's server thread, so an original can't arrive after its status answer. Add a tombstone
        // (store NOT_APPLIED here) if the ARRS ever retries requests or sends over more than one connection.
        public synchronized OutputData status(String peer, @Nullable String transferId, long ageMs, long now) {
            if (transferId == null) return OutputData.UNKNOWN_RESULT;
            Entry e = map.get(peer + "|" + transferId);
            if (e != null) return e.reply() == null ? OutputData.UNKNOWN_RESULT : new OutputData(e.applied() ? APPLIED : NOT_APPLIED, e.remainder());
            return now - Math.max(0L, ageMs) - MARGIN_MS > horizonMs ? new OutputData(NOT_APPLIED, Map.of()) : OutputData.UNKNOWN_RESULT;
        }

        public synchronized int size() {
            return map.size();
        }
    }

    public static final Results RESULTS = new Results();

    /**
     * Stored reply of an already handled transfer, or null for a new one. A marker (the apply threw) fails the reply:
     * the sender then asks the status, gets UNKNOWN and parks the transfer. Nothing is applied twice.
     */
    @SuppressWarnings("unchecked")
    public static @Nullable <T> CompletableFuture<T> replay(String peer, @Nullable String transferId) {
        Results.Entry e = RESULTS.get(peer, transferId);
        if (e == null) return null;
        return e.reply() == null ? CompletableFuture.failedFuture(new IllegalStateException("transfer " + transferId + " did not complete"))
                : CompletableFuture.completedFuture((T) e.reply());
    }

    /** Call right before the apply; {@link #remember} overwrites it. If the apply throws, the marker stays: status UNKNOWN. */
    public static void applying(String peer, @Nullable String transferId) {
        if (transferId != null)
            RESULTS.put(peer, transferId, new Results.Entry(System.currentTimeMillis(), false, Map.of(), null));
    }

    /** Stores the reply of a transfer this server just applied (or refused). No-op without an id. */
    public static void remember(String peer, @Nullable String transferId, boolean applied, Map<ItemID, Long> remainder, Object reply) {
        if (transferId != null)
            RESULTS.put(peer, transferId, new Results.Entry(System.currentTimeMillis(), applied, Map.copyOf(remainder), reply));
    }

    /**
     * Asks the receiver of a transfer for its outcome: the master when {@code slaveID} is null (slave side), else that slave.
     * Never fails: any failure (not sent, timeout, old peer without this request) answers UNKNOWN.
     */
    public static CompletableFuture<OutputData> ask(@Nullable String slaveID, String transferId, long sentMs) {
        InputData in = new InputData(Math.max(0L, System.currentTimeMillis() - sentMs), transferId);
        try {
            CompletableFuture<OutputData> f = slaveID == null
                    ? BankSystemNetworking.TRANSFER_STATUS_REQUEST.sendRequestToMaster(in)
                    : BankSystemNetworking.TRANSFER_STATUS_REQUEST.sendRequestToSlave(slaveID, in);
            return f.handle((o, ex) -> ex == null && o != null ? o : OutputData.NO_ANSWER);
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(OutputData.NO_ANSWER);
        }
    }

    /**
     * Outcome of a sent transfer request, for the sender.
     * <ul>
     *     <li>reply → the reply; null reply or {@link RequestNotSentException} (never sent) → {@code notApplied}</li>
     *     <li>any other failure → asks {@code askStatus}: APPLIED → {@code onApplied(remainder)}, NOT_APPLIED → {@code notApplied},
     *         UNKNOWN (or no id: {@code askStatus} null) → fails with {@link RequestOutcomeUnknownException}: do not refund</li>
     *     <li>Task #158: no answer ({@link OutputData#NO_ANSWER}, link down) → asked again every {@link #RETRY_TICKS} server
     *         ticks until {@link Results#TTL_MS} after the call, then UNKNOWN</li>
     * </ul>
     * While {@code RequestManager.ENABLE_REQUEST_FAILURES} is off a sent request never fails, so only the first line applies.
     */
    public static <T> CompletableFuture<T> resolve(CompletableFuture<T> sent, Supplier<T> notApplied,
                                                   Function<Map<ItemID, Long>, T> onApplied,
                                                   @Nullable Supplier<CompletableFuture<OutputData>> askStatus) {
        CompletableFuture<T> out = new CompletableFuture<>();
        final long startMs = System.currentTimeMillis();
        sent.whenComplete((reply, ex) -> {
            try {
                if (ex == null) {
                    out.complete(reply != null ? reply : notApplied.get());
                    return;
                }
                Throwable cause = cause(ex);
                if (cause instanceof RequestNotSentException) {
                    out.complete(notApplied.get());
                    return;
                }
                if (askStatus == null) {
                    out.completeExceptionally(unknown(cause));
                    return;
                }
                check(new Retry(askStatus, startMs + Results.TTL_MS, st -> {
                    try {
                        byte state = st != null ? st.state() : UNKNOWN;
                        if (state == APPLIED) out.complete(onApplied.apply(st.remainder()));
                        else if (state == NOT_APPLIED) out.complete(notApplied.get());
                        else out.completeExceptionally(unknown(cause));
                    } catch (Throwable t) {
                        out.completeExceptionally(t);
                    }
                }));
            } catch (Throwable t) {
                out.completeExceptionally(t);
            }
        });
        return out;
    }

    // ---- Task #158: status asks without an answer, retried on server tick ----------------------------------------

    /** Ask every 5 s while the link is down. MU has no reconnect hook yet (MU #136), so this polls. */
    public static final int RETRY_TICKS = 100;
    /** {@code done} gets the answer, or null (failed ask) / NO_ANSWER (deadline passed): both mean UNKNOWN. */
    private record Retry(Supplier<CompletableFuture<OutputData>> ask, long deadlineMs, Consumer<OutputData> done) {}
    private static final List<Retry> RETRIES = new ArrayList<>();
    private static int retryTicks;

    static {
        TickEvent.SERVER_POST.register(server -> {
            if (++retryTicks % RETRY_TICKS == 0) retryTick(System.currentTimeMillis());
        });
    }

    private static void check(Retry r) {
        try {
            r.ask().get().whenComplete((st, ex) -> {
                if (ex == null && st == OutputData.NO_ANSWER) {
                    synchronized (RETRIES) { RETRIES.add(r); }
                } else r.done().accept(ex == null ? st : null);
            });
        } catch (RuntimeException e) {
            r.done().accept(null);
        }
    }

    /** Asks every waiting transfer again; one past its deadline gives up (UNKNOWN: parked as before). Public for tests. */
    public static void retryTick(long now) {
        List<Retry> due;
        synchronized (RETRIES) {
            if (RETRIES.isEmpty()) return;
            due = new ArrayList<>(RETRIES);
            RETRIES.clear();
        }
        for (Retry r : due) {
            if (now < r.deadlineMs()) {
                check(r);
                continue;
            }
            if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null)
                BACKEND_INSTANCES.LOGGER.warn("[TransferStatusRequest] no status answer within " + Results.TTL_MS / 60_000 + " min; transfer outcome stays unknown");
            r.done().accept(OutputData.NO_ANSWER);
        }
    }

    public static int waitingRetries() {
        synchronized (RETRIES) { return RETRIES.size(); }
    }

    /** Unwraps CompletionException / ExecutionException. */
    public static Throwable cause(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null)
            t = t.getCause();
        return t;
    }

    private static RequestOutcomeUnknownException unknown(Throwable cause) {
        if (cause instanceof RequestOutcomeUnknownException u) return u;
        RequestOutcomeUnknownException u = new RequestOutcomeUnknownException("transfer outcome unknown after status check: " + cause);
        u.initCause(cause);
        return u;
    }

    @Override
    public String getRequestTypeID() {
        return TransferStatusRequest.class.getName();
    }

    @Override
    public boolean needsRoutingToMaster() { return false; }

    /** Read-only and keyed by the authenticated slave id: a slave only sees its own transfers, so untrusted slaves may ask too. */
    @Override
    public CompletableFuture<OutputData> handleOnMasterServer(InputData input, String slaveID, @Nullable UUID playerSender) {
        if (playerSender != null || slaveID == null || slaveID.isEmpty()) {
            warn("transfer status asked by a player or without a slave id; answered UNKNOWN");
            return CompletableFuture.completedFuture(OutputData.UNKNOWN_RESULT);
        }
        return CompletableFuture.completedFuture(RESULTS.status(slaveID, input.transferId(), input.ageMs(), System.currentTimeMillis()));
    }

    /** DROP outcome from this slave's delivery cache. */
    @Override
    public CompletableFuture<OutputData> handleOnSlaveServer(InputData input, @Nullable UUID playerSender) {
        if (playerSender != null)
            return CompletableFuture.completedFuture(OutputData.UNKNOWN_RESULT);
        return CompletableFuture.completedFuture(RESULTS.status(MASTER, input.transferId(), input.ageMs(), System.currentTimeMillis()));
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
