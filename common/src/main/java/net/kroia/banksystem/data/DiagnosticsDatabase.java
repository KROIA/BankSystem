package net.kroia.banksystem.data;

import net.kroia.banksystem.BankSystemModBackend;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Task #123 — write-only SQLite file for the external audit tool,
 * {@code <world>/data/BankSystem/Database/banksystem_diagnostics.db}, on master, slaves and single servers.
 * Task #130 — add-ons reuse the writer for their own file through
 * {@link #DiagnosticsDatabase(Path, String, String, List)}, which takes their thread name, schema version and
 * tables; such a file gets only Meta plus those tables, never the BankSystem ones or their migration.
 * <p>
 * Gameplay never reads it; deleting, losing or corrupting it never changes game state. It has its own
 * connection and its own daemon thread ({@value #THREAD_NAME} for the BankSystem file), never the server
 * thread or the {@code banksystem-db-worker}. Calls enqueue and return at once; a full queue drops the write
 * (counted, one warning per session). The worker commits once per drained batch. The file is opened on the
 * first write: a corrupt file is renamed {@code .corrupt-<ms>} and recreated empty; any other open failure,
 * or a failed recreate, disables the instance for the session. Nothing here throws to callers.
 * <p>
 * Every open writes Meta {@code created_ms} (first open only) and {@code schema_version}. Any other Meta row,
 * such as a last-save time, is the caller's job through {@link #setMeta(String, String)}; BankSystem writes
 * {@value #META_BANK_LAST_SAVE_MS} after each bank save.
 */
public final class DiagnosticsDatabase {
    public static final String FILE_NAME = "banksystem_diagnostics.db";
    public static final String THREAD_NAME = "banksystem-diag-db";
    /** Version of the BankSystem file's schema; later units bump it when they add tables. */
    public static final String SCHEMA_VERSION = "1";
    public static final String META_BANK_LAST_SAVE_MS = "bank_last_save_ms";
    public static final int DEFAULT_QUEUE_CAPACITY = 10_000;
    /** Commit at least every this many statements while the queue never runs empty. */
    private static final int MAX_BATCH = 500;
    private static final long CLOSE_DRAIN_MS = 2_000L;
    private static final String TAG = "[DiagnosticsDatabase] ";

    /** Every file's first statement, before its own schema. */
    private static final String META_TABLE = "CREATE TABLE IF NOT EXISTS Meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)";
    /** The BankSystem file's tables, run in order after Meta on every open; later units append their CREATE ... IF NOT EXISTS here. */
    private static final List<String> BANK_SCHEMA = List.of(
            // Task #124 (B2): slaves only; follow-up rows leave direction / path / items_json NULL.
            "CREATE TABLE IF NOT EXISTS TransferJournal (id INTEGER PRIMARY KEY AUTOINCREMENT, transfer_id TEXT NOT NULL, "
                    + "ts INTEGER NOT NULL, direction TEXT, path TEXT, step TEXT NOT NULL, account_number INTEGER, "
                    + "player_uuid TEXT, items_json TEXT, ledger_type INTEGER, context TEXT, detail TEXT)",
            "CREATE INDEX IF NOT EXISTS TransferJournal_tid ON TransferJournal(transfer_id)",
            // Task #124 (B5): every role. Villager columns: NOTE_FLOW_ADDED.
            "CREATE TABLE IF NOT EXISTS NoteFlow (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, "
                    + "source TEXT NOT NULL, player_uuid TEXT, item_id INTEGER NOT NULL, amount INTEGER NOT NULL, context TEXT)");
    /**
     * NoteFlow columns added after the table reached dev worlds: the trading villager, null for other sources.
     * Every open adds the missing ones, so new and old files take the same path.
     */
    private static final List<String> NOTE_FLOW_ADDED = List.of("villager_uuid TEXT", "dimension TEXT", "x INTEGER", "y INTEGER", "z INTEGER");

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;

    private final Path file;
    private final String threadName;
    private final String schemaVersion;
    private final List<String> schema;
    /** BankSystem file only: the NoteFlow column migration. */
    private final boolean bankMigration;
    private final ThreadPoolExecutor executor;
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean disabled;
    private volatile boolean overflowWarned;
    // Worker thread only.
    private @Nullable Connection connection;
    private int uncommitted;
    private boolean writeFailWarned;

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    /** @return the live instance, or null when none is open (client, before start, after stop) */
    public static @Nullable DiagnosticsDatabase live() {
        return BACKEND_INSTANCES == null ? null : BACKEND_INSTANCES.DIAGNOSTICS_DB;
    }

    /** Context text for the diagnostics tables: {@code "<dimension> x y z"}. */
    public static String at(@Nullable Level level, BlockPos pos) {
        return (level == null ? "?" : level.dimension().location().toString()) + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ(); // mc<=1.21.10
        //return (level == null ? "?" : level.dimension().identifier().toString()) + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ(); // mc>=1.21.11
    }

    /** The BankSystem file: Meta, TransferJournal and NoteFlow, thread {@value #THREAD_NAME}. */
    public DiagnosticsDatabase(Path file) {
        this(file, DEFAULT_QUEUE_CAPACITY);
    }

    /** @param queueCapacity max queued writes before new ones are dropped (tests use a small value) */
    public DiagnosticsDatabase(Path file, int queueCapacity) {
        this(file, THREAD_NAME, SCHEMA_VERSION, BANK_SCHEMA, true, queueCapacity);
    }

    /**
     * Own diagnostics file: always a Meta table (created_ms, schema_version = schemaVersion), then
     * {@code schema} run in order on every open (CREATE ... IF NOT EXISTS). Same writer semantics: own daemon
     * thread named threadName, bounded queue, corrupt → renamed and recreated, never throws to callers.
     */
    public DiagnosticsDatabase(Path file, String threadName, String schemaVersion, List<String> schema) {
        this(file, threadName, schemaVersion, schema, false, DEFAULT_QUEUE_CAPACITY);
    }

    private DiagnosticsDatabase(Path file, String threadName, String schemaVersion, List<String> schema,
                                boolean bankMigration, int queueCapacity) {
        this.file = file.toAbsolutePath();
        this.threadName = Objects.requireNonNull(threadName, "threadName");
        this.schemaVersion = Objects.requireNonNull(schemaVersion, "schemaVersion");
        this.schema = List.copyOf(Objects.requireNonNull(schema, "schema"));
        this.bankMigration = bankMigration;
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity), r -> {
            Thread t = new Thread(r, this.threadName);
            t.setDaemon(true);
            return t;
        }) {
            @Override
            protected void terminated() {
                closeConnection(); // every job has finished, so nothing else uses the connection
            }
        };
    }

    /** Enqueues one write statement with {@code ?} parameters; returns at once. */
    public void execute(String sql, Object... params) {
        enqueue(() -> {
            Connection c = open();
            if (c == null) return;
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
                ps.executeUpdate();
            } catch (SQLException e) {
                writeFailed(e);
            }
        });
    }

    /** Upserts one Meta row. */
    public void setMeta(String key, String value) {
        execute("INSERT OR REPLACE INTO Meta (key, value) VALUES (?, ?)", key, value);
    }

    /**
     * @return completes on the writer thread, with that thread's name, once every earlier write is committed;
     *         completes at once with {@code null} when the write cannot be queued (disabled, closed, full)
     */
    public CompletableFuture<String> flush() {
        CompletableFuture<String> done = new CompletableFuture<>();
        if (!enqueue(() -> {
            commit();
            done.complete(Thread.currentThread().getName());
        }))
            done.complete(null);
        return done;
    }

    /** Drains the queue for up to {@value #CLOSE_DRAIN_MS} ms, then the connection is committed and closed. */
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(CLOSE_DRAIN_MS, TimeUnit.MILLISECONDS))
                warn(TAG + "writer did not drain within " + CLOSE_DRAIN_MS + " ms at shutdown; dropping "
                        + executor.shutdownNow().size() + " queued write(s)");
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public Path getFile() {
        return file;
    }

    public boolean isDisabled() {
        return disabled;
    }

    /** @return writes dropped because the queue was full */
    public long dropped() {
        return dropped.get();
    }

    /**
     * Plain connection (auto-commit on) to {@code file}. Loads the driver like {@link DatabaseManager} and
     * calls it directly, so it also works on slaves where nothing registered the driver. The tests use it
     * to read the file.
     */
    public static Connection connect(Path file) throws SQLException {
        Driver driver;
        try {
            Class<?> cls;
            try {
                cls = ClassLoader.getSystemClassLoader().loadClass("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                cls = DiagnosticsDatabase.class.getClassLoader().loadClass("org.sqlite.JDBC");
            }
            driver = (Driver) cls.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new SQLException("SQLite JDBC driver not found", e);
        }
        Connection c = driver.connect("jdbc:sqlite:" + file.toAbsolutePath(), new Properties());
        if (c == null) throw new SQLException("SQLite JDBC driver rejected " + file);
        return c;
    }

    // ------------------------------------------------------------------ worker side

    private boolean enqueue(Runnable job) {
        if (disabled) return false;
        try {
            executor.execute(() -> {
                job.run();
                if (++uncommitted >= MAX_BATCH || executor.getQueue().isEmpty()) commit();
            });
            return true;
        } catch (RejectedExecutionException e) {
            if (!executor.isShutdown()) {
                dropped.incrementAndGet();
                if (!overflowWarned) {
                    overflowWarned = true;
                    warn(TAG + "write queue full; dropping diagnostics writes. Further drops this session "
                            + "are counted, not logged.");
                }
            }
            return false;
        }
    }

    /** Opens the file on first use. @return the connection, or null when disabled */
    private @Nullable Connection open() {
        if (connection != null || disabled) return connection;
        try {
            connection = connectAndInit();
        } catch (Exception e) {
            if (!isCorrupt(e)) return disable("cannot open " + file + ": " + e.getMessage());
            Path moved = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
            try {
                Files.move(file, moved);
                connection = connectAndInit();
            } catch (Exception e2) {
                return disable(file + " is corrupt and could not be recreated: " + e2.getMessage());
            }
            warn(TAG + file + " was not a readable SQLite database (" + e.getMessage() + "); moved it to "
                    + moved.getFileName() + " and created an empty one");
        }
        return connection;
    }

    private Connection connectAndInit() throws Exception {
        Files.createDirectories(file.getParent());
        Connection c = connect(file);
        try {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(META_TABLE);
                for (String sql : schema) st.execute(sql);
                if (bankMigration) {
                    addMissingColumns(c, "NoteFlow", NOTE_FLOW_ADDED);
                    st.execute("CREATE INDEX IF NOT EXISTS NoteFlow_villager ON NoteFlow(villager_uuid)");
                }
                st.execute("INSERT OR IGNORE INTO Meta (key, value) VALUES ('created_ms', '" + System.currentTimeMillis() + "')");
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO Meta (key, value) VALUES ('schema_version', ?)")) {
                ps.setString(1, schemaVersion); // caller-supplied text
                ps.executeUpdate();
            }
            c.commit();
            return c;
        } catch (Exception e) {
            try {
                c.close();
            } catch (SQLException ignored) {
            }
            throw e;
        }
    }

    /** {@code ALTER TABLE ADD COLUMN} for each {@code "name TYPE"} the table lacks. */
    private static void addMissingColumns(Connection c, String table, List<String> columns) throws SQLException {
        Set<String> have = new HashSet<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) have.add(rs.getString("name"));
        }
        try (Statement st = c.createStatement()) {
            for (String col : columns)
                if (!have.contains(col.substring(0, col.indexOf(' '))))
                    st.execute("ALTER TABLE " + table + " ADD COLUMN " + col);
        }
    }

    private static boolean isCorrupt(Exception e) {
        if (!(e instanceof SQLException se)) return false;
        int code = se.getErrorCode() & 0xFF; // primary code of an extended one
        String msg = String.valueOf(se.getMessage());
        return code == 11 || code == 26 || msg.contains("SQLITE_CORRUPT") || msg.contains("SQLITE_NOTADB");
    }

    private @Nullable Connection disable(String why) {
        disabled = true;
        warn(TAG + "diagnostics database disabled for this session: " + why);
        return null;
    }

    private void commit() {
        uncommitted = 0;
        Connection c = connection;
        if (c == null) return;
        try {
            c.commit();
        } catch (SQLException e) {
            writeFailed(e);
            try {
                c.rollback();
            } catch (SQLException ignored) {
            }
        }
    }

    private void closeConnection() {
        Connection c = connection;
        connection = null;
        if (c == null) return;
        try {
            c.commit();
        } catch (SQLException e) {
            writeFailed(e);
        }
        try {
            c.close();
        } catch (SQLException ignored) {
        }
    }

    private void writeFailed(SQLException e) {
        if (writeFailWarned) return;
        writeFailWarned = true;
        warn(TAG + "write to " + file.getFileName() + " failed (further failures this session are not logged): "
                + e.getMessage());
    }

    private static void warn(String msg) {
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) BACKEND_INSTANCES.LOGGER.warn(msg);
    }
}
