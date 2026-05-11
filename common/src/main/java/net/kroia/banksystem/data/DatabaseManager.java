package net.kroia.banksystem.data;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.util.BankSystemLogger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


public class DatabaseManager {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    private Connection connection;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "banksystem-db-worker");
        t.setDaemon(true);
        return t;
    });

    public static final Path DATABASE_PATH = Path.of("data", "BankSystem", "Database");

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    public boolean createDatabase(MinecraftServer server) {
        try {
            executeSqlFile("/sql/BalanceHistory.sql");
            return true;
        } catch (SQLException | IOException e) {
            getLogger().error("Failed to create database table: " + e.getMessage());
            return false;
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

    private static BankSystemLogger getLogger() {
        return BACKEND_INSTANCES.LOGGER;
    }
}
