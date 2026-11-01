package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.banking.bank.ServerBank;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;

import java.lang.reflect.Constructor;

/**
 * Audit test suite that verifies every *Async() method on ServerBank
 * delegates to the correct synchronous counterpart and returns the same result.
 *
 * Known bugs being tested:
 *   Issue #8  - withdrawLockedAsync calls withdraw() instead of withdrawLocked()
 *   Issue #42 - getNormalizedTotalBalanceAsync returns lockedBalance instead of total
 *   Issue #43 - getFormattedTotalBalanceAsync returns lockedBalance instead of total
 *   Issue #22 - toStringNoOwnerAsync calls toJsonString() instead of toStringNoOwner()
 */
public class AsyncMethodAuditTests extends TestSuite {

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.ITEM_BANK;
    }

    @Override
    public void registerTests() {
        // Known-bug tests (these are expected to FAIL until the bugs are fixed)
        addTest("withdrawLockedAsync_calls_withdrawLocked", this::withdrawLockedAsync_calls_withdrawLocked);
        addTest("getNormalizedTotalBalanceAsync_returns_total", this::getNormalizedTotalBalanceAsync_returns_total);
        addTest("getFormattedTotalBalanceAsync_returns_total", this::getFormattedTotalBalanceAsync_returns_total);
        addTest("toStringNoOwnerAsync_calls_correct_method", this::toStringNoOwnerAsync_calls_correct_method);

        // General async-matches-sync tests
        addTest("depositAsync_matches_deposit", this::depositAsync_matches_deposit);
        addTest("withdrawAsync_matches_withdraw", this::withdrawAsync_matches_withdraw);
        addTest("getBalanceAsync_matches_getBalance", this::getBalanceAsync_matches_getBalance);
        addTest("getLockedBalanceAsync_matches_getLockedBalance", this::getLockedBalanceAsync_matches_getLockedBalance);
        addTest("getTotalBalanceAsync_matches_getTotalBalance", this::getTotalBalanceAsync_matches_getTotalBalance);
        addTest("lockAmountAsync_matches_lockAmount", this::lockAmountAsync_matches_lockAmount);
        addTest("unlockAmountAsync_matches_unlockAmount", this::unlockAmountAsync_matches_unlockAmount);
    }

    @Override
    public void setup() {
        // Nothing to set up globally; each test creates its own bank instances.
    }

    @Override
    public void teardown() {
        // Nothing to tear down.
    }

    /**
     * Creates a ServerBank instance via reflection, bypassing the private constructor
     * and the static factory's backend/isItemIDAllowed checks.
     */
    private ServerBank createTestBank(long balance) {
        try {
            ItemID itemID = new ItemID((short) 1, "test:audit_item");
            Constructor<ServerBank> ctor = ServerBank.class.getDeclaredConstructor(ItemID.class, long.class);
            ctor.setAccessible(true);
            return ctor.newInstance(itemID, balance);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create test ServerBank via reflection", e);
        }
    }

    // ========================================================================
    // KNOWN BUG TESTS
    // ========================================================================

    /**
     * Issue #8: withdrawLockedAsync calls withdraw() instead of withdrawLocked().
     *
     * With balance=500 and lockedBalance=500 (from locking 500 of an initial 1000),
     * calling withdrawLocked(200) should deduct from lockedBalance only,
     * leaving balance=500 and lockedBalance=300.
     *
     * The bug causes it to call withdraw(200) which deducts from balance instead,
     * leaving balance=300 and lockedBalance=500.
     */
    private TestResult withdrawLockedAsync_calls_withdrawLocked() {
        // Sync version
        ServerBank syncBank = createTestBank(1000);
        syncBank.lockAmount(500);
        BankStatus syncStatus = syncBank.withdrawLocked(200);

        // Async version
        ServerBank asyncBank = createTestBank(1000);
        asyncBank.lockAmount(500);
        BankStatus asyncStatus = asyncBank.withdrawLockedAsync(200).join();

        if (syncStatus != asyncStatus) {
            return fail("withdrawLockedAsync status (" + asyncStatus +
                    ") does not match withdrawLocked status (" + syncStatus + ")");
        }
        if (syncBank.getBalance() != asyncBank.getBalance()) {
            return fail("withdrawLockedAsync balance (" + asyncBank.getBalance() +
                    ") does not match withdrawLocked balance (" + syncBank.getBalance() +
                    "). Async likely called withdraw() instead of withdrawLocked()");
        }
        if (syncBank.getLockedBalance() != asyncBank.getLockedBalance()) {
            return fail("withdrawLockedAsync lockedBalance (" + asyncBank.getLockedBalance() +
                    ") does not match withdrawLocked lockedBalance (" + syncBank.getLockedBalance() +
                    "). Async likely called withdraw() instead of withdrawLocked()");
        }
        return pass("withdrawLockedAsync correctly delegates to withdrawLocked");
    }

    /**
     * Issue #42: getNormalizedTotalBalanceAsync returns getNormalizedAmount(lockedBalance)
     * instead of getNormalizedAmount(balance + lockedBalance).
     *
     * When balance != lockedBalance the sync and async results will diverge.
     */
    private TestResult getNormalizedTotalBalanceAsync_returns_total() {
        ServerBank bank = createTestBank(1000);
        bank.lockAmount(300);
        // balance=700, lockedBalance=300, total=1000

        String syncResult = bank.getNormalizedTotalBalance();
        String asyncResult = bank.getNormalizedTotalBalanceAsync().join();

        return assertEquals(
                "getNormalizedTotalBalanceAsync should return the same as getNormalizedTotalBalance " +
                        "(sync=" + syncResult + ", async=" + asyncResult + ")",
                syncResult, asyncResult);
    }

    /**
     * Issue #43: getFormattedTotalBalanceAsync returns getFormattedAmount(lockedBalance)
     * instead of getFormattedAmount(balance + lockedBalance).
     *
     * When balance != lockedBalance the sync and async results will diverge.
     */
    private TestResult getFormattedTotalBalanceAsync_returns_total() {
        ServerBank bank = createTestBank(1000);
        bank.lockAmount(300);
        // balance=700, lockedBalance=300, total=1000

        String syncResult = bank.getFormattedTotalBalance();
        String asyncResult = bank.getFormattedTotalBalanceAsync().join();

        return assertEquals(
                "getFormattedTotalBalanceAsync should return the same as getFormattedTotalBalance " +
                        "(sync=" + syncResult + ", async=" + asyncResult + ")",
                syncResult, asyncResult);
    }

    /**
     * Issue #22: toStringNoOwnerAsync calls toJsonString() instead of toStringNoOwner().
     *
     * toStringNoOwner() produces a human-readable summary like "item_name10.00"
     * whereas toJsonString() produces a JSON representation. They should never be equal
     * in normal circumstances, so if async returns the JSON version, the test will catch it.
     */
    private TestResult toStringNoOwnerAsync_calls_correct_method() {
        ServerBank bank = createTestBank(1000);

        String syncResult = bank.toStringNoOwner();
        String asyncResult = bank.toStringNoOwnerAsync().join();

        return assertEquals(
                "toStringNoOwnerAsync should return toStringNoOwner() not toJsonString() " +
                        "(sync=" + syncResult + ", async=" + asyncResult + ")",
                syncResult, asyncResult);
    }

    // ========================================================================
    // GENERAL ASYNC-MATCHES-SYNC TESTS
    // ========================================================================

    private TestResult depositAsync_matches_deposit() {
        // Sync
        ServerBank syncBank = createTestBank(100);
        BankStatus syncStatus = syncBank.deposit(500);

        // Async
        ServerBank asyncBank = createTestBank(100);
        BankStatus asyncStatus = asyncBank.depositAsync(500).join();

        if (syncStatus != asyncStatus) {
            return fail("depositAsync status (" + asyncStatus +
                    ") does not match deposit status (" + syncStatus + ")");
        }
        return assertEquals("depositAsync balance should match deposit balance",
                syncBank.getBalance(), asyncBank.getBalance());
    }

    private TestResult withdrawAsync_matches_withdraw() {
        // Sync
        ServerBank syncBank = createTestBank(1000);
        BankStatus syncStatus = syncBank.withdraw(400);

        // Async
        ServerBank asyncBank = createTestBank(1000);
        BankStatus asyncStatus = asyncBank.withdrawAsync(400).join();

        if (syncStatus != asyncStatus) {
            return fail("withdrawAsync status (" + asyncStatus +
                    ") does not match withdraw status (" + syncStatus + ")");
        }
        return assertEquals("withdrawAsync balance should match withdraw balance",
                syncBank.getBalance(), asyncBank.getBalance());
    }

    private TestResult getBalanceAsync_matches_getBalance() {
        ServerBank bank = createTestBank(12345);
        long syncResult = bank.getBalance();
        long asyncResult = bank.getBalanceAsync().join();
        return assertEquals("getBalanceAsync should match getBalance", syncResult, asyncResult);
    }

    private TestResult getLockedBalanceAsync_matches_getLockedBalance() {
        ServerBank bank = createTestBank(1000);
        bank.lockAmount(350);
        long syncResult = bank.getLockedBalance();
        long asyncResult = bank.getLockedBalanceAsync().join();
        return assertEquals("getLockedBalanceAsync should match getLockedBalance",
                syncResult, asyncResult);
    }

    private TestResult getTotalBalanceAsync_matches_getTotalBalance() {
        ServerBank bank = createTestBank(1000);
        bank.lockAmount(250);
        long syncResult = bank.getTotalBalance();
        long asyncResult = bank.getTotalBalanceAsync().join();
        return assertEquals("getTotalBalanceAsync should match getTotalBalance",
                syncResult, asyncResult);
    }

    private TestResult lockAmountAsync_matches_lockAmount() {
        // Sync
        ServerBank syncBank = createTestBank(1000);
        BankStatus syncStatus = syncBank.lockAmount(300);

        // Async
        ServerBank asyncBank = createTestBank(1000);
        BankStatus asyncStatus = asyncBank.lockAmountAsync(300).join();

        if (syncStatus != asyncStatus) {
            return fail("lockAmountAsync status (" + asyncStatus +
                    ") does not match lockAmount status (" + syncStatus + ")");
        }
        if (syncBank.getBalance() != asyncBank.getBalance()) {
            return fail("lockAmountAsync balance mismatch: sync=" + syncBank.getBalance() +
                    ", async=" + asyncBank.getBalance());
        }
        return assertEquals("lockAmountAsync lockedBalance should match lockAmount lockedBalance",
                syncBank.getLockedBalance(), asyncBank.getLockedBalance());
    }

    private TestResult unlockAmountAsync_matches_unlockAmount() {
        // Sync
        ServerBank syncBank = createTestBank(1000);
        syncBank.lockAmount(500);
        BankStatus syncStatus = syncBank.unlockAmount(200);

        // Async
        ServerBank asyncBank = createTestBank(1000);
        asyncBank.lockAmount(500);
        BankStatus asyncStatus = asyncBank.unlockAmountAsync(200).join();

        if (syncStatus != asyncStatus) {
            return fail("unlockAmountAsync status (" + asyncStatus +
                    ") does not match unlockAmount status (" + syncStatus + ")");
        }
        if (syncBank.getBalance() != asyncBank.getBalance()) {
            return fail("unlockAmountAsync balance mismatch: sync=" + syncBank.getBalance() +
                    ", async=" + asyncBank.getBalance());
        }
        return assertEquals("unlockAmountAsync lockedBalance should match unlockAmount lockedBalance",
                syncBank.getLockedBalance(), asyncBank.getLockedBalance());
    }
}
