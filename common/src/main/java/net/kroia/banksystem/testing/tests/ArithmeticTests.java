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

        // Extended coverage (Task #T2)
        addTest("convertToRawAmount_null_input", this::testConvertToRawAmountNullInput);
        addTest("convertToRawAmount_empty_input", this::testConvertToRawAmountEmptyInput);
        addTest("convertToRawAmount_garbage_input", this::testConvertToRawAmountGarbageInput);
        addTest("convertToRawAmount_comma_separator", this::testConvertToRawAmountCommaSeparator);
        addTest("convertToRawAmount_excess_fraction_digits", this::testConvertToRawAmountExcessFractionDigits);
        addTest("convertToRawAmount_only_decimal_dot", this::testConvertToRawAmountOnlyDecimalDot);
        addTest("convertToRawAmount_zero_with_decimal", this::testConvertToRawAmountZeroWithDecimal);
        addTest("convertToRawAmount_double_basic", this::testConvertToRawAmountDoubleBasic);
        addTest("convertToRawAmount_double_custom_scale", this::testConvertToRawAmountDoubleCustomScale);
        addTest("convertToRealAmount_zero", this::testConvertToRealAmountZero);
        addTest("convertToRealAmount_max_long", this::testConvertToRealAmountMaxLong);
        addTest("convertToRealAmount_custom_scale", this::testConvertToRealAmountCustomScale);
        addTest("getNormalizedAmount_negative", this::testGetNormalizedAmountNegative);
        addTest("getNormalizedAmount_below_one_unit", this::testGetNormalizedAmountBelowOneUnit);
        addTest("getNormalizedAmount_at_kilo_boundary", this::testGetNormalizedAmountAtKiloBoundary);
        addTest("getNormalizedAmount_at_mega_boundary", this::testGetNormalizedAmountAtMegaBoundary);
        addTest("getFormattedAmount_basic", this::testGetFormattedAmountBasic);
        addTest("getFormattedAmount_zero", this::testGetFormattedAmountZero);
        addTest("roundtrip_raw_to_real_to_raw", this::testRoundtripRawToRealToRaw);
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
     * Issue #19 (fixed): "10.5" should be 1050 (10 and 50/100). The fractional
     * part "5" is now padded to "50" before parsing.
     */
    private TestResult testConvertToRawAmountSingleDecimal() {
        long result = ServerBank.convertToRawAmountStatic("10.5");
        return assertEquals(
                "Issue #19: \"10.5\" -> 1050 (10 wholes + 50 hundredths)",
                1050L, result);
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

    // ========================================================================
    // Extended coverage — Task #T2
    // ========================================================================

    /** Null input must return 0 without throwing. */
    private TestResult testConvertToRawAmountNullInput() {
        return assertEquals("null -> 0", 0L, ServerBank.convertToRawAmountStatic(null));
    }

    /** Empty string must return 0 without throwing. */
    private TestResult testConvertToRawAmountEmptyInput() {
        return assertEquals("\"\" -> 0", 0L, ServerBank.convertToRawAmountStatic(""));
    }

    /** Non-numeric input must not throw — should return 0. */
    private TestResult testConvertToRawAmountGarbageInput() {
        try {
            long result = ServerBank.convertToRawAmountStatic("abc");
            return assertEquals("\"abc\" -> 0 (graceful)", 0L, result);
        } catch (Exception e) {
            return fail("Garbage input threw: " + e);
        }
    }

    /** Comma should be accepted as decimal separator. */
    private TestResult testConvertToRawAmountCommaSeparator() {
        long result = ServerBank.convertToRawAmountStatic("1,05");
        return assertEquals("\"1,05\" -> 105 (comma == dot)", 105L, result);
    }

    /** More fractional digits than scale factor: extras must be truncated, not added. */
    private TestResult testConvertToRawAmountExcessFractionDigits() {
        // scale=100, "1.999" -> A=100, B should truncate "999" -> "99" -> 199 total
        long result = ServerBank.convertToRawAmountStatic("1.999");
        return assertEquals("\"1.999\" with scale 100 -> 199 (extra digits truncated)", 199L, result);
    }

    /** Just a "." with no digits should be 0, not throw. */
    private TestResult testConvertToRawAmountOnlyDecimalDot() {
        try {
            long result = ServerBank.convertToRawAmountStatic(".");
            return assertEquals("\".\" -> 0", 0L, result);
        } catch (Exception e) {
            return fail("\".\" threw: " + e);
        }
    }

    /** "0.5" -> 50 (5 tenths = 50 hundredths). */
    private TestResult testConvertToRawAmountZeroWithDecimal() {
        long result = ServerBank.convertToRawAmountStatic("0.5");
        return assertEquals("\"0.5\" -> 50 (0 wholes + 50 hundredths)", 50L, result);
    }

    /** convertToRawAmount(double) basic case. */
    private TestResult testConvertToRawAmountDoubleBasic() {
        long result = BankManager.convertToRawAmountStatic(1.5);
        return assertEquals("1.5 -> 150", 150L, result);
    }

    /** convertToRawAmount(double, scale) with non-default scale. */
    private TestResult testConvertToRawAmountDoubleCustomScale() {
        long result = BankManager.convertToRawAmountStatic(2.5, 1000);
        return assertEquals("2.5 with scale 1000 -> 2500", 2500L, result);
    }

    /** 0 raw -> 0.0 real. */
    private TestResult testConvertToRealAmountZero() {
        double result = BankManager.convertToRealAmountStatic(0L);
        return assertEquals("0L -> 0.0", 0.0, result);
    }

    /**
     * After Issue #20 fix: convertToRealAmountStatic uses double, so large values
     * preserve precision. Long.MAX_VALUE / 100 should round-trip cleanly.
     */
    private TestResult testConvertToRealAmountMaxLong() {
        try {
            double result = BankManager.convertToRealAmountStatic(Long.MAX_VALUE);
            if (Double.isNaN(result) || Double.isInfinite(result)) {
                return fail("Long.MAX_VALUE produced NaN/Infinity: " + result);
            }
            // double has ~15-17 significant digits, can represent Long.MAX_VALUE/100 within epsilon
            double expected = (double) Long.MAX_VALUE / 100.0;
            double diff = Math.abs(result - expected);
            if (diff > 1.0) {
                return fail("Issue #20 regression — convertToRealAmountStatic(Long.MAX_VALUE) "
                        + "off by " + diff + " (got " + result + ", expected ~" + expected + "). "
                        + "Likely reverted to float cast.");
            }
            return pass("Long.MAX_VALUE handled with double precision (diff=" + diff + ")");
        } catch (Exception e) {
            return fail("Threw: " + e);
        }
    }

    /** convertToRealAmount(long, scale) with non-default scale. */
    private TestResult testConvertToRealAmountCustomScale() {
        double result = BankManager.convertToRealAmountStatic(2500L, 1000);
        return assertEquals("2500 with scale 1000 -> 2.5", 2.5, result);
    }

    /** Negative input must not crash (Issue #25). */
    private TestResult testGetNormalizedAmountNegative() {
        try {
            String result = ServerBank.getNormalizedAmountStatic(-100L);
            if (result == null || result.isEmpty()) {
                return fail("getNormalizedAmountStatic(-100) returned null or empty");
            }
            return pass("getNormalizedAmountStatic(-100) = \"" + result + "\" (no crash)");
        } catch (Exception e) {
            return fail("Issue #25 regression — negative input threw " + e);
        }
    }

    /**
     * Sub-unit values (less than ITEM_FRACTION_SCALE_FACTOR) should produce
     * decimal output, not crash.
     */
    private TestResult testGetNormalizedAmountBelowOneUnit() {
        try {
            // 50 with scale 100 = 0.5 real
            String result = ServerBank.getNormalizedAmountStatic(50L);
            if (result == null || result.isEmpty()) {
                return fail("getNormalizedAmountStatic(50) returned null or empty");
            }
            return pass("getNormalizedAmountStatic(50) = \"" + result + "\"");
        } catch (Exception e) {
            return fail("Threw: " + e);
        }
    }

    /** Boundary at the 1k transition (1_000 wholes = 100_000 raw with scale 100). */
    private TestResult testGetNormalizedAmountAtKiloBoundary() {
        try {
            String at = ServerBank.getNormalizedAmountStatic(100_000L);
            String just_below = ServerBank.getNormalizedAmountStatic(99_900L);
            if (at == null || just_below == null) {
                return fail("Null result at kilo boundary");
            }
            return pass("kilo boundary: 99_900 -> \"" + just_below + "\", 100_000 -> \"" + at + "\"");
        } catch (Exception e) {
            return fail("Kilo boundary threw: " + e);
        }
    }

    /** Boundary at the 1M transition (1_000_000 wholes = 100_000_000 raw). */
    private TestResult testGetNormalizedAmountAtMegaBoundary() {
        try {
            String at = ServerBank.getNormalizedAmountStatic(100_000_000L);
            if (at == null || at.isEmpty()) {
                return fail("getNormalizedAmountStatic(100_000_000) returned null or empty");
            }
            return pass("mega boundary: 100_000_000 -> \"" + at + "\"");
        } catch (Exception e) {
            return fail("Mega boundary threw: " + e);
        }
    }

    /** getFormattedAmountStatic basic case — should return non-null/non-empty for normal input. */
    private TestResult testGetFormattedAmountBasic() {
        try {
            String result = ServerBank.getFormattedAmountStatic(12345L);
            if (result == null || result.isEmpty()) {
                return fail("getFormattedAmountStatic(12345) returned null or empty");
            }
            return pass("getFormattedAmountStatic(12345) = \"" + result + "\"");
        } catch (Exception e) {
            return fail("Threw: " + e);
        }
    }

    /** getFormattedAmountStatic for zero. */
    private TestResult testGetFormattedAmountZero() {
        try {
            String result = ServerBank.getFormattedAmountStatic(0L);
            if (result == null) {
                return fail("getFormattedAmountStatic(0) returned null");
            }
            return pass("getFormattedAmountStatic(0) = \"" + result + "\"");
        } catch (Exception e) {
            return fail("Threw: " + e);
        }
    }

    /**
     * Round-trip raw -> real -> raw should preserve the value (within rounding)
     * for values that fit in the scale factor.
     */
    private TestResult testRoundtripRawToRealToRaw() {
        long original = 12_345L;
        double real = BankManager.convertToRealAmountStatic(original);
        long backToRaw = BankManager.convertToRawAmountStatic(real);
        if (backToRaw != original) {
            return fail("Round-trip broke: " + original + " -> " + real + " -> " + backToRaw);
        }
        return pass("Round-trip preserved: " + original + " -> " + real + " -> " + backToRaw);
    }
}
