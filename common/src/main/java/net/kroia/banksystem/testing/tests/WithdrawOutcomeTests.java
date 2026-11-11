package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.networking.multi_server.WithdrawItemsFromBankRequest;
import net.kroia.banksystem.networking.multi_server.WithdrawItemsFromBankRequest.OutputData;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.modutilities.networking.client_server.arrs.RequestNotSentException;
import net.kroia.modutilities.networking.client_server.arrs.RequestOutcomeUnknownException;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Task #160: a failed slave withdraw request must never read as "nothing withdrawn". Offline. */
public class WithdrawOutcomeTests extends TestSuite {

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
        addTest("withdraw_failure_is_not_empty_map", this::testFailures);
    }

    @Override
    public void setup() {
        SharedConstants.tryDetectVersion(); // offline JUnit run; no-op in game
        Bootstrap.bootStrap();
    }

    private static Class<?> failure(CompletableFuture<?> f) {
        if (!f.isCompletedExceptionally()) return null;
        try {
            f.join();
            return null;
        } catch (CompletionException e) {
            return e.getCause() == null ? null : e.getCause().getClass();
        }
    }

    private TestResult testFailures() {
        Map<?, ?> got = WithdrawItemsFromBankRequest.withdrawn(CompletableFuture.completedFuture(new OutputData(Map.of()))).getNow(null);
        if (!Map.of().equals(got)) return fail("a reply must pass through");
        if (failure(WithdrawItemsFromBankRequest.withdrawn(CompletableFuture.failedFuture(new IllegalStateException("timeout")))) != RequestOutcomeUnknownException.class)
            return fail("a lost request must fail as unknown, not complete empty");
        if (failure(WithdrawItemsFromBankRequest.withdrawn(CompletableFuture.completedFuture(null))) != RequestOutcomeUnknownException.class)
            return fail("a null reply must fail as unknown");
        if (failure(WithdrawItemsFromBankRequest.withdrawn(CompletableFuture.failedFuture(new CompletionException(new RequestNotSentException("x"))))) != RequestNotSentException.class)
            return fail("never sent must fail as not sent");
        return assertTrue("unknown passes through", failure(WithdrawItemsFromBankRequest.withdrawn(
                CompletableFuture.failedFuture(new RequestOutcomeUnknownException("t")))) == RequestOutcomeUnknownException.class);
    }
}
