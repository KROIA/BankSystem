package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.banking.bank.AsyncBank;
import net.kroia.banksystem.banking.ledger.TransactionLedger;
import net.kroia.banksystem.data.DiagnosticsDatabase;
import net.kroia.banksystem.data.TransferJournal;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Task #124 (B2) — TransferJournal rows. Registered twice: SLAVE_ONLY (REQUESTED through the real AsyncBank
 * path has full columns, a follow-up row is minimal, DROP writes DELIVERED) and MASTER_ONLY (no rows).
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
