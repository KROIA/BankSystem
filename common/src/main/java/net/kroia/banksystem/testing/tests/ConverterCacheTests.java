package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.banking.converter.ConverterCacheManager;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;

import java.util.UUID;

/**
 * Tests for {@link ConverterCacheManager} — the per-player in-memory cache
 * powering the ATM Money Converter tab (Task #39, v2.0.7).
 *
 * <p>Covers deposit summing, refusal on withdraw overshoot, atomic reserve
 * semantics on withdraw, clear-returns-and-empties, overflow-safety on
 * deposit ({@link Math#addExact} refusal keeps the previous balance), and
 * the "disconnect drops residual" contract by simulating a call to
 * {@link ConverterCacheManager#clear(java.util.UUID)}.
 */
public class ConverterCacheTests extends TestSuite {

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.MONEY;
    }

    @Override
    public void registerTests() {
        addTest("get_returns_zero_for_missing_player", this::testGetForMissing);
        addTest("deposit_sums_correctly", this::testDepositSums);
        addTest("deposit_ignores_non_positive", this::testDepositIgnoresNonPositive);
        addTest("deposit_overflow_leaves_balance_untouched", this::testDepositOverflowRefused);
        addTest("withdraw_refuses_when_sum_gt_cache", this::testWithdrawRefusesOvershoot);
        addTest("withdraw_decrements_on_success", this::testWithdrawDecrements);
        addTest("withdraw_ignores_non_positive", this::testWithdrawIgnoresNonPositive);
        addTest("withdraw_to_zero_removes_entry", this::testWithdrawToZeroRemoves);
        addTest("clear_returns_amount_and_empties", this::testClearReturnsAndEmpties);
        addTest("clear_on_missing_returns_zero", this::testClearMissing);
        addTest("disconnect_drop_semantics", this::testDisconnectDropSemantics);
    }

    @Override
    public void setup() {
        ConverterCacheManager.get().clearAll_forTesting();
    }

    @Override
    public void teardown() {
        ConverterCacheManager.get().clearAll_forTesting();
    }

    // ---- Tests -------------------------------------------------------------

    private TestResult testGetForMissing() {
        UUID p = UUID.randomUUID();
        return assertEquals("cache for missing player must be 0", 0L, ConverterCacheManager.get().getCache(p));
    }

    private TestResult testDepositSums() {
        UUID p = UUID.randomUUID();
        ConverterCacheManager.get().deposit(p, 10_000L);
        ConverterCacheManager.get().deposit(p, 1_000L);
        ConverterCacheManager.get().deposit(p, 50L);
        long result = ConverterCacheManager.get().getCache(p);
        return assertEquals("deposits should sum to 11050", 11_050L, result);
    }

    private TestResult testDepositIgnoresNonPositive() {
        UUID p = UUID.randomUUID();
        ConverterCacheManager.get().deposit(p, 100L);
        long before = ConverterCacheManager.get().getCache(p);
        ConverterCacheManager.get().deposit(p, 0L);
        ConverterCacheManager.get().deposit(p, -500L);
        long after = ConverterCacheManager.get().getCache(p);
        return assertEquals("non-positive deposit must not change balance", before, after);
    }

    private TestResult testDepositOverflowRefused() {
        UUID p = UUID.randomUUID();
        ConverterCacheManager.get().deposit(p, Long.MAX_VALUE - 5L);
        long before = ConverterCacheManager.get().getCache(p);
        long returned = ConverterCacheManager.get().deposit(p, 10L); // would overflow
        long after = ConverterCacheManager.get().getCache(p);
        if (after != before) {
            return fail("overflow-inducing deposit must keep previous balance; before=" + before + " after=" + after);
        }
        if (returned != before) {
            return fail("overflow-refused deposit should return prior balance, returned=" + returned);
        }
        return pass("overflow deposit refused, balance intact");
    }

    private TestResult testWithdrawRefusesOvershoot() {
        UUID p = UUID.randomUUID();
        ConverterCacheManager.get().deposit(p, 500L);
        boolean ok = ConverterCacheManager.get().withdraw(p, 501L);
        if (ok) return fail("withdraw(501) against balance 500 must return false");
        long balance = ConverterCacheManager.get().getCache(p);
        return assertEquals("balance must be unchanged after refused withdraw", 500L, balance);
    }

    private TestResult testWithdrawDecrements() {
        UUID p = UUID.randomUUID();
        ConverterCacheManager.get().deposit(p, 500L);
        boolean ok = ConverterCacheManager.get().withdraw(p, 300L);
        if (!ok) return fail("withdraw(300) against balance 500 should succeed");
        return assertEquals("balance must be 200 after withdraw", 200L, ConverterCacheManager.get().getCache(p));
    }

    private TestResult testWithdrawIgnoresNonPositive() {
        UUID p = UUID.randomUUID();
        ConverterCacheManager.get().deposit(p, 100L);
        boolean zeroOk = ConverterCacheManager.get().withdraw(p, 0L);
        boolean negOk = ConverterCacheManager.get().withdraw(p, -10L);
        if (zeroOk || negOk) return fail("withdraw(<=0) must return false");
        return assertEquals("balance unchanged after refused withdraw", 100L, ConverterCacheManager.get().getCache(p));
    }

    private TestResult testWithdrawToZeroRemoves() {
        UUID p = UUID.randomUUID();
        ConverterCacheManager.get().deposit(p, 42L);
        ConverterCacheManager.get().withdraw(p, 42L);
        return assertEquals("getCache after withdraw-to-zero should be 0", 0L, ConverterCacheManager.get().getCache(p));
    }

    private TestResult testClearReturnsAndEmpties() {
        UUID p = UUID.randomUUID();
        ConverterCacheManager.get().deposit(p, 1234L);
        long cleared = ConverterCacheManager.get().clear(p);
        if (cleared != 1234L) return fail("clear should return 1234, got " + cleared);
        return assertEquals("balance after clear must be 0", 0L, ConverterCacheManager.get().getCache(p));
    }

    private TestResult testClearMissing() {
        UUID p = UUID.randomUUID();
        long cleared = ConverterCacheManager.get().clear(p);
        return assertEquals("clear() on missing player returns 0", 0L, cleared);
    }

    private TestResult testDisconnectDropSemantics() {
        // Simulates the auto-drop hook: on player-quit, the cache is drained via
        // clear() and dispatched to MoneyDenominationOptimizer + drop logic
        // elsewhere. This test only verifies the drain step — the drop path
        // is exercised by in-game acceptance criterion G.
        UUID p = UUID.randomUUID();
        ConverterCacheManager.get().deposit(p, 999_999L);
        long onDisconnect = ConverterCacheManager.get().clear(p);
        long after = ConverterCacheManager.get().getCache(p);
        if (onDisconnect != 999_999L) return fail("disconnect clear expected 999999, got " + onDisconnect);
        if (after != 0L) return fail("balance after disconnect clear expected 0, got " + after);
        return pass("disconnect drop semantics: clear returns full balance and empties entry");
    }
}
