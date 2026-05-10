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
 * Tests for ServerBank balance, locking, and transfer operations.
 */
public class ServerBankTests extends TestSuite {

    private ServerBank bankA;
    private ServerBank bankB;

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.ITEM_BANK;
    }

    @Override
    public void registerTests() {
        // Balance operations
        addTest("deposit_positive_success", this::deposit_positive_success);
        addTest("deposit_negative_fails", this::deposit_negative_fails);
        addTest("deposit_overflow_detected", this::deposit_overflow_detected);
        addTest("withdraw_sufficient_balance", this::withdraw_sufficient_balance);
        addTest("withdraw_insufficient_fails", this::withdraw_insufficient_fails);
        addTest("withdraw_negative_fails", this::withdraw_negative_fails);
        addTest("getBalance_after_operations", this::getBalance_after_operations);
        addTest("getTotalBalance_includes_locked", this::getTotalBalance_includes_locked);

        // Locking operations
        addTest("lock_valid_amount", this::lock_valid_amount);
        addTest("lock_more_than_balance_fails", this::lock_more_than_balance_fails);
        addTest("unlock_valid_amount", this::unlock_valid_amount);
        addTest("unlockAll_clears_locked", this::unlockAll_clears_locked);
        addTest("withdrawLocked_deducts_from_locked", this::withdrawLocked_deducts_from_locked);
        addTest("withdrawLocked_insufficient_fails", this::withdrawLocked_insufficient_fails);

        // Transfer operations
        addTest("transfer_moves_between_banks", this::transfer_moves_between_banks);
        addTest("transfer_insufficient_fails", this::transfer_insufficient_fails);
    }

    @Override
    public void setup() {
        bankA = createTestBank((short) 1, "test:item_a", 1000);
        bankB = createTestBank((short) 1, "test:item_a", 500);
    }

    @Override
    public void teardown() {
        bankA = null;
        bankB = null;
    }

    /**
     * Creates a ServerBank instance via reflection, bypassing the private constructor
     * and the static factory's backend/isItemIDAllowed checks.
     */
    private ServerBank createTestBank(short itemIdValue, String itemName, long balance) {
        try {
            ItemID itemID = new ItemID(itemIdValue, itemName);
            Constructor<ServerBank> ctor = ServerBank.class.getDeclaredConstructor(ItemID.class, long.class);
            ctor.setAccessible(true);
            return ctor.newInstance(itemID, balance);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create test ServerBank via reflection", e);
        }
    }

    // ========================================================================
    // Balance operations
    // ========================================================================

    private TestResult deposit_positive_success() {
        ServerBank bank = createTestBank((short) 1, "test:item", 0);
        BankStatus status = bank.deposit(500);
        if (status != BankStatus.SUCCESS) {
            return fail("deposit(500) should return SUCCESS but got " + status);
        }
        return assertEquals("Balance should be 500 after deposit", 500L, bank.getBalance());
    }

    private TestResult deposit_negative_fails() {
        ServerBank bank = createTestBank((short) 1, "test:item", 100);
        BankStatus status = bank.deposit(-10);
        if (status != BankStatus.FAILED_NEGATIVE_VALUE) {
            return fail("deposit(-10) should return FAILED_NEGATIVE_VALUE but got " + status);
        }
        return assertEquals("Balance should remain 100", 100L, bank.getBalance());
    }

    private TestResult deposit_overflow_detected() {
        ServerBank bank = createTestBank((short) 1, "test:item", Long.MAX_VALUE - 10);
        BankStatus status = bank.deposit(20);
        if (status != BankStatus.FAILED_OVERFLOW) {
            return fail("deposit near MAX_VALUE should return FAILED_OVERFLOW but got " + status);
        }
        return assertEquals("Balance should remain unchanged on overflow",
                Long.MAX_VALUE - 10, bank.getBalance());
    }

    private TestResult withdraw_sufficient_balance() {
        ServerBank bank = createTestBank((short) 1, "test:item", 1000);
        BankStatus status = bank.withdraw(400);
        if (status != BankStatus.SUCCESS) {
            return fail("withdraw(400) should return SUCCESS but got " + status);
        }
        return assertEquals("Balance should be 600 after withdrawal", 600L, bank.getBalance());
    }

    private TestResult withdraw_insufficient_fails() {
        ServerBank bank = createTestBank((short) 1, "test:item", 100);
        BankStatus status = bank.withdraw(200);
        if (status != BankStatus.FAILED_NOT_ENOUGH_FUNDS) {
            return fail("withdraw(200) with balance=100 should return FAILED_NOT_ENOUGH_FUNDS but got " + status);
        }
        return assertEquals("Balance should remain 100 on insufficient funds", 100L, bank.getBalance());
    }

    private TestResult withdraw_negative_fails() {
        ServerBank bank = createTestBank((short) 1, "test:item", 100);
        BankStatus status = bank.withdraw(-5);
        if (status != BankStatus.FAILED_NEGATIVE_VALUE) {
            return fail("withdraw(-5) should return FAILED_NEGATIVE_VALUE but got " + status);
        }
        return assertEquals("Balance should remain 100", 100L, bank.getBalance());
    }

    private TestResult getBalance_after_operations() {
        ServerBank bank = createTestBank((short) 1, "test:item", 0);
        bank.deposit(1000);
        bank.withdraw(300);
        return assertEquals("Balance should be 700 after deposit(1000) then withdraw(300)",
                700L, bank.getBalance());
    }

    private TestResult getTotalBalance_includes_locked() {
        ServerBank bank = createTestBank((short) 1, "test:item", 1000);
        bank.lockAmount(300);
        long expectedTotal = bank.getBalance() + bank.getLockedBalance();
        if (expectedTotal != 1000) {
            return fail("Total balance should be 1000 but was " + expectedTotal);
        }
        return assertEquals("getTotalBalance should equal balance + lockedBalance",
                expectedTotal, bank.getTotalBalance());
    }

    // ========================================================================
    // Locking operations
    // ========================================================================

    private TestResult lock_valid_amount() {
        ServerBank bank = createTestBank((short) 1, "test:item", 1000);
        BankStatus status = bank.lockAmount(400);
        if (status != BankStatus.SUCCESS) {
            return fail("lockAmount(400) should return SUCCESS but got " + status);
        }
        if (bank.getBalance() != 600) {
            return fail("Balance should be 600 after locking 400 but was " + bank.getBalance());
        }
        return assertEquals("Locked balance should be 400", 400L, bank.getLockedBalance());
    }

    private TestResult lock_more_than_balance_fails() {
        ServerBank bank = createTestBank((short) 1, "test:item", 100);
        BankStatus status = bank.lockAmount(200);
        if (status != BankStatus.FAILED_NOT_ENOUGH_FUNDS) {
            return fail("lockAmount(200) with balance=100 should return FAILED_NOT_ENOUGH_FUNDS but got " + status);
        }
        if (bank.getBalance() != 100) {
            return fail("Balance should remain 100 but was " + bank.getBalance());
        }
        return assertEquals("Locked balance should remain 0", 0L, bank.getLockedBalance());
    }

    private TestResult unlock_valid_amount() {
        ServerBank bank = createTestBank((short) 1, "test:item", 1000);
        bank.lockAmount(400);
        BankStatus status = bank.unlockAmount(200);
        if (status != BankStatus.SUCCESS) {
            return fail("unlockAmount(200) should return SUCCESS but got " + status);
        }
        if (bank.getBalance() != 800) {
            return fail("Balance should be 800 after unlocking 200 but was " + bank.getBalance());
        }
        return assertEquals("Locked balance should be 200", 200L, bank.getLockedBalance());
    }

    private TestResult unlockAll_clears_locked() {
        ServerBank bank = createTestBank((short) 1, "test:item", 1000);
        bank.lockAmount(400);
        bank.unlockAll();
        if (bank.getLockedBalance() != 0) {
            return fail("Locked balance should be 0 after unlockAll but was " + bank.getLockedBalance());
        }
        return assertEquals("Balance should be restored to 1000", 1000L, bank.getBalance());
    }

    private TestResult withdrawLocked_deducts_from_locked() {
        ServerBank bank = createTestBank((short) 1, "test:item", 1000);
        bank.lockAmount(500);
        // balance=500, lockedBalance=500
        BankStatus status = bank.withdrawLocked(200);
        if (status != BankStatus.SUCCESS) {
            return fail("withdrawLocked(200) should return SUCCESS but got " + status);
        }
        if (bank.getBalance() != 500) {
            return fail("Balance should remain 500 (untouched) but was " + bank.getBalance());
        }
        return assertEquals("Locked balance should be 300 after withdrawLocked(200)",
                300L, bank.getLockedBalance());
    }

    private TestResult withdrawLocked_insufficient_fails() {
        ServerBank bank = createTestBank((short) 1, "test:item", 1000);
        bank.lockAmount(100);
        // balance=900, lockedBalance=100
        BankStatus status = bank.withdrawLocked(200);
        if (status != BankStatus.FAILED_NOT_ENOUGH_FUNDS) {
            return fail("withdrawLocked(200) with lockedBalance=100 should return FAILED_NOT_ENOUGH_FUNDS but got " + status);
        }
        if (bank.getBalance() != 900) {
            return fail("Balance should remain 900 but was " + bank.getBalance());
        }
        return assertEquals("Locked balance should remain 100", 100L, bank.getLockedBalance());
    }

    // ========================================================================
    // Transfer operations
    // ========================================================================

    private TestResult transfer_moves_between_banks() {
        ServerBank source = createTestBank((short) 1, "test:item", 1000);
        ServerBank target = createTestBank((short) 1, "test:item", 200);
        BankStatus status = source.transfer(300, target);
        if (status != BankStatus.SUCCESS) {
            return fail("transfer(300) should return SUCCESS but got " + status);
        }
        if (source.getBalance() != 700) {
            return fail("Source balance should be 700 but was " + source.getBalance());
        }
        return assertEquals("Target balance should be 500", 500L, target.getBalance());
    }

    private TestResult transfer_insufficient_fails() {
        ServerBank source = createTestBank((short) 1, "test:item", 100);
        ServerBank target = createTestBank((short) 1, "test:item", 200);
        BankStatus status = source.transfer(500, target);
        if (status != BankStatus.FAILED_NOT_ENOUGH_FUNDS) {
            return fail("transfer(500) with balance=100 should return FAILED_NOT_ENOUGH_FUNDS but got " + status);
        }
        if (source.getBalance() != 100) {
            return fail("Source balance should remain 100 but was " + source.getBalance());
        }
        return assertEquals("Target balance should remain 200 on failed transfer",
                200L, target.getBalance());
    }
}
