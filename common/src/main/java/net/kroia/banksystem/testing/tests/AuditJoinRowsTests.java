package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.IAsyncBank;
import net.kroia.banksystem.api.bank.IServerBank;
import net.kroia.banksystem.api.bankaccount.IAsyncBankAccount;
import net.kroia.banksystem.api.bankmanager.IAsyncBankManager;
import net.kroia.banksystem.api.ledger.LedgerActor;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.bankaccount.ServerBankAccount;
import net.kroia.banksystem.banking.bankmanager.ServerBankManager;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.banking.converter.ConverterCacheManager;
import net.kroia.banksystem.banking.ledger.TransactionLedger;
import net.kroia.banksystem.data.DiagnosticsDatabase;
import net.kroia.banksystem.data.TransferJournal;
import net.kroia.banksystem.data.table.record.TransactionLogRecord;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.networking.entity.ConverterCommitToBankPacket;
import net.kroia.banksystem.networking.multi_server.ServerInfoRequest;
import net.kroia.banksystem.networking.multi_server.TransferStatusRequest;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.testing.LedgerByRefRequest;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.MultiServerUtils;
import net.kroia.modutilities.networking.multi_server.MultiServerManager;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.kroia.modutilities.testing.autotest.AutoTest;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Rows for StockMarket's T-236 audit join, LEFT IN PLACE in the live databases (no cleanup of ledger or journal rows).
 * Autotest runs only: they hand items to a real player and leave a test account behind.
 * <ul>
 *   <li>Master ({@code user_deletion_payout_leg_on_slave}): a user-deletion payout delivered on a connected slave to a
 *       player online there. Master ledger: ACCOUNT_DELETE_PAYOUT with ref = the slave leg's transfer_id; slave
 *       journal: DROP / DELIVERED under the same transfer_id.</li>
 *   <li>Slave ({@code converter_deposit_rows_left}): a converter commit from this slave. Slave journal: CONVERTER_DEPOSIT
 *       REQUESTED + CONFIRMED; master ledger: CONVERTER_DEPOSIT with ref = the same transfer_id. Needs a master that
 *       trusts this slave (an untrusted slave's deposit is refused before any ledger row).</li>
 * </ul>
 */
public class AuditJoinRowsTests extends TestSuite {

    private static final long WAIT_MS = 10_000;
    private static final long PLAYER_WAIT_MS = 120_000; // slave cell: the client joins the slave independently of the master run
    private static final long PAYOUT_CENTS = 500L;
    private static final long CONVERTER_CENTS = 300L;
    private static final UUID CONVERTER_PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000a236");

    private final boolean slave;

    public AuditJoinRowsTests(boolean slave) {
        this.slave = slave;
    }

    @Override
    public TestCategory getCategory() {
        return slave ? BankSystemTestCategories.DIAGNOSTICS_SLAVE : BankSystemTestCategories.DATA_PERSISTENCE;
    }

    @Override
    public void registerTests() {
        if (slave) {
            addTest("converter_deposit_rows_left", () -> threw(this::converterRowsLeft));
            addTest("async_bank_ledger_ref_is_journal_id", () -> threw(this::asyncBankLedgerRef)); // Task #177
        }
        else addTest("user_deletion_payout_leg_on_slave", () -> threw(this::payoutLegOnSlave));
    }

    // ------------------------------------------------------------------ master

    private TestResult payoutLegOnSlave() throws Exception {
        if (!AutoTest.isEnabled()) return skip("autotest runs only: hands items to a real player on a slave");
        if (!MultiServerManager.isRunning() || !MultiServerManager.isMaster()) return skip("not a multi-server master");
        if (MultiServerManager.getConnectedSlaveIDs().isEmpty()) return skip("no slave connected");
        ServerInfoRequest.PlayerInfo player = onlineSlavePlayer();
        if (player == null) return skip("no player online on a connected slave within " + PLAYER_WAIT_MS / 1000 + " s");
        if (!(BankSystemModBackend.getInstances_forTesting().SERVER_BANK_MANAGER.getSync() instanceof ServerBankManager sbm))
            return fail("live manager is not a ServerBankManager");
        // an untrusted slave's join never reaches the master, so the matrix player has no master user; a real one is never deleted
        if (sbm.userExists(player.uuid())) return skip("player " + player.name() + " has a master user: not deleting it");

        ItemID money = MoneyItem.getItemID();
        sbm.addUser(player.uuid(), player.name());
        IServerBank bank = sbm.getOrCreatePersonalBank(player.uuid(), money);
        if (bank == null) {
            sbm.removeUser(player.uuid());
            return fail("no personal money bank");
        }
        int nr = sbm.getPersonalBankAccount(player.uuid()).getAccountNumber();
        TransactionLedger.withoutRawLog(() -> bank.deposit(PAYOUT_CENTS)); // no RAW_CHANGE row: the ADMIN_ADD row below logs the funding
        TransactionLedger.logRow(nr, LedgerType.ADMIN_ADD, money, PAYOUT_CENTS, LedgerActor.system(), null, "audit join test funding");

        long sentMs = System.currentTimeMillis();
        CompletableFuture<ServerBankManager.PayoutResult> payout = sbm.removeUserWithPayout(player.uuid());
        if (payout == null) return fail("user not removed");
        ServerBankManager.PayoutResult result = await(payout, 65_000); // per slave: ARRS timeout + status check
        if (!result.undelivered().isEmpty()) return fail("undelivered " + result.undelivered() + " (" + result.reason() + ")");
        if (result.delivered().size() != 1) return fail("expected one slave leg, got " + result.delivered());
        Map.Entry<String, Map<ItemID, Long>> leg = result.delivered().entrySet().iterator().next();
        String tid = leg.getKey();
        if (!Map.of(money, PAYOUT_CENTS).equals(leg.getValue())) return fail("leg " + leg);

        // the slave's delivery cache, written in the same handler as its DROP journal rows
        TransferStatusRequest.OutputData st = null;
        for (String s : MultiServerManager.getConnectedSlaveIDs()) {
            TransferStatusRequest.OutputData o = await(TransferStatusRequest.ask(s, tid, sentMs), WAIT_MS);
            if (o.state() == TransferStatusRequest.APPLIED) st = o;
        }
        if (st == null) return fail("no slave reports transfer " + tid + " APPLIED");

        List<TransactionLogRecord> rows = await(BankSystemModBackend.getTransactionLogManager().getByRef(tid), WAIT_MS);
        TransactionLogRecord row = rows.stream().filter(r -> r.type() == LedgerType.ACCOUNT_DELETE_PAYOUT).findFirst().orElse(null);
        if (rows.size() != 1 || row == null || row.accountNumber() != nr || row.itemId() != money.getShort() || row.amount() != PAYOUT_CENTS)
            return fail("ledger rows for ref " + tid + ": " + rows);
        return pass("ACCOUNT_DELETE_PAYOUT " + PAYOUT_CENTS + " on account " + nr + ", ref " + tid
                + " = slave DROP leg (APPLIED) to " + player.name());
    }

    /** A player online on a connected slave; waits for the client to join. Null if none. */
    private ServerInfoRequest.PlayerInfo onlineSlavePlayer() {
        long end = System.currentTimeMillis() + PLAYER_WAIT_MS;
        do {
            for (String s : new ArrayList<>(MultiServerManager.getConnectedSlaveIDs())) {
                try {
                    ServerInfoRequest.ServerInfo info = await(ServerInfoRequest.sendRequest(s), WAIT_MS);
                    if (info != null && info.onlinePlayers != null && !info.onlinePlayers.isEmpty()) return info.onlinePlayers.get(0);
                } catch (RuntimeException ignored) {
                    // slave busy or gone: try again
                }
            }
            pump(1_000);
        } while (System.currentTimeMillis() < end);
        return null;
    }

    // ------------------------------------------------------------------ slave

    private TestResult converterRowsLeft() throws Exception {
        if (!AutoTest.isEnabled()) return skip("autotest runs only: leaves a test account and its rows behind");
        if (!MultiServerUtils.canInteractWithBankSystem()) return skip("slave not connected to its master");
        IAsyncBankManager mgr = BankSystemModBackend.getInstances_forTesting().SERVER_BANK_MANAGER.getAsync();
        if (!Boolean.TRUE.equals(await(mgr.isSlaveServerTrustedAsync(""), WAIT_MS)))
            return skip("master does not trust this slave: it refuses the deposit before any ledger row");
        DiagnosticsDatabase db = DiagnosticsDatabase.live();
        if (db == null || db.isDisabled()) return fail("no live diagnostics DB");

        ItemID money = MoneyItem.getItemID();
        Integer nr = await(mgr.createBankAccountGetAccountNrAsync("__test_audit_join_converter"), WAIT_MS);
        if (nr == null || nr == ServerBankAccount.INVALID_ACCOUNT_NUMBER) return fail("test account not created: " + nr);
        IAsyncBankAccount account = await(mgr.getBankAccountAsync(nr), WAIT_MS);
        if (account == null) return fail("test account " + nr + " not found");
        account.addUserAsync(new User(CONVERTER_PLAYER, "audit_join_test"), BankPermission.DEPOSIT.getValue());
        if (!await(account.hasPermissionAsync(CONVERTER_PLAYER, BankPermission.DEPOSIT), WAIT_MS))
            return fail("test player has no DEPOSIT permission");
        try {
            ConverterCacheManager.get().clear(CONVERTER_PLAYER);
            ConverterCacheManager.get().deposit(CONVERTER_PLAYER, CONVERTER_CENTS);
            long sentMs = System.currentTimeMillis();
            CompletableFuture<Map<ItemID, Long>> sent = new ConverterCommitToBankPacket(nr).commitFromSlave(CONVERTER_PLAYER, CONVERTER_CENTS);
            if (sent == null) return fail("cache not reserved");
            Map<ItemID, Long> notDeposited = await(sent, WAIT_MS);
            if (!notDeposited.isEmpty()) return fail("master did not deposit " + notDeposited);
            long balance = 0;
            for (BankData d : await(account.getBankDataAsync(), WAIT_MS)) if (d.itemID().equals(money)) balance = d.balance();
            if (balance != CONVERTER_CENTS) return fail("account " + nr + " money " + balance + ", expected " + CONVERTER_CENTS);

            await(db.flush(), WAIT_MS);
            List<Map<String, Object>> req = TransferJournalTests.query(db.getFile(),
                    "SELECT * FROM TransferJournal WHERE path = ? AND step = ? AND account_number = ?",
                    TransferJournal.CONVERTER_DEPOSIT, TransferJournal.REQUESTED, nr);
            String json = "{\"" + money.getShort() + "\":" + CONVERTER_CENTS + "}";
            if (req.size() != 1 || !json.equals(req.get(0).get("items_json"))) return fail("REQUESTED rows " + req);
            String tid = (String) req.get(0).get("transfer_id");
            if (TransferJournalTests.query(db.getFile(), "SELECT * FROM TransferJournal WHERE transfer_id = ? AND step = ?",
                    tid, TransferJournal.CONFIRMED).size() != 1) return fail("no CONFIRMED row for " + tid);
            // the master's per-transfer store, written where it logs the CONVERTER_DEPOSIT row with ref = tid
            // and the ledger row itself, read through the test-only LedgerByRefRequest (Task #177)
            TransferStatusRequest.OutputData st = await(TransferStatusRequest.ask(null, tid, sentMs), WAIT_MS);
            if (st.state() != TransferStatusRequest.APPLIED || !st.remainder().isEmpty()) return fail("master status " + st);
            String bad = ledgerMatchesJournal(db, tid, LedgerType.CONVERTER_DEPOSIT);
            if (bad != null) return fail(bad);
            return pass("journal CONVERTER_DEPOSIT REQUESTED " + json + " + CONFIRMED, master APPLIED, account " + nr + ", transfer_id " + tid);
        } finally {
            ConverterCacheManager.get().clear(CONVERTER_PLAYER);
        }
    }

    /**
     * Task #177: an AsyncBank deposit and withdraw from this slave; the master ledger row of each has ref = the slave
     * journal transfer_id, and its account / item / raw amount equal the journal REQUESTED row (amounts are positive,
     * the type gives the direction; one row per transfer).
     */
    private TestResult asyncBankLedgerRef() throws Exception {
        if (!AutoTest.isEnabled()) return skip("autotest runs only: leaves a test account and its rows behind");
        if (!MultiServerUtils.canInteractWithBankSystem()) return skip("slave not connected to its master");
        IAsyncBankManager mgr = BankSystemModBackend.getInstances_forTesting().SERVER_BANK_MANAGER.getAsync();
        if (!Boolean.TRUE.equals(await(mgr.isSlaveServerTrustedAsync(""), WAIT_MS)))
            return skip("master does not trust this slave: it refuses the transfer before any ledger row");
        DiagnosticsDatabase db = DiagnosticsDatabase.live();
        if (db == null || db.isDisabled()) return fail("no live diagnostics DB");

        Integer nr = await(mgr.createBankAccountGetAccountNrAsync("__test_ledger_ref"), WAIT_MS);
        if (nr == null || nr == ServerBankAccount.INVALID_ACCOUNT_NUMBER) return fail("test account not created: " + nr);
        IAsyncBankAccount account = await(mgr.getBankAccountAsync(nr), WAIT_MS);
        if (account == null) return fail("test account " + nr + " not found");
        IAsyncBank bank = await(account.getOrCreateBankAsync(MoneyItem.getItemID()), WAIT_MS);
        if (bank == null) return fail("no money bank on " + nr);

        TransferJournal.Ref dep = TransferJournal.ref("ledger_ref_test");
        BankStatus s = await(TransactionLedger.tagged(LedgerType.DEPOSIT, null, null, dep, () -> bank.depositAsync(400L)), WAIT_MS);
        if (s != BankStatus.SUCCESS) return fail("deposit " + s);
        TransferJournal.Ref wd = TransferJournal.ref("ledger_ref_test");
        s = await(TransactionLedger.tagged(LedgerType.WITHDRAW, null, null, wd, () -> bank.withdrawAsync(150L)), WAIT_MS);
        if (s != BankStatus.SUCCESS) return fail("withdraw " + s);

        String bad = ledgerMatchesJournal(db, dep.transferId(), LedgerType.DEPOSIT);
        if (bad == null) bad = ledgerMatchesJournal(db, wd.transferId(), LedgerType.WITHDRAW);
        if (bad != null) return fail(bad);
        return pass("DEPOSIT " + dep.transferId() + " + WITHDRAW " + wd.transferId() + " on account " + nr + ": master ref = journal id");
    }

    /**
     * Null when the master has exactly one ledger row with ref = {@code tid}, of {@code type}, whose account and
     * {item: raw amount} equal this slave's REQUESTED journal row for {@code tid}; else what differs.
     */
    private @Nullable String ledgerMatchesJournal(DiagnosticsDatabase db, String tid, LedgerType type) throws Exception {
        await(db.flush(), WAIT_MS);
        List<Map<String, Object>> j = TransferJournalTests.query(db.getFile(),
                "SELECT * FROM TransferJournal WHERE transfer_id = ? AND step = ?", tid, TransferJournal.REQUESTED);
        if (j.size() != 1) return "journal REQUESTED rows for " + tid + ": " + j;
        List<LedgerByRefRequest.Row> rows = await(LedgerByRefRequest.INSTANCE.sendRequestToMaster(tid), WAIT_MS);
        if (rows == null || rows.size() != 1) return "master ledger rows for ref " + tid + ": " + rows;
        LedgerByRefRequest.Row r = rows.get(0);
        String json = "{\"" + r.itemId() + "\":" + r.amount() + "}";
        Object acc = j.get(0).get("account_number");
        if (!tid.equals(r.ref()) || r.type() != type || !(acc instanceof Number n) || n.intValue() != r.account()
                || !json.equals(j.get(0).get("items_json")))
            return "ledger " + r + " != journal " + j.get(0);
        return null;
    }

    // ------------------------------------------------------------------ helpers

    @FunctionalInterface
    private interface Body { TestResult run() throws Exception; }

    private TestResult threw(Body body) {
        try {
            return body.run();
        } catch (Exception e) {
            return fail("threw " + e);
        }
    }
}
