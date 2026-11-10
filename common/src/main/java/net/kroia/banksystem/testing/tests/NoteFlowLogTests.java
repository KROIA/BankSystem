package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.data.DiagnosticsDatabase;
import net.kroia.banksystem.data.NoteFlowLog;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.kroia.banksystem.testing.tests.TransferJournalTests.WAIT_MS;
import static net.kroia.banksystem.testing.tests.TransferJournalTests.num;
import static net.kroia.banksystem.testing.tests.TransferJournalTests.query;

/**
 * Task #124 (B5) — {@link NoteFlowLog}: a record writes one row, a disabled or missing DB is a no-op,
 * converter rows carry the right sign. <b>Server type:</b> BOTH (every role writes NoteFlow).
 * Each case swaps a temp-file DB in for the live one.
 */
public class NoteFlowLogTests extends TestSuite {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000c124");

    private Path dir;

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.LIFECYCLE;
    }

    @Override
    public void registerTests() {
        addTest("record_writes_row", () -> TransferJournalTests.withTempDb(dir, "record.db", this::record, this::fail));
        addTest("record_noop_with_disabled_db", this::disabled);
        addTest("converter_rows_have_sign", () -> TransferJournalTests.withTempDb(dir, "converter.db", this::converter, this::fail));
    }

    @Override
    public void setup() {
        try {
            dir = Files.createTempDirectory("banksystem-noteflow-test");
        } catch (Exception e) {
            dir = null;
        }
    }

    @Override
    public void teardown() {
        TransferJournalTests.deleteDir(dir);
        dir = null;
    }

    private TestResult record(DiagnosticsDatabase db, Path f) throws Exception {
        net.kroia.banksystem.BankSystemMod.getAPI().getNoteFlowLog().record("test:record", PLAYER, new ItemID((short) 77), -4L, "ctx");
        await(db.flush(), WAIT_MS);
        List<Map<String, Object>> rows = query(f, "SELECT * FROM NoteFlow WHERE source = 'test:record'");
        if (rows.size() != 1) return fail("rows " + rows);
        Map<String, Object> r = rows.get(0);
        if (!PLAYER.toString().equals(r.get("player_uuid")) || num(r.get("item_id")) != 77 || num(r.get("amount")) != -4
                || !"ctx".equals(r.get("context")) || num(r.get("ts")) <= 0)
            return fail("row " + r);
        return pass("row " + r);
    }

    private TestResult disabled() {
        if (dir == null) return fail("temp dir missing");
        BankSystemModBackend.Instances inst = BankSystemModBackend.getInstances_forTesting();
        DiagnosticsDatabase live = inst.DIAGNOSTICS_DB;
        try {
            Path blocker = dir.resolve("not-a-folder");
            Files.writeString(blocker, "x");
            Path f = blocker.resolve(DiagnosticsDatabase.FILE_NAME); // parent is a file: cannot be created
            DiagnosticsDatabase db = new DiagnosticsDatabase(f);
            inst.DIAGNOSTICS_DB = db;
            NoteFlowLog.INSTANCE.record("test:disabled", PLAYER, new ItemID((short) 1), 1L, null);
            await(db.flush(), WAIT_MS);
            boolean off = db.isDisabled();
            NoteFlowLog.INSTANCE.record("test:disabled", PLAYER, new ItemID((short) 1), 2L, null);
            inst.DIAGNOSTICS_DB = null;
            NoteFlowLog.INSTANCE.record("test:disabled", null, null, 3L, null); // no DB, null item: still no throw
            db.close();
            if (!off) return fail("instance not disabled");
            if (Files.exists(f)) return fail("file created");
            return pass("disabled and missing DB: record is a no-op, no exception");
        } catch (Exception e) {
            return fail(e.toString());
        } finally {
            inst.DIAGNOSTICS_DB = live;
        }
    }

    private TestResult converter(DiagnosticsDatabase db, Path f) throws Exception {
        ItemID money = MoneyItem.getItemID();
        if (!money.isValid()) return skip("money ItemID not synced yet");
        NoteFlowLog.converter(true, PLAYER, Map.of(money, 3L), "test");
        NoteFlowLog.converter(false, PLAYER, Map.of(money, 2L), "test");
        await(db.flush(), WAIT_MS);
        List<Map<String, Object>> rows = query(f, "SELECT source, amount FROM NoteFlow WHERE player_uuid = ? ORDER BY id", PLAYER.toString());
        if (rows.size() != 2) return fail("rows " + rows);
        if (!NoteFlowLog.CONVERTER_IN.equals(rows.get(0).get("source")) || num(rows.get(0).get("amount")) != -3)
            return fail("converter_in " + rows.get(0));
        if (!NoteFlowLog.CONVERTER_OUT.equals(rows.get(1).get("source")) || num(rows.get(1).get("amount")) != 2)
            return fail("converter_out " + rows.get(1));
        return pass("converter_in -3, converter_out +2");
    }
}
