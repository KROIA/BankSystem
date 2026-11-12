package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.IAsyncBank;
import net.kroia.banksystem.api.bankaccount.IAsyncBankAccount;
import net.kroia.banksystem.api.bankmanager.IAsyncBankManager;
import net.kroia.banksystem.api.bankmanager.IBankManager;
import net.kroia.banksystem.banking.binding.BankAccountBindings;
import net.kroia.banksystem.minecraft.entity.custom.BankDownloadBlockEntity;
import net.kroia.banksystem.util.BankSystemLogger;
import net.kroia.banksystem.data.TransferJournal;
import net.kroia.banksystem.minecraft.block.BankSystemBlocks;
import net.kroia.banksystem.networking.multi_server.TransferStatusRequest;
import net.kroia.banksystem.minecraft.entity.custom.BankTerminalBlockEntity;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.ContainerItemInsertion;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.banksystem.util.VolatileItemComponents;
import net.kroia.modutilities.networking.client_server.arrs.RequestOutcomeUnknownException;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * In-game tests for component-aware physical stack placement when bank credits materialize
 * into a container ({@link ContainerItemInsertion}, used by the bank terminal block
 * inventory and the bank download block).
 * <p>
 * The bug these tests guard against: ItemID identity deliberately ignores volatile and
 * deposit-gated components, so a "spoiled" and a "fresh" stack share one ItemID. Any
 * insertion that picks its merge slot by ItemID equality merges withdrawn (fresh) items
 * onto the spoiled stack, silently rewriting their state. Physical merging must instead use
 * the game's own {@code ItemStack.isSameItemSameComponents} (which mods like TFC hook so
 * rotten never equals fresh).
 * <p>
 * Like {@link DepositGateTests}, these tests use {@code minecraft:custom_data} as a
 * stand-in for a third-party gated component by temporarily adding it to the config-sourced
 * gated set (making it invisible to identity, exactly like {@code tfc:food}); both config
 * sets are restored in {@code teardown()}.
 */
public class WithdrawMergeTests extends TestSuite {

    /** Config-sourced volatile ids captured in setup() and restored in teardown(). */
    private List<String> savedVolatileConfigIds = null;
    /** Config-sourced deposit-gated ids captured in setup() and restored in teardown(). */
    private List<String> savedGatedConfigIds = null;

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.ITEM_ID;
    }

    @Override
    public void registerTests() {
        addTest("withdrawn_items_do_not_merge_into_mismatched_stack", this::testNoMergeIntoMismatchedStack);
        addTest("withdrawn_items_merge_into_component_equal_stack", this::testMergeIntoComponentEqualStack);
        addTest("merge_preferred_over_empty_slot", this::testMergePreferredOverEmptySlot);
        addTest("full_container_inserts_nothing_and_keeps_stacks", this::testFullContainerNoSilentLoss);
        addTest("terminal_inventory_keeps_mismatched_stack_untouched", this::testTerminalInventoryKeepsMismatchedStack);
        addTest("failed_debit_rollback_removes_inserted_items", this::testRollbackRemovesInsertedItems);
        addTest("terminal_deposit_reserves_before_send", this::testTerminalDepositReservesBeforeSend); // Task #145
        addTest("download_rollback_unlocks_only_returned_part", this::testDownloadRollbackWithEscape); // BS-201
        addTest("terminal_rollback_unlocks_only_returned_part", this::testTerminalRollbackWithEscape); // BS-201
    }

    @Override
    public void setup() {
        savedVolatileConfigIds = VolatileItemComponents.getConfigComponentIdStrings();
        savedGatedConfigIds = VolatileItemComponents.getGatedConfigComponentIdStrings();
    }

    @Override
    public void teardown() {
        // Restore the server's real config-sourced component sets. Both sets affect
        // ItemID identity, so a change requires re-normalizing the registry.
        boolean changed = false;
        if (savedVolatileConfigIds != null)
            changed = VolatileItemComponents.setConfigComponentIds(savedVolatileConfigIds);
        if (savedGatedConfigIds != null)
            changed |= VolatileItemComponents.setGatedConfigComponentIds(savedGatedConfigIds);
        if (changed)
            ItemIDManager.renormalizeAndMerge();
        savedVolatileConfigIds = null;
        savedGatedConfigIds = null;
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /** Creates a paper stack carrying the given string inside minecraft:custom_data. */
    private static ItemStack paperWithCustomData(String value, int count) {
        ItemStack stack = new ItemStack(Items.PAPER, count);
        CompoundTag nbt = new CompoundTag();
        nbt.putString("banksystem_withdraw_merge_test", value);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(nbt));
        return stack;
    }

    /**
     * Makes custom_data a deposit-gated component (invisible to identity, like tfc:food)
     * and resolves the plain-paper ItemID both the "fresh" and "spoiled" variants share.
     */
    private ItemID gateCustomDataAndGetPaperId() {
        VolatileItemComponents.setConfigComponentIds(List.of());
        VolatileItemComponents.setGatedConfigComponentIds(List.of("minecraft:custom_data"));
        return ItemIDManager.getItemID(new ItemStack(Items.PAPER));
    }

    // ========================================================================
    // Tests
    // ========================================================================

    /**
     * Acceptance criterion 1: withdrawing into a container holding a component-mismatched
     * stack of the same ItemID must use a separate slot and leave the existing stack
     * untouched — never merge onto it.
     */
    private TestResult testNoMergeIntoMismatchedStack() {
        ItemID paperId = gateCustomDataAndGetPaperId();
        if (!paperId.isValid())
            return fail("Plain paper is not registered — default ItemIDs missing on this server?");

        // Premise of the bug: the "spoiled" variant resolves to the same ItemID.
        TestResult r = assertEquals("spoiled variant shares the plain paper ItemID",
                paperId, ItemIDManager.getItemID(paperWithCustomData("spoiled", 1)));
        if (!r.passed()) return r;

        SimpleContainer container = new SimpleContainer(9);
        container.setItem(0, paperWithCustomData("spoiled", 10));

        long inserted = ContainerItemInsertion.insertWithdrawnItems(container, paperId, 10);
        r = assertEquals("all 10 items inserted", 10L, inserted);
        if (!r.passed()) return r;

        ItemStack slot0 = container.getItem(0);
        r = assertEquals("mismatched stack count untouched", 10, slot0.getCount());
        if (!r.passed()) return r;
        r = assertTrue("mismatched stack kept its gated component",
                slot0.has(DataComponents.CUSTOM_DATA));
        if (!r.passed()) return r;

        ItemStack slot1 = container.getItem(1);
        r = assertEquals("withdrawn items placed in a separate slot", 10, slot1.getCount());
        if (!r.passed()) return r;
        r = assertFalse("withdrawn items did not inherit the mismatched component",
                slot1.has(DataComponents.CUSTOM_DATA));
        if (!r.passed()) return r;
        return pass("withdrawn items never merge onto a component-mismatched stack");
    }

    /**
     * Issue #74: when the bank debit behind a download-block insert fails, the inserted items
     * are taken back out. The container must end exactly as before the insert, and a
     * component-mismatched stack with the same ItemID must stay untouched.
     */
    private TestResult testRollbackRemovesInsertedItems() {
        ItemID paperId = gateCustomDataAndGetPaperId();
        if (!paperId.isValid())
            return fail("Plain paper is not registered — default ItemIDs missing on this server?");

        SimpleContainer container = new SimpleContainer(9);
        container.setItem(0, paperWithCustomData("spoiled", 10));
        container.setItem(1, new ItemStack(Items.PAPER, 60)); // insert tops this up, then spills

        ItemStack withdrawalStack = paperId.getStack();
        long inserted = ContainerItemInsertion.insertWithdrawnItems(container, withdrawalStack, 20);
        TestResult r = assertEquals("all 20 items inserted", 20L, inserted);
        if (!r.passed()) return r;

        long removed = ContainerItemInsertion.removeItems(container, withdrawalStack, inserted);
        r = assertEquals("all 20 items removed again", 20L, removed);
        if (!r.passed()) return r;
        r = assertEquals("mismatched stack untouched", 10, container.getItem(0).getCount());
        if (!r.passed()) return r;
        r = assertTrue("mismatched stack kept its gated component", container.getItem(0).has(DataComponents.CUSTOM_DATA));
        if (!r.passed()) return r;
        r = assertEquals("pre-existing equal stack back to 60", 60, container.getItem(1).getCount());
        if (!r.passed()) return r;
        for (int i = 2; i < container.getContainerSize(); i++) {
            r = assertTrue("slot " + i + " empty again", container.getItem(i).isEmpty());
            if (!r.passed()) return r;
        }
        return pass("rollback restores the container exactly");
    }

    /** Component-equal stacks must still merge (normal stacking is unchanged). */
    private TestResult testMergeIntoComponentEqualStack() {
        ItemID paperId = ItemIDManager.getItemID(new ItemStack(Items.PAPER));
        if (!paperId.isValid())
            return fail("Plain paper is not registered — default ItemIDs missing on this server?");

        SimpleContainer container = new SimpleContainer(9);
        container.setItem(0, new ItemStack(Items.PAPER, 5));

        long inserted = ContainerItemInsertion.insertWithdrawnItems(container, paperId, 10);
        TestResult r = assertEquals("all 10 items inserted", 10L, inserted);
        if (!r.passed()) return r;
        r = assertEquals("items merged into the component-equal stack", 15, container.getItem(0).getCount());
        if (!r.passed()) return r;
        r = assertTrue("no second slot used", container.getItem(1).isEmpty());
        if (!r.passed()) return r;
        return pass("component-equal stacks merge as before");
    }

    /** Vanilla semantics: top up existing component-equal stacks before opening empty slots. */
    private TestResult testMergePreferredOverEmptySlot() {
        ItemID paperId = ItemIDManager.getItemID(new ItemStack(Items.PAPER));
        if (!paperId.isValid())
            return fail("Plain paper is not registered — default ItemIDs missing on this server?");

        SimpleContainer container = new SimpleContainer(9);
        // Slot 0 empty, slot 1 partially filled (max stack size of paper is 64).
        container.setItem(1, new ItemStack(Items.PAPER, 60));

        long inserted = ContainerItemInsertion.insertWithdrawnItems(container, paperId, 10);
        TestResult r = assertEquals("all 10 items inserted", 10L, inserted);
        if (!r.passed()) return r;
        r = assertEquals("existing stack topped up first", 64, container.getItem(1).getCount());
        if (!r.passed()) return r;
        r = assertEquals("remainder placed into the empty slot", 6, container.getItem(0).getCount());
        if (!r.passed()) return r;
        return pass("merging is preferred over opening a new slot");
    }

    /**
     * Acceptance criterion 3: when only component-mismatched stacks occupy the container and
     * no slot is free, nothing is inserted (return 0) and every stack stays untouched — the
     * un-inserted remainder is reported to the caller, which keeps it in the bank.
     */
    private TestResult testFullContainerNoSilentLoss() {
        ItemID paperId = gateCustomDataAndGetPaperId();
        if (!paperId.isValid())
            return fail("Plain paper is not registered — default ItemIDs missing on this server?");

        SimpleContainer container = new SimpleContainer(3);
        for (int i = 0; i < 3; i++)
            container.setItem(i, paperWithCustomData("spoiled", 64));

        long inserted = ContainerItemInsertion.insertWithdrawnItems(container, paperId, 10);
        TestResult r = assertEquals("nothing inserted into the full container", 0L, inserted);
        if (!r.passed()) return r;
        for (int i = 0; i < 3; i++) {
            ItemStack slot = container.getItem(i);
            r = assertEquals("slot " + i + " count untouched", 64, slot.getCount());
            if (!r.passed()) return r;
            r = assertTrue("slot " + i + " kept its gated component",
                    slot.has(DataComponents.CUSTOM_DATA));
            if (!r.passed()) return r;
        }
        return pass("full container: nothing merged, nothing voided, remainder reported");
    }

    /**
     * Same guarantee through the real bank terminal inventory (the container the user's
     * repro used): withdrawn items land in a separate slot, the spoiled stack is untouched.
     */
    private TestResult testTerminalInventoryKeepsMismatchedStack() {
        ItemID paperId = gateCustomDataAndGetPaperId();
        if (!paperId.isValid())
            return fail("Plain paper is not registered — default ItemIDs missing on this server?");

        // Detached block entity (no level): TerminalInventory only needs it for setChanged(),
        // which is a no-op without a level.
        BankTerminalBlockEntity blockEntity = new BankTerminalBlockEntity(
                BlockPos.ZERO, BankSystemBlocks.BANK_TERMINAL_BLOCK.get().defaultBlockState());
        BankTerminalBlockEntity.TerminalInventory inventory =
                new BankTerminalBlockEntity.TerminalInventory(blockEntity, 9);
        inventory.setStackInSlot(0, paperWithCustomData("spoiled", 10));

        long added = inventory.addItem(paperId, 10);
        TestResult r = assertEquals("all 10 items added", 10L, added);
        if (!r.passed()) return r;

        ItemStack slot0 = inventory.getItem(0);
        r = assertEquals("spoiled stack count untouched", 10, slot0.getCount());
        if (!r.passed()) return r;
        r = assertTrue("spoiled stack kept its gated component", slot0.has(DataComponents.CUSTOM_DATA));
        if (!r.passed()) return r;

        ItemStack slot1 = inventory.getItem(1);
        r = assertEquals("withdrawn items placed in a separate slot", 10, slot1.getCount());
        if (!r.passed()) return r;
        r = assertFalse("withdrawn items are clean", slot1.has(DataComponents.CUSTOM_DATA));
        if (!r.passed()) return r;
        return pass("terminal inventory withdrawal keeps component-mismatched stacks intact");
    }

    // ------------------------------------------------------------------ BS-201: rollback with escaped items

    private static final int FILL = 10;
    private static final int ESCAPED = 3;

    /**
     * A bank with plenty of balance whose locked withdraw fails. {@code beforeFail} runs inside the withdraw call,
     * i.e. after the items were inserted and before the rollback: it stands in for a hopper / the player taking
     * part of them out. Every unlock amount is recorded.
     */
    private static IAsyncBank failingWithdrawBank(Runnable beforeFail, List<Long> unlocked) {
        return (IAsyncBank) Proxy.newProxyInstance(IAsyncBank.class.getClassLoader(), new Class<?>[]{IAsyncBank.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "getBalanceAsync" -> CompletableFuture.completedFuture(1_000_000L);
                    case "lockAmountAsync" -> CompletableFuture.completedFuture(BankStatus.SUCCESS);
                    case "withdrawLockedPreferedAsync" -> {
                        beforeFail.run();
                        yield CompletableFuture.completedFuture(BankStatus.FAILED_NOT_ENOUGH_FUNDS);
                    }
                    case "unlockAmountAsync" -> {
                        unlocked.add((Long) args[0]);
                        yield CompletableFuture.completedFuture(BankStatus.SUCCESS);
                    }
                    case "toString" -> "FailingWithdrawBank";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    /** Proxy of {@code iface} that answers {@code override} where it returns non-null, else forwards to {@code target}. */
    @SuppressWarnings("unchecked")
    private static <T> T delegating(Class<T> iface, T target, java.util.function.BiFunction<Method, Object[], Object> override) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, (proxy, m, args) -> {
            Object answer = override.apply(m, args);
            if (answer != null)
                return answer;
            try {
                return m.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    /** Runs {@code body} with error lines captured; the real logger still gets them. */
    private static void withErrorCapture(List<String> errors, Runnable body) {
        BankSystemModBackend.Instances instances = BankSystemModBackend.getInstances_forTesting();
        BankSystemLogger original = instances.LOGGER;
        instances.LOGGER = new BankSystemLogger(instances) {
            @Override
            public void error(String message) {
                errors.add(message);
                original.error(message);
            }
        };
        try {
            body.run();
        } finally {
            instances.LOGGER = original;
        }
    }

    /**
     * BS-201, download block: the debit fails after a hopper already pulled {@link #ESCAPED} of the {@link #FILL}
     * inserted items. Only the part taken back is unlocked; the escaped part stays locked and is logged.
     */
    private TestResult testDownloadRollbackWithEscape() {
        ItemID paperId = ItemIDManager.getItemID(new ItemStack(Items.PAPER));
        if (!paperId.isValid())
            return fail("Plain paper is not registered — default ItemIDs missing on this server?");
        // Detached block entity: its own 27-slot inventory is the container a hopper below would pull from.
        BankDownloadBlockEntity block = new BankDownloadBlockEntity(BlockPos.ZERO,
                BankSystemBlocks.BANK_DOWNLOAD_BLOCK.get().defaultBlockState());
        long ratio = BankAccountBindings.getRawUnitsPerItem(0, paperId); // the block's account is 0 (unset)
        List<Long> unlocked = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int[] pulled = {0};
        IAsyncBank bank = failingWithdrawBank(() -> pulled[0] = block.removeItem(0, ESCAPED).getCount(), unlocked);
        Object[] filled = {null};
        withErrorCapture(errors, () -> {
            try {
                Method fill = BankDownloadBlockEntity.class.getDeclaredMethod("withdrawAndFillInventory",
                        ItemID.class, int.class, IAsyncBank.class);
                fill.setAccessible(true);
                filled[0] = ((CompletableFuture<?>) fill.invoke(block, paperId, FILL, bank)).get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                filled[0] = e;
            }
        });
        TestResult r = assertEquals("hopper stand-in pulled " + ESCAPED + " items", ESCAPED, pulled[0]);
        if (!r.passed()) return r;
        r = assertEquals("failed withdraw reports 0 filled", 0, filled[0]);
        if (!r.passed()) return r;
        r = assertTrue("block empty again (the rest was taken back)", block.isEmpty());
        if (!r.passed()) return r;
        r = assertEquals("only the returned part is unlocked", List.of((long) (FILL - ESCAPED) * ratio), unlocked);
        if (!r.passed()) return r;
        return assertTrue("escaped part logged as left locked, got " + errors, errors.stream().anyMatch(e ->
                e.contains("only " + (FILL - ESCAPED) + " item(s)") && e.contains(ESCAPED * ratio + " (raw) left locked")));
    }

    /**
     * BS-201, bank terminal: same escape case through the real {@code sendToBlock} path; the player takes
     * {@link #ESCAPED} items out of their terminal inventory before the rollback. The bank manager is swapped for a
     * delegating proxy that hands out a fake account (only its account number is overridden) for the call.
     */
    private TestResult testTerminalRollbackWithEscape() {
        ItemID paperId = ItemIDManager.getItemID(new ItemStack(Items.PAPER));
        if (!paperId.isValid())
            return fail("Plain paper is not registered — default ItemIDs missing on this server?");
        BankSystemModBackend.Instances instances = BankSystemModBackend.getInstances_forTesting();
        IBankManager realManager = instances.SERVER_BANK_MANAGER;
        if (realManager == null)
            return skip("no bank manager on this side");
        // Detached block entity (no level): runOnMainThread runs inline, so the whole flow is synchronous.
        BankTerminalBlockEntity terminal = new BankTerminalBlockEntity(
                BlockPos.ZERO, BankSystemBlocks.BANK_TERMINAL_BLOCK.get().defaultBlockState());
        UUID player = UUID.randomUUID();
        BankTerminalBlockEntity.TerminalInventory inventory = terminal.getInventory(player);
        final int accountNr = Integer.MAX_VALUE - 201; // never a real account
        long ratio = BankAccountBindings.getRawUnitsPerItem(accountNr, paperId);
        List<Long> unlocked = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        long[] taken = {0};
        IAsyncBank bank = failingWithdrawBank(() -> taken[0] = inventory.removeItem(paperId, ESCAPED), unlocked);
        IAsyncBankAccount account = (IAsyncBankAccount) Proxy.newProxyInstance(IAsyncBankAccount.class.getClassLoader(),
                new Class<?>[]{IAsyncBankAccount.class}, (proxy, m, args) -> switch (m.getName()) {
                    case "hasPermissionAsync" -> CompletableFuture.completedFuture(true);
                    case "getAccountNumberAsync" -> accountNr;
                    case "getOrCreateBankAsync" -> CompletableFuture.completedFuture(bank);
                    case "toString" -> "FakeAccount";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        IAsyncBankManager asyncManager = delegating(IAsyncBankManager.class, realManager.getAsync(), (m, args) ->
                m.getName().equals("getBankAccountAsync") && args != null && Integer.valueOf(accountNr).equals(args[0])
                        ? CompletableFuture.completedFuture(account) : null);
        Exception[] failure = {null};
        withErrorCapture(errors, () -> {
            instances.SERVER_BANK_MANAGER = delegating(IBankManager.class, realManager,
                    (m, args) -> m.getName().equals("getAsync") ? asyncManager : null);
            try {
                Method send = BankTerminalBlockEntity.class.getDeclaredMethod("sendToBlock", UUID.class, int.class, HashMap.class);
                send.setAccessible(true);
                send.invoke(terminal, player, accountNr, new HashMap<>(Map.of(paperId, (long) FILL)));
            } catch (Exception e) {
                failure[0] = e;
            } finally {
                instances.SERVER_BANK_MANAGER = realManager;
            }
        });
        if (failure[0] != null)
            return fail("sendToBlock threw " + failure[0]);
        TestResult r = assertEquals("player took " + ESCAPED + " items out", (long) ESCAPED, taken[0]);
        if (!r.passed()) return r;
        r = assertEquals("rest taken back out of the terminal", 0L, inventory.getItemCount_direct(paperId));
        if (!r.passed()) return r;
        r = assertEquals("only the returned part is unlocked", List.of((long) (FILL - ESCAPED) * ratio), unlocked);
        if (!r.passed()) return r;
        return assertTrue("escaped part logged as left locked, got " + errors, errors.stream().anyMatch(e ->
                e.contains("Withdraw rollback: " + ESCAPED + " ") && e.contains(ESCAPED * ratio + " (raw) left locked")));
    }

    /** A slave deposit whose reply was lost, resolved by the status check the way AsyncBank does it. */
    private static CompletableFuture<BankStatus> lostReply(byte statusCheck) {
        return TransferStatusRequest.resolve(CompletableFuture.failedFuture(new RequestOutcomeUnknownException("timeout")),
                () -> BankStatus.FAILED_NO_MASTER_CONNECTION, rem -> BankStatus.SUCCESS,
                () -> CompletableFuture.completedFuture(new TransferStatusRequest.OutputData(statusCheck, Map.of())));
    }

    /**
     * Task #145: a terminal deposit takes the items out before it sends. A lost reply the master may have applied
     * (UNKNOWN) must not leave them in the terminal; NOT_APPLIED gives them back; only what was taken is deposited.
     */
    private TestResult testTerminalDepositReservesBeforeSend() {
        ItemID paperId = ItemIDManager.getItemID(new ItemStack(Items.PAPER));
        if (!paperId.isValid())
            return fail("Plain paper is not registered — default ItemIDs missing on this server?");
        // Detached block entity (no level): runOnMainThread runs inline, so the flow is synchronous.
        BankTerminalBlockEntity blockEntity = new BankTerminalBlockEntity(
                BlockPos.ZERO, BankSystemBlocks.BANK_TERMINAL_BLOCK.get().defaultBlockState());
        BankTerminalBlockEntity.TerminalInventory inventory = new BankTerminalBlockEntity.TerminalInventory(blockEntity, 9);
        UUID player = UUID.randomUUID();
        long[] atSend = {-1}, credited = {0};

        inventory.setStackInSlot(0, new ItemStack(Items.PAPER, 10));
        blockEntity.depositReserved(inventory, player, paperId, 10, TransferJournal.ref("test"), n -> {
            atSend[0] = inventory.getItemCount_direct(paperId);
            return lostReply(TransferStatusRequest.UNKNOWN);
        }, n -> credited[0] += n);
        TestResult r = assertEquals("items taken out before the send", 0L, atSend[0]);
        if (!r.passed()) return r;
        r = assertEquals("UNKNOWN: items stay out of the terminal (parked)", 0L, inventory.getItemCount_direct(paperId));
        if (!r.passed()) return r;

        inventory.setStackInSlot(0, new ItemStack(Items.PAPER, 10));
        blockEntity.depositReserved(inventory, player, paperId, 10, TransferJournal.ref("test"),
                n -> lostReply(TransferStatusRequest.NOT_APPLIED), n -> credited[0] += n);
        r = assertEquals("NOT_APPLIED: items given back", 10L, inventory.getItemCount_direct(paperId));
        if (!r.passed()) return r;

        long[] sent = {0};
        blockEntity.depositReserved(inventory, player, paperId, 15, TransferJournal.ref("test"), n -> {
            sent[0] = n;
            return CompletableFuture.completedFuture(BankStatus.SUCCESS);
        }, n -> credited[0] += n);
        r = assertEquals("only the 10 still in the terminal are sent", 10L, sent[0]);
        if (!r.passed()) return r;
        r = assertEquals("SUCCESS: items stay out", 0L, inventory.getItemCount_direct(paperId));
        if (!r.passed()) return r;
        return assertEquals("only SUCCESS credits", 10L, credited[0]);
    }
}
