package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.IAsyncBank;
import net.kroia.banksystem.api.bankaccount.IAsyncBankAccount;
import net.kroia.banksystem.api.bankmanager.IAsyncBankManager;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.banking.bankaccount.ServerBankAccount;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.banking.ledger.TransactionLedger;
import net.kroia.banksystem.data.DiagnosticsDatabase;
import net.kroia.banksystem.data.TransferJournal;
import net.kroia.banksystem.minecraft.entity.custom.BankTerminalBlockEntity;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.banksystem.banking.bankmanager.AsyncBankManager;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.banking.company.SlaveCompanyMirror;
import net.kroia.banksystem.client.cache.CompanyInfoCache;
import net.kroia.banksystem.client.cache.LookupBackoff;
import net.kroia.banksystem.client.cache.ShareVisualCache;
import net.kroia.banksystem.minecraft.block.BankSystemBlocks;
import net.kroia.banksystem.minecraft.entity.custom.BankDownloadBlockEntity;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.networking.general.AuditPageRequest;
import net.kroia.banksystem.networking.general.S2CCompanyVisualBulkPacket;
import net.kroia.banksystem.networking.multi_server.TransferStatusRequest;
import net.kroia.modutilities.NbtCompat;
import net.kroia.modutilities.networking.client_server.arrs.RequestNotSentException;
import net.kroia.modutilities.networking.multi_server.MultiServerManager;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.kroia.modutilities.testing.autotest.AutoTest;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Task #174: slave with its master down (replaces most of MANUAL_QA_159, covers #167 / #169).
 * <ul>
 *   <li>{@code link_down_*}: normal slave / spslave cells, link taken down with
 *       {@link MultiServerManager#setSimulatedMasterDown}; each test brings it back and checks a request works.</li>
 *   <li>{@code masterdown_*}: only the slave-masterdown cell ({@link AutoTest#isMasterDown()}), master killed.</li>
 * </ul>
 * In the slave-masterdown cell the link_down tests skip (link not up); elsewhere the masterdown tests skip.
 */
public class SlaveMasterDownTests extends TestSuite {

    private static final TestCategory MASTER_DOWN = new TestCategory(
            "banksystem", "master_down", "Slave behaviour while its master is down",
            TestCategory.ServerType.SLAVE_ONLY, true);

    /** Never created: with the link down nothing reaches the master, so the number need not exist. */
    private static final int ACCOUNT = 2_000_000_174;
    private static final int COMPANY = 2_000_000_174;
    private static final long FAST_MS = 2_000;

    @Override
    public TestCategory getCategory() {
        return MASTER_DOWN;
    }

    @Override
    public void registerTests() {
        addTest("link_down_requests_fail_fast", () -> withMasterDown(this::requestsFailFast));
        addTest("link_down_download_block_keeps_binding", () -> withMasterDown(this::downloadBlockKeepsBinding));
        addTest("link_down_failed_lookup_backs_off", () -> withMasterDown(this::lookupBacksOff));
        addTest("link_down_cleanup_runs_once", this::cleanupRunsOnce);
        addTest("link_cut_after_terminal_deposit_credited_once", this::terminalDepositLinkCut); // BS-207

        addTest("masterdown_tick_stats", () -> masterDownCell(this::tickStats));
        addTest("masterdown_requests_fail_fast", () -> masterDownCell(this::requestsFailFast));
        addTest("masterdown_download_block_keeps_binding", () -> masterDownCell(this::downloadBlockKeepsBinding));
    }

    // ------------------------------------------------------------------------------------------
    // Gates
    // ------------------------------------------------------------------------------------------

    private TestResult masterDownCell(Supplier<TestResult> body) {
        if (!AutoTest.isMasterDown()) return skip("not the slave-masterdown cell");
        if (MultiServerManager.isRunning()) return fail("master link is up in the slave-masterdown cell");
        return body.get();
    }

    /**
     * Takes the master link down (simulated), runs {@code body}, always resets the switch, waits for the
     * reconnect and checks a normal request is answered again (link back up).
     */
    private TestResult withMasterDown(Supplier<TestResult> body) {
        if (!MultiServerManager.isSlave()) return skip("not a slave");
        if (!MultiServerManager.isRunning()) return skip("master link not up");
        TestResult result;
        try {
            MultiServerManager.setSimulatedMasterDown(true);
            if (!MultiServerManager.isSimulatedMasterDown())
                result = skip("simulated master down unavailable (dev features off)");
            else if (!pumpUntil(() -> !MultiServerManager.isRunning(), 5_000))
                result = fail("link must go down after setSimulatedMasterDown(true)");
            else
                result = body.get();
        } finally {
            MultiServerManager.setSimulatedMasterDown(false);
        }
        if (!pumpUntil(MultiServerManager::isRunning, 15_000))
            return fail("link must reconnect after setSimulatedMasterDown(false)");
        String back = requestAnsweredAfterReconnect();
        if (back != null) return fail("after reconnect: " + back + (result.passed() ? "" : "; body: " + result.getMessage()));
        return result;
    }

    /** @return null once a master-answered request succeeds (handshake may lag the link by a moment). */
    private String requestAnsweredAfterReconnect() {
        long end = System.currentTimeMillis() + 15_000;
        String last = "no attempt";
        while (System.currentTimeMillis() < end) {
            CompletableFuture<Boolean> f = bankManager().bankAccountExistsAsync(ACCOUNT);
            if (!pumpUntil(f::isDone, 10_000)) return "bankAccountExistsAsync still pending after 10 s";
            Throwable cause = causeOf(f);
            if (cause == null && f.join() != null) return null;
            last = "bankAccountExistsAsync failed: " + cause;
            pump(500);
        }
        return last;
    }

    // ------------------------------------------------------------------------------------------
    // Bodies (shared by the simulated and the real master-down cell)
    // ------------------------------------------------------------------------------------------

    /** A1 / B2: every request answers within FAST_MS (no 30 s ARRS timeout); raw S2S requests are "not sent". */
    private TestResult requestsFailFast() {
        IAsyncBankManager bm = bankManager();
        Map<String, Supplier<CompletableFuture<?>>> api = new LinkedHashMap<>();
        api.put("getBankAccountAsync", () -> bm.getBankAccountAsync(ACCOUNT));
        api.put("getBankAccountDataAsync", () -> bm.getBankAccountDataAsync(ACCOUNT));
        api.put("getBankManagerDataAsync", bm::getBankManagerDataAsync);
        api.put("getShareVisualsAsync", () -> AsyncCompanyManager.getShareVisualsAsync(COMPANY));
        api.put("getCompanyInfoByIdAsync", () -> AsyncCompanyManager.getCompanyInfoByIdAsync(COMPANY));
        api.put("listAllCompanyVisualsAsync", AsyncCompanyManager::listAllCompanyVisualsAsync);
        // Raw ARRS hops, below the canInteractWithBankSystem() guards: MU must refuse them at once.
        Map<String, Supplier<CompletableFuture<?>>> raw = new LinkedHashMap<>();
        raw.put("AsyncBankManager.Request", () -> AsyncBankManager.Request.instance.sendRequestToMaster(
                AsyncBankManager.InputData.of(AsyncBankManager.FunctionType.BankAccountExistsAsync, ACCOUNT)));
        raw.put("AsyncCompanyManager.Request", () -> AsyncCompanyManager.Request.instance.sendRequestToMaster(
                AsyncCompanyManager.InputData.of(AsyncCompanyManager.FunctionType.GET_SHARE_VISUALS,
                        new AsyncCompanyManager.GetShareVisualsInput(COMPANY))));
        raw.put("AuditPageRequest", () -> BankSystemNetworking.AUDIT_PAGE_REQUEST.sendRequestToMaster(
                new AuditPageRequest.Query(ACCOUNT, -1, 0L, 0, 10)));

        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Supplier<CompletableFuture<?>>> e : api.entrySet()) {
            String err = fast(e.getKey(), e.getValue(), false, out);
            if (err != null) return fail(err);
        }
        for (Map.Entry<String, Supplier<CompletableFuture<?>>> e : raw.entrySet()) {
            String err = fast(e.getKey(), e.getValue(), true, out);
            if (err != null) return fail(err);
        }
        return pass(out.toString());
    }

    /** @return failure message, or null; appends "name=outcome(ms)" to {@code out}. */
    private String fast(String name, Supplier<CompletableFuture<?>> call, boolean mustBeNotSent, StringBuilder out) {
        long t0 = System.nanoTime();
        CompletableFuture<?> f;
        try {
            f = call.get();
        } catch (RuntimeException ex) {
            return name + " threw instead of completing: " + ex;
        }
        if (!pumpUntil(f::isDone, FAST_MS))
            return name + " still pending after " + FAST_MS + " ms with the master down";
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        Throwable cause = causeOf(f);
        Object value = cause == null ? f.join() : null;
        if (mustBeNotSent && cause != null && !(cause instanceof RequestNotSentException))
            return name + " must fail with RequestNotSentException, got " + cause;
        if (mustBeNotSent && cause == null && value != null)
            return name + " answered " + value + " with the master down";
        out.append(name).append('=').append(cause != null ? cause.getClass().getSimpleName() : String.valueOf(value))
                .append('(').append(ms).append("ms) ");
        return null;
    }

    /** A2 / B3 (#169): a bound, powered download block keeps its account across receive cycles and open checks. */
    private TestResult downloadBlockKeepsBinding() {
        if (getServer() == null) return skip("no server");
        BankDownloadBlockEntity block = new BankDownloadBlockEntity(BlockPos.ZERO,
                BankSystemBlocks.BANK_DOWNLOAD_BLOCK.get().defaultBlockState());
        CompoundTag tag = new CompoundTag(); // BankDownloadBlockEntity.TAGS: A account, P powered, U owner
        tag.putInt("A", ACCOUNT);
        tag.putBoolean("P", true);
        NbtCompat.putUUID(tag, "U", UUID.randomUUID());
        block.loadAdditional(tag, getServer().registryAccess()); // mc<=1.21.5
        //block.loadAdditional(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, getServer().registryAccess(), tag)); // mc>=1.21.6
        if (block.getBankAccountNumber() != ACCOUNT || !block.isBlockIsPowered())
            return fail("fixture: block not bound/powered after load");
        for (int cycle = 1; cycle <= 5; cycle++) {
            block.update(); // one receive cycle
            pump(100);
            if (block.getBankAccountNumber() != ACCOUNT)
                return fail("receive cycle " + cycle + " unbound the block (account now " + block.getBankAccountNumber() + ")");
        }
        CompletableFuture<Boolean> open = block.hasPermissionToOpenBlock(UUID.randomUUID());
        if (!pumpUntil(open::isDone, FAST_MS)) return fail("open-permission check pending with the master down");
        if (causeOf(open) == null && Boolean.TRUE.equals(open.join()))
            return fail("open-permission check must deny while the account cannot be checked");
        return assertEquals("open-permission check must keep the binding", ACCOUNT, block.getBankAccountNumber());
    }

    /** A3 (#167): a failed share-visual / company-info lookup on the real request path arms the backoff. */
    private TestResult lookupBacksOff() {
        try {
            LookupBackoff visuals = backoffOf(ShareVisualCache.class);
            LookupBackoff infos = backoffOf(CompanyInfoCache.class);
            visuals.succeeded(COMPANY);
            infos.succeeded(COMPANY);
            try {
                ShareVisualCache.tryLookup(COMPANY);
                CompanyInfoCache.tryLookup(COMPANY);
                long now = System.currentTimeMillis();
                if (!pumpUntil(() -> visuals.isWaiting(COMPANY, now) && infos.isWaiting(COMPANY, now), FAST_MS))
                    return fail("failed lookup did not arm the backoff (visuals " + visuals.isWaiting(COMPANY, now)
                            + ", infos " + infos.isWaiting(COMPANY, now) + "): it would resend every frame");
                return pass("both caches back off after a failed lookup");
            } finally {
                visuals.succeeded(COMPANY);
                infos.succeeded(COMPANY);
            }
        } catch (ReflectiveOperationException e) {
            return fail("backoff field not found: " + e);
        }
    }

    /**
     * A4 (#167 / Issue #64): SLAVE_CONNECTION_LOST and the mirror clear run once per drop, not on each failed
     * reconnect attempt (5 s apart, so ~12 s down covers two). A5 is checked by withMasterDown.
     */
    private TestResult cleanupRunsOnce() {
        var events = BankSystemModBackend.getInstances_forTesting().SERVER_EVENTS;
        if (events == null) return skip("no server events");
        AtomicInteger lost = new AtomicInteger();
        Runnable listener = lost::incrementAndGet;
        events.SLAVE_CONNECTION_LOST.addListener(listener);
        try {
            return withMasterDown(() -> {
                if (!pumpUntil(() -> lost.get() >= 1, 5_000)) return fail("SLAVE_CONNECTION_LOST did not fire on the drop");
                if (!pumpUntil(SlaveCompanyMirror::isEmpty, 1_000)) return fail("company mirror not cleared on the drop");
                SlaveCompanyMirror.put(sentinel()); // must survive the failed reconnect attempts
                pump(12_000);
                if (lost.get() != 1)
                    return fail("SLAVE_CONNECTION_LOST fired " + lost.get() + "x during one drop (expected 1)");
                boolean kept = SlaveCompanyMirror.snapshot().stream().anyMatch(e -> e.companyId() == COMPANY);
                return assertTrue("link-down cleanup re-ran on a failed reconnect (mirror cleared again)", kept);
            });
        } finally {
            events.SLAVE_CONNECTION_LOST.removeListener(listener);
            SlaveCompanyMirror.remove(COMPANY);
        }
    }

    private static final long BS207_ITEMS = 10, BS207_UNIT = 100; // raw money per terminal item

    /**
     * BS-207: a real terminal deposit (AsyncBank.depositAsync to the master), link cut right after the send, then
     * back. The master normally applies the queued write, so: items out of the terminal, the account credited exactly
     * once, no RETURNED (refund) journal row. If the master never got it,
     * the status check answers NOT_APPLIED: items refunded exactly once, account untouched, one RETURNED row; passes too.
     * Needs a trusted slave (an untrusted one is refused before the send).
     * Leaves nothing: the test account is deleted after.
     */
    private TestResult terminalDepositLinkCut() {
        if (!AutoTest.isEnabled()) return skip("autotest runs only: cuts the master link");
        if (!MultiServerManager.isSlave()) return skip("not a slave");
        if (!MultiServerManager.isRunning()) return skip("master link not up");
        IAsyncBankManager mgr = bankManager();
        if (!Boolean.TRUE.equals(await(mgr.isSlaveServerTrustedAsync(""), 10_000)))
            return skip("master does not trust this slave: it refuses the deposit");
        ItemID paper = ItemIDManager.getItemID(new ItemStack(Items.PAPER));
        if (!paper.isValid()) return skip("plain paper has no ItemID on this slave");
        DiagnosticsDatabase db = DiagnosticsDatabase.live();
        if (db == null || db.isDisabled()) return fail("no live diagnostics DB");

        Integer nr = await(mgr.createBankAccountGetAccountNrAsync("__test_bs207_link_cut"), 10_000);
        if (nr == null || nr == ServerBankAccount.INVALID_ACCOUNT_NUMBER) return fail("test account not created: " + nr);
        try {
            IAsyncBankAccount account = await(mgr.getBankAccountAsync(nr), 10_000);
            IAsyncBank bank = account == null ? null : await(account.getOrCreateBankAsync(MoneyItem.getItemID()), 10_000);
            if (bank == null) return fail("no money bank on test account " + nr);

            // Detached terminal (no level): depositReserved runs inline on this thread.
            BankTerminalBlockEntity terminal = new BankTerminalBlockEntity(
                    BlockPos.ZERO, BankSystemBlocks.BANK_TERMINAL_BLOCK.get().defaultBlockState());
            BankTerminalBlockEntity.TerminalInventory inventory = new BankTerminalBlockEntity.TerminalInventory(terminal, 9);
            inventory.setStackInSlot(0, new ItemStack(Items.PAPER, (int) BS207_ITEMS));
            TransferJournal.Ref journal = TransferJournal.ref("bs207_test");
            List<CompletableFuture<BankStatus>> sent = new java.util.ArrayList<>();
            AtomicInteger credited = new AtomicInteger();
            boolean cut;
            try {
                terminal.depositReserved(inventory, UUID.randomUUID(), paper, BS207_ITEMS, journal, n -> {
                    CompletableFuture<BankStatus> f = TransactionLedger.tagged(LedgerType.DEPOSIT, null, null, journal,
                            () -> bank.depositAsync(n * BS207_UNIT));
                    sent.add(f);
                    MultiServerManager.setSimulatedMasterDown(true); // cut right after the send
                    return f;
                }, n -> credited.incrementAndGet());
                cut = MultiServerManager.isSimulatedMasterDown();
                if (sent.size() != 1) return fail("deposit sent " + sent.size() + "x");
                if (inventory.getItemCount_direct(paper) != 0) return fail("items still in the terminal after the send");
                if (cut) pumpUntil(() -> !MultiServerManager.isRunning(), 5_000);
            } finally {
                MultiServerManager.setSimulatedMasterDown(false);
            }
            if (!cut) return skip("simulated master down unavailable (dev features off)");
            if (!pumpUntil(MultiServerManager::isRunning, 15_000)) return fail("link must reconnect after setSimulatedMasterDown(false)");
            String back = requestAnsweredAfterReconnect();
            if (back != null) return fail("after reconnect: " + back);
            // pumpUntil runs no server ticks, so the tick-driven status re-ask never fires: re-ask by hand, once a second.
            long[] lastAsk = {0};
            if (!pumpUntil(() -> {
                long now = System.currentTimeMillis();
                if (now - lastAsk[0] >= 1_000) {
                    lastAsk[0] = now;
                    TransferStatusRequest.retryTick(now);
                }
                return sent.get(0).isDone();
            }, 90_000)) return fail("deposit outcome still pending 90 s after the reconnect");
            pump(1_000); // a refund would hop to the main thread

            long items = inventory.getItemCount_direct(paper);
            long balance = 0;
            for (BankData d : await(account.getBankDataAsync(), 10_000))
                if (MoneyItem.isMoney(d.itemID())) balance += d.balance();
            await(db.flush(), 10_000);
            int returned = TransferJournalTests.query(db.getFile(), "SELECT * FROM TransferJournal WHERE transfer_id = ? AND step = ?",
                    journal.transferId(), TransferJournal.RETURNED).size();
            Throwable cause = causeOf(sent.get(0));
            String state = "outcome " + (cause != null ? "unknown (" + cause.getClass().getSimpleName() + ")" : sent.get(0).join())
                    + ", terminal " + items + ", balance " + balance + ", RETURNED rows " + returned + ", ledger callbacks " + credited.get();
            if (cause == null && sent.get(0).join() == BankStatus.FAILED_NO_MASTER_CONNECTION) { // status check: NOT_APPLIED
                if (items != BS207_ITEMS) return fail("not applied, expected " + BS207_ITEMS + " items refunded once: " + state);
                if (balance != 0) return fail("not applied, but the account was credited: " + state);
                if (returned != 1 || credited.get() != 0) return fail("not applied, expected 1 RETURNED row and no ledger callback: " + state);
                return pass("not applied, refunded once: " + state);
            }
            if (items != 0) return fail("items came back into the terminal: " + state);
            if (balance != BS207_ITEMS * BS207_UNIT) return fail("expected balance " + BS207_ITEMS * BS207_UNIT + " (credited once): " + state);
            if (returned != 0) return fail("refund row written: " + state);
            return pass(state);
        } catch (java.sql.SQLException e) {
            return fail("journal query: " + e);
        } finally {
            mgr.deleteBankAccountAsync(nr);
        }
    }

    /** B1: server ticks sampled from the master kill until the suites started. */
    private TestResult tickStats() {
        AutoTest.TickStats t = AutoTest.tickStats();
        String nums = String.format("ticks %d, max %.1f ms, avg %.1f ms", t.ticks(), t.maxTickMs(), t.avgTickMs());
        if (t.ticks() == 0) return fail("no ticks sampled while the master was down");
        if (t.maxTickMs() >= 250) return fail("tick spike with the master down: " + nums);
        if (t.avgTickMs() >= 60) return fail("server lagging with the master down: " + nums);
        return pass(nums);
    }

    // ------------------------------------------------------------------------------------------

    private static IAsyncBankManager bankManager() {
        return BankSystemModBackend.getInstances_forTesting().SERVER_BANK_MANAGER.getAsync();
    }

    private static LookupBackoff backoffOf(Class<?> cache) throws ReflectiveOperationException {
        Field f = cache.getDeclaredField("backoff");
        f.setAccessible(true);
        return (LookupBackoff) f.get(null);
    }

    private static S2CCompanyVisualBulkPacket.Entry sentinel() {
        return new S2CCompanyVisualBulkPacket.Entry(COMPANY, "", 0, "", 0, 0, "MasterDownTest", "",
                0L, 0L, "MasterDownTest", "", 0, List.of(), 0);
    }

    private static Throwable causeOf(CompletableFuture<?> f) {
        if (!f.isCompletedExceptionally()) return null;
        try {
            f.join();
            return null;
        } catch (CompletionException | java.util.concurrent.CancellationException e) {
            return e.getCause() != null ? e.getCause() : e;
        }
    }
}
