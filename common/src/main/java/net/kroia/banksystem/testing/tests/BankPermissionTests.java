package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;

/**
 * Tests for {@link BankPermission} enum and its bitmask utility methods.
 */
public class BankPermissionTests extends TestSuite {

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.PERMISSION;
    }

    @Override
    public void registerTests() {
        addTest("getValue_deposit_returns_1", this::testGetValueDeposit);
        addTest("getValue_withdraw_returns_2", this::testGetValueWithdraw);
        addTest("getValue_manage_returns_4", this::testGetValueManage);
        addTest("ordinal_differs_from_getValue", this::testOrdinalDiffersFromGetValue);
        addTest("hasPermission_single_deposit", this::testHasPermissionSingleDeposit);
        addTest("hasPermission_single_withdraw", this::testHasPermissionSingleWithdraw);
        addTest("hasPermission_single_manage", this::testHasPermissionSingleManage);
        addTest("hasPermission_missing_returns_false", this::testHasPermissionMissingReturnsFalse);
        addTest("hasPermission_combined", this::testHasPermissionCombined);
        addTest("addPermission", this::testAddPermission);
        addTest("removePermission", this::testRemovePermission);
        addTest("getAllPermissions", this::testGetAllPermissions);
    }

    // --- getValue tests ---

    private TestResult testGetValueDeposit() {
        return assertEquals("DEPOSIT.getValue() should be 1",
                1, BankPermission.DEPOSIT.getValue());
    }

    private TestResult testGetValueWithdraw() {
        return assertEquals("WITHDRAW.getValue() should be 2",
                2, BankPermission.WITHDRAW.getValue());
    }

    private TestResult testGetValueManage() {
        return assertEquals("MANAGE.getValue() should be 4",
                4, BankPermission.MANAGE.getValue());
    }

    /**
     * Documents the trap: ordinal() values (0, 1, 2) differ from getValue() values (1, 2, 4).
     * Using ordinal() instead of getValue() for permission checks would be a bug.
     */
    private TestResult testOrdinalDiffersFromGetValue() {
        for (BankPermission perm : BankPermission.values()) {
            if (perm.ordinal() == perm.getValue()) {
                return fail("ordinal() should differ from getValue() for " + perm.name()
                        + " but both are " + perm.ordinal());
            }
        }
        return pass("All permissions have ordinal() != getValue()");
    }

    // --- hasPermission tests ---

    private TestResult testHasPermissionSingleDeposit() {
        return assertTrue("hasPermission(1, DEPOSIT) should be true",
                BankPermission.hasPermission(1, BankPermission.DEPOSIT));
    }

    private TestResult testHasPermissionSingleWithdraw() {
        return assertTrue("hasPermission(2, WITHDRAW) should be true",
                BankPermission.hasPermission(2, BankPermission.WITHDRAW));
    }

    private TestResult testHasPermissionSingleManage() {
        return assertTrue("hasPermission(4, MANAGE) should be true",
                BankPermission.hasPermission(4, BankPermission.MANAGE));
    }

    private TestResult testHasPermissionMissingReturnsFalse() {
        // permissions=1 means only DEPOSIT; checking for WITHDRAW should be false
        return assertFalse("hasPermission(1, WITHDRAW) should be false",
                BankPermission.hasPermission(1, BankPermission.WITHDRAW));
    }

    private TestResult testHasPermissionCombined() {
        // 3 = DEPOSIT(1) | WITHDRAW(2)
        int combined = 3;
        boolean hasDeposit = BankPermission.hasPermission(combined, BankPermission.DEPOSIT);
        boolean hasWithdraw = BankPermission.hasPermission(combined, BankPermission.WITHDRAW);
        if (!hasDeposit) {
            return fail("hasPermission(3, DEPOSIT) should be true");
        }
        if (!hasWithdraw) {
            return fail("hasPermission(3, WITHDRAW) should be true");
        }
        return pass("Combined permissions 3 has both DEPOSIT and WITHDRAW");
    }

    // --- addPermission / removePermission tests ---

    private TestResult testAddPermission() {
        int result = BankPermission.addPermission(0, BankPermission.DEPOSIT);
        return assertEquals("addPermission(0, DEPOSIT) should be 1",
                1, result);
    }

    private TestResult testRemovePermission() {
        // 3 = DEPOSIT | WITHDRAW; removing DEPOSIT should leave 2 (WITHDRAW only)
        int result = BankPermission.removePermission(3, BankPermission.DEPOSIT);
        return assertEquals("removePermission(3, DEPOSIT) should be 2",
                2, result);
    }

    // --- getAllPermissions test ---

    private TestResult testGetAllPermissions() {
        // DEPOSIT(1) | WITHDRAW(2) | MANAGE(4) = 7
        return assertEquals("getAllPermissions() should be 7",
                7, BankPermission.getAllPermissions());
    }
}
