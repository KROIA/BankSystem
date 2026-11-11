package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Issue #93 — company history/stats reads complete from the DB future instead of blocking the
 * calling (master) thread while the DB worker is busy.
 * <p>
 * <b>Server type:</b> MASTER_ONLY (DATABASE category). Holds the DB worker for a few seconds.
 */
public class CompanyReadAsyncTests extends TestSuite {

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.DATABASE;
    }

    @Override
    public void registerTests() {
        addTest("history_read_does_not_block_on_busy_db", this::historyDoesNotBlock);
    }

    private TestResult historyDoesNotBlock() {
        DatabaseManager db = BankSystemModBackend.getDatabaseManager();
        if (db == null) return fail("no DatabaseManager");
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch workerHeld = new CountDownLatch(1);
        try {
            db.getDatabaseThread().submit(() -> {
                workerHeld.countDown();
                try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            });
            if (!workerHeld.await(10, TimeUnit.SECONDS)) return fail("DB worker never picked up the hold task");

            AsyncCompanyManager.InputData in = AsyncCompanyManager.InputData.of(AsyncCompanyManager.FunctionType.GET_HISTORY,
                    new AsyncCompanyManager.GetHistoryInput(-93L, 1));
            // Run the handler on a helper thread: with a blocking .get() it would hang until release.
            CompletableFuture<CompletableFuture<AsyncCompanyManager.OutputData>> call = CompletableFuture.supplyAsync(
                    () -> AsyncCompanyManager.Request.instance.handleOnMasterServer(in, "", null));
            CompletableFuture<AsyncCompanyManager.OutputData> reply;
            try {
                reply = call.get(2, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                return fail("GET_HISTORY handler blocked while the DB worker was busy");
            }
            if (reply.isDone()) return fail("reply completed before the DB worker ran the query");

            release.countDown();
            AsyncCompanyManager.OutputData out = reply.get(10, TimeUnit.SECONDS);
            AsyncCompanyManager.GetHistoryOutput h = out.decodeResult();
            if (h == null || !h.rows().isEmpty()) return fail("unexpected history rows for an unknown schedule");
            return pass("GET_HISTORY returned at once and completed from the DB future.");
        } catch (Exception e) {
            return fail("exception: " + e);
        } finally {
            release.countDown();
        }
    }
}
