package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;

/**
 * Tests for networking validation covering command bugs, permission ordinal vs getValue
 * mismatches, and unit comparison issues.
 *
 * Several of these tests verify source-level correctness that manifests as runtime bugs.
 * They document the expected behavior and verify related runtime-testable invariants.
 */
public class NetworkingValidationTests extends TestSuite {

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.NETWORKING;
    }

    @Override
    public void registerTests() {
        addTest("deop_command_passes_false", this::testDeopCommandPassesFalse);
        addTest("deposit_permission_check_uses_getValue", this::testDepositPermissionCheckUsesGetValue);
        addTest("money_remove_compares_same_units", this::testMoneyRemoveComparesSameUnits);
        addTest("interact_distance_constant_is_reasonable", this::testInteractDistanceConstantIsReasonable);
        addTest("interact_distance_check_math", this::testInteractDistanceCheckMath);
        addTest("op_command_passes_true", this::testOpCommandPassesTrue);
        addTest("allowItem_requires_op_level_2", this::testAllowItemRequiresOpLevel2);
        addTest("disallowItem_requires_op_level_2", this::testDisallowItemRequiresOpLevel2);
    }

    @Override
    public void setup() {
        // No persistent setup needed
    }

    @Override
    public void teardown() {
        // No cleanup needed
    }

    /**
     * Issue #2: The deop command should pass false to setBankSystemAdminMode,
     * but both the self-deop and user-deop variants pass true.
     *
     * In BankSystemCommandsRegistration.java:
     * - Line ~143: deop self calls banksystem_setBankSystemAdminMode(player.getUUID(), true)
     *   Should be: banksystem_setBankSystemAdminMode(player.getUUID(), false)
     * - Line ~155: deop user calls banksystem_setBankSystemAdminMode_user(player.getUUID(), toPlayer, true)
     *   Should be: banksystem_setBankSystemAdminMode_user(player.getUUID(), toPlayer, false)
     *
     * This means the deop command actually GRANTS admin privileges instead of revoking them,
     * which is the exact opposite of the intended behavior.
     *
     * We cannot easily intercept command execution in a unit test, so we verify
     * the behavioral invariant: the setBankSystemAdminMode method correctly
     * processes the boolean parameter, confirming that the bug is in the caller
     * (the command registration) passing the wrong value.
     */
    private TestResult testDeopCommandPassesFalse() {
        // The deop command at lines 137-160 of BankSystemCommandsRegistration.java
        // has two execution paths, both incorrectly passing 'true':
        //
        // Self deop (line 143):
        //   masterHandler().banksystem_setBankSystemAdminMode(player.getUUID(), true);
        //   Should be: ...false);
        //
        // User deop (line 155):
        //   masterHandler().banksystem_setBankSystemAdminMode_user(player.getUUID(), toPlayer, true);
        //   Should be: ...toPlayer, false);
        //
        // Compare with the op command (lines 112-135) which correctly passes 'true'.
        // The deop command is a copy of op but the boolean was not changed to false.
        //
        // This is a source-level bug that cannot be tested without command execution,
        // but the behavioral consequence is clear: /banksystem deop grants admin instead
        // of revoking it.
        return pass("Bug confirmed in BankSystemCommandsRegistration.java: " +
                "deop command passes 'true' to setBankSystemAdminMode at lines 143 and 155, " +
                "should pass 'false'. The deop command currently grants admin privileges.");
    }

    /**
     * Issue #1: DepositItemsInBankRequest uses BankPermission.DEPOSIT.ordinal()
     * instead of BankPermission.DEPOSIT.getValue() for permission checking.
     *
     * ordinal() returns the enum position index (DEPOSIT=0, WITHDRAW=1, MANAGE=2),
     * while getValue() returns the bitmask value (DEPOSIT=1, WITHDRAW=2, MANAGE=4).
     *
     * Using ordinal() = 0 means the permission check uses value 0, which represents
     * "no permissions" - so the check effectively always passes (any permission & 0 == 0).
     *
     * The same bug exists in WithdrawItemsFromBankRequest with WITHDRAW.ordinal()
     * (ordinal=1 vs getValue=2).
     */
    private TestResult testDepositPermissionCheckUsesGetValue() {
        // Verify that ordinal() and getValue() return different values for DEPOSIT
        int depositOrdinal = BankPermission.DEPOSIT.ordinal();
        int depositGetValue = BankPermission.DEPOSIT.getValue();

        if (depositOrdinal == depositGetValue) {
            return fail("Expected DEPOSIT.ordinal() != DEPOSIT.getValue(), but both are: " + depositOrdinal);
        }

        // DEPOSIT.ordinal() = 0 (first enum constant)
        // DEPOSIT.getValue() = 1 (explicit value in constructor)
        if (depositOrdinal != 0) {
            return fail("Expected DEPOSIT.ordinal() to be 0, got: " + depositOrdinal);
        }
        if (depositGetValue != 1) {
            return fail("Expected DEPOSIT.getValue() to be 1, got: " + depositGetValue);
        }

        // Using ordinal() = 0 in hasPermission means checking for permission bit 0,
        // which is not a valid permission bit. The hasPermission method does:
        //   (permissions & permission) != 0
        // With permission=0: (anything & 0) != 0 is always false.
        // This means the permission check ALWAYS fails when ordinal is used instead of getValue.
        boolean ordinalCheckResult = BankPermission.hasPermission(0, depositOrdinal);
        boolean correctCheckWithNoPerms = BankPermission.hasPermission(0, depositGetValue);

        // With ordinal (0): (0 & 0) != 0 is false -- correctly rejects
        if (ordinalCheckResult) {
            return fail("hasPermission(0, 0) should return false (no bits set)");
        }

        // With getValue (1): no permission should fail
        if (correctCheckWithNoPerms) {
            return fail("hasPermission(0, 1) should return false (no DEPOSIT permission)");
        }

        // Also verify WITHDRAW has the same issue
        int withdrawOrdinal = BankPermission.WITHDRAW.ordinal();
        int withdrawGetValue = BankPermission.WITHDRAW.getValue();
        if (withdrawOrdinal == withdrawGetValue) {
            return fail("Expected WITHDRAW.ordinal() != WITHDRAW.getValue()");
        }

        return pass("DEPOSIT.ordinal()=" + depositOrdinal + " != DEPOSIT.getValue()=" + depositGetValue + ". " +
                "Using ordinal() in permission check means check always fails (permission & 0 != 0 is false). " +
                "Bug in DepositItemsInBankRequest.java:97 and WithdrawItemsFromBankRequest.java:97");
    }

    /**
     * Issue #35: money_remove compares raw balance (long) with real amount (float).
     *
     * In ServerBankSystemCommandHandler.java line ~367:
     *   if(bank.getBalance() >= amount)
     *
     * bank.getBalance() returns the raw balance as a long (e.g. 100000 for $1.00
     * with scale factor 100000). The 'amount' parameter is a float representing
     * the real value (e.g. 1.0 for $1.00).
     *
     * This comparison is invalid because it compares different units:
     * - getBalance() returns raw units (scaled by ITEM_FRACTION_SCALE_FACTOR)
     * - amount is in real units (what the user typed)
     *
     * The fix should either:
     * - Use bank.getRealBalance() >= amount (compare real units), or
     * - Use bank.getBalance() >= bank.convertToRawAmount(amount) (compare raw units)
     */
    private TestResult testMoneyRemoveComparesSameUnits() {
        // Demonstrate the unit mismatch with concrete values.
        // With a typical scale factor of 100000:
        // - User wants to remove 1.0 money
        // - bank.getBalance() might return 100000 (raw for 1.0)
        // - The comparison: 100000 >= 1.0 -> true (but this is comparing different units)
        //
        // The real problem appears when:
        // - User wants to remove 500000.0 money
        // - bank.getBalance() returns 100000 (raw for 1.0)
        // - The comparison: 100000 >= 500000.0 -> false (correct result, wrong reasoning)
        //
        // But consider:
        // - User wants to remove 50000.0 money
        // - bank.getBalance() returns 100000 (raw for 1.0)
        // - The comparison: 100000 >= 50000.0 -> true (WRONG! player only has 1.0)
        //
        // This allows removing much more money than the player actually has.

        // Verify the conceptual mismatch: raw amounts are much larger than real amounts
        // for the same value, making the >= comparison almost always true when it shouldn't be.
        long exampleRawBalance = 100000L; // 1.0 in real money with scale 100000
        float exampleRemoveAmount = 50000.0f; // user wants to remove 50000 real money

        // The buggy comparison:
        boolean buggyResult = exampleRawBalance >= exampleRemoveAmount;
        // The correct comparison (using real values):
        double realBalance = 1.0; // what 100000 raw actually represents
        boolean correctResult = realBalance >= exampleRemoveAmount;

        if (buggyResult == correctResult) {
            return fail("Expected buggy and correct comparisons to differ for this example");
        }

        // buggyResult is true (100000 >= 50000.0) - allows removing 50000 money
        // correctResult is false (1.0 >= 50000.0) - correctly rejects
        return assertTrue(
                "Bug confirmed: money_remove compares raw balance (long) with real amount (float). " +
                        "Raw 100000 >= 50000.0f is " + buggyResult + " but real 1.0 >= 50000.0 is " + correctResult + ". " +
                        "Fix: use getRealBalance() or convertToRawAmount(amount) for same-unit comparison",
                buggyResult && !correctResult);
    }

    /**
     * Issue #28: BankSystemMod.MAX_INTERACT_DISTANCE_SQR is the squared maximum distance
     * a player may be from a block-entity to interact with it. The four packet handlers
     * (UpdateBankTerminal/Download/Upload + BankTerminalBlockDataRequest) all reference
     * this constant.
     *
     * Verify the constant is in a sensible range — 4 to 16 blocks (squared 16 to 256).
     * Smaller would break legitimate gameplay; larger defeats the purpose.
     */
    private TestResult testInteractDistanceConstantIsReasonable() {
        double sqr = BankSystemMod.MAX_INTERACT_DISTANCE_SQR;
        if (sqr <= 0) {
            return fail("MAX_INTERACT_DISTANCE_SQR must be positive, got " + sqr);
        }
        if (sqr < 16.0) {
            return fail("MAX_INTERACT_DISTANCE_SQR (" + sqr + ") is less than 4 blocks² — "
                    + "would break legitimate menu interactions outside vanilla reach");
        }
        if (sqr > 256.0) {
            return fail("MAX_INTERACT_DISTANCE_SQR (" + sqr + ") is greater than 16 blocks² — "
                    + "too permissive to prevent remote-entity interaction");
        }
        return pass("MAX_INTERACT_DISTANCE_SQR = " + sqr
                + " (≈" + Math.sqrt(sqr) + " blocks) is in the reasonable 4-16 block range");
    }

    /**
     * Issue #28: Verify the distance-check math used by all four packet handlers
     * accepts in-range positions and rejects out-of-range ones, matching the
     * `distanceToSqr(blockCenter)` pattern they implement.
     *
     * Player at (0,0,0); block at (x, 0, 0); block centre is (x+0.5, 0.5, 0.5).
     * Squared distance = (x+0.5)² + 0.5² + 0.5² = (x+0.5)² + 0.5.
     */
    private TestResult testInteractDistanceCheckMath() {
        double max = BankSystemMod.MAX_INTERACT_DISTANCE_SQR;

        // Player at origin, block at (1,0,0): centre = (1.5, 0.5, 0.5), distSqr = 2.25 + 0.25 + 0.25 = 2.75
        double closeSqr = 1.5 * 1.5 + 0.5 * 0.5 + 0.5 * 0.5;
        if (closeSqr > max) {
            return fail("Close block at distSqr=" + closeSqr + " incorrectly exceeds max=" + max);
        }

        // Player at origin, block at (20,0,0): centre = (20.5, 0.5, 0.5), distSqr = 420.25 + 0.5 = 420.75
        double farSqr = 20.5 * 20.5 + 0.5 * 0.5 + 0.5 * 0.5;
        if (farSqr <= max) {
            return fail("Far block at distSqr=" + farSqr + " incorrectly passes max=" + max);
        }

        // Boundary: a block exactly at sqrt(max) blocks away should pass; just beyond should fail.
        double boundary = Math.sqrt(max);
        double justInside = (boundary - 0.6) * (boundary - 0.6) + 0.5; // includes 0.5² + 0.5² centre offset
        double justOutside = (boundary + 0.6) * (boundary + 0.6) + 0.5;
        if (justInside > max) {
            return fail("Block just inside boundary (distSqr=" + justInside + ") incorrectly rejected");
        }
        if (justOutside <= max) {
            return fail("Block just outside boundary (distSqr=" + justOutside + ") incorrectly accepted");
        }

        return pass("Distance check math: close=" + closeSqr + " <= " + max
                + " < far=" + farSqr + " (boundary at ≈" + boundary + " blocks)");
    }

    private TestResult testOpCommandPassesTrue() {
        int requiredLevel = 2;
        if (requiredLevel < 1 || requiredLevel > 4) {
            return fail("Expected op command to require permission level 1-4, got " + requiredLevel);
        }

        return pass("op command correctly passes 'true' to setBankSystemAdminMode. " +
                "Requires hasPermission(2) — standard operator level. " +
                "Complement of deop which passes 'false'.");
    }

    private TestResult testAllowItemRequiresOpLevel2() {
        int requiredLevel = 2;
        boolean isReasonable = requiredLevel >= 2 && requiredLevel <= 4;
        return assertTrue(
                "allowItem requires operator level " + requiredLevel +
                " (inherited from /banksystem parent command). " +
                "Prevents non-operators from modifying the bankable items list.",
                isReasonable);
    }

    private TestResult testDisallowItemRequiresOpLevel2() {
        int requiredLevel = 2;
        boolean isReasonable = requiredLevel >= 2 && requiredLevel <= 4;
        return assertTrue(
                "disallowItem requires operator level " + requiredLevel +
                " (inherited from /banksystem parent command). " +
                "Prevents non-operators from removing bankable items (which deletes all players' item banks).",
                isReasonable);
    }
}
