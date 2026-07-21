package net.kroia.banksystem.minecraft.menu.custom;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.IAsyncBank;
import net.kroia.banksystem.api.bankaccount.IAsyncBankAccount;
import net.kroia.banksystem.api.bankmanager.IAsyncBankManager;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.bankmanager.BankManager;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.minecraft.block.BankSystemBlocks;
import net.kroia.banksystem.minecraft.entity.custom.BankTerminalBlockEntity;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.minecraft.menu.BankSystemMenus;
import net.kroia.banksystem.networking.entity.SetBankTerminalGhostRecipePacket;
import net.kroia.banksystem.util.BankCraftingMatcher;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.VolatileItemComponents;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Menu of the Bank Terminal block: block inventory + player inventory plus a
 * 3x3 crafting grid with result slot rendered above the container area.
 * <p>
 * <b>Crafting modes</b> (two independent, per-player-persisted flags, gated by
 * bank-account permissions and validated server-side):
 * <ul>
 *   <li><b>Use Bank Items</b> (needs {@link BankPermission#WITHDRAW}): empty grid
 *       slots are virtually completed with items from the selected bank account
 *       (see {@link BankCraftingMatcher}).</li>
 *   <li><b>Auto-deposit output</b> (needs {@link BankPermission#DEPOSIT}): the
 *       crafted output is deposited into the bank account instead of being handed
 *       to the player. Non-bankable outputs fall back to the player inventory
 *       with a log warning — the craft is never blocked and never silently dropped.</li>
 * </ul>
 * <b>Take-result paths:</b>
 * <ul>
 *   <li>Both flags off: exact vanilla crafting-table behavior (cursor pickup,
 *       shift-click craft-all) via {@link BankTerminalResultSlot}.</li>
 *   <li>Either flag on: the click is intercepted ({@link #clicked}) and handled by
 *       the asynchronous bank-craft flow. Bank ingredients are deducted atomically:
 *       every required amount is locked first ({@code lockAmountAsync}); if any
 *       lock fails, all previously acquired locks are released and the craft aborts
 *       untouched. Only after all locks succeed (and the grid is revalidated on the
 *       main thread) are grid items consumed, locked amounts withdrawn and the
 *       output delivered. On multi-server setups the lock/withdraw/deposit calls
 *       forward to the master transparently via the async bank API; all container
 *       mutations are marshaled back onto the main server thread (same pattern as
 *       {@link BankTerminalBlockEntity}).
 *       Because delivery may complete asynchronously, the output goes directly to
 *       the player inventory (or the bank) instead of the cursor in this mode.</li>
 * </ul>
 */
public class BankTerminalContainerMenu extends AbstractBankContainerMenu {

    /** Height (in GUI pixels) of the crafting panel rendered above the container texture. */
    public static final int CRAFTING_AREA_HEIGHT = 62;

    // Slot index layout (see AbstractBankContainerMenu: 0-8 hotbar, 9-35 player inv, 36-62 block inv)
    public static final int PLAYER_SLOT_START = 0;
    public static final int PLAYER_SLOT_END = 36;      // exclusive
    public static final int CRAFT_GRID_SLOT_START = 63;
    public static final int CRAFT_GRID_SLOT_COUNT = BankCraftingMatcher.GRID_SIZE;
    public static final int CRAFT_RESULT_SLOT_INDEX = CRAFT_GRID_SLOT_START + CRAFT_GRID_SLOT_COUNT;

    // Crafting panel slot positions (element-local coordinates of the screen's container view)
    public static final int CRAFT_GRID_X = 12;
    public static final int CRAFT_GRID_Y = 4;
    public static final int CRAFT_RESULT_X = 92;
    public static final int CRAFT_RESULT_Y = 22;

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    private final BankTerminalBlockEntity blockEntity;
    private final Player player;

    private final TransientCraftingContainer craftSlots = new TransientCraftingContainer(this, BankCraftingMatcher.GRID_WIDTH, BankCraftingMatcher.GRID_HEIGHT);
    private final ResultContainer resultSlots = new ResultContainer();

    // Crafting settings. On the server these are only set after permission
    // validation (applyCraftingSettings); on the client they mirror the checkbox
    // state so the click path decision matches the server.
    private boolean useBankItems = false;
    private boolean depositOutputToBank = false;
    private int craftingAccountNumber = 0;

    // Server-side crafting state
    private @Nullable IAsyncBankAccount craftingAccount = null;
    private @Nullable BankAccountData bankDataSnapshot = null;
    private @Nullable BankCraftingMatcher.Match currentMatch = null;
    private boolean craftInProgress = false;
    private boolean suppressRecipeUpdate = false;
    /** Monotonic id to discard stale async settings-validation results. */
    private int settingsRequestId = 0;

    /**
     * Explicitly selected "ghost" recipe (via JEI's "+" button while "Use Bank
     * Items" is active): the recipe is shown as ghost items in the grid and its
     * ingredients are sourced entirely from the bank (physical items still take
     * precedence per slot). Kept on both sides: the server copy (validated via
     * {@link #applyGhostRecipe}) drives matching/crafting, the client copy
     * (optimistically set in {@link #requestGhostRecipe}) drives rendering.
     * <p>
     * Cleared when: "Use Bank Items" turns off, the menu closes (menu state is
     * not persisted), the player clicks an empty ghost grid slot with an empty
     * cursor (symmetric on both sides, see {@link #clicked}), or a newly selected
     * recipe replaces it.
     */
    private @Nullable RecipeHolder<CraftingRecipe> ghostRecipe = null;

    /**
     * Ghost selection that arrived while "Use Bank Items" was not (yet) active
     * server-side. The settings packet's permission validation is asynchronous
     * (master round trip on multi-server), so a ghost packet sent right after
     * the settings packet can be processed BEFORE the flag flips on — dropping
     * it silently left the client showing optimistic ghosts with a result slot
     * that never fills. The pending selection is promoted to {@link #ghostRecipe}
     * the moment a settings validation enables bank mode, and discarded whenever
     * bank mode is (or stays) off — preserving the invariant that
     * {@code ghostRecipe} is only ever non-null in bank mode.
     */
    private @Nullable RecipeHolder<CraftingRecipe> pendingGhostRecipe = null;

    /** Tick counter for the periodic server-side bank-snapshot refresh. */
    private int snapshotRefreshTickCounter = 0;
    /** Refresh cadence (1 s) — same order as the client's stream/poll freshness. */
    private static final int SNAPSHOT_REFRESH_INTERVAL_TICKS = 20;

    // Client Constructor
    public BankTerminalContainerMenu(int containerId, Inventory playerInv, FriendlyByteBuf additionalData) {
        this(   containerId,
                playerInv,
                (BankTerminalBlockEntity) playerInv.player.level().getBlockEntity(additionalData.readBlockPos()));
    }

    // Server Constructor
    public BankTerminalContainerMenu(int containerId, Inventory playerInv, BankTerminalBlockEntity blockEntity) {
        super(  BankSystemBlocks.BANK_TERMINAL_BLOCK.get(),
                BankSystemMenus.BANK_TERMINAL_CONTAINER_MENU.get(),
                blockEntity.getInventory(playerInv.player.getUUID()),
                containerId, playerInv, blockEntity);
        this.blockEntity = blockEntity;
        this.player = playerInv.player;

        // Crafting grid (indices 63..71) + result slot (72), positioned in the
        // crafting panel above the container area.
        for (int row = 0; row < BankCraftingMatcher.GRID_HEIGHT; row++) {
            for (int col = 0; col < BankCraftingMatcher.GRID_WIDTH; col++) {
                addSlot(new Slot(craftSlots, col + row * BankCraftingMatcher.GRID_WIDTH,
                        CRAFT_GRID_X + col * 18, CRAFT_GRID_Y + row * 18));
            }
        }
        addSlot(new BankTerminalResultSlot(this, player, craftSlots, resultSlots, 0, CRAFT_RESULT_X, CRAFT_RESULT_Y));

        // Crafting flags start OFF server-side. The player's saved preference
        // lives in the per-player User.customData store (global, master-side);
        // the CLIENT restores it on screen open and sends the regular settings
        // packet, which runs the full per-account permission validation here.
    }

    @Override
    protected int getSlotYOffset() {
        // Shift hotbar / player inventory / block inventory down to make room for
        // the crafting panel rendered above them.
        return CRAFTING_AREA_HEIGHT;
    }

    public BankTerminalBlockEntity getBlockEntity() {
        return this.blockEntity;
    }
    @Override
    public BlockPos getBlockPos() {
        return this.blockEntity.getBlockPos();
    }

    public boolean isUseBankItems() {
        return useBankItems;
    }
    public boolean isDepositOutputToBank() {
        return depositOutputToBank;
    }
    public TransientCraftingContainer getCraftSlots() {
        return craftSlots;
    }

    /** True while an asynchronous bank craft is running (result clicks are ignored). */
    public boolean isCraftInProgress() {
        return craftInProgress;
    }

    /**
     * True when taking the result must go through the asynchronous bank-craft flow
     * instead of vanilla slot logic. Decided from the two crafting flags only, so
     * client and server always agree (both know the flags) without extra sync.
     */
    public boolean isCustomCraftPath() {
        return useBankItems || depositOutputToBank;
    }

    /**
     * Client-side mirror of the checkbox state (set by the screen together with the
     * settings packet). Only used for the {@link #isCustomCraftPath()} decision and
     * never trusted by the server.
     */
    public void setClientCraftingSettings(boolean useBankItems, boolean depositOutputToBank) {
        if (!player.level().isClientSide)
            return;
        this.useBankItems = useBankItems;
        this.depositOutputToBank = depositOutputToBank;
        if (!useBankItems)
            this.ghostRecipe = null; // ghost layout only exists in bank mode
    }

    // ------------------------------------------------------------------
    // Ghost recipe (explicit JEI selection, bank mode)
    // ------------------------------------------------------------------

    public @Nullable RecipeHolder<CraftingRecipe> getGhostRecipe() {
        return ghostRecipe;
    }

    /**
     * Client entry point (called from the JEI transfer handler): mirrors the
     * selection locally for immediate ghost rendering and asks the server to
     * apply it. Never trusted server-side — see {@link #applyGhostRecipe}.
     */
    public void requestGhostRecipe(RecipeHolder<CraftingRecipe> recipe) {
        if (!player.level().isClientSide)
            return;
        this.ghostRecipe = recipe;
        SetBankTerminalGhostRecipePacket.sendPacketToServer(getBlockPos(), recipe.id());
    }

    /**
     * Resolves a recipe id to a 3x3-craftable {@link RecipeType#CRAFTING} recipe
     * holder, or null (with a log warning) when the id is unknown or ineligible.
     */
    private @Nullable RecipeHolder<CraftingRecipe> resolveCraftingRecipe(net.minecraft.resources.ResourceLocation recipeId) {
        var holder = player.level().getRecipeManager().byKey(recipeId).orElse(null);
        if (holder == null || !(holder.value() instanceof CraftingRecipe craftingRecipe)
                || !craftingRecipe.canCraftInDimensions(BankCraftingMatcher.GRID_WIDTH, BankCraftingMatcher.GRID_HEIGHT)) {
            warn("Rejected recipe " + recipeId + " for player " + player.getUUID()
                    + " (not a 3x3-craftable crafting recipe)");
            return null;
        }
        @SuppressWarnings("unchecked")
        RecipeHolder<CraftingRecipe> craftingHolder = (RecipeHolder<CraftingRecipe>) holder;
        return craftingHolder;
    }

    /**
     * Server-side validation + application of a ghost-recipe selection: the id
     * must resolve to an existing {@link RecipeType#CRAFTING} recipe that fits the
     * 3x3 grid. A new selection replaces the previous layout.
     * <p>
     * If "Use Bank Items" is not active yet — the settings packet's asynchronous
     * permission validation may still be in flight when the ghost packet lands —
     * the selection is parked as {@link #pendingGhostRecipe} and promoted by
     * {@link #applyCraftingSettings} once bank mode turns on (or discarded when
     * it does not). Dropping it instead caused the intermittent
     * "ghosts visible but result slot never fills" bug.
     * <p>
     * A direct apply also refreshes the bank snapshot: the click-to-craft flow
     * is validated client-side against LIVE streamed bank data, while this
     * menu's snapshot may predate deposits made since the last refresh — the
     * immediate recompute gives instant feedback and the refresh converges it.
     */
    public void applyGhostRecipe(net.minecraft.resources.ResourceLocation recipeId) {
        if (player.level().isClientSide)
            return;
        RecipeHolder<CraftingRecipe> craftingHolder = resolveCraftingRecipe(recipeId);
        if (craftingHolder == null)
            return;
        if (!useBankItems) {
            this.pendingGhostRecipe = craftingHolder;
            return;
        }
        this.ghostRecipe = craftingHolder;
        updateCraftingResult();
        refreshBankSnapshot();
    }

    /**
     * Server-side physical grid fill for an explicitly selected recipe (bank-list
     * "craft this item" with "Use Bank Items" OFF; works without JEI).
     * <p>
     * Mirrors the semantics verified in JEI 19.21's
     * {@code BasicRecipeTransferHandlerServer}: the grid is cleared first (its
     * items are the preferred ingredient source), each recipe cell then sources
     * one matching item — displaced grid items first, then the player inventory
     * (menu slots 0..35), then the terminal block inventory (36..62) — and all
     * leftovers are stowed back player-first (merge into existing stacks, then
     * empty slots, final fallback the player inventory/drop). The fill is
     * best-effort: cells whose ingredient cannot be sourced stay empty, so a
     * partially-fillable selection shows the player what is still missing.
     * <p>
     * In bank mode the request routes to {@link #applyGhostRecipe} instead —
     * with "Use Bank Items" active, explicit selections are ghost layouts.
     */
    public void applyPhysicalRecipeFill(net.minecraft.resources.ResourceLocation recipeId) {
        if (player.level().isClientSide || craftInProgress)
            return;
        if (useBankItems) {
            applyGhostRecipe(recipeId);
            return;
        }
        RecipeHolder<CraftingRecipe> craftingHolder = resolveCraftingRecipe(recipeId);
        if (craftingHolder == null)
            return;
        Ingredient[] layout = BankCraftingMatcher.ingredientLayout(craftingHolder);

        suppressRecipeUpdate = true;
        try {
            // 1) Clear the grid; its items are the first-choice ingredient source.
            List<ItemStack> stow = new ArrayList<>();
            for (int i = 0; i < CRAFT_GRID_SLOT_COUNT; i++) {
                ItemStack inGrid = craftSlots.removeItemNoUpdate(i);
                if (!inGrid.isEmpty())
                    stow.add(inGrid);
            }
            // 2) Source one item per recipe cell (best-effort).
            for (int i = 0; i < CRAFT_GRID_SLOT_COUNT; i++) {
                if (layout[i] == null)
                    continue;
                ItemStack found = takeOneMatching(layout[i], stow);
                if (!found.isEmpty())
                    craftSlots.setItem(i, found);
            }
            // 3) Stow leftover displaced items back, player-first.
            for (ItemStack leftover : stow) {
                if (!leftover.isEmpty())
                    stowPlayerFirst(leftover);
            }
        } finally {
            suppressRecipeUpdate = false;
        }
        updateCraftingResult();
    }

    /**
     * Takes one item matching the ingredient: from the displaced-grid list first,
     * then from menu slots 0..62 in order (player hotbar/inventory before the
     * terminal block inventory — same preference as the JEI fill).
     */
    private ItemStack takeOneMatching(Ingredient ingredient, List<ItemStack> stow) {
        for (ItemStack stack : stow) {
            if (!stack.isEmpty() && ingredient.test(stack))
                return stack.split(1);
        }
        for (int i = PLAYER_SLOT_START; i < CRAFT_GRID_SLOT_START; i++) {
            Slot slot = getSlot(i);
            ItemStack inSlot = slot.getItem();
            if (!inSlot.isEmpty() && ingredient.test(inSlot) && slot.mayPickup(player))
                return slot.safeTake(1, Integer.MAX_VALUE, player);
        }
        return ItemStack.EMPTY;
    }

    /**
     * Stows a stack into menu slots 0..62 (merge into existing stacks first,
     * then empty slots — player inventory before block inventory), with the
     * player inventory/drop as the final fallback. Mirror of JEI's
     * {@code BasicRecipeTransferHandlerServer.stowItem}.
     */
    private void stowPlayerFirst(ItemStack stack) {
        ItemStack remainder = stack;
        for (int i = PLAYER_SLOT_START; i < CRAFT_GRID_SLOT_START && !remainder.isEmpty(); i++) {
            Slot slot = getSlot(i);
            ItemStack inSlot = slot.getItem();
            if (!inSlot.isEmpty() && inSlot.isStackable() && slot.mayPickup(player))
                remainder = slot.safeInsert(remainder);
        }
        for (int i = PLAYER_SLOT_START; i < CRAFT_GRID_SLOT_START && !remainder.isEmpty(); i++) {
            Slot slot = getSlot(i);
            if (slot.getItem().isEmpty())
                remainder = slot.safeInsert(remainder);
        }
        if (!remainder.isEmpty())
            player.getInventory().placeItemBackInInventory(remainder);
    }

    /** Clears the ghost layout (both sides; the server recomputes the result). */
    public void clearGhostRecipe() {
        pendingGhostRecipe = null; // same lifecycle as the applied layout
        if (ghostRecipe == null)
            return;
        ghostRecipe = null;
        if (!player.level().isClientSide)
            updateCraftingResult();
    }

    // ------------------------------------------------------------------
    // Crafting settings (server)
    // ------------------------------------------------------------------

    /**
     * Validates and applies the crafting settings server-side: checks WITHDRAW /
     * DEPOSIT permission on the target account, forces unauthorized flags off and
     * fetches a fresh bank-data snapshot for recipe matching. The flags are
     * runtime state only — the player's saved preference lives in the per-player
     * User.customData store, written by the CLIENT exclusively on genuine user
     * toggles (never on forced-off validation results, so a revoked account can
     * never wipe the global preference).
     */
    public void applyCraftingSettings(boolean requestedUseBankItems, boolean requestedDepositOutput, int accountNumber) {
        if (player.level().isClientSide)
            return;
        final int requestId = ++settingsRequestId;
        if ((!requestedUseBankItems && !requestedDepositOutput) || accountNumber <= 0) {
            runOnMainThread(() -> {
                if (requestId != settingsRequestId)
                    return;
                this.useBankItems = false;
                this.depositOutputToBank = false;
                this.craftingAccount = null;
                this.bankDataSnapshot = null;
                this.craftingAccountNumber = Math.max(accountNumber, 0);
                this.ghostRecipe = null; // ghost layout only exists in bank mode
                this.pendingGhostRecipe = null;
                updateCraftingResult();
            });
            return;
        }
        IAsyncBankManager bankManager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync();
        UUID playerUUID = player.getUUID();
        bankManager.getBankAccountAsync(accountNumber).thenAccept(account -> {
            if (account == null) {
                runOnMainThread(() -> {
                    if (requestId != settingsRequestId)
                        return;
                    this.useBankItems = false;
                    this.depositOutputToBank = false;
                    this.craftingAccount = null;
                    this.bankDataSnapshot = null;
                    this.ghostRecipe = null; // ghost layout only exists in bank mode
                    this.pendingGhostRecipe = null;
                    updateCraftingResult();
                });
                return;
            }
            account.hasPermissionAsync(playerUUID, BankPermission.WITHDRAW).thenAccept(canWithdraw ->
                    account.hasPermissionAsync(playerUUID, BankPermission.DEPOSIT).thenAccept(canDeposit ->
                            account.getAccountDataAsync().thenAccept(accountData ->
                                    runOnMainThread(() -> {
                                        if (requestId != settingsRequestId)
                                            return;
                                        this.useBankItems = requestedUseBankItems && canWithdraw;
                                        this.depositOutputToBank = requestedDepositOutput && canDeposit;
                                        this.craftingAccount = account;
                                        this.craftingAccountNumber = accountNumber;
                                        this.bankDataSnapshot = accountData;
                                        if (!this.useBankItems) {
                                            this.ghostRecipe = null; // ghost layout only exists in bank mode
                                            this.pendingGhostRecipe = null;
                                        } else if (this.pendingGhostRecipe != null) {
                                            // Promote a ghost selection that arrived while this
                                            // validation was still in flight (see applyGhostRecipe).
                                            this.ghostRecipe = this.pendingGhostRecipe;
                                            this.pendingGhostRecipe = null;
                                        }
                                        updateCraftingResult();
                                    }))));
        });
    }

    /** Refreshes the bank-data snapshot used for recipe matching (server). */
    private void refreshBankSnapshot() {
        IAsyncBankAccount account = this.craftingAccount;
        if (account == null)
            return;
        final int requestId = settingsRequestId;
        account.getAccountDataAsync().thenAccept(accountData -> runOnMainThread(() -> {
            if (requestId != settingsRequestId)
                return;
            this.bankDataSnapshot = accountData;
            updateCraftingResult();
        }));
    }

    /**
     * Server-per-tick hook (vanilla invokes {@code broadcastChanges} once per
     * tick for the open menu): periodically refreshes the bank snapshot while
     * bank mode is active. This completes the recompute-input set — the match
     * already recomputes on grid changes, ghost changes and settings changes,
     * but the BANK CONTENTS can change at any time (deposits via the terminal's
     * own "send items to bank", other players, other blocks) and previously the
     * snapshot only refreshed at settings-apply and after crafts. A stale
     * snapshot made the server deny matches the client (live change stream)
     * correctly previewed — an intermittent "ghosts but no result". 1 s cadence
     * matches the freshness of comparable polling in this codebase
     * (BankDownloadScreen); skipped mid-craft — the craft flow refreshes on
     * completion/abort itself.
     */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (player.level().isClientSide || !useBankItems || craftingAccount == null || craftInProgress)
            return;
        if (++snapshotRefreshTickCounter >= SNAPSHOT_REFRESH_INTERVAL_TICKS) {
            snapshotRefreshTickCounter = 0;
            refreshBankSnapshot();
        }
    }

    // ------------------------------------------------------------------
    // Recipe matching
    // ------------------------------------------------------------------

    @Override
    public void slotsChanged(Container container) {
        super.slotsChanged(container);
        if (container == craftSlots)
            updateCraftingResult();
    }

    /**
     * Recomputes the crafting result (server only — the client receives the result
     * slot via the vanilla menu sync).
     */
    private void updateCraftingResult() {
        if (player.level().isClientSide || suppressRecipeUpdate)
            return;
        BankCraftingMatcher.Match match = BankCraftingMatcher.findMatch(
                player.level(),
                craftSlots.getItems(),
                useBankItems && craftingAccount != null,
                bankDataSnapshot,
                ghostRecipe);
        this.currentMatch = match;
        if (match == null) {
            resultSlots.setRecipeUsed(null);
            resultSlots.setItem(0, ItemStack.EMPTY);
        } else {
            resultSlots.setRecipeUsed(match.recipe());
            resultSlots.setItem(0, match.result().copy());
        }
    }

    // ------------------------------------------------------------------
    // Click handling
    // ------------------------------------------------------------------

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player clickPlayer) {
        // Explicit ghost-layout clear: clicking an EMPTY grid slot with an EMPTY
        // cursor while a ghost recipe is set removes the whole layout. Runs
        // symmetrically on both sides (all inputs — slot content, carried stack,
        // ghost state — are mirrored), so no extra packet is needed.
        if (ghostRecipe != null
                && clickType == ClickType.PICKUP
                && slotId >= CRAFT_GRID_SLOT_START && slotId < CRAFT_GRID_SLOT_START + CRAFT_GRID_SLOT_COUNT
                && getCarried().isEmpty()
                && !getSlot(slotId).hasItem()) {
            clearGhostRecipe();
            return;
        }
        if (slotId == CRAFT_RESULT_SLOT_INDEX && isCustomCraftPath()) {
            // Custom bank-craft path: never let vanilla pickup logic run. The
            // client does nothing (result/grid state comes back via menu sync);
            // the server runs the asynchronous atomic craft flow.
            if (!clickPlayer.level().isClientSide) {
                if (clickType == ClickType.PICKUP)
                    handleCustomCraft(false);
                else if (clickType == ClickType.QUICK_MOVE)
                    handleCustomCraft(true);
                // Other click types (PICKUP_ALL/THROW/SWAP/CLONE/QUICK_CRAFT) are
                // ignored in this mode — PICKUP_ALL in particular would turn a
                // double click into a surprise second craft.
            }
            return;
        }
        super.clicked(slotId, button, clickType, clickPlayer);
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        // Never let double-click "collect all" pull from the result slot.
        return slot.container != resultSlots && super.canTakeItemForPickAll(stack, slot);
    }

    @Override
    public ItemStack quickMoveStack(Player pPlayer, int pIndex) {
        if (pIndex == CRAFT_RESULT_SLOT_INDEX) {
            if (isCustomCraftPath())
                return ItemStack.EMPTY; // handled by clicked() interception
            // Vanilla crafting-table behavior (mirrors CraftingMenu.quickMoveStack):
            // craft repeatedly into the player inventory. The QUICK_MOVE loop in
            // AbstractContainerMenu keeps calling while the result refills.
            Slot slot = getSlot(pIndex);
            if (!slot.hasItem() || !slot.mayPickup(pPlayer))
                return ItemStack.EMPTY;
            ItemStack slotStack = slot.getItem();
            ItemStack original = slotStack.copy();
            slotStack.getItem().onCraftedBy(slotStack, pPlayer.level(), pPlayer);
            if (!moveItemStackTo(slotStack, PLAYER_SLOT_START, PLAYER_SLOT_END, true))
                return ItemStack.EMPTY;
            if (slot instanceof BankTerminalResultSlot resultSlot)
                resultSlot.onQuickCraft_public(slotStack, original);
            if (slotStack.isEmpty())
                slot.set(ItemStack.EMPTY);
            else
                slot.setChanged();
            if (slotStack.getCount() == original.getCount())
                return ItemStack.EMPTY;
            slot.onTake(pPlayer, slotStack);
            pPlayer.drop(slotStack, false);
            return original;
        }
        if (pIndex >= CRAFT_GRID_SLOT_START && pIndex < CRAFT_GRID_SLOT_START + CRAFT_GRID_SLOT_COUNT) {
            // Grid slot -> player inventory
            Slot fromSlot = getSlot(pIndex);
            if (!fromSlot.hasItem())
                return ItemStack.EMPTY;
            ItemStack fromStack = fromSlot.getItem();
            ItemStack copyFromStack = fromStack.copy();
            if (!moveItemStackTo(fromStack, PLAYER_SLOT_START, PLAYER_SLOT_END, false))
                return ItemStack.EMPTY;
            // DUPE GUARD: TransientCraftingContainer.setChanged() is a silent
            // NO-OP — only setItem/removeItem fire slotsChanged. moveItemStackTo
            // shrinks the source stack in place, so calling only setChanged()
            // here left the recipe match and the result slot STALE after
            // shift-clicking items out of the grid (clicking the stale result
            // then crafted for free). Mirror vanilla CraftingMenu's tail
            // (setByPlayer(EMPTY) -> setItem -> slotsChanged) and force one
            // recompute explicitly to also cover partial moves.
            if (fromStack.isEmpty())
                fromSlot.set(ItemStack.EMPTY);
            else
                fromSlot.setChanged();
            slotsChanged(craftSlots);
            fromSlot.onTake(pPlayer, fromStack);
            return copyFromStack;
        }
        return super.quickMoveStack(pPlayer, pIndex);
    }

    @Override
    public void removed(Player pPlayer) {
        super.removed(pPlayer);
        this.resultSlots.clearContent();
        if (!pPlayer.level().isClientSide) {
            // Vanilla crafting-table behavior: hand the grid contents back to the
            // player (never deposit them into the bank).
            // This also covers a craft still in its async lock phase when the menu
            // closes: clearing the grid makes the grid-revalidation in
            // applyCraftResults fail, which releases all acquired locks. Only a
            // hard server stop that drops the queued main-thread task can leave a
            // locked amount behind (see craftFailedUnexpectedly javadoc).
            this.clearContainer(pPlayer, this.craftSlots);
        }
    }

    // ------------------------------------------------------------------
    // Custom (bank-aware) craft flow — server only
    // ------------------------------------------------------------------

    /**
     * Executes one craft (or a shift-click batch) through the atomic bank flow:
     * <ol>
     *   <li>snapshot the grid and the current match on the main thread</li>
     *   <li>re-validate the WITHDRAW / DEPOSIT permission for the operations this
     *       craft actually performs — per-operation, like every other bank access
     *       in this mod (a permission revoked mid-session must take effect even if
     *       the client never sends another settings packet)</li>
     *   <li>lock every required bank amount sequentially; on any failure release
     *       all acquired locks and abort (grid untouched, player notified)</li>
     *   <li>back on the main thread: revalidate the grid against the snapshot
     *       (abort + unlock on mismatch), consume grid items + apply remainder
     *       items per craft, withdraw the locked amounts, deliver the output</li>
     * </ol>
     * Every asynchronous step is routed through {@link #craftStep} so an unexpected
     * exception can never leak acquired locks or leave {@link #craftInProgress} set.
     */
    private void handleCustomCraft(boolean craftAll) {
        if (player.level().isClientSide || craftInProgress)
            return;
        // STRUCTURAL STALENESS GUARD (dupe fix): never act on a cached match.
        // The match is recomputed synchronously HERE, on the server thread, from
        // the grid AS IT IS at click entry — the matcher is a pure function of
        // (grid, ghost recipe, bank snapshot) and guarantees that an empty grid
        // without a ghost recipe can never match. Even if some grid-mutation
        // path ever skips slotsChanged again (the original bug: our shift-click
        // branch relied on TransientCraftingContainer.setChanged(), which is a
        // no-op), a click on a stale result now just clears/refreshes the result
        // slot instead of crafting from thin air. The gridSnapshot below is then
        // taken against the exact same grid state the match was derived from.
        updateCraftingResult();
        final BankCraftingMatcher.Match match = currentMatch;
        if (match == null || match.result().isEmpty())
            return;
        // Capture the account reference once for the whole craft — a mid-craft
        // settings change must not split one craft's bank operations (permission
        // check, locks, withdrawals) across two different accounts.
        final IAsyncBankAccount account = this.craftingAccount;
        final boolean needsWithdraw = match.usesBankItems();
        final boolean needsDeposit = depositOutputToBank;
        if ((needsWithdraw || needsDeposit) && account == null)
            return;

        final int craftCount = craftAll ? computeMaxCrafts(match) : 1;
        if (craftCount <= 0)
            return;

        // Grid snapshot for revalidation after the async lock phase.
        final ItemStack[] gridSnapshot = new ItemStack[CRAFT_GRID_SLOT_COUNT];
        for (int i = 0; i < CRAFT_GRID_SLOT_COUNT; i++)
            gridSnapshot[i] = craftSlots.getItem(i).copy();

        // Raw bank amounts to lock/withdraw (whole items * fraction scale).
        final Map<ItemID, Long> rawNeeds = new HashMap<>();
        for (Map.Entry<ItemID, Integer> entry : match.bankCountsPerCraft().entrySet())
            rawNeeds.put(entry.getKey(), BankManager.convertToRawAmountStatic((long) entry.getValue() * craftCount));

        craftInProgress = true;
        if (!needsWithdraw && !needsDeposit) {
            // No bank interaction at all (grid physically complete, deposit off,
            // but "Use Bank Items" enabled) — deliver directly.
            guardedApplyCraftResults(match, craftCount, gridSnapshot, new ArrayList<>());
            return;
        }

        // Per-craft permission re-check BEFORE any lock is acquired. The settings
        // packet / menu-open validation alone is not enough: a revoked permission
        // must stop the next craft even against a client that never re-sends its
        // settings (same per-operation standard as sendItemsToBank / sendToBlock).
        final UUID playerUUID = player.getUUID();
        craftStep(account.hasPermissionAsync(playerUUID, BankPermission.WITHDRAW), List.of(), canWithdraw ->
                craftStep(account.hasPermissionAsync(playerUUID, BankPermission.DEPOSIT), List.of(), canDeposit -> {
                    if ((needsWithdraw && !canWithdraw) || (needsDeposit && !canDeposit)) {
                        onCraftPermissionRevoked(account, canWithdraw, canDeposit);
                        return;
                    }
                    if (rawNeeds.isEmpty())
                        runOnMainThread(() -> guardedApplyCraftResults(match, craftCount, gridSnapshot, new ArrayList<>()));
                    else
                        lockSequentially(account, new ArrayList<>(rawNeeds.entrySet()), 0, new ArrayList<>(), match, craftCount, gridSnapshot);
                }));
    }

    /**
     * A required permission was revoked since the last settings validation:
     * abort the craft before any lock, tell the player, and force the revoked
     * flag(s) off through the regular settings-validation path (which persists
     * the change and recomputes the result, clearing bank-assisted matches).
     */
    private void onCraftPermissionRevoked(IAsyncBankAccount account, boolean canWithdraw, boolean canDeposit) {
        final BankPermission missing = (useBankItems && !canWithdraw) ? BankPermission.WITHDRAW : BankPermission.DEPOSIT;
        account.getAccountNameAsync().thenAccept(accountName ->
                ServerPlayerUtilities.printToClientConsole(player.getUUID(),
                        BankSystemTextMessages.getNoBankPermissionMessage(accountName, missing)));
        runOnMainThread(() -> craftInProgress = false);
        applyCraftingSettings(useBankItems && canWithdraw, depositOutputToBank && canDeposit, craftingAccountNumber);
    }

    /**
     * Upper bound for a shift-click batch: limited by one output stack, by the
     * smallest physical grid stack in use, and by the snapshot bank balances.
     * The locks remain the authoritative check.
     */
    private int computeMaxCrafts(BankCraftingMatcher.Match match) {
        int maxCrafts = Math.max(1, match.result().getMaxStackSize() / Math.max(1, match.result().getCount()));
        for (int i = 0; i < CRAFT_GRID_SLOT_COUNT; i++) {
            if (match.virtualGrid()[i].isEmpty() || match.bankPerSlot()[i] != null)
                continue;
            ItemStack physical = craftSlots.getItem(i);
            if (physical.isEmpty())
                return 0; // grid changed since matching — recompute will follow
            maxCrafts = Math.min(maxCrafts, physical.getCount());
        }
        if (bankDataSnapshot != null) {
            for (Map.Entry<ItemID, Integer> entry : match.bankCountsPerCraft().entrySet()) {
                var bankData = bankDataSnapshot.bankData.get(entry.getKey());
                long available = bankData == null ? 0 : bankData.balance();
                long perCraftRaw = BankManager.convertToRawAmountStatic(entry.getValue());
                if (perCraftRaw <= 0)
                    return 0;
                maxCrafts = (int) Math.min(maxCrafts, available / perCraftRaw);
            }
        }
        return Math.max(maxCrafts, 0);
    }

    /**
     * Locks the required amounts one by one. All-or-nothing: the first failure
     * releases every already-acquired lock and aborts the craft (criterion: abort
     * all on any failure — the grid stays untouched because physical consumption
     * only happens after every lock succeeded).
     *
     * @param account the account captured at craft start (never re-read from the
     *                menu field — a mid-craft settings change must not split the
     *                locks across two accounts)
     */
    private void lockSequentially(IAsyncBankAccount account, List<Map.Entry<ItemID, Long>> needs, int index,
                                  List<Map.Entry<IAsyncBank, Long>> acquiredLocks,
                                  BankCraftingMatcher.Match match, int craftCount, ItemStack[] gridSnapshot) {
        if (index >= needs.size()) {
            runOnMainThread(() -> guardedApplyCraftResults(match, craftCount, gridSnapshot, acquiredLocks));
            return;
        }
        final ItemID itemID = needs.get(index).getKey();
        final long rawAmount = needs.get(index).getValue();
        craftStep(account.getBankAsync(itemID), acquiredLocks, bank -> {
            if (bank == null) {
                abortCraft(acquiredLocks, itemID);
                return;
            }
            craftStep(bank.lockAmountAsync(rawAmount), acquiredLocks, lockStatus -> {
                if (lockStatus != BankStatus.SUCCESS) {
                    abortCraft(acquiredLocks, itemID);
                    return;
                }
                acquiredLocks.add(Map.entry(bank, rawAmount));
                lockSequentially(account, needs, index + 1, acquiredLocks, match, craftCount, gridSnapshot);
            });
        });
    }

    /**
     * Exception guard for one asynchronous craft step: without it, a
     * RuntimeException inside a {@code thenAccept} body would be swallowed by the
     * CompletableFuture, permanently leaking the acquired locks (lockedBalance is
     * persisted!) and leaving {@link #craftInProgress} set (dead result slot).
     * Both an exceptionally completed future and an exception thrown by the step
     * body route to {@link #craftFailedUnexpectedly}.
     */
    private <T> void craftStep(CompletableFuture<T> future, List<Map.Entry<IAsyncBank, Long>> acquiredLocks,
                               java.util.function.Consumer<T> body) {
        future.whenComplete((value, throwable) -> {
            if (throwable != null) {
                craftFailedUnexpectedly(acquiredLocks, throwable);
                return;
            }
            try {
                body.accept(value);
            } catch (Throwable t) {
                craftFailedUnexpectedly(acquiredLocks, t);
            }
        });
    }

    /** Main-thread craft completion with the same exception guarantee as {@link #craftStep}. */
    private void guardedApplyCraftResults(BankCraftingMatcher.Match match, int craftCount,
                                          ItemStack[] gridSnapshot, List<Map.Entry<IAsyncBank, Long>> acquiredLocks) {
        try {
            applyCraftResults(match, craftCount, gridSnapshot, acquiredLocks);
        } catch (Throwable t) {
            craftFailedUnexpectedly(acquiredLocks, t);
        }
    }

    /**
     * Last-resort failure handler: releases every acquired lock, clears
     * {@link #craftInProgress} and recomputes the result so the menu stays usable.
     * <p>
     * Residual (documented): if the failure was an exceptionally completed
     * {@code lockAmountAsync} (e.g. a multi-server request that died mid-flight),
     * the state of that one in-flight lock is unknown and it is deliberately not
     * unlocked here — releasing a lock that was never acquired could free another
     * mod's locked funds. Likewise, a server shutdown can drop a queued
     * main-thread task; the grid-revalidation in {@code applyCraftResults} plus
     * {@link #removed(Player)} clearing the grid cover the reachable cases, but a
     * hard stop mid-craft can leave a locked amount behind (admin: unlock via
     * balance edit).
     */
    private void craftFailedUnexpectedly(List<Map.Entry<IAsyncBank, Long>> acquiredLocks, Throwable throwable) {
        error("Unexpected error during bank-assisted craft for player " + player.getUUID()
                + " on account " + craftingAccountNumber + " — releasing " + acquiredLocks.size()
                + " acquired lock(s) and aborting the craft", throwable);
        for (Map.Entry<IAsyncBank, Long> lock : acquiredLocks)
            lock.getKey().unlockAmountAsync(lock.getValue());
        runOnMainThread(() -> {
            craftInProgress = false;
            updateCraftingResult();
        });
    }

    /** Releases all acquired locks and notifies the player which item was missing. */
    private void abortCraft(List<Map.Entry<IAsyncBank, Long>> acquiredLocks, @Nullable ItemID missingItem) {
        for (Map.Entry<IAsyncBank, Long> lock : acquiredLocks)
            lock.getKey().unlockAmountAsync(lock.getValue());
        if (missingItem != null) {
            ServerPlayerUtilities.printToClientConsole(player.getUUID(),
                    BankSystemTextMessages.getCraftingMissingBankItemsMessage(missingItem.getName()));
        }
        runOnMainThread(() -> {
            craftInProgress = false;
            refreshBankSnapshot();
            updateCraftingResult();
        });
    }

    /**
     * Main-thread completion of the craft: revalidate, consume, withdraw, deliver.
     * Runs after all bank locks succeeded (or immediately for pure-physical crafts).
     */
    private void applyCraftResults(BankCraftingMatcher.Match match, int craftCount,
                                   ItemStack[] gridSnapshot, List<Map.Entry<IAsyncBank, Long>> acquiredLocks) {
        // Liveness check: abort when this menu is no longer the player's open
        // container (closed / logged out during the async lock phase on a
        // multi-server round trip). This replaces the grid-mismatch tripwire
        // below for the all-ghost case: with a fully bank-sourced craft the grid
        // snapshot is 9 empties, so removed() clearing an already-empty grid
        // passes ItemStack.matches vacuously — without this check the craft
        // would complete against a dead menu (remainders written into discarded
        // craftSlots; on logout, output delivered into a detached, already-saved
        // inventory → lost while the bank is debited). For the plain path this
        // check is harmless: it is strictly earlier/stronger than the mismatch
        // check, and on a single server the whole flow completes synchronously
        // inside clicked(), where the menu is trivially still open.
        if (player.containerMenu != this
                || (player instanceof ServerPlayer serverPlayer && serverPlayer.hasDisconnected())) {
            List<Map.Entry<IAsyncBank, Long>> locksToRelease = new ArrayList<>(acquiredLocks);
            acquiredLocks.clear();
            for (Map.Entry<IAsyncBank, Long> lock : locksToRelease)
                lock.getKey().unlockAmountAsync(lock.getValue());
            craftInProgress = false;
            return;
        }

        // Revalidate: the grid must be unchanged since the click (async lock phase
        // may have taken time on a multi-server setup).
        for (int i = 0; i < CRAFT_GRID_SLOT_COUNT; i++) {
            if (!ItemStack.matches(craftSlots.getItem(i), gridSnapshot[i])) {
                // Drain before releasing: the last-resort handler shares this list,
                // so clearing it first makes a re-release on a later exception
                // (e.g. updateCraftingResult below) a no-op instead of a double unlock.
                List<Map.Entry<IAsyncBank, Long>> locksToRelease = new ArrayList<>(acquiredLocks);
                acquiredLocks.clear();
                for (Map.Entry<IAsyncBank, Long> lock : locksToRelease)
                    lock.getKey().unlockAmountAsync(lock.getValue());
                craftInProgress = false;
                updateCraftingResult();
                return;
            }
        }

        // Consume grid items + apply remainder items, one craft at a time
        // (mirrors vanilla ResultSlot.onTake remainder semantics).
        CraftingInput.Positioned positioned = CraftingInput.ofPositioned(
                BankCraftingMatcher.GRID_WIDTH, BankCraftingMatcher.GRID_HEIGHT, List.of(match.virtualGrid()));
        CraftingInput virtualInput = positioned.input();
        int left = positioned.left();
        int top = positioned.top();
        NonNullList<ItemStack> remainders = player.level().getRecipeManager()
                .getRemainingItemsFor(RecipeType.CRAFTING, virtualInput, player.level());

        suppressRecipeUpdate = true;
        try {
            for (int craft = 0; craft < craftCount; craft++) {
                for (int row = 0; row < virtualInput.height(); row++) {
                    for (int col = 0; col < virtualInput.width(); col++) {
                        int gridIndex = (top + row) * BankCraftingMatcher.GRID_WIDTH + (left + col);
                        if (match.virtualGrid()[gridIndex].isEmpty())
                            continue;
                        boolean bankSourced = match.bankPerSlot()[gridIndex] != null;
                        if (!bankSourced && !craftSlots.getItem(gridIndex).isEmpty())
                            craftSlots.removeItem(gridIndex, 1);
                        ItemStack remainder = remainders.get(row * virtualInput.width() + col).copy();
                        if (remainder.isEmpty())
                            continue;
                        ItemStack inSlot = craftSlots.getItem(gridIndex);
                        if (inSlot.isEmpty())
                            craftSlots.setItem(gridIndex, remainder);
                        else if (ItemStack.isSameItemSameComponents(inSlot, remainder)
                                && inSlot.getCount() + remainder.getCount() <= inSlot.getMaxStackSize())
                            inSlot.grow(remainder.getCount());
                        else
                            player.getInventory().placeItemBackInInventory(remainder);
                    }
                }
            }
        } finally {
            suppressRecipeUpdate = false;
        }

        // Withdraw the locked bank amounts. After a successful lock this can only
        // fail through the documented weak-lock escape hatch: an admin setBalance
        // between lock and withdraw (a real window on a multi-server round trip)
        // can shrink or consume the locked amount.
        // Drain before withdrawing: these locks are consumed now — clearing the
        // shared list first keeps the last-resort handler from "releasing" already
        // withdrawn locks if a later step (delivery/stats) throws.
        List<Map.Entry<IAsyncBank, Long>> locksToWithdraw = new ArrayList<>(acquiredLocks);
        acquiredLocks.clear();
        for (Map.Entry<IAsyncBank, Long> lock : locksToWithdraw) {
            final IAsyncBank bank = lock.getKey();
            final long rawAmount = lock.getValue();
            bank.withdrawLockedPreferedAsync(rawAmount).whenComplete((status, throwable) -> {
                if (throwable == null && status == BankStatus.SUCCESS)
                    return;
                // Un-debited craft: the output has already been delivered and may
                // already be in use, so there is nothing safe to roll back here
                // (unlike sendToBlock, which can simply remove the inserted stack).
                // Log at ERROR with full context so admins can reconcile.
                String itemName = bank.getItemIDAsync().getName();
                String cause = throwable != null ? ("exception: " + throwable) : String.valueOf(status);
                error("Un-debited craft: failed to withdraw locked crafting ingredients (item " + itemName
                        + ", raw amount " + rawAmount + ") from bank account " + craftingAccountNumber
                        + " for player " + player.getUUID() + " (" + player.getName().getString() + ") : " + cause
                        + " — the crafted output was already delivered; the bank was not debited.");
                if (throwable != null)
                    return; // request died mid-flight: lock state unknown, leave it untouched
                // Release only what is verifiably still locked, never more than our
                // own contribution. Residual (documented): the lock ledger is a
                // single counter without per-owner tracking, so if the admin edit
                // already consumed part of our lock, this min() may free amounts
                // another mod locked — inherent to the ledger design (the same
                // limitation exists in BankTerminalBlockEntity.sendToBlock's
                // rollback path).
                bank.getLockedBalanceAsync().thenAccept(lockedBalance -> {
                    long toUnlock = Math.min(rawAmount, Math.max(0L, lockedBalance));
                    if (toUnlock > 0)
                        bank.unlockAmountAsync(toUnlock);
                });
            });
        }

        // Deliver the output.
        ItemStack output = match.result().copy();
        output.setCount(match.result().getCount() * craftCount);
        output.getItem().onCraftedBy(output, player.level(), player);
        if (depositOutputToBank)
            depositCraftedOutput(output);
        else
            // Known & accepted: with a full inventory the overflow of a shift-click
            // batch drops at the player's feet instead of stopping the batch early.
            player.getInventory().placeItemBackInInventory(output);

        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.awardStat(net.minecraft.stats.Stats.ITEM_CRAFTED.get(match.result().getItem()), match.result().getCount() * craftCount);
            // Recipe-book unlock / recipe advancement parity with the vanilla path
            // (ResultSlot.checkTakeAchievements does this via the result container).
            resultSlots.awardUsedRecipes(serverPlayer, List.of(output));
        }

        craftInProgress = false;
        updateCraftingResult();
        refreshBankSnapshot();
    }

    /**
     * Deposits the crafted output into the crafting bank account. Any failure
     * (non-bankable item, refused deposit, state-gated components, missing account)
     * falls back to the player inventory with a log warning — the output is never
     * blocked and never silently dropped (user decision 2026-07-16).
     */
    private void depositCraftedOutput(ItemStack output) {
        IAsyncBankAccount account = this.craftingAccount;
        if (account == null) {
            deliverDepositFallback(output, "no bank account selected");
            return;
        }
        ItemID.getOrRegisterFromItemStackServerSide(output).thenAccept(itemID -> {
            if (itemID == null || !itemID.isValid()) {
                deliverDepositFallback(output, "item has no bankable ItemID");
                return;
            }
            // Deposit gate: identical protection as the terminal's "send items to
            // bank" — state-gated components (e.g. dated food) must match what the
            // bank would hand back, otherwise crediting would launder item state.
            if (!VolatileItemComponents.isDepositEquivalent(output, itemID)) {
                deliverDepositFallback(output, "item state does not match the bankable template");
                return;
            }
            final boolean isMoney = MoneyItem.isMoney(itemID);
            CompletableFuture<@Nullable IAsyncBank> bankFuture;
            if (isMoney) {
                // Same denomination-agnostic money targeting as
                // BankTerminalBlockEntity.sendItemsToBank: reuse the account's
                // existing money bank when present.
                bankFuture = account.getAllBanksAsync().thenCompose(banks -> {
                    for (ItemID bankItemID : banks.keySet()) {
                        if (MoneyItem.isMoney(bankItemID))
                            return account.getOrCreateBankAsync(bankItemID);
                    }
                    return account.getOrCreateBankAsync(MoneyItem.getItemID());
                });
            } else {
                bankFuture = account.getOrCreateBankAsync(itemID);
            }
            bankFuture.thenAccept(bank -> {
                if (bank == null) {
                    ServerPlayerUtilities.printToClientConsole(player.getUUID(),
                            BankSystemTextMessages.getItemNotAllowedMessage(itemID.getName()));
                    deliverDepositFallback(output, "item is not allowed for banking");
                    return;
                }
                long amountToDeposit;
                if (isMoney)
                    amountToDeposit = output.getCount() * ((MoneyItem) output.getItem()).worth();
                else
                    amountToDeposit = BankManager.convertToRawAmountStatic(output.getCount());
                bank.depositAsync(amountToDeposit).thenAccept(status -> {
                    if (status != BankStatus.SUCCESS)
                        deliverDepositFallback(output, "deposit failed: " + status);
                });
            });
        });
    }

    /**
     * Fallback delivery when auto-deposit cannot bank the crafted output: hand it
     * to the player inventory (main thread, so the menu sync sees it) and log a
     * warning — never block the craft, never silently drop the item.
     */
    private void deliverDepositFallback(ItemStack output, String reason) {
        warn("Auto-deposit of crafted output " + output + " for player " + player.getUUID()
                + " fell back to the player inventory: " + reason);
        ServerPlayerUtilities.printToClientConsole(player.getUUID(),
                BankSystemTextMessages.getCraftingDepositFallbackMessage(output.getHoverName().getString()));
        runOnMainThread(() -> player.getInventory().placeItemBackInInventory(output));
    }

    /**
     * Marshals container-touching work onto the main server thread. Async bank-op
     * callbacks can run off-thread on a dedicated (or multi-)server; only
     * main-thread mutations are observed by {@code broadcastChanges()} and synced
     * to the client (same pattern as {@link BankTerminalBlockEntity}).
     */
    private void runOnMainThread(Runnable action) {
        if (player instanceof ServerPlayer serverPlayer && serverPlayer.getServer() != null)
            serverPlayer.getServer().execute(action);
        else
            action.run();
    }

    private static void error(String msg) {
        BACKEND_INSTANCES.LOGGER.error("[BankTerminalContainerMenu] " + msg);
    }
    private static void error(String msg, Throwable e) {
        BACKEND_INSTANCES.LOGGER.error("[BankTerminalContainerMenu] " + msg, e);
    }
    private static void warn(String msg) {
        BACKEND_INSTANCES.LOGGER.warn("[BankTerminalContainerMenu] " + msg);
    }
}
