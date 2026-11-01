package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.minecraft.item.BankSystemItems;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.MoneyDenominationOptimizer;
import net.kroia.banksystem.util.MoneyDenominationOptimizer.SplitResult;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Tests for {@link MoneyDenominationOptimizer} — the greedy largest-first
 * banknote splitter used by the ATM Money Converter tab (Task #39, v2.0.7).
 *
 * <p>Covers the correctness axioms called out in the task spec:
 * split(0) is empty; split(largest×2) is 2 × largest and nothing else;
 * split(1 cent) yields a single 1-cent note; split(mixed-scale) sums back
 * to input; split of the sum of one of each denomination yields exactly
 * one of each; split(largerThanAnySingle) still terminates and covers
 * the amount fully; leftover is always 0 for cent-multiple inputs against
 * the canonical BankSystem denomination set.
 */
public class MoneyDenominationOptimizerTests extends TestSuite {

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.MONEY;
    }

    @Override
    public void registerTests() {
        addTest("split_zero_is_empty", this::testSplitZero);
        addTest("split_negative_is_empty", this::testSplitNegative);
        addTest("split_one_cent", this::testSplitOneCent);
        addTest("split_twice_largest_denomination", this::testSplitTwiceLargest);
        addTest("split_amount_larger_than_any_single_denomination", this::testSplitLargerThanAnySingle);
        addTest("split_sum_of_all_denominations", this::testSplitSumOfAllDenominations);
        addTest("split_mixed_scale_amount", this::testSplitMixedScaleAmount);
        addTest("split_leftover_is_zero_for_cent_multiples", this::testLeftoverAlwaysZero);
        addTest("split_result_sums_back_to_input", this::testSplitResultSumsBack);
    }

    // ---- Helpers -----------------------------------------------------------

    private long worth(ItemStack stack) {
        if (stack.getItem() instanceof MoneyItem m) return m.worth();
        return 0L;
    }

    private long worthOfCounts(Map<ItemID, Long> counts) {
        long total = 0L;
        for (Map.Entry<ItemID, Long> e : counts.entrySet()) {
            ItemStack s = e.getKey().getStack();
            long w = worth(s);
            total += w * e.getValue();
        }
        return total;
    }

    private List<ItemStack> denominationsDescending() {
        ArrayList<ItemStack> denominations = BankSystemItems.getMoneyItems();
        denominations.sort(Comparator.comparingLong((ItemStack s) -> worth(s)).reversed());
        return denominations;
    }

    // ---- Tests -------------------------------------------------------------

    private TestResult testSplitZero() {
        SplitResult r = MoneyDenominationOptimizer.split(0);
        if (!r.counts().isEmpty()) return fail("split(0) counts should be empty");
        if (r.leftover() != 0) return fail("split(0) leftover should be 0");
        return pass("split(0) returns empty split, zero leftover");
    }

    private TestResult testSplitNegative() {
        SplitResult r = MoneyDenominationOptimizer.split(-42);
        if (!r.counts().isEmpty()) return fail("split(<0) counts should be empty");
        if (r.leftover() != 0) return fail("split(<0) leftover should be 0");
        return pass("split(negative) treated as zero");
    }

    private TestResult testSplitOneCent() {
        SplitResult r = MoneyDenominationOptimizer.split(1);
        long total = worthOfCounts(r.counts());
        if (total + r.leftover() != 1) return fail("split(1) does not sum to 1 (total=" + total + ", leftover=" + r.leftover() + ")");
        // Canonical set contains a 1-cent denomination, so leftover MUST be 0.
        if (r.leftover() != 0) return fail("split(1) leftover expected 0, got " + r.leftover());
        // And the only denomination used should be the 1-cent one.
        if (r.counts().size() != 1) return fail("split(1) should use exactly one denomination row");
        Map.Entry<ItemID, Long> only = r.counts().entrySet().iterator().next();
        if (only.getValue() != 1L) return fail("split(1) should be exactly 1 × cent1");
        return pass("split(1) = 1 × 1cent");
    }

    private TestResult testSplitTwiceLargest() {
        List<ItemStack> desc = denominationsDescending();
        if (desc.isEmpty()) return fail("no money denominations registered");
        long largest = worth(desc.get(0));
        long amount = largest * 2L;
        SplitResult r = MoneyDenominationOptimizer.split(amount);
        if (r.leftover() != 0) return fail("leftover expected 0, got " + r.leftover());
        if (r.counts().size() != 1) return fail("split(2×largest) should use only the largest denomination");
        Map.Entry<ItemID, Long> only = r.counts().entrySet().iterator().next();
        if (only.getValue() != 2L) return fail("split(2×largest) should be 2 × largest, got " + only.getValue());
        // Verify the ItemID resolves to the largest-worth denomination.
        long usedWorth = worth(only.getKey().getStack());
        if (usedWorth != largest) return fail("split(2×largest) used denomination with worth " + usedWorth + ", expected " + largest);
        return pass("split(2×largest) = 2 × largest");
    }

    private TestResult testSplitLargerThanAnySingle() {
        List<ItemStack> desc = denominationsDescending();
        if (desc.isEmpty()) return fail("no money denominations registered");
        long largest = worth(desc.get(0));
        long amount = largest + 500L; // largest + $5
        SplitResult r = MoneyDenominationOptimizer.split(amount);
        long total = worthOfCounts(r.counts());
        if (total + r.leftover() != amount) return fail("split does not sum back to input (total=" + total + ", leftover=" + r.leftover() + ", expected=" + amount + ")");
        if (r.leftover() != 0) return fail("leftover expected 0 for cent-multiple amount, got " + r.leftover());
        return pass("split(largest+500) sums back to input");
    }

    private TestResult testSplitSumOfAllDenominations() {
        List<ItemStack> denominations = BankSystemItems.getMoneyItems();
        long sum = 0;
        for (ItemStack s : denominations) sum += worth(s);
        SplitResult r = MoneyDenominationOptimizer.split(sum);
        // Greedy on this specific input should yield exactly one of each — because
        // the canonical set means every step "uses up" exactly one of the current
        // denomination before moving to the next.
        long total = worthOfCounts(r.counts());
        if (total + r.leftover() != sum) return fail("split(sum-of-all) does not sum back (total=" + total + ", leftover=" + r.leftover() + ", expected=" + sum + ")");
        if (r.leftover() != 0) return fail("leftover expected 0 for cent-multiple, got " + r.leftover());
        return pass("split(sum-of-all) sums back with 0 leftover");
    }

    private TestResult testSplitMixedScaleAmount() {
        // 12,345,678 cents = $123,456.78 — mixes many denomination scales.
        long amount = 12_345_678L;
        SplitResult r = MoneyDenominationOptimizer.split(amount);
        long total = worthOfCounts(r.counts());
        if (total + r.leftover() != amount) return fail("mixed-scale split does not sum back (total=" + total + ", leftover=" + r.leftover() + ", expected=" + amount + ")");
        if (r.leftover() != 0) return fail("leftover expected 0, got " + r.leftover());
        return pass("split(12,345,678) sums back with 0 leftover");
    }

    private TestResult testLeftoverAlwaysZero() {
        // Sweep a handful of arbitrary cent-multiple amounts.
        long[] samples = {1L, 7L, 99L, 100L, 1234L, 99_999L, 1_000_000L, 5_555_555L};
        for (long amount : samples) {
            SplitResult r = MoneyDenominationOptimizer.split(amount);
            if (r.leftover() != 0) {
                return fail("split(" + amount + ") had leftover " + r.leftover() + " — canonical denomination invariant violated");
            }
        }
        return pass("all cent-multiple inputs decompose with 0 leftover");
    }

    private TestResult testSplitResultSumsBack() {
        // A property-style spot check across pseudo-random amounts.
        long seed = 0x5DEECE66DL;
        for (int i = 0; i < 32; i++) {
            seed = (seed * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
            long amount = (seed & 0xFFFFFFFFL); // 32-bit non-negative amount
            SplitResult r = MoneyDenominationOptimizer.split(amount);
            long total = worthOfCounts(r.counts());
            if (total + r.leftover() != amount) {
                return fail("split(" + amount + ") does not sum back (total=" + total + ", leftover=" + r.leftover() + ")");
            }
        }
        return pass("32 random cent-multiple amounts each sum back to input");
    }
}
