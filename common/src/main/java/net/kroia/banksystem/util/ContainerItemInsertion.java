package net.kroia.banksystem.util;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Shared helper for materializing bank credits as physical {@link ItemStack}s inside a
 * {@link Container} (bank terminal inventory, bank download block, ...).
 * <p>
 * <b>Why physical stack merging must NOT be driven by {@link ItemID} equality:</b>
 * ItemID identity deliberately ignores volatile and deposit-gated data components
 * (see {@link VolatileItemComponents}), so two stacks with the same ItemID can still be
 * component-distinct — e.g. a spoiled and a fresh TFC food share one ItemID. Merging
 * withdrawn (fresh) items onto such a stack keeps the existing stack's components and
 * silently rewrites the withdrawn items' state (fresh food turns spoiled on arrival).
 * <p>
 * This helper therefore mirrors vanilla insertion semantics ({@code Inventory.add}):
 * <ol>
 *     <li>Merge only into stacks that are component-equal to the withdrawn stack per the
 *         game's own {@link ItemStack#isSameItemSameComponents} — the same predicate
 *         vanilla containers use, which mods (e.g. TFC) mixin their own equivalence
 *         semantics into (rotten never equals fresh).</li>
 *     <li>Place the remainder into empty slots.</li>
 *     <li>Report back how much was actually inserted — whatever did not fit is the
 *         caller's responsibility (typically: leave it in the bank). Items are never
 *         silently voided.</li>
 * </ol>
 * ItemID equality remains the correct predicate for <i>ledger</i> operations (counting
 * credit value); only physical slot placement must use component-aware equality.
 */
public final class ContainerItemInsertion {

    private ContainerItemInsertion() {}

    /**
     * Inserts up to {@code amount} withdrawn items of the given {@link ItemID} into the
     * container, merging only into component-equal stacks and using empty slots otherwise.
     * <p>
     * The inserted stacks are copies of the ItemID's registered template
     * ({@link ItemID#getStack()} returns a defensive copy) — third-party constructor hooks
     * stamp fresh volatile state (e.g. a fresh {@code tfc:food} creation date) onto the
     * copy, exactly like on any other withdrawal.
     *
     * @param container the target container (mutated; {@code setChanged()} is triggered
     *                  through {@link Container#setItem} when something was inserted)
     * @param itemID    the ItemID whose withdrawal-equivalent stack should be inserted
     * @param amount    the number of items to insert
     * @return the number of items actually inserted ({@code 0 <= result <= amount});
     *         the caller must handle the remainder (e.g. keep it in the bank)
     */
    public static long insertWithdrawnItems(@NotNull Container container, @NotNull ItemID itemID, long amount) {
        if (amount <= 0)
            return 0;
        return insertWithdrawnItems(container, itemID.getStack(), amount);
    }

    /**
     * Inserts up to {@code amount} items equal to {@code withdrawalStack} into the container.
     * See {@link #insertWithdrawnItems(Container, ItemID, long)} for the merge rules and the
     * rationale; this overload exists for callers that already built the withdrawal stack.
     *
     * @param container       the target container (mutated)
     * @param withdrawalStack the stack the inserted items must be component-equal to
     *                        (never mutated; its count is ignored)
     * @param amount          the number of items to insert
     * @return the number of items actually inserted ({@code 0 <= result <= amount})
     */
    public static long insertWithdrawnItems(@NotNull Container container, @NotNull ItemStack withdrawalStack, long amount) {
        if (amount <= 0 || withdrawalStack.isEmpty())
            return 0;
        long remaining = amount;

        // Pass 1 (like vanilla Inventory.add): top up existing stacks, but ONLY those that
        // are component-equal to the withdrawn stack. ItemID equality must not be used here:
        // identity ignores volatile/gated components, so an ItemID-equal stack may still be
        // component-distinct (e.g. spoiled food) and must keep its own slot untouched.
        for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
            ItemStack slotStack = container.getItem(i);
            if (slotStack.isEmpty() || slotStack.getCount() >= slotStack.getMaxStackSize())
                continue;
            if (!ItemStack.isSameItemSameComponents(slotStack, withdrawalStack))
                continue;
            int addCount = (int) Math.min(remaining, slotStack.getMaxStackSize() - slotStack.getCount());
            slotStack.grow(addCount);
            container.setItem(i, slotStack);
            remaining -= addCount;
        }

        // Pass 2: place whatever could not be merged into empty slots, as full template
        // copies (a plain `new ItemStack(item)` would drop the template's data components).
        for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
            if (!container.getItem(i).isEmpty())
                continue;
            ItemStack newStack = withdrawalStack.copy();
            int count = (int) Math.min(remaining, newStack.getMaxStackSize());
            newStack.setCount(count);
            container.setItem(i, newStack);
            remaining -= count;
        }

        // No free capacity left: the un-inserted remainder is returned to the caller and
        // stays in the bank — items are never silently voided.
        return amount - remaining;
    }
}
