package net.kroia.banksystem.util;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.modutilities.persistence.DataMigration;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * BS-274: one data version for all BankSystem world data, stored in {@code data/BankSystem/data_version.json}.
 * {@link #migrate} runs once per server start at {@code SERVER_BEFORE_START} (master, slave and singleplayer),
 * before anything opens a BankSystem file. ModUtilities' {@link DataMigration} zips the data first
 * ({@code data/BankSystem_backups/banksystem-data-v<old>-backup-<time>.zip}), runs the missing steps and refuses a save
 * from a newer BankSystem ({@link DataMigration.DataVersionTooNewException}).
 * <p>
 * A new step is needed whenever a format change would be misread by the code before it; append it to
 * the step list in {@link #migrate}. Every step must be safe to run again and must skip files a role doesn't have.
 */
public final class BankSystemDataMigration {

    public static final Path MARKER = BankSystemDataHandler.BASE_PATH.resolve("data_version.json");
    /**
     * Backup zips go next to data/BankSystem, not into it (nor into any other data path): a later migration
     * would zip the earlier zips again, and exports and folder backups would grow.
     */
    public static final Path BACKUP_DIR = Path.of("data", "BankSystem_backups");
    /** Pre-2.0 data folder; moved into {@link BankSystemDataHandler#BASE_PATH} by step 1. */
    static final Path OLD_DATA_PATH = Path.of("Finance", "BankSystem");
    /** Early 2.1 share symbol folder; moved into {@code data/BankSystem/share_symbols} by step 1. */
    static final Path OLD_SHARE_SYMBOLS_PATH = Path.of("banksystem", "share_symbols");

    private BankSystemDataMigration() {
    }

    /**
     * Brings the BankSystem data under {@code worldRoot} to the current data version. The in-game tests call it
     * on a synthetic world folder.
     *
     * @throws DataMigration.DataVersionTooNewException saved by a newer BankSystem; nothing was touched
     * @throws IOException marker unreadable, backup failed or a step failed (the message names the backup zip)
     */
    public static void migrate(Path worldRoot, BankSystemLogger log) throws IOException {
        DataMigration.migrate(BankSystemMod.MOD_ID, BankSystemMod.VERSION, worldRoot, worldRoot.resolve(MARKER),
                worldRoot.resolve(BACKUP_DIR),
                List.of(worldRoot.resolve(BankSystemDataHandler.BASE_PATH),
                        worldRoot.resolve(OLD_DATA_PATH),
                        worldRoot.resolve(OLD_SHARE_SYMBOLS_PATH.getParent())),
                List.of(new DataMigration.Step(1, "2.2.0", () -> step1(worldRoot, log))));
    }

    /** The backup zips in {@link #BACKUP_DIR}; empty when there are none. */
    public static Set<Path> backupZips(Path worldRoot) {
        Path dir = worldRoot.resolve(BACKUP_DIR);
        if (!Files.isDirectory(dir))
            return Set.of();
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(p -> p.getFileName().toString().endsWith(".zip")).collect(Collectors.toSet());
        } catch (IOException e) {
            return Set.of();
        }
    }

    /**
     * Start-up refusal after a failed migration (not a too-new save). {@code backupZip} is the zip this run
     * made before the first step, or null when the run failed before that (then nothing was changed).
     */
    public static final class Failed extends BankSystemStartupAbortException {
        public final @Nullable Path backupZip;

        public Failed(String report, @Nullable Path backupZip) {
            super(report);
            this.backupZip = backupZip;
        }
    }

    /**
     * Step 1, pre-2.2.0 to 2.2.0. Each part skips what isn't on disk (slaves have only settings.json,
     * Meta_data.nbt, the diagnostics DB and share_symbols; parts of old saves may be missing).
     * <ol>
     *   <li>{@code Finance/BankSystem} into {@code data/BankSystem} (newer file wins), then the old folder is deleted.
     *       Before 2.2.0 this ran on every load and save (BS-276).</li>
     *   <li>{@code banksystem/share_symbols} to {@code data/BankSystem/share_symbols}.</li>
     *   <li>{@code banksystem.db}: missing columns of PayoutHistory, TransactionLog (with the legacy kind to
     *       type_code backfill) and SuspicionFlag ({@link DatabaseManager#migrateColumns}).</li>
     *   <li>{@code settings.json}: Placeholder.* and Utilities.SYNC_ADMIN_FLAG_WITH_PERMISSION into Integrations
     *       ({@link BankSystemModSettings#migrateIntegrations}).</li>
     * </ol>
     * Covered by load-time readers instead (they need the item registry or the managers, or are part of file
     * creation): ItemIDs formatVersion and repairs, Bank_data.dat (OldBankDataLoader), the legacy NBT keys
     * (ShareVisuals iconPresetId/tint, account createdAt/creatorUUID, ShareStamper AllowHopperRedeem,
     * CompanyManager stamper_bindings) and the diagnostics DB NoteFlow columns (added by the open that also
     * creates a new file).
     */
    static void step1(Path worldRoot, BankSystemLogger log) throws Exception {
        Path data = worldRoot.resolve(BankSystemDataHandler.BASE_PATH);
        moveOldDataFolder(worldRoot.resolve(OLD_DATA_PATH), data, log);
        moveShareSymbols(worldRoot.resolve(OLD_SHARE_SYMBOLS_PATH), data.resolve("share_symbols"), log);
        migrateDatabase(worldRoot.resolve(DatabaseManager.DATABASE_PATH).resolve(DatabaseManager.FILE_NAME), log);
        Path settings = data.resolve("settings.json");
        if (Files.isRegularFile(settings) && BankSystemModSettings.migrateIntegrations(settings))
            log.info("[DataMigration] Moved the Placeholder settings and SYNC_ADMIN_FLAG_WITH_PERMISSION into the Integrations group.");
    }

    /**
     * Copies each old file unless the file in data/BankSystem is at least as new: data/BankSystem is what every
     * version since the move writes, so a leftover old folder (failed delete) never overwrites live data.
     * Attributes are copied, so a re-run skips what it already copied. A failed delete only warns: everything
     * was copied, and nothing reads the old folder any more.
     */
    private static void moveOldDataFolder(Path oldDir, Path newDir, BankSystemLogger log) throws IOException {
        if (!Files.isDirectory(oldDir))
            return;
        List<Path> files;
        try (Stream<Path> walk = Files.walk(oldDir)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        int copied = 0;
        int kept = 0;
        for (Path f : files) {
            Path target = newDir.resolve(oldDir.relativize(f).toString());
            if (Files.exists(target) && Files.getLastModifiedTime(target).compareTo(Files.getLastModifiedTime(f)) >= 0) {
                kept++;
                continue;
            }
            Files.createDirectories(target.getParent());
            Files.copy(f, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            copied++;
        }
        log.info("[DataMigration] Moved old data from " + oldDir + " to " + newDir + ": " + copied
                + " file(s) copied, " + kept + " kept because the file in the new folder is newer.");
        try {
            BankSystemDataHandler.deleteFolderContents(oldDir);
            Files.delete(oldDir);
        } catch (IOException e) {
            log.warn("[DataMigration] Could not delete the old data folder " + oldDir + " (" + e
                    + "). Its files are already in " + newDir + "; it is not read any more and can be deleted by hand.");
        }
    }

    /** Moves the whole folder; when the new one already exists it wins and the old one stays (warned). */
    private static void moveShareSymbols(Path oldDir, Path newDir, BankSystemLogger log) throws IOException {
        if (!Files.isDirectory(oldDir))
            return;
        if (Files.exists(newDir)) {
            log.warn("[DataMigration] Both " + oldDir + " and " + newDir + " exist; keeping " + newDir
                    + ". The old folder is not read any more.");
            return;
        }
        Files.createDirectories(newDir.getParent());
        Files.move(oldDir, newDir);
        try {
            Files.deleteIfExists(oldDir.getParent()); // the legacy "banksystem" folder, only when empty
        } catch (IOException ignored) {
            // not empty: leave it
        }
        log.info("[DataMigration] Moved the share symbols from " + oldDir + " to " + newDir + ".");
    }

    private static void migrateDatabase(Path db, BankSystemLogger log) throws Exception {
        if (!Files.isRegularFile(db))
            return;
        Connection c = DatabaseManager.newSqliteDriver().connect("jdbc:sqlite:" + db.toAbsolutePath(), new Properties());
        if (c == null)
            throw new SQLException("SQLite JDBC driver rejected " + db);
        try (c) {
            c.setAutoCommit(false);
            List<String> added = DatabaseManager.migrateColumns(c);
            c.commit();
            if (!added.isEmpty())
                log.info("[DataMigration] " + db.getFileName() + ": added columns " + added);
        }
    }
}
