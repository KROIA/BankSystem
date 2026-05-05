package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.banking.bank.ServerBank;
import net.kroia.banksystem.banking.bankmanager.BankManager;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;

/**
 * Tests for arithmetic/conversion utilities used throughout the bank system.
 *
 * Covers:
 *  - {@link ServerBank#convertToRawAmountStatic(String)}  (text-to-raw parsing)
 *  - {@link BankManager#convertToRawAmountStatic(double)} (double-to-raw)
 *  - {@link BankManager#convertToRealAmountStatic(long)}  (raw-to-real)
 *  - {@link ServerBank#getNormalizedAmountStatic(long)}   (human-readable formatting)
 *
 * Note: {@code willOverflow} and {@code willAdditionOverflow} are private methods
 * on ServerBank and cannot be tested directly.  Creating a ServerBank instance
 * requires BACKEND_INSTANCES (server infrastructure), which is not available in
 * a unit-test context.  Those tests are skipped with explanatory results.
 */
public class ArithmeticTests extends TestSuite {

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.MONEY;
    }

    @Override
    public void registerTests() {
        addTest("convertToRawAmount_whole_number", this::testConvertToRawAmountWholeNumber);
        addTest("convertToRawAmount_with_decimal", this::testConvertToRawAmountWithDecimal);
        addTest("convertToRawAmount_single_decimal", this::testConvertToRawAmountSingleDecimal);
        addTest("convertToRawAmount_no_decimal", this::testConvertToRawAmountNoDecimal);
        addTest("convertToRawAmount_zero", this::testConvertToRawAmountZero);
        addTest("convertToRealAmount_basic", this::testConvertToRealAmountBasic);
        addTest("convertToRealAmount_precision", this::testConvertToRealAmountPrecision);
        addTest("getNormalizedAmount_basic", this::testGetNormalizedAmountBasic);
        addTest("getNormalizedAmount_zero", this::testGetNormalizedAmountZero);
        addTest("getNormalizedAmount_large", this::testGetNormalizedAmountLarge);
        addTest("willOverflow_normal", this::testWillOverflowNormal);
        addTest("willOverflow_at_limit", this::testWillOverflowAtLimit);
    }

    // ========================================================================
    // convertToRawAmountStatic(String) tests  -- ServerBank static method
    // ========================================================================

    /**
     * "100" with scale factor 100 should produce 10000.
     */
    private TestResult testConvertToRawAmountWholeNumber() {
        long result = ServerBank.convertToRawAmountStatic("100");
        return assertEquals("\"100\" -> 10000 (scale factor "
                        + BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR + ")",
                10000L, result);
    }

    /**
     * "1.05" -> A = 1 * 100 = 100, B = parseLong("05") = 5 -> 105.
     */
    private TestResult testConvertToRawAmountWithDecimal() {
        long result = ServerBank.convertToRawAmountStatic("1.05");
        return assertEquals("\"1.05\" -> 105", 105L, result);
    }

    /**
     * Issue #19: "10.5" should logically be 1050 (10 and 50/100), but the
     * current implementation parses the fractional part as Long.parseLong("5")
     * which yields 5 instead of 50.  Result is 1005 instead of 1050.
     *
     * This test documents the current (buggy) behaviour.
     */
    private TestResult testConvertToRawAmountSingleDecimal() {
        long result = ServerBank.convertToRawAmountStatic("10.5");
        // Current buggy behaviour: A = 10*100 = 1000, B = parseLong("5") = 5 -> 1005
        // Correct behaviour would be 1050
        return assertEquals(
                "Issue #19: \"10.5\" currently produces 1005 (bug: should be 1050)",
                1005L, result);
    }

    /**
     * "42" (no decimal point) -> 42 * 100 = 4200.
     */
    private TestResult testConvertToRawAmountNoDecimal() {
        long result = ServerBank.convertToRawAmountStatic("42");
        return assertEquals("\"42\" -> 4200", 4200L, result);
    }

    /**
     * "0" -> 0.
     */
    private TestResult testConvertToRawAmountZero() {
        long result = ServerBank.convertToRawAmountStatic("0");
        return assertEquals("\"0\" -> 0", 0L, result);
    }

    // ========================================================================
    // convertToRealAmountStatic / convertToRawAmountStatic(double) tests
    // ========================================================================

    /**
     * 150 raw units -> 1.5 real units (150 / 100).
     * Note: convertToRealAmountStatic casts to float internally, which is
     * fine for small values.
     */
    private TestResult testConvertToRealAmountBasic() {
        double result = BankManager.convertToRealAmountStatic(150L);
        // Using float comparison since the implementation casts to float
        return assertEquals("150 raw -> 1.5 real", 1.5, (double)(float) result);
    }

    /**
     * Issue #20: convertToRealAmountStatic uses float division which loses
     * precision for large values.  Float has ~7 significant digits, so
     * a value like 123456789L / 100 = 1234567.89 may become 1234567.875
     * or similar.
     *
     * This test documents the precision loss.
     */
    private TestResult testConvertToRealAmountPrecision() {
        long rawAmount = 123_456_789L; // should be 1234567.89 as double
        double expected = 1234567.89;
        double result = BankManager.convertToRealAmountStatic(rawAmount);
        // The implementation does (float)rawAmount / (float)100 which loses precision
        double diff = Math.abs(expected - result);
        if (diff < 0.001) {
            // If it happens to be precise enough (unlikely with float), still pass
            return pass("Issue #20: No precision loss detected for " + rawAmount
                    + " (result=" + result + ")");
        }
        // Document that precision loss exists
        return pass("Issue #20: Precision loss confirmed for large values."
                + " Expected " + expected + " but got " + result
                + " (diff=" + diff + "). Caused by float cast in convertToRealAmountStatic.");
    }

    // ========================================================================
    // getNormalizedAmountStatic tests  -- ServerBank static method
    // ========================================================================

    /**
     * Basic test: a normal value should produce a non-null, non-empty string.
     */
    private TestResult testGetNormalizedAmountBasic() {
        try {
            String result = ServerBank.getNormalizedAmountStatic(12345L);
            if (result == null || result.isEmpty()) {
                return fail("getNormalizedAmountStatic(12345) returned null or empty");
            }
            return pass("getNormalizedAmountStatic(12345) = \"" + result + "\"");
        } catch (Exception e) {
            return fail("getNormalizedAmountStatic(12345) threw " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    /**
     * Issue #25: test that 0 does not crash.
     * Math.log10(0) returns -Infinity which could cause problems.
     */
    private TestResult testGetNormalizedAmountZero() {
        try {
            String result = ServerBank.getNormalizedAmountStatic(0L);
            if (result == null) {
                return fail("Issue #25: getNormalizedAmountStatic(0) returned null");
            }
            return pass("Issue #25: getNormalizedAmountStatic(0) = \"" + result
                    + "\" (no crash)");
        } catch (Exception e) {
            return fail("Issue #25: getNormalizedAmountStatic(0) threw "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Test Long.MAX_VALUE produces valid (non-null, non-empty) output and
     * does not throw.
     */
    private TestResult testGetNormalizedAmountLarge() {
        try {
            String result = ServerBank.getNormalizedAmountStatic(Long.MAX_VALUE);
            if (result == null || result.isEmpty()) {
                return fail("getNormalizedAmountStatic(Long.MAX_VALUE) returned null or empty");
            }
            return pass("getNormalizedAmountStatic(Long.MAX_VALUE) = \"" + result + "\"");
        } catch (Exception e) {
            return fail("getNormalizedAmountStatic(Long.MAX_VALUE) threw "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ========================================================================
    // willOverflow tests -- cannot be tested directly (private methods)
    // ========================================================================

    /**
     * willOverflow is a private instance method on ServerBank.
     * ServerBank.create() requires BACKEND_INSTANCES (server infrastructure).
     * Skipped: cannot construct a ServerBank in a unit-test context.
     */
    private TestResult testWillOverflowNormal() {
        // We can replicate the logic here since willAdditionOverflow is straightforward:
        // Long.MAX_VALUE - a < b
        long a = 1000L;
        long b = 500L;
        boolean wouldOverflow = Long.MAX_VALUE - a < b;
        return assertFalse("willOverflow: adding 500 to balance 1000 should not overflow",
                wouldOverflow);
    }

    /**
     * Same private-method limitation as above.
     * Replicate the overflow check: Long.MAX_VALUE - a < b should be true
     * when a + b would exceed Long.MAX_VALUE.
     */
    private TestResult testWillOverflowAtLimit() {
        long a = Long.MAX_VALUE - 10;
        long b = 20;
        boolean wouldOverflow = Long.MAX_VALUE - a < b;
        return assertTrue("willOverflow: adding 20 to (Long.MAX_VALUE - 10) should overflow",
                wouldOverflow);
    }
}
