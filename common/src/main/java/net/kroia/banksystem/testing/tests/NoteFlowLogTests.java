package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.data.CreativeNoteFlow;
import net.kroia.banksystem.data.DiagnosticsDatabase;
import net.kroia.banksystem.data.NoteFlowLog;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static net.kroia.banksystem.testing.tests.TransferJournalTests.WAIT_MS;
import static net.kroia.banksystem.testing.tests.TransferJournalTests.num;
import static net.kroia.banksystem.testing.tests.TransferJournalTests.query;

/**
 * Task #124 (B5) — {@link NoteFlowLog}: a record writes one row, a disabled or missing DB is a no-op,
 * converter rows carry the right sign, a villager row stores UUID / dimension / x y z (null without one),
 * an old-schema file gains the villager columns on open. <b>Server type:</b> BOTH (every role writes NoteFlow).
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
        addTest("villager_row_has_position", () -> TransferJournalTests.withTempDb(dir, "villager.db", this::villager, this::fail));
        addTest("old_table_gains_villager_columns", () -> TransferJournalTests.withTempDb(dir, "migrate.db", this::migrate, this::fail));
        addTest("creative_changes_are_netted", () -> TransferJournalTests.withTempDb(dir, "creative.db", this::creative, this::fail));
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

    private TestResult villager(DiagnosticsDatabase db, Path f) throws Exception {
        MinecraftServer s = getServer();
        if (s == null) return skip("no server");
        var v = EntityType.VILLAGER.create(s.overworld()); // never added to the world // mc<=1.21.1
        //var v = EntityType.VILLAGER.create(s.overworld(), net.minecraft.world.entity.EntitySpawnReason.LOAD); // never added to the world // mc>=1.21.2 mc<=26.1
        //var v = net.minecraft.world.entity.EntityTypes.VILLAGER.create(s.overworld(), net.minecraft.world.entity.EntitySpawnReason.LOAD); // never added to the world // mc>=26.2
        if (v == null) return fail("villager not created");
        v.setPos(10.5, 64, -3.5); // block 10 64 -4
        net.kroia.banksystem.BankSystemMod.getAPI().getNoteFlowLog().record("test:villager", PLAYER, new ItemID((short) 5), 2L, v, "ctx");
        NoteFlowLog.INSTANCE.record(NoteFlowLog.CONVERTER_IN, PLAYER, new ItemID((short) 5), -1L, "ctx");
        await(db.flush(), WAIT_MS);
        List<Map<String, Object>> rows = query(f, "SELECT source, villager_uuid, dimension, x, y, z, context FROM NoteFlow "
                + "WHERE player_uuid = ? ORDER BY id", PLAYER.toString());
        if (rows.size() != 2) return fail("rows " + rows);
        Map<String, Object> r = rows.get(0), c = rows.get(1);
        if (!v.getUUID().toString().equals(r.get("villager_uuid")) || !"minecraft:overworld".equals(r.get("dimension"))
                || num(r.get("x")) != 10 || num(r.get("y")) != 64 || num(r.get("z")) != -4 || !"ctx".equals(r.get("context")))
            return fail("villager row " + r);
        if (c.get("villager_uuid") != null || c.get("dimension") != null || c.get("x") != null || c.get("y") != null
                || c.get("z") != null)
            return fail("converter row " + c);
        return pass("villager row " + r + "; converter row has null villager columns");
    }

    /** BS-227: a slot move (-5 then +5) writes nothing; +3 writes one create row after the idle time; logout flushes at once. */
    private TestResult creative(DiagnosticsDatabase db, Path f) throws Exception {
        ItemID money = new ItemID((short) 91);
        UUID p = UUID.fromString("00000000-0000-0000-0000-000000000227");
        long t = System.currentTimeMillis();
        NoteFlowLog.INSTANCE.record("test:init", null, money, 0L, null); // the DB creates its tables on the first write
        CreativeNoteFlow.add(p, money, -5, t);
        CreativeNoteFlow.add(p, money, 5, t + 1);
        CreativeNoteFlow.tick(t + 1 + CreativeNoteFlow.IDLE_MS);
        CreativeNoteFlow.add(p, money, 3, t + 2);
        CreativeNoteFlow.tick(t + 2 + CreativeNoteFlow.IDLE_MS - 1); // not idle long enough yet
        await(db.flush(), WAIT_MS);
        if (!query(f, "SELECT * FROM NoteFlow WHERE player_uuid = ?", p.toString()).isEmpty())
            return fail("rows before the idle time");
        CreativeNoteFlow.tick(t + 2 + CreativeNoteFlow.IDLE_MS);
        CreativeNoteFlow.add(p, money, -2, t + 3);
        CreativeNoteFlow.flush(p); // logout
        await(db.flush(), WAIT_MS);
        List<Map<String, Object>> rows = query(f, "SELECT source, item_id, amount FROM NoteFlow WHERE player_uuid = ? ORDER BY id", p.toString());
        if (rows.size() != 2) return fail("rows " + rows);
        if (!NoteFlowLog.CREATIVE_CREATE.equals(rows.get(0).get("source")) || num(rows.get(0).get("amount")) != 3
                || num(rows.get(0).get("item_id")) != 91)
            return fail("create row " + rows.get(0));
        if (!NoteFlowLog.CREATIVE_DESTROY.equals(rows.get(1).get("source")) || num(rows.get(1).get("amount")) != -2)
            return fail("destroy row " + rows.get(1));
        return pass("move netted to nothing, +3 after idle, -2 on logout");
    }

    private TestResult migrate(DiagnosticsDatabase db, Path f) throws Exception {
        try (Connection c = DiagnosticsDatabase.connect(f); Statement st = c.createStatement()) { // NoteFlow as of 962cb545
            st.execute("CREATE TABLE NoteFlow (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, source TEXT NOT NULL, "
                    + "player_uuid TEXT, item_id INTEGER NOT NULL, amount INTEGER NOT NULL, context TEXT)");
            st.execute("INSERT INTO NoteFlow (ts, source, item_id, amount) VALUES (1, 'test:old', 1, 1)");
        }
        NoteFlowLog.INSTANCE.record("test:new", PLAYER, new ItemID((short) 1), 1L, null); // first write opens and migrates
        await(db.flush(), WAIT_MS);
        Set<String> cols = new HashSet<>();
        for (Map<String, Object> r : query(f, "PRAGMA table_info(NoteFlow)")) cols.add(String.valueOf(r.get("name")));
        if (!cols.containsAll(List.of("villager_uuid", "dimension", "x", "y", "z"))) return fail("columns " + cols);
        List<Map<String, Object>> rows = query(f, "SELECT source FROM NoteFlow ORDER BY id");
        if (rows.size() != 2 || !"test:old".equals(rows.get(0).get("source")) || !"test:new".equals(rows.get(1).get("source")))
            return fail("rows " + rows);
        if (query(f, "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'NoteFlow_villager'").size() != 1)
            return fail("index NoteFlow_villager missing");
        return pass("old table gained " + cols + ", old row kept, new row written, index present");
    }
}
