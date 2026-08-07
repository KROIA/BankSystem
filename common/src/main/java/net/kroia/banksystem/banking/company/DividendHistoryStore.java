package net.kroia.banksystem.banking.company;

import net.kroia.banksystem.util.BankSystemLogger;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Task #52 (v2.0.8) — SQLite store for dividend distribution events.
 * Master-only; {@code DIVIDEND_HISTORY_STORE} is null on slaves.
 * All DB access is serialised on a single background thread; every
 * public method is fail-open (catches {@link Throwable}, logs WARN).
 */
public final class DividendHistoryStore {
    private static final String TAG = "[DividendHistoryStore] ";

    private final @Nullable BankSystemLogger logger;
    private Connection connection;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "banksystem-dividend-hist");
        t.setDaemon(true);
        return t;
    });

    public DividendHistoryStore(@Nullable BankSystemLogger logger) {
        this.logger = logger;
    }

    public void open(Path worldDir) {
        try {
            Path dbDir = worldDir.resolve("data").resolve("BankSystem");
            Files.createDirectories(dbDir);
            Path dbFile = dbDir.resolve("dividend_history.db");
            try { Class.forName("org.sqlite.JDBC"); } catch (ClassNotFoundException ignored) {}
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS dividend_events (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "company_id INTEGER NOT NULL," +
                        "schedule_id INTEGER," +
                        "timestamp_ms BIGINT NOT NULL," +
                        "currency_short SMALLINT NOT NULL," +
                        "per_share_raw BIGINT NOT NULL," +
                        "total_raw BIGINT NOT NULL," +
                        "holder_count INTEGER NOT NULL," +
                        "source_kind TEXT NOT NULL)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_dividend_company_time " +
                        "ON dividend_events(company_id, timestamp_ms DESC)");
            }
            if (logger != null) logger.info(TAG + "opened at " + dbFile);
        } catch (Throwable t) {
            if (logger != null) logger.warn(TAG + "failed to open: " + t.getMessage());
        }
    }

    public void insert(DividendEvent event) {
        executor.submit(() -> {
            try {
                if (connection == null || connection.isClosed()) return;
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO dividend_events (company_id, schedule_id, timestamp_ms, currency_short, per_share_raw, total_raw, holder_count, source_kind) VALUES (?,?,?,?,?,?,?,?)")) {
                    ps.setInt(1, event.companyId());
                    if (event.scheduleId() != null) ps.setInt(2, event.scheduleId());
                    else ps.setNull(2, Types.INTEGER);
                    ps.setLong(3, event.timestampMs());
                    ps.setShort(4, event.currencyShort());
                    ps.setLong(5, event.perShareRaw());
                    ps.setLong(6, event.totalRaw());
                    ps.setInt(7, event.holderCount());
                    ps.setString(8, event.sourceKind());
                    ps.executeUpdate();
                }
            } catch (Throwable t) {
                if (logger != null) logger.warn(TAG + "insert failed: " + t.getMessage());
            }
        });
    }

    public List<DividendEvent> listByCompany(int companyId, int limit) {
        try {
            return executor.submit(() -> {
                List<DividendEvent> out = new ArrayList<>();
                if (connection == null || connection.isClosed()) return out;
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT company_id, schedule_id, timestamp_ms, currency_short, per_share_raw, total_raw, holder_count, source_kind " +
                        "FROM dividend_events WHERE company_id=? ORDER BY timestamp_ms DESC LIMIT ?")) {
                    ps.setInt(1, companyId);
                    ps.setInt(2, limit);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            int sid = rs.getInt("schedule_id");
                            Integer scheduleId = rs.wasNull() ? null : sid;
                            out.add(new DividendEvent(
                                    rs.getInt("company_id"),
                                    scheduleId,
                                    rs.getLong("timestamp_ms"),
                                    rs.getShort("currency_short"),
                                    rs.getLong("per_share_raw"),
                                    rs.getLong("total_raw"),
                                    rs.getInt("holder_count"),
                                    rs.getString("source_kind")));
                        }
                    }
                } catch (Throwable t) {
                    if (logger != null) logger.warn(TAG + "listByCompany failed: " + t.getMessage());
                }
                return out;
            }).get(5, TimeUnit.SECONDS);
        } catch (Throwable t) {
            if (logger != null) logger.warn(TAG + "listByCompany timeout/error: " + t.getMessage());
            return List.of();
        }
    }

    public void close() {
        executor.shutdown();
        try { executor.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        try { if (connection != null && !connection.isClosed()) connection.close(); } catch (Throwable ignored) {}
        if (logger != null) logger.info(TAG + "closed");
    }
}
