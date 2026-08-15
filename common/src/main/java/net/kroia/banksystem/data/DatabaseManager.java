package net.kroia.banksystem.data;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.util.BankSystemLogger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;


public class DatabaseManager {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    private Connection connection;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "banksystem-db-worker");
        t.setDaemon(true);
        return t;
    });

    public static final Path DATABASE_PATH = Path.of("data", "BankSystem", "Database");

    // ----- Task #42 (v2.0.7) — external-backup pause/resume state -----

    /**
     * Backup-pause state exposed to callers. IDLE = writes flow normally,
     * PAUSED = the db-worker is blocked at the head of its queue and no
     * further tasks execute until {@link #endBackupPause()} is called (or
     * the safety timeout fires).
     */
    public enum BackupState { IDLE, PAUSED }

    /** Default max time a pause session may hold the worker before the safety timer
     *  auto-resumes to prevent a permanent softlock. Overridable per-instance for
     *  tests via {@link #setPauseSafetyTimeoutMillis(long)}. */
    public static final long DEFAULT_PAUSE_SAFETY_TIMEOUT_MS = 120_000L;

    private volatile long pauseSafetyTimeoutMs = DEFAULT_PAUSE_SAFETY_TIMEOUT_MS;

    private final AtomicReference<CountDownLatch> currentPauseLatch = new AtomicReference<>();
    private final AtomicLong pauseStartMs = new AtomicLong();

    private final ScheduledExecutorService pauseTimer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "banksystem-backup-safety-timer");
        t.setDaemon(true);
        return t;
    });
    private volatile @Nullable ScheduledFuture<?> currentSafetyFuture;

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    /**
     * Classpath-resource paths of every schema file the DB layer applies at boot.
     * <p>
     * Task #44 (v2.1.0): converted from a single hardcoded path to a list so new
     * tables can be added by appending an entry. Each file is executed via
     * {@link #executeSqlFile(String)} in order; every {@code CREATE TABLE} and
     * {@code CREATE INDEX} statement in these scripts is idempotent
     * ({@code IF NOT EXISTS}), so re-running on an existing world is safe.
     */
    public static final java.util.List<String> SQL_SCHEMA_FILES = java.util.List.of(
            "/sql/BalanceHistory.sql",
            "/sql/TransactionLog.sql",
            "/sql/PayoutHistory.sql"
    );

    public boolean createDatabase(MinecraftServer server) {
        for (String path : SQL_SCHEMA_FILES) {
            try {
                executeSqlFile(path);
            } catch (SQLException | IOException e) {
                getLogger().error("Failed to create database table from " + path + ": " + e.getMessage());
                return false;
            }
        }
        migratePayoutHistoryColumns();
        migrateTransactionLogColumns();
        return true;
    }

    /**
     * Spec A.9 / B.3 / B.4 (v2.1.0) — column migration for pre-existing worlds.
     * {@code CREATE TABLE IF NOT EXISTS} does not add columns to an existing table,
     * so add the new PayoutHistory columns via {@code ALTER TABLE} when missing.
     * Idempotent: checks {@code PRAGMA table_info} first.
     */
    private void migratePayoutHistoryColumns() {
        java.util.Map<String, String> wanted = new java.util.LinkedHashMap<>();
        wanted.put("target_player_name", "TEXT NOT NULL DEFAULT ''");
        wanted.put("target_account_name", "TEXT NOT NULL DEFAULT ''");
        wanted.put("currency_item", "INTEGER NOT NULL DEFAULT 0");
        wanted.put("type", "TEXT NOT NULL DEFAULT 'NORMAL'");
        try (Statement stmt = connection.createStatement()) {
            java.util.Set<String> existing = new java.util.HashSet<>();
            try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(PayoutHistory)")) {
                while (rs.next()) existing.add(rs.getString("name"));
            }
            for (var e : wanted.entrySet()) {
                if (existing.contains(e.getKey())) continue;
                try (Statement alter = connection.createStatement()) {
                    alter.execute("ALTER TABLE PayoutHistory ADD COLUMN "
                            + e.getKey() + " " + e.getValue());
                }
                getLogger().info("[DatabaseManager] PayoutHistory migration: added column " + e.getKey());
            }
            commitTransaction();
        } catch (SQLException e) {
            getLogger().error("PayoutHistory column migration failed: " + e.getMessage());
        }
    }

    /**
     * v2.1.0 — column migration for TransactionLog.
     * Adds source_kind and tag columns when missing (pre-existing worlds).
     */
    private void migrateTransactionLogColumns() {
        java.util.Map<String, String> wanted = new java.util.LinkedHashMap<>();
        wanted.put("source_kind", "TEXT NOT NULL DEFAULT 'UNKNOWN'");
        wanted.put("tag", "TEXT");
        try (Statement stmt = connection.createStatement()) {
            java.util.Set<String> existing = new java.util.HashSet<>();
            try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(TransactionLog)")) {
                while (rs.next()) existing.add(rs.getString("name"));
            }
            for (var e : wanted.entrySet()) {
                if (existing.contains(e.getKey())) continue;
                try (Statement alter = connection.createStatement()) {
                    alter.execute("ALTER TABLE TransactionLog ADD COLUMN "
                            + e.getKey() + " " + e.getValue());
                }
                getLogger().info("[DatabaseManager] TransactionLog migration: added column " + e.getKey());
            }
            commitTransaction();
        } catch (SQLException e) {
            getLogger().error("TransactionLog column migration failed: " + e.getMessage());
        }
    }

    public void executeSqlFile(String resourcePath) throws IOException, SQLException {
        try (InputStream is = DatabaseManager.class.getResourceAsStream(resourcePath)) {
            if (is == null) throw new IOException("SQL file not found: " + resourcePath);

            String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);

            try (Statement stmt = connection.createStatement()) {
                for (String statement : sql.split(";")) {
                    String trimmed = statement.trim();
                    if (!trimmed.isEmpty()) {
                        stmt.execute(trimmed);
                    }
                }
            }
        }
    }

    public void connectToDatabase(MinecraftServer server) {
        Path worldPath = server.getWorldPath(LevelResource.ROOT);
        Path dbPath = worldPath.resolve(DATABASE_PATH);
        String url = "jdbc:sqlite:" + Path.of(String.valueOf(dbPath.toAbsolutePath()), "banksystem.db");
        Class<?> driverClass = null;
        Exception exception = null;
        try {
            driverClass = ClassLoader.getSystemClassLoader().loadClass("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            exception = e;
        }
        if (exception != null) {
            try {
                driverClass = DatabaseManager.class.getClassLoader().loadClass("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                getLogger().error("Failed to register JDBC driver", e);
                return;
            }
        }
        try {
            Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
            DriverManager.registerDriver(new DriverShim(driver));
        } catch (Exception e) {
            getLogger().error("Failed to register JDBC driver", e);
            return;
        }

        try {
            getLogger().info("Database path: " + dbPath.toAbsolutePath());
            getLogger().info("Database URL: " + url);
            if (!Files.exists(dbPath.toAbsolutePath())) {
                Files.createDirectories(dbPath.toAbsolutePath());
            }
            connection = DriverManager.getConnection(url);
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            getLogger().error("Failed to connect to database: " + e.getMessage());
            return;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (createDatabase(server)) {
            getLogger().info("Successfully connected to database " + url);
        } else {
            getLogger().error("Database connected but table creation failed for: " + url);
        }
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.commit();
                connection.close();
                getLogger().info("Successfully closed database connection");
            }
        } catch (SQLException e) {
            getLogger().error("Failed to close database connection: " + e.getMessage());
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Task #42 — release the backup safety-timer thread so the JVM can exit cleanly.
        pauseTimer.shutdown();
        try {
            if (!pauseTimer.awaitTermination(2, TimeUnit.SECONDS)) {
                pauseTimer.shutdownNow();
            }
        } catch (InterruptedException e) {
            pauseTimer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public ExecutorService getDatabaseThread() {
        return executor;
    }

    public Connection getConnection() {
        return connection;
    }

    public boolean commitTransaction() {
        try {
            connection.commit();
            return true;
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException re) {
                getLogger().error("Failed to rollback transaction: " + re.getMessage());
            }
            getLogger().error("Failed to commit transaction, rolled back: " + e.getMessage());
            return false;
        }
    }

    // ========================================================================
    // Task #42 (v2.0.7) — backup pause/resume/status/snapshot
    // ========================================================================

    /**
     * Blocks the {@code banksystem-db-worker} at the head of its queue so an
     * external process (e.g. a {@code tar} of the world directory) can copy
     * {@code banksystem.db} without racing an in-flight commit.
     * <p>
     * The returned future completes with {@code true} <em>after</em> the pause
     * task has actually started running on the worker — this is the ack primitive
     * a backup script can wait on to know the queue has drained. If a pause is
     * already active, the future completes with {@code false} immediately and
     * the worker state is unchanged.
     * <p>
     * A safety timer (see {@link #DEFAULT_PAUSE_SAFETY_TIMEOUT_MS}) auto-resumes
     * the worker if the operator forgets to call {@link #endBackupPause()},
     * warn-logging when it fires. The timer is scoped to <em>this</em> pause
     * session — a subsequent pause installs a fresh latch and any leftover
     * safety task for the previous session no-ops on its CAS attempt.
     */
    public CompletableFuture<Boolean> beginBackupPause() {
        CountDownLatch latch = new CountDownLatch(1);
        if (!currentPauseLatch.compareAndSet(null, latch)) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> ack = new CompletableFuture<>();
        getDatabaseThread().submit(() -> {
            // Belt-and-braces commit before the backup captures the file. Individual
            // write paths already commit as they go, but this ensures no pending
            // transaction is sitting in the rollback journal at snapshot time.
            try {
                Connection c = getConnection();
                if (c != null) c.commit();
            } catch (SQLException e) {
                logWarn("[BankSystem] commit before backup pause failed: " + e.getMessage());
            }
            pauseStartMs.set(System.currentTimeMillis());
            // Distinctive log line -- external backup scripts can tail-grep for this
            // to know the worker has drained.
            logWarn("[BankSystem] db-worker paused for backup");
            ack.complete(true);
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            logWarn("[BankSystem] db-worker resumed");
        });
        // Safety timer -- if the operator forgets to resume, auto-release after
        // pauseSafetyTimeoutMs. Uses compareAndSet(latch, null) so that a resume
        // followed by a re-pause (installing a new latch) makes this task a no-op:
        // the reference identity check prevents releasing an unrelated session.
        currentSafetyFuture = pauseTimer.schedule(() -> {
            if (currentPauseLatch.compareAndSet(latch, null)) {
                logWarn("[BankSystem] Backup pause exceeded " + (pauseSafetyTimeoutMs / 1000L)
                        + "s -- auto-resuming to prevent softlock");
                latch.countDown();
            }
        }, pauseSafetyTimeoutMs, TimeUnit.MILLISECONDS);
        return ack;
    }

    /**
     * Releases the current pause. Returns {@code true} if a pause was active
     * (i.e. we transitioned {@link BackupState#PAUSED} -&gt; {@link BackupState#IDLE}),
     * {@code false} if no pause was in effect.
     */
    public boolean endBackupPause() {
        CountDownLatch latch = currentPauseLatch.getAndSet(null);
        if (latch == null) return false;
        ScheduledFuture<?> f = currentSafetyFuture;
        if (f != null) f.cancel(false);
        currentSafetyFuture = null;
        latch.countDown();
        return true;
    }

    /** @return the current backup state. Cheap; safe to call from any thread. */
    public BackupState getBackupState() {
        return currentPauseLatch.get() == null ? BackupState.IDLE : BackupState.PAUSED;
    }

    /** @return millis elapsed since the current pause started, or {@code 0} if idle. */
    public long getPausedForMs() {
        if (currentPauseLatch.get() == null) return 0L;
        return System.currentTimeMillis() - pauseStartMs.get();
    }

    /**
     * Overrides the pause safety-timeout for this instance. Intended for tests
     * that want to verify auto-resume without waiting 120 seconds. Production
     * callers should not touch this.
     */
    public void setPauseSafetyTimeoutMillis(long ms) {
        this.pauseSafetyTimeoutMs = ms;
    }

    /**
     * Writes a transactionally-consistent snapshot of the live DB to
     * {@code target} using SQLite's {@code VACUUM INTO} (driver-agnostic;
     * available in SQLite 3.27+). Runs on the DB worker thread so it is
     * serialized against writes but does not need a pause -- concurrent
     * transactions are captured against VACUUM's read snapshot and land
     * in the rollback journal, invisible to the copy.
     * <p>
     * Parent directories are created if missing. The target path is
     * interpolated into a SQL literal after doubling single-quotes
     * ({@code '} -&gt; {@code ''}).
     */
    public CompletableFuture<Boolean> snapshotTo(Path target) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        getDatabaseThread().submit(() -> {
            try {
                Path abs = target.toAbsolutePath();
                Path parent = abs.getParent();
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent);
                }
                Connection c = getConnection();
                if (c == null) {
                    logError("[BankSystem] snapshot aborted: connection is null");
                    result.complete(false);
                    return;
                }
                String escaped = abs.toString().replace("'", "''");
                try (Statement stmt = c.createStatement()) {
                    stmt.execute("VACUUM INTO '" + escaped + "'");
                }
                logInfo("[BankSystem] snapshot written to " + abs);
                result.complete(true);
            } catch (SQLException | IOException e) {
                logError("[BankSystem] snapshot to " + target + " failed: " + e.getMessage());
                result.complete(false);
            } catch (RuntimeException e) {
                logError("[BankSystem] snapshot to " + target + " threw: " + e.getMessage());
                result.complete(false);
            }
        });
        return result;
    }

    // ========================================================================
    // Log helpers
    // ========================================================================

    private static BankSystemLogger getLogger() {
        return BACKEND_INSTANCES.LOGGER;
    }

    /** Null-safe wrappers for logs emitted from paths that may run without
     *  {@link BankSystemModBackend#INSTANCES} being wired (unit tests). */
    private static void logInfo(String msg) {
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
            BACKEND_INSTANCES.LOGGER.info(msg);
        }
    }
    private static void logWarn(String msg) {
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
            BACKEND_INSTANCES.LOGGER.warn(msg);
        }
    }
    private static void logError(String msg) {
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
            BACKEND_INSTANCES.LOGGER.error(msg);
        }
    }
}
