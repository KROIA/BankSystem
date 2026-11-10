package net.kroia.banksystem.testing.tests;

import io.netty.buffer.Unpooled;
import net.kroia.banksystem.networking.multi_server.DepositItemsInBankRequest;
import net.kroia.banksystem.networking.multi_server.TransferStatusRequest;
import net.kroia.banksystem.networking.multi_server.TransferStatusRequest.OutputData;
import net.kroia.banksystem.networking.multi_server.TransferStatusRequest.Results;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.client_server.arrs.RequestManager;
import net.kroia.modutilities.networking.client_server.arrs.RequestNotSentException;
import net.kroia.modutilities.networking.client_server.arrs.RequestOutcomeUnknownException;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.SharedConstants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.Bootstrap;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Task #126 (B6): the transfer result store and the sender's outcome resolution, offline. The clock is passed in, so no
 * test sleeps. The failure paths are injected as failed futures, the same as ARRS completes them while
 * {@code ENABLE_REQUEST_FAILURES} is on; a never-completing future is the switch-off behavior.
 */
public class TransferStatusTests extends TestSuite {

    private ItemID A, B; // created after the bootstrap in setup()

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.MONEY;
    }

    @Override
    public boolean needsGame() {
        return false;
    }

    @Override
    public void registerTests() {
        addTest("switch_stays_off", () -> assertFalse("#126 ships without the flip", RequestManager.ENABLE_REQUEST_FAILURES));
        addTest("store_hit_and_peer_isolation", this::testHitAndPeers);
        addTest("store_miss_young_vs_old", this::testMissAge);
        addTest("store_eviction_raises_horizon", this::testEviction);
        addTest("store_reset_forgets", this::testReset);
        addTest("throwing_apply_leaves_marker", this::testThrowingApply);
        addTest("resolve_reply_or_never_sent", this::testResolveSent);
        addTest("resolve_unknown_asks_status", this::testResolveUnknown);
        addTest("resolve_pending_stays_pending", this::testResolvePending);
        addTest("deposit_mapping", this::testDepositMapping);
        addTest("wire_roundtrip", this::testWire);
    }

    @Override
    public void setup() {
        SharedConstants.tryDetectVersion(); // offline JUnit run; no-op in game
        Bootstrap.bootStrap();
        A = new ItemID((short) 1, null);
        B = new ItemID((short) 2, null);
    }

    private static Results.Entry entry(long at, boolean applied, Map<ItemID, Long> rem) {
        return new Results.Entry(at, applied, rem, "reply@" + at);
    }

    private TestResult testHitAndPeers() {
        Results r = new Results();
        r.reset(1_000);
        r.put("s1", "t1", entry(2_000, true, Map.of(A, 1L)));
        r.put("s1", "t2", entry(2_000, false, Map.of()));
        if (r.get("s1", "t1") == null || !"reply@2000".equals(r.get("s1", "t1").reply())) return fail("stored reply missing");
        if (r.get("s2", "t1") != null) return fail("other slave must not see s1's transfer");
        if (!r.status("s1", "t1", 0, 3_000).equals(new OutputData(TransferStatusRequest.APPLIED, Map.of(A, 1L)))) return fail("hit must be APPLIED with remainder");
        if (r.status("s1", "t2", 0, 3_000).state() != TransferStatusRequest.NOT_APPLIED) return fail("refused transfer must be NOT_APPLIED");
        if (r.status("s2", "t1", 100, 20_000).state() != TransferStatusRequest.NOT_APPLIED) return fail("young miss on s2 must be NOT_APPLIED");
        if (r.status("s1", null, 0, 20_000).state() != TransferStatusRequest.UNKNOWN) return fail("no id must be UNKNOWN");
        r.put("s1", null, entry(2_000, true, Map.of()));
        return assertEquals("null id must not be stored", 2, r.size());
    }

    private TestResult testMissAge() {
        Results r = new Results();
        r.reset(100_000);
        if (r.status("s", "x", 0, 103_000).state() != TransferStatusRequest.UNKNOWN) return fail("miss within margin after start must be UNKNOWN");
        if (r.status("s", "x", 150_000, 200_000).state() != TransferStatusRequest.UNKNOWN) return fail("sent before start must be UNKNOWN");
        if (r.status("s", "x", -5, 106_000).state() != TransferStatusRequest.NOT_APPLIED) return fail("negative age counts as 0");
        return assertEquals("sent after start + margin", TransferStatusRequest.NOT_APPLIED, r.status("s", "x", 1_000, 200_000).state());
    }

    private TestResult testEviction() {
        Results r = new Results();
        r.reset(0);
        r.put("s", "old", entry(10_000, true, Map.of()));
        r.put("s", "new", entry(10_000 + Results.TTL_MS + 1, true, Map.of()));
        if (r.get("s", "old") != null || r.size() != 1) return fail("TTL must evict the old entry");
        long now = 10_000 + Results.TTL_MS + 2_000;
        if (r.status("s", "old", now - 9_000, now).state() != TransferStatusRequest.UNKNOWN) return fail("evicted transfer must be UNKNOWN, not NOT_APPLIED");
        if (r.status("s", "later", now - 20_000, now).state() != TransferStatusRequest.NOT_APPLIED) return fail("transfer sent after the horizon must be NOT_APPLIED");

        r.reset(0);
        for (int i = 0; i <= Results.CAP; i++) r.put("s", "c" + i, entry(1_000 + i, true, Map.of()));
        if (r.size() != Results.CAP || r.get("s", "c0") != null || r.get("s", "c1") == null) return fail("cap must evict the oldest only, size " + r.size());
        return assertEquals("cap-evicted transfer must be UNKNOWN", TransferStatusRequest.UNKNOWN, r.status("s", "c0", 9_000, 10_000).state());
    }

    private TestResult testReset() {
        Results r = new Results();
        r.reset(0);
        r.put("s", "t", entry(1_000, true, Map.of()));
        r.reset(50_000);
        if (r.get("s", "t") != null) return fail("reset must forget");
        return assertEquals("pre-restart transfer must be UNKNOWN", TransferStatusRequest.UNKNOWN, r.status("s", "t", 49_000, 51_000).state());
    }

    /** An apply that throws after {@code applying()} leaves the marker: status UNKNOWN (park), a repeat fails, nothing re-applied. */
    private TestResult testThrowingApply() {
        String id = java.util.UUID.randomUUID().toString();
        long later = System.currentTimeMillis() + 60_000; // a plain miss this young would be NOT_APPLIED
        try {
            TransferStatusRequest.applying("s", id);
            throw new IllegalStateException("apply failed");
        } catch (IllegalStateException expected) {
            // the handler threw: no remember()
        }
        if (TransferStatusRequest.RESULTS.status("s", id, 0, later).state() != TransferStatusRequest.UNKNOWN) return fail("marker must answer UNKNOWN");
        if (!TransferStatusRequest.replay("s", id).isCompletedExceptionally()) return fail("a repeat must fail, not apply");
        TransferStatusRequest.remember("s", id, true, Map.of(), "done");
        if (!"done".equals(TransferStatusRequest.<String>replay("s", id).join())) return fail("remember must overwrite the marker");
        return assertEquals("finished apply", TransferStatusRequest.APPLIED, TransferStatusRequest.RESULTS.status("s", id, 0, later).state());
    }

    // ---- resolve ---------------------------------------------------------------------------------------

    private final AtomicInteger asks = new AtomicInteger();

    private Supplier<CompletableFuture<OutputData>> answer(byte state, Map<ItemID, Long> rem) {
        return () -> {
            asks.incrementAndGet();
            return CompletableFuture.completedFuture(new OutputData(state, rem));
        };
    }

    private CompletableFuture<String> resolve(CompletableFuture<String> sent, Supplier<CompletableFuture<OutputData>> ask) {
        return TransferStatusRequest.resolve(sent, () -> "refund", rem -> "applied" + rem.values(), ask);
    }

    private static boolean unknown(CompletableFuture<?> f) {
        try {
            f.getNow(null);
            return false;
        } catch (CompletionException e) {
            return e.getCause() instanceof RequestOutcomeUnknownException;
        }
    }

    private TestResult testResolveSent() {
        asks.set(0);
        Supplier<CompletableFuture<OutputData>> ask = answer(TransferStatusRequest.APPLIED, Map.of());
        if (!"ok".equals(resolve(CompletableFuture.completedFuture("ok"), ask).getNow(null))) return fail("reply must pass through");
        if (!"refund".equals(resolve(CompletableFuture.completedFuture(null), ask).getNow(null))) return fail("null reply = never sent = refund");
        if (!"refund".equals(resolve(CompletableFuture.failedFuture(new RequestNotSentException("x")), ask).getNow(null))) return fail("NotSent must refund");
        if (!"refund".equals(resolve(CompletableFuture.failedFuture(new CompletionException(new RequestNotSentException("x"))), ask).getNow(null)))
            return fail("wrapped NotSent must refund");
        return assertEquals("never-sent paths must not ask", 0, asks.get());
    }

    private TestResult testResolveUnknown() {
        asks.set(0);
        Supplier<CompletableFuture<String>> lost = () -> CompletableFuture.failedFuture(new RequestOutcomeUnknownException("timeout"));
        if (!"applied[2]".equals(resolve(lost.get(), answer(TransferStatusRequest.APPLIED, Map.of(A, 2L))).getNow(null))) return fail("APPLIED must map the remainder");
        if (!"refund".equals(resolve(lost.get(), answer(TransferStatusRequest.NOT_APPLIED, Map.of())).getNow(null))) return fail("NOT_APPLIED must refund");
        if (!unknown(resolve(lost.get(), answer(TransferStatusRequest.UNKNOWN, Map.of())))) return fail("UNKNOWN must fail as unknown");
        if (asks.get() != 3) return fail("each lost request asks once, asks " + asks.get());
        if (!unknown(resolve(lost.get(), () -> CompletableFuture.failedFuture(new IllegalStateException("x"))))) return fail("failed ask must stay unknown");
        if (!unknown(resolve(lost.get(), null))) return fail("no id must stay unknown");
        return assertTrue("other failures count as unknown", unknown(resolve(CompletableFuture.failedFuture(new IllegalStateException("handler")), answer(TransferStatusRequest.UNKNOWN, Map.of()))));
    }

    private TestResult testResolvePending() {
        asks.set(0);
        CompletableFuture<String> out = resolve(new CompletableFuture<>(), answer(TransferStatusRequest.NOT_APPLIED, Map.of()));
        if (out.isDone()) return fail("switch off: a pending request must stay pending");
        return assertEquals("switch off: no status ask", 0, asks.get());
    }

    private TestResult testDepositMapping() {
        Map<ItemID, Long> requested = Map.of(A, 5L, B, 7L);
        CompletableFuture<DepositItemsInBankRequest.OutputData> lost = CompletableFuture.failedFuture(new RequestOutcomeUnknownException("t"));
        if (!Map.of(B, 7L).equals(DepositItemsInBankRequest.notDeposited(requested, lost, answer(TransferStatusRequest.APPLIED, Map.of(B, 7L))).join()))
            return fail("APPLIED must return the master's remainder");
        if (!requested.equals(DepositItemsInBankRequest.notDeposited(requested, lost, answer(TransferStatusRequest.NOT_APPLIED, Map.of())).join()))
            return fail("NOT_APPLIED must return everything");
        if (!requested.equals(DepositItemsInBankRequest.notDeposited(requested, CompletableFuture.failedFuture(new RequestNotSentException("x")), null).join()))
            return fail("NotSent must return everything");
        return assertTrue("UNKNOWN must fail", unknown(DepositItemsInBankRequest.notDeposited(requested, lost, answer(TransferStatusRequest.UNKNOWN, Map.of()))));
    }

    private TestResult testWire() {
        TransferStatusRequest req = new TransferStatusRequest();
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
        TransferStatusRequest.InputData in = new TransferStatusRequest.InputData(12_345L, java.util.UUID.randomUUID().toString());
        req.encodeInput(buf, in);
        if (!in.equals(req.decodeInput(buf))) return fail("input roundtrip");
        TransferStatusRequest.InputData noId = new TransferStatusRequest.InputData(0L, null);
        req.encodeInput(buf, noId);
        if (!noId.equals(req.decodeInput(buf))) return fail("null id roundtrip");
        OutputData out = new OutputData(TransferStatusRequest.APPLIED, Map.of(A, 3L));
        req.encodeOutput(buf, out);
        return assertEquals("output roundtrip", out, req.decodeOutput(buf));
    }
}
