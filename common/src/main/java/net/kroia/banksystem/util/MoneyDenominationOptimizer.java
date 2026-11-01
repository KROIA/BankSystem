package net.kroia.banksystem.util;

import net.kroia.banksystem.minecraft.item.BankSystemItems;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Greedy largest-first splitter for a cent-value amount into BankSystem money-item
 * denominations (Task #39, v2.0.7). Used by the ATM Money Converter tab to turn a
 * cache-balance amount into a minimum-item banknote combination for dispensing.
 *
 * <p><b>Canonical currency precondition.</b> The current denomination set advertised
 * by {@link BankSystemItems#getMoneyItems()} — 0.01 through 1,000,000 with the
 * within-magnitude ratios 1 / 5 / 10 / 20 / 50 — is a <i>canonical</i> currency
 * system: every non-negative amount decomposes into the item-count-minimal split
 * using pure greedy (largest-first) subtraction. Each denomination is either at
 * least twice the previous or the ratios cascade cleanly (5&rarr;10, 10&rarr;20,
 * 20&rarr;50, 50&rarr;100), so a smaller denomination can never yield a better
 * count than the largest that fits.
 *
 * <p>If a future denomination set breaks the canonical property (e.g. adding a
 * $30 note between $20 and $50), the greedy strategy is no longer guaranteed
 * optimal — replace this class's implementation with a coin-change dynamic
 * programming solve. Assumption is intentional for v2.0.7 to keep the hot path
 * O(D) with D denominations.
 */
public final class MoneyDenominationOptimizer {

    private MoneyDenominationOptimizer() {}

    /**
     * Result of a split: item counts by ItemID plus any leftover cents that the
     * denomination set could not represent. For cent-multiple amounts against the
     * canonical BankSystem set, {@code leftover} is always {@code 0}.
     */
    public record SplitResult(Map<ItemID, Long> counts, long leftover) {}

    /**
     * Greedy largest-first split of {@code amount} cents into money-item denominations.
     * Returns a {@link SplitResult} whose {@link SplitResult#counts()} is a
     * {@link LinkedHashMap} ordered largest-first (deterministic iteration order for
     * dispensing / testing). Negative amounts are treated as {@code 0} (empty split,
     * zero leftover).
     *
     * @param amount cents to decompose
     * @return non-null result, safe to consume even when {@code amount <= 0}
     */
    public static SplitResult split(long amount) {
        Map<ItemID, Long> counts = new LinkedHashMap<>();
        if (amount <= 0) {
            return new SplitResult(counts, 0L);
        }

        // Snapshot the current denomination set + sort descending by worth.
        // BankSystemItems.getMoneyItems() returns a fresh list each call.
        ArrayList<ItemStack> denominations = BankSystemItems.getMoneyItems();
        List<ItemStack> sorted = new ArrayList<>(denominations);
        sorted.sort((a, b) -> {
            long wa = worthOf(a);
            long wb = worthOf(b);
            return Long.compare(wb, wa);
        });

        long remaining = amount;
        for (ItemStack stack : sorted) {
            long worth = worthOf(stack);
            if (worth <= 0) continue;
            long count = remaining / worth;
            if (count > 0) {
                counts.put(ItemID.of(stack), count);
                remaining -= count * worth;
                if (remaining == 0) break;
            }
        }
        return new SplitResult(counts, remaining);
    }

    private static long worthOf(ItemStack stack) {
        if (stack.getItem() instanceof MoneyItem money) {
            return money.worth();
        }
        return 0L;
    }
}
