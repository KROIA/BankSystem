package net.kroia.banksystem.minecraft.menu.custom;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * Result slot of the Bank Terminal crafting grid.
 * <p>
 * In the plain-vanilla configuration ("Use Bank Items" and "Auto-deposit output"
 * both off) this behaves exactly like the vanilla crafting-table result slot: the
 * inherited {@link ResultSlot#onTake} decrements the grid and applies remainder
 * items (buckets etc.).
 * <p>
 * When either crafting checkbox is enabled, taking the result is handled by the
 * menu's asynchronous bank-craft flow instead ({@code BankTerminalContainerMenu}
 * intercepts the click before slot logic runs) — bank withdrawals can complete
 * asynchronously on multi-server setups, so the item cannot be handed out
 * synchronously here. {@link #mayPickup(Player)} additionally blocks pickup while
 * such a craft is in flight or while the custom path is active, so vanilla slot
 * logic (drag-crafting, double-click collection) can never duplicate or bypass the
 * atomic bank deduction.
 */
public class BankTerminalResultSlot extends ResultSlot {

    private final BankTerminalContainerMenu menu;

    public BankTerminalResultSlot(BankTerminalContainerMenu menu, Player player, CraftingContainer craftSlots,
                                  Container resultContainer, int index, int x, int y) {
        super(player, craftSlots, resultContainer, index, x, y);
        this.menu = menu;
    }

    @Override
    public boolean mayPickup(Player player) {
        // The custom bank-craft flow owns the take; never let vanilla slot logic
        // hand the item out (would bypass the atomic bank ingredient deduction).
        if (menu.isCustomCraftPath() || menu.isCraftInProgress())
            return false;
        return super.mayPickup(player);
    }

    @Override
    public void onTake(Player player, ItemStack stack) {
        // Vanilla path only: decrements the grid and applies remainder items.
        super.onTake(player, stack);
    }

    /**
     * Public bridge to the protected {@link net.minecraft.world.inventory.Slot#onQuickCraft}
     * so the menu (different package) can report shift-click craft counts for
     * stats/achievements, mirroring vanilla {@code CraftingMenu.quickMoveStack}.
     */
    public void onQuickCraft_public(ItemStack taken, ItemStack original) {
        this.onQuickCraft(taken, original);
    }
}
