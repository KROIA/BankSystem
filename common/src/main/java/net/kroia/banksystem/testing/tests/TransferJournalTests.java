package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankaccount.IAsyncBankAccount;
import net.kroia.banksystem.api.bankmanager.IAsyncBankManager;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.bank.AsyncBank;
import net.kroia.banksystem.banking.bankaccount.ServerBankAccount;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.banking.converter.ConverterCacheManager;
import net.kroia.banksystem.banking.ledger.TransactionLedger;
import net.kroia.banksystem.data.DiagnosticsDatabase;
import net.kroia.banksystem.data.TransferJournal;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.networking.entity.ConverterCommitToBankPacket;
import net.kroia.banksystem.networking.multi_server.DropItemsInPlayerInventoryRequest;
import net.kroia.banksystem.networking.multi_server.TransferStatusRequest;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.MultiServerUtils;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Task #124 (B2) — TransferJournal rows. Registered twice: SLAVE_ONLY (REQUESTED through the real AsyncBank
 * path has full columns, a follow-up row is minimal, DROP writes DELIVERED, Task #128 converter commit round
 * trip) and MASTER_ONLY (no rows).
 * Every case swaps a temp-file DB in for the live one and restores it after.
 */
public class TransferJournalTests extends TestSuite {

    static final long WAIT_MS = 10_000;
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000b124");
    private static final int NO_SUCH_ACCOUNT = -124_124; // the master applies nothing to it

    private final boolean slave;
    private Path dir;

    public TransferJournalTests(boolean slave) {
        this.slave = slave;
    }

    @Override
    public TestCategory getCategory() {
        return slave ? BankSystemTestCategories.DIAGNOSTICS_SLAVE : BankSystemTestCategories.DATABASE;
    }

    @Override
    public void registerTests() {
        if (slave) {
            addTest("async_bank_requested_has_full_columns", () -> run("requested.db", this::requested));
            addTest("follow_up_row_is_minimal", () -> run("followup.db", this::followUp));
            addTest("drop_writes_delivered", () -> run("drop.db", this::drop));
            addTest("drop_uses_wire_transfer_id", () -> run("dropwire.db", this::dropUsesWireId)); // Task #125
            addTest("drop_repeat_answered_from_cache", () -> run("dropcache.db", this::dropRepeat)); // Task #126
            addTest("converter_commit_credits_money_bank", () -> run("converter.db", this::converterCommit)); // Task #128
        } else {
            addTest("master_writes_no_journal_rows", () -> run("master.db", this::master));
        }
    }

    @Override
    public void setup() {
        try {
            dir = Files.createTempDirectory("banksystem-journal-test");
        } catch (Exception e) {
            dir = null;
        }
    }

    @Override
    public void teardown() {
        deleteDir(dir);
        dir = null;
    }

    private TestResult requested(DiagnosticsDatabase db, Path f) throws Exception {
        if (!MultiServerUtils.canInteractWithBankSystem()) return skip("slave not connected to its master");
        ItemID money = MoneyItem.getItemID();
        TransferJournal.Ref ref = TransferJournal.ref("journal_test");
        await(TransactionLedger.tagged(LedgerType.UPLOAD_DEPOSIT, PLAYER, null, ref,
                () -> AsyncBank.createSlaveServerBank(NO_SUCH_ACCOUNT, money).depositAsync(123L)), WAIT_MS);
        await(db.flush(), WAIT_MS);
        List<Map<String, Object>> rows = query(f, "SELECT * FROM TransferJournal WHERE transfer_id = ?", ref.transferId());
        if (rows.size() != 1) return fail("rows " + rows);
        Map<String, Object> r = rows.get(0);
        String json = "{\"" + money.getShort() + "\":123}";
        if (!"REQUESTED".equals(r.get("step")) || !"TO_MASTER".equals(r.get("direction")) || !"ASYNC_BANK".equals(r.get("path"))
                || num(r.get("account_number")) != NO_SUCH_ACCOUNT || !PLAYER.toString().equals(r.get("player_uuid"))
                || !json.equals(r.get("items_json")) || num(r.get("ledger_type")) != LedgerType.UPLOAD_DEPOSIT.code()
                || !"journal_test".equals(r.get("context")) || r.get("detail") != null || num(r.get("ts")) <= 0)
            return fail("row " + r);
        return pass("REQUESTED row " + r);
    }

    private TestResult followUp(DiagnosticsDatabase db, Path f) throws Exception {
        String tid = TransferJournal.newTransferId();
        TransferJournal.step(tid, TransferJournal.CONFIRMED, null, null);
        await(db.flush(), WAIT_MS);
        List<Map<String, Object>> rows = query(f, "SELECT * FROM TransferJournal WHERE transfer_id = ?", tid);
        if (rows.size() != 1) return fail("rows " + rows);
        Map<String, Object> r = rows.get(0);
        if (!"CONFIRMED".equals(r.get("step")) || num(r.get("ts")) <= 0) return fail("row " + r);
        for (String col : List.of("direction", "path", "account_number", "player_uuid", "items_json", "ledger_type", "context", "detail"))
            if (r.get(col) != null) return fail(col + " not null: " + r);
        return pass("CONFIRMED row carries transfer_id, ts, step only");
    }

    private TestResult drop(DiagnosticsDatabase db, Path f) throws Exception {
        ItemID money = MoneyItem.getItemID();
        if (!money.isValid()) return skip("money ItemID not synced yet");
        String tid = TransferJournal.newTransferId();
        TransferJournal.drop(tid, PLAYER, Map.of(money, 5L), Map.of(money, 2L));
        await(db.flush(), WAIT_MS);
        List<Map<String, Object>> rows = query(f, "SELECT * FROM TransferJournal WHERE transfer_id = ? ORDER BY id", tid);
        String s = Short.toString(money.getShort());
        if (rows.size() != 2) return fail("rows " + rows);
        Map<String, Object> d = rows.get(0), x = rows.get(1);
        if (!"DELIVERED".equals(d.get("step")) || !("{\"" + s + "\":300}").equals(d.get("items_json"))
                || !"FROM_MASTER".equals(d.get("direction")) || !"DROP".equals(d.get("path"))
                || !PLAYER.toString().equals(d.get("player_uuid")))
            return fail("DELIVERED row " + d);
        if (!"FAILED".equals(x.get("step")) || !("{\"" + s + "\":200}").equals(x.get("items_json")))
            return fail("FAILED row " + x);
        return pass("3 of 5 notes DELIVERED (300 raw), 2 FAILED (200 raw)");
    }

    /** Task #125: the slave's DROP rows use the master's wire transfer_id; without one (older master) a fresh id. */
    private TestResult dropUsesWireId(DiagnosticsDatabase db, Path f) throws Exception {
        ItemID money = MoneyItem.getItemID();
        if (!money.isValid()) return skip("money ItemID not synced yet");
        String tid = TransferJournal.newTransferId();
        DropItemsInPlayerInventoryRequest req = new DropItemsInPlayerInventoryRequest();
        // PLAYER is offline: nothing is dropped, so each call writes one FAILED row
        req.handleOnSlaveServer(new DropItemsInPlayerInventoryRequest.InputData(PLAYER, Map.of(money, 5L), false, tid), null).join();
        req.handleOnSlaveServer(new DropItemsInPlayerInventoryRequest.InputData(PLAYER, Map.of(money, 5L), false), null).join();
        await(db.flush(), WAIT_MS);
        List<Map<String, Object>> wire = query(f, "SELECT * FROM TransferJournal WHERE transfer_id = ?", tid);
        if (wire.size() != 1 || !TransferJournal.FAILED.equals(wire.get(0).get("step"))) return fail("rows for the wire id " + wire);
        List<Map<String, Object>> other = query(f, "SELECT * FROM TransferJournal WHERE transfer_id <> ?", tid);
        if (other.size() != 1 || other.get(0).get("transfer_id") == null) return fail("rows without a wire id " + other);
        return pass("wire id used; fresh id " + other.get(0).get("transfer_id") + " without one");
    }

    /** Task #126: a repeated DROP gets the first reply and drops nothing again; the status handler reads the cache. */
    private TestResult dropRepeat(DiagnosticsDatabase db, Path f) throws Exception {
        ItemID money = MoneyItem.getItemID();
        if (!money.isValid()) return skip("money ItemID not synced yet");
        String tid = TransferJournal.newTransferId();
        DropItemsInPlayerInventoryRequest req = new DropItemsInPlayerInventoryRequest();
        var in = new DropItemsInPlayerInventoryRequest.InputData(PLAYER, Map.of(money, 5L), false, tid);
        // PLAYER is offline: nothing is dropped, so the transfer is stored as not applied
        var first = req.handleOnSlaveServer(in, null).join();
        if (first != req.handleOnSlaveServer(in, null).join()) return fail("repeat must get the stored reply");
        await(db.flush(), WAIT_MS);
        if (query(f, "SELECT * FROM TransferJournal WHERE transfer_id = ?", tid).size() != 1) return fail("repeat wrote journal rows");
        TransferStatusRequest status = new TransferStatusRequest();
        if (status.handleOnSlaveServer(new TransferStatusRequest.InputData(0, tid), null).join().state() != TransferStatusRequest.NOT_APPLIED)
            return fail("status of a DROP that dropped nothing");
        if (status.handleOnSlaveServer(new TransferStatusRequest.InputData(0, tid), PLAYER).join().state() != TransferStatusRequest.UNKNOWN)
            return fail("a player must get UNKNOWN");
        return pass("repeat DROP answered from the cache; status NOT_APPLIED");
    }

    /**
     * Task #128: a slave converter commit of a 3.00 cache; the branch depends on whether the master trusts this slave.
     * <ul>
     *   <li>Trusted: 300 cents credited to the master's money bank of a test account, cache 0, no banknote bank,
     *       journal REQUESTED {money: 300} + a minimal CONFIRMED row.</li>
     *   <li>Untrusted (the autotest matrix slave): no test account (the master refuses creating one). The master
     *       refuses the whole deposit, the 300 cents go back to the cache, journal REQUESTED {money: 300} + a minimal
     *       RETURNED row and no CONFIRMED row.</li>
     * </ul>
     */
    private TestResult converterCommit(DiagnosticsDatabase db, Path f) throws Exception {
        if (!MultiServerUtils.canInteractWithBankSystem()) return skip("slave not connected to its master");
        IAsyncBankManager mgr = BankSystemModBackend.getInstances_forTesting().SERVER_BANK_MANAGER.getAsync();
        boolean trusted = Boolean.TRUE.equals(await(mgr.isSlaveServerTrustedAsync(""), WAIT_MS)); // "" = this slave
        try {
            return trusted ? converterCommitTrusted(db, f, mgr) : converterCommitUntrusted(db, f);
        } finally {
            ConverterCacheManager.get().clear(PLAYER);
        }
    }

    private TestResult converterCommitTrusted(DiagnosticsDatabase db, Path f, IAsyncBankManager mgr) throws Exception {
        ItemID money = MoneyItem.getItemID();
        Integer nr = await(mgr.createBankAccountGetAccountNrAsync("Task128ConverterTest"), WAIT_MS);
        if (nr == null || nr == ServerBankAccount.INVALID_ACCOUNT_NUMBER) return fail("test account not created: " + nr);
        try {
            IAsyncBankAccount account = await(mgr.getBankAccountAsync(nr), WAIT_MS);
            if (account == null) return fail("test account " + nr + " not found");
            account.addUserAsync(new User(PLAYER, "converter_test"), BankPermission.DEPOSIT.getValue());
            if (!await(account.hasPermissionAsync(PLAYER, BankPermission.DEPOSIT), WAIT_MS))
                return fail("test player has no DEPOSIT permission");
            Map<ItemID, Long> before = balances(account);
            ConverterCacheManager.get().clear(PLAYER);
            ConverterCacheManager.get().deposit(PLAYER, 300L);
            long sentMs = System.currentTimeMillis();
            CompletableFuture<Map<ItemID, Long>> sent = new ConverterCommitToBankPacket(nr).commitFromSlave(PLAYER, 300L);
            if (sent == null) return fail("cache not reserved");
            Map<ItemID, Long> notDeposited = await(sent, WAIT_MS);
            if (!notDeposited.isEmpty()) return fail("master did not deposit " + notDeposited);
            long cache = ConverterCacheManager.get().getCache(PLAYER);
            Map<ItemID, Long> after = balances(account);
            long credited = after.getOrDefault(money, 0L) - before.getOrDefault(money, 0L);
            if (credited != 300L) return fail("money bank credited " + credited + ", expected 300");
            if (cache != 0L) return fail("cache " + cache + " after commit, expected 0");
            if (!after.keySet().equals(before.keySet())) return fail("banks changed " + before.keySet() + " -> " + after.keySet());

            await(db.flush(), WAIT_MS);
            String json = "{\"" + money.getShort() + "\":300}";
            List<Map<String, Object>> req = query(f, "SELECT * FROM TransferJournal WHERE path = ? AND step = ?",
                    TransferJournal.CONVERTER_DEPOSIT, TransferJournal.REQUESTED);
            if (req.size() != 1 || !json.equals(req.get(0).get("items_json"))) return fail("REQUESTED rows " + req);
            List<Map<String, Object>> ok = query(f, "SELECT * FROM TransferJournal WHERE transfer_id = ? AND step = ?",
                    req.get(0).get("transfer_id"), TransferJournal.CONFIRMED);
            if (ok.size() != 1 || ok.get(0).get("items_json") != null) return fail("CONFIRMED rows " + ok);
            // Task #126: real status round trip to the master
            TransferStatusRequest.OutputData st = await(TransferStatusRequest.ask(null, (String) req.get(0).get("transfer_id"), sentMs), WAIT_MS);
            if (st.state() != TransferStatusRequest.APPLIED || !st.remainder().isEmpty()) return fail("master status " + st);
            return pass("300 cents to the money bank, cache 0, no note bank, journal " + json + ", master status APPLIED");
        } finally {
            mgr.deleteBankAccountAsync(nr);
        }
    }

    private TestResult converterCommitUntrusted(DiagnosticsDatabase db, Path f) throws Exception {
        ItemID money = MoneyItem.getItemID();
        ConverterCacheManager.get().clear(PLAYER);
        ConverterCacheManager.get().deposit(PLAYER, 300L);
        long sentMs = System.currentTimeMillis();
        // any account number: the master refuses an untrusted slave before the account lookup
        CompletableFuture<Map<ItemID, Long>> sent = new ConverterCommitToBankPacket(1).commitFromSlave(PLAYER, 300L);
        if (sent == null) return fail("cache not reserved");
        Map<ItemID, Long> notDeposited = await(sent, WAIT_MS);
        if (!Map.of(money, 300L).equals(notDeposited)) return fail("not deposited " + notDeposited + ", expected {money: 300}");
        // the refund hops to the server thread
        if (!pumpUntil(() -> ConverterCacheManager.get().getCache(PLAYER) == 300L, WAIT_MS))
            return fail("cache " + ConverterCacheManager.get().getCache(PLAYER) + " after refusal, expected 300");

        await(db.flush(), WAIT_MS);
        String json = "{\"" + money.getShort() + "\":300}";
        List<Map<String, Object>> req = query(f, "SELECT * FROM TransferJournal WHERE path = ? AND step = ?",
                TransferJournal.CONVERTER_DEPOSIT, TransferJournal.REQUESTED);
        if (req.size() != 1 || !json.equals(req.get(0).get("items_json"))) return fail("REQUESTED rows " + req);
        // exactly one follow-up: RETURNED without items (full refund); a full refund writes no CONFIRMED row
        List<Map<String, Object>> next = query(f, "SELECT * FROM TransferJournal WHERE transfer_id = ? AND step <> ?",
                req.get(0).get("transfer_id"), TransferJournal.REQUESTED);
        if (next.size() != 1 || !TransferJournal.RETURNED.equals(next.get(0).get("step")) || next.get(0).get("items_json") != null)
            return fail("follow-up rows " + next);
        // Task #126: real status round trip; the master stored nothing for the refused transfer
        TransferStatusRequest.OutputData st = await(TransferStatusRequest.ask(null, (String) req.get(0).get("transfer_id"), sentMs), WAIT_MS);
        if (st.state() != TransferStatusRequest.NOT_APPLIED) return fail("master status " + st);
        return pass("untrusted slave: 300 cents refunded to the cache, journal REQUESTED {money:300} + RETURNED, master status NOT_APPLIED");
    }

    private Map<ItemID, Long> balances(IAsyncBankAccount account) {
        Map<ItemID, Long> out = new HashMap<>();
        for (BankData d : await(account.getBankDataAsync(), WAIT_MS)) out.put(d.itemID(), d.balance());
        return out;
    }

    private TestResult master(DiagnosticsDatabase db, Path f) throws Exception {
        if (BankSystemModBackend.getInstances_forTesting().isSlaveServer) return skip("slave");
        db.setMeta("journal_test", "1"); // opens the file, so the table exists
        TransferJournal.requested(TransferJournal.ref("x"), TransferJournal.TO_MASTER, TransferJournal.ASYNC_BANK, 1, PLAYER,
                Map.of(), LedgerType.DEPOSIT.code());
        TransferJournal.step(TransferJournal.newTransferId(), TransferJournal.CONFIRMED, null, null);
        TransferJournal.drop(TransferJournal.newTransferId(), PLAYER, Map.of(MoneyItem.getItemID(), 1L), Map.of());
        await(db.flush(), WAIT_MS);
        List<Map<String, Object>> rows = query(f, "SELECT * FROM TransferJournal");
        return rows.isEmpty() ? pass("no TransferJournal rows on the master") : fail("rows " + rows);
    }

    // ------------------------------------------------------------------ helpers

    @FunctionalInterface
    interface Body {
        TestResult run(DiagnosticsDatabase db, Path file) throws Exception;
    }

    private TestResult run(String fileName, Body body) {
        return withTempDb(dir, fileName, body, this::fail);
    }

    /** Runs {@code body} with a temp-file DB as the live instance; restores the live one and closes the temp one after. */
    static TestResult withTempDb(Path dir, String fileName, Body body, java.util.function.Function<String, TestResult> fail) {
        if (dir == null) return fail.apply("temp dir missing");
        BankSystemModBackend.Instances inst = BankSystemModBackend.getInstances_forTesting();
        Path f = dir.resolve(fileName);
        DiagnosticsDatabase db = new DiagnosticsDatabase(f);
        DiagnosticsDatabase live = inst.DIAGNOSTICS_DB;
        inst.DIAGNOSTICS_DB = db;
        try {
            return body.run(db, f);
        } catch (Exception e) {
            return fail.apply(e.toString());
        } finally {
            inst.DIAGNOSTICS_DB = live;
            db.close();
        }
    }

    static List<Map<String, Object>> query(Path f, String sql, Object... params) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = DiagnosticsDatabase.connect(f); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) m.put(md.getColumnName(i), rs.getObject(i));
                    out.add(m);
                }
            }
        }
        return out;
    }

    static long num(Object o) {
        return o instanceof Number n ? n.longValue() : Long.MIN_VALUE;
    }

    static void deleteDir(Path dir) {
        if (dir == null) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (Exception ignored) {
            // best effort
        }
    }
}
