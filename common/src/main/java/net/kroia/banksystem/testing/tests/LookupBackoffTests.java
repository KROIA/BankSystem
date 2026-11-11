package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.client.cache.LookupBackoff;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;

/** Task #167: a failed client cache lookup is not resent before its retry time (was: every frame). Offline. */
public class LookupBackoffTests extends TestSuite {

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.COMPANY;
    }

    @Override
    public boolean needsGame() {
        return false;
    }

    @Override
    public void registerTests() {
        addTest("failed_lookup_waits_and_doubles", this::waitsAndDoubles);
        addTest("success_clears_wait", this::successClears);
    }

    private TestResult waitsAndDoubles() {
        LookupBackoff b = new LookupBackoff();
        long t = 1_000L;
        if (b.isWaiting(7, t)) return fail("fresh id must not wait");
        b.failed(7, t);
        if (!b.isWaiting(7, t + LookupBackoff.BASE_MS - 1)) return fail("must wait BASE_MS after a failure");
        if (b.isWaiting(7, t + LookupBackoff.BASE_MS)) return fail("retry allowed at BASE_MS");
        if (b.isWaiting(8, t)) return fail("backoff is per id");
        t += LookupBackoff.BASE_MS;
        b.failed(7, t);
        if (!b.isWaiting(7, t + 2 * LookupBackoff.BASE_MS - 1)) return fail("second failure must double the wait");
        for (int i = 0; i < 10; i++) b.failed(7, t);
        if (b.isWaiting(7, t + LookupBackoff.MAX_MS)) return fail("wait must cap at MAX_MS");
        return pass("ok");
    }

    private TestResult successClears() {
        LookupBackoff b = new LookupBackoff();
        b.failed(3, 0L);
        b.succeeded(3);
        return assertTrue("success must clear the wait", !b.isWaiting(3, 0L));
    }
}
