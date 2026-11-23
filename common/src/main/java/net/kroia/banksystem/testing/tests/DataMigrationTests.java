package net.kroia.banksystem.testing.tests;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.BankSystemDataHandler;
import net.kroia.banksystem.util.BankSystemDataMigration;
import net.kroia.banksystem.util.BankSystemLogger;
import net.kroia.modutilities.persistence.DataMigration;
import net.kroia.testkit.TestCategory;
import net.kroia.testkit.TestResult;
import net.kroia.testkit.TestSuite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * BS-274 / BS-276 — {@link BankSystemDataMigration} on synthetic world folders (own temp dirs, no live data):
 * a pre-2.2.0 save is backed up and upgraded once, a newer data version is refused untouched, a fresh
 * world only gets the marker, and step 2 drops the obsolete snapshot cap setting (BS-278).
 */
public class DataMigrationTests extends TestSuite {

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.LIFECYCLE;
    }

    @Override
    public boolean needsGame() {
        return false;
    }

    @Override
    public void registerTests() {
        addTest("upgrades_pre_2_2_0_save_once", this::upgradesOldSave);
        addTest("newer_data_version_refused_untouched", this::newerVersionRefused);
        addTest("fresh_world_writes_marker_only", this::freshWorld);
        addTest("removes_obsolete_snapshot_cap_setting", this::removesObsoleteSetting);
    }

    /** The mod backend sets LOGGER only when the mod starts; the offline JUnit run (:common:test) never starts it. */
    private static BankSystemLogger log() {
        BankSystemModBackend.Instances i = BankSystemModBackend.getInstances_forTesting();
        return i.LOGGER != null ? i.LOGGER : new BankSystemLogger(i);
    }

    private TestResult upgradesOldSave() {
        Path root = null;
        try {
            root = Files.createTempDirectory("banksystem-bs274");
            Path data = root.resolve(BankSystemDataHandler.BASE_PATH);
            Path old = root.resolve("Finance").resolve("BankSystem");
            write(old.resolve("Meta_data.nbt"), "old-meta");
            write(old.resolve("Bank_data").resolve("accounts.nbt"), "old-accounts");
            // BS-276: a leftover old folder must not overwrite the live (newer) file.
            write(old.resolve("ItemIDs.nbt"), "stale");
            Files.setLastModifiedTime(old.resolve("ItemIDs.nbt"), FileTime.fromMillis(1_577_836_800_000L)); // 2020
            write(data.resolve("ItemIDs.nbt"), "live");
            write(data.resolve("settings.json"), "{\"Utilities\":{\"SAVE_INTERVAL_SECONDS\":9,\"SYNC_ADMIN_FLAG_WITH_PERMISSION\":true},"
                    + "\"Placeholder\":{\"TOP_COUNT\":7}}");
            write(root.resolve("banksystem").resolve("share_symbols").resolve("x.png"), "png");
            Path db = root.resolve(DatabaseManager.DATABASE_PATH).resolve(DatabaseManager.FILE_NAME);
            createOldDatabase(db);

            BankSystemDataMigration.migrate(root, log());

            check(markerVersion(root) == 2, "marker is " + markerVersion(root) + ", expected 2");
            Path backupDir = root.resolve(BankSystemDataMigration.BACKUP_DIR);
            // The zips must sit outside every data path, or the next migration would zip them again.
            for (Path dataPath : List.of(data, root.resolve("Finance").resolve("BankSystem"), root.resolve("banksystem")))
                check(!backupDir.toAbsolutePath().normalize().startsWith(dataPath.toAbsolutePath().normalize()),
                        "backup folder " + backupDir + " is inside data path " + dataPath);
            check(backups(data).isEmpty(), "backup zip written into data/BankSystem: " + backups(data));
            check(BankSystemDataMigration.backupZips(root).equals(new HashSet<>(backups(backupDir))), "backupZips() disagrees with " + backupDir);
            List<Path> zips = backups(backupDir);
            check(zips.size() == 1, "expected one backup zip, found " + zips);
            check(zips.get(0).getFileName().toString().startsWith("banksystem-data-v0-backup-"), "zip name " + zips.get(0));
            Set<String> entries = new HashSet<>();
            try (ZipFile zip = new ZipFile(zips.get(0).toFile())) {
                zip.stream().forEach(e -> entries.add(e.getName()));
            }
            for (String e : List.of("Finance/BankSystem/Meta_data.nbt", "Finance/BankSystem/ItemIDs.nbt",
                    "banksystem/share_symbols/x.png", "data/BankSystem/settings.json", "data/BankSystem/Database/banksystem.db"))
                check(entries.contains(e), "backup lacks " + e + " (has " + entries + ")");

            check(!Files.exists(old), "Finance/BankSystem still exists");
            check("old-meta".equals(Files.readString(data.resolve("Meta_data.nbt"))), "Meta_data.nbt not moved");
            check("old-accounts".equals(Files.readString(data.resolve("Bank_data").resolve("accounts.nbt"))), "Bank_data not moved");
            check("live".equals(Files.readString(data.resolve("ItemIDs.nbt"))), "stale old ItemIDs.nbt overwrote the newer live file");

            check(Files.exists(data.resolve("share_symbols").resolve("x.png")), "share symbol not moved");
            check(!Files.exists(root.resolve("banksystem")), "empty legacy banksystem folder not removed");

            JsonObject settings = JsonParser.parseString(Files.readString(data.resolve("settings.json"))).getAsJsonObject();
            check(settings.has("Integrations") && settings.getAsJsonObject("Integrations").get("TOP_COUNT").getAsInt() == 7,
                    "Integrations.TOP_COUNT not migrated: " + settings);
            check(settings.getAsJsonObject("Integrations").get("SYNC_ADMIN_FLAG_WITH_PERMISSION").getAsBoolean(),
                    "SYNC_ADMIN_FLAG_WITH_PERMISSION not migrated: " + settings);
            check(!settings.has("Placeholder"), "Placeholder group still present");
            check(!settings.getAsJsonObject("Utilities").has("SYNC_ADMIN_FLAG_WITH_PERMISSION"), "old Utilities key still present");

            try (Connection c = connect(db); Statement st = c.createStatement()) {
                check(columns(st, "TransactionLog").containsAll(List.of("type_code", "source_kind", "origin_server", "x", "z")),
                        "TransactionLog columns " + columns(st, "TransactionLog"));
                check(columns(st, "PayoutHistory").containsAll(List.of("target_player_name", "currency_item", "type")),
                        "PayoutHistory columns " + columns(st, "PayoutHistory"));
                check(columns(st, "SuspicionFlag").containsAll(List.of("severity", "acknowledged", "details")),
                        "SuspicionFlag columns " + columns(st, "SuspicionFlag"));
                try (ResultSet rs = st.executeQuery("SELECT type_code, amount FROM TransactionLog WHERE kind = 'DEPOSIT'")) {
                    check(rs.next() && rs.getInt(1) == LedgerType.fromLegacyKind("DEPOSIT").code(), "DEPOSIT type_code not backfilled");
                }
                try (ResultSet rs = st.executeQuery("SELECT type_code, amount FROM TransactionLog WHERE kind = 'TRANSFER_OUT'")) {
                    check(rs.next() && rs.getInt(1) == 3 && rs.getLong(2) == -5, "TRANSFER_OUT not mapped to a negative TRANSFER row");
                }
            }

            // Second start: already at version 2, nothing runs and no new backup.
            String settingsOnce = Files.readString(data.resolve("settings.json"));
            BankSystemDataMigration.migrate(root, log());
            check(backups(backupDir).size() == 1, "second run made another backup: " + backups(backupDir));
            check(markerVersion(root) == 2, "marker changed on the second run");
            check(settingsOnce.equals(Files.readString(data.resolve("settings.json"))), "second run rewrote settings.json");
            return pass("pre-2.2.0 save backed up and upgraded; second run is a no-op");
        } catch (Exception e) {
            return fail(e.toString());
        } finally {
            delete(root);
        }
    }

    private TestResult newerVersionRefused() {
        Path root = null;
        try {
            root = Files.createTempDirectory("banksystem-bs274-newer");
            Path data = root.resolve(BankSystemDataHandler.BASE_PATH);
            String marker = "{\"dataVersion\": 99, \"modVersion\": \"9.9.9\"}";
            write(root.resolve(BankSystemDataMigration.MARKER), marker);
            write(root.resolve("Finance").resolve("BankSystem").resolve("Meta_data.nbt"), "old-meta");
            try {
                BankSystemDataMigration.migrate(root, log());
                return fail("no DataVersionTooNewException for data version 99");
            } catch (DataMigration.DataVersionTooNewException expected) {
                // the refusal under test
            }
            check(marker.equals(Files.readString(root.resolve(BankSystemDataMigration.MARKER))), "marker changed");
            check(backups(root.resolve(BankSystemDataMigration.BACKUP_DIR)).isEmpty(), "backup made for a refused save");
            check(Files.exists(root.resolve("Finance").resolve("BankSystem").resolve("Meta_data.nbt")), "old folder touched");
            check(!Files.exists(data.resolve("Meta_data.nbt")), "step 1 ran on a newer save");
            return pass("data version 99 refused, nothing changed");
        } catch (Exception e) {
            return fail(e.toString());
        } finally {
            delete(root);
        }
    }

    private TestResult freshWorld() {
        Path root = null;
        try {
            root = Files.createTempDirectory("banksystem-bs274-fresh");
            BankSystemDataMigration.migrate(root, log());
            check(markerVersion(root) == 2, "fresh marker is " + markerVersion(root));
            check(!Files.exists(root.resolve(BankSystemDataMigration.BACKUP_DIR)), "backup folder made for a fresh world");
            return pass("fresh world: marker 2, no backup");
        } catch (Exception e) {
            return fail(e.toString());
        } finally {
            delete(root);
        }
    }

    /** BS-278: step 2 drops Utilities.BALANCE_SNAPSHOT_MAX_RECORDS_PER_ITEM from a data-version-1 save, keeps the rest. */
    private TestResult removesObsoleteSetting() {
        Path root = null;
        try {
            root = Files.createTempDirectory("banksystem-bs278");
            Path settingsFile = root.resolve(BankSystemDataHandler.BASE_PATH).resolve("settings.json");
            write(root.resolve(BankSystemDataMigration.MARKER), "{\"dataVersion\": 1, \"modVersion\": \"2.2.0\"}");
            write(settingsFile, "{\"Utilities\":{\"SAVE_INTERVAL_SECONDS\":9,\"BALANCE_SNAPSHOT_MAX_RECORDS_PER_ITEM\":1440}}");

            BankSystemDataMigration.migrate(root, log());

            check(markerVersion(root) == 2, "marker is " + markerVersion(root) + ", expected 2");
            JsonObject utilities = JsonParser.parseString(Files.readString(settingsFile)).getAsJsonObject().getAsJsonObject("Utilities");
            check(!utilities.has("BALANCE_SNAPSHOT_MAX_RECORDS_PER_ITEM"), "obsolete key still present: " + utilities);
            check(utilities.has("SAVE_INTERVAL_SECONDS") && utilities.get("SAVE_INTERVAL_SECONDS").getAsInt() == 9,
                    "SAVE_INTERVAL_SECONDS lost: " + utilities);
            return pass("obsolete snapshot cap removed, other settings kept, marker 2");
        } catch (Exception e) {
            return fail(e.toString());
        } finally {
            delete(root);
        }
    }

    /** banksystem.db as a pre-2.2.0 world had it: the schema-file tables minus every migrated column. */
    private static void createOldDatabase(Path db) throws Exception {
        Files.createDirectories(db.getParent());
        try (Connection c = connect(db); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE TransactionLog (id INTEGER PRIMARY KEY AUTOINCREMENT, account_number INTEGER NOT NULL, "
                    + "actor_uuid TEXT, kind TEXT NOT NULL, item_id INTEGER NOT NULL, amount INTEGER NOT NULL, "
                    + "other_account INTEGER, company_id INTEGER, ts INTEGER NOT NULL, note TEXT)");
            st.execute("CREATE TABLE PayoutHistory (id INTEGER PRIMARY KEY AUTOINCREMENT, company_id INTEGER NOT NULL, "
                    + "schedule_id INTEGER NOT NULL, source_account INTEGER NOT NULL, target_uuid TEXT NOT NULL, "
                    + "amount INTEGER NOT NULL, ts INTEGER NOT NULL, status TEXT NOT NULL)");
            st.execute("CREATE TABLE SuspicionFlag (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, "
                    + "account_number INTEGER NOT NULL, flag_type INTEGER NOT NULL)");
            st.execute("INSERT INTO TransactionLog (account_number, kind, item_id, amount, ts) VALUES (1, 'DEPOSIT', 7, 10, 1)");
            st.execute("INSERT INTO TransactionLog (account_number, kind, item_id, amount, ts) VALUES (1, 'TRANSFER_OUT', 7, 5, 2)");
        }
    }

    private static Connection connect(Path db) throws Exception {
        return DatabaseManager.newSqliteDriver().connect("jdbc:sqlite:" + db.toAbsolutePath(), new Properties());
    }

    private static Set<String> columns(Statement st, String table) throws Exception {
        Set<String> cols = new HashSet<>();
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) cols.add(rs.getString("name"));
        }
        return cols;
    }

    private static int markerVersion(Path root) throws Exception {
        return JsonParser.parseString(Files.readString(root.resolve(BankSystemDataMigration.MARKER)))
                .getAsJsonObject().get("dataVersion").getAsInt();
    }

    private static List<Path> backups(Path dir) throws Exception {
        List<Path> zips = new ArrayList<>();
        if (!Files.isDirectory(dir)) return zips;
        try (Stream<Path> list = Files.list(dir)) {
            list.filter(p -> p.getFileName().toString().endsWith(".zip")).forEach(zips::add);
        }
        return zips;
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new IllegalStateException(what);
    }

    private static void delete(Path dir) {
        if (dir == null) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (Exception ignored) {
            // best effort
        }
    }
}
