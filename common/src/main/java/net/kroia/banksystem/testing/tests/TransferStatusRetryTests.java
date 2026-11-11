package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.networking.multi_server.TransferStatusRequest;
import net.kroia.banksystem.networking.multi_server.TransferStatusRequest.OutputData;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.modutilities.networking.client_server.arrs.RequestOutcomeUnknownException;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Task #158: a status ask that got no answer (link down) is retried on server tick within the result TTL, then the
 * transfer stays unknown (parked). Offline: the tick is called directly with the clock passed in.
 */
public class TransferStatusRetryTests extends TestSuite {

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
        addTest("no_answer_retries_until_link_back", this::retriesUntilAnswer);
        addTest("no_answer_gives_up_after_ttl", this::givesUpAfterTtl);
        addTest("real_unknown_answer_does_not_retry", this::realUnknownFinal);
    }

    private static CompletableFuture<String> resolve(Supplier<CompletableFuture<OutputData>> ask) {
        return TransferStatusRequest.resolve(CompletableFuture.failedFuture(new RequestOutcomeUnknownException("link dropped")),
                () -> "refund", rem -> "applied", ask);
    }

    /** Answers NO_ANSWER for the first {@code silent} asks, then {@code state}. */
    private static Supplier<CompletableFuture<OutputData>> linkBackAfter(int silent, byte state, AtomicInteger asks) {
        return () -> CompletableFuture.completedFuture(asks.incrementAndGet() <= silent ? OutputData.NO_ANSWER : new OutputData(state, Map.of()));
    }

    private static boolean unknown(CompletableFuture<?> f) {
        try {
            f.getNow(null);
            return false;
        } catch (CompletionException e) {
            return e.getCause() instanceof RequestOutcomeUnknownException;
        }
    }

    private TestResult retriesUntilAnswer() {
        AtomicInteger asks = new AtomicInteger();
        CompletableFuture<String> out = resolve(linkBackAfter(2, TransferStatusRequest.APPLIED, asks));
        if (out.isDone()) return fail("no answer must wait for a retry, got " + (unknown(out) ? "unknown" : out.getNow(null)));
        long now = System.currentTimeMillis();
        TransferStatusRequest.retryTick(now);
        if (out.isDone()) return fail("still no answer: must keep waiting");
        TransferStatusRequest.retryTick(now);
        if (!"applied".equals(out.getNow(null))) return fail("link back: APPLIED must resolve the transfer");
        AtomicInteger asks2 = new AtomicInteger();
        CompletableFuture<String> refund = resolve(linkBackAfter(1, TransferStatusRequest.NOT_APPLIED, asks2));
        TransferStatusRequest.retryTick(now);
        if (!"refund".equals(refund.getNow(null))) return fail("link back: NOT_APPLIED must refund");
        return assertEquals("asks", 3, asks.get());
    }

    private TestResult givesUpAfterTtl() {
        AtomicInteger asks = new AtomicInteger();
        CompletableFuture<String> out = resolve(linkBackAfter(Integer.MAX_VALUE, TransferStatusRequest.APPLIED, asks));
        TransferStatusRequest.retryTick(System.currentTimeMillis() + TransferStatusRequest.Results.TTL_MS + 1);
        if (!unknown(out)) return fail("past the TTL the transfer must stay unknown (parked)");
        return assertEquals("no ask past the deadline", 1, asks.get());
    }

    private TestResult realUnknownFinal() {
        AtomicInteger asks = new AtomicInteger();
        CompletableFuture<String> out = resolve(linkBackAfter(0, TransferStatusRequest.UNKNOWN, asks));
        if (!unknown(out)) return fail("an UNKNOWN answer (receiver forgot) is final");
        TransferStatusRequest.retryTick(System.currentTimeMillis());
        return assertEquals("asked once", 1, asks.get());
    }
}
