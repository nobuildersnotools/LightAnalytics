package org.carrotcraft.lightAnalytics.storage;

import org.slf4j.Logger;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Owns the embedded SQLite connection and serializes all database access onto a
 * single background thread. SQLite permits only one writer at a time, so routing
 * every read and write through one executor both guarantees thread-safe use of
 * the single {@link Connection} and keeps Velocity event/scheduler threads free
 * of blocking JDBC work.
 *
 * <p>Callers never touch the {@link Connection} directly. Writes go through
 * {@link #write(SqlConsumer)} (fire-and-forget) and reads through
 * {@link #read(SqlFunction)} (submit-and-wait).
 */
public final class Database implements AutoCloseable {

    /** A unit of work that uses the connection and may throw {@link SQLException}. */
    @FunctionalInterface
    public interface SqlConsumer {
        void accept(Connection connection) throws SQLException;
    }

    /** A query that uses the connection, returns a value, and may throw {@link SQLException}. */
    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }

    private static final String SCHEMA_RESOURCE = "/schema.sql";

    /**
     * How long a {@link #read} waits for the single database thread before giving
     * up. A read can queue behind a write or a compaction pass, so this is generous;
     * its purpose is to stop a wedged database thread from blocking a caller (an HTTP
     * worker from the bounded web pool, or a Velocity event/command thread) forever.
     */
    private static final long READ_TIMEOUT_SECONDS = 30L;

    private final Logger logger;
    private final Path databaseFile;
    private final ExecutorService writer =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "LightAnalytics-DB");
                thread.setDaemon(true);
                return thread;
            });

    private Connection connection;

    public Database(Logger logger, Path dataDirectory) {
        this.logger = logger;
        this.databaseFile = dataDirectory.resolve("analytics.db");
    }

    /**
     * Opens the connection, applies pragmas, and runs the schema. Must be called
     * once before any {@link #write}/{@link #read}.
     */
    public void open() throws SQLException, IOException {
        Files.createDirectories(databaseFile.getParent());
        // Instantiate the driver's DataSource directly rather than going through
        // DriverManager: Velocity isolates each plugin in its own classloader, so
        // DriverManager's service-based discovery (run under the system classloader)
        // never finds the shaded org.sqlite driver ("No suitable driver found").
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + databaseFile);
        connection = dataSource.getConnection();
        applySchema();
        logger.info("LightAnalytics database ready at {}", databaseFile);
    }

    private void applySchema() throws SQLException, IOException {
        String schema;
        try (InputStream in = Database.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IOException("Missing bundled resource " + SCHEMA_RESOURCE);
            }
            schema = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Statement statement = connection.createStatement()) {
            for (String sql : schema.split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        }
    }

    /** Submits a write to the background thread. Failures are logged, not thrown. */
    public void write(SqlConsumer work) {
        writer.execute(() -> {
            try {
                work.accept(connection);
            } catch (SQLException e) {
                logger.error("Database write failed", e);
            }
        });
    }

    /**
     * Runs a query on the background thread and blocks until it returns, up to
     * {@link #READ_TIMEOUT_SECONDS}. A timeout throws rather than hanging the
     * caller; the queued task is cancelled if it has not yet started.
     */
    public <T> T read(SqlFunction<T> work) {
        Callable<T> task = () -> work.apply(connection);
        Future<T> future = writer.submit(task);
        try {
            return future.get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Database read failed", e.getCause());
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("Database read timed out after " + READ_TIMEOUT_SECONDS + "s", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during database read", e);
        }
    }

    /**
     * Drains pending work, then closes the connection. Pending writes submitted
     * before this call (e.g. shutdown session finalization) are allowed to run.
     */
    @Override
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warn("Timed out waiting for pending database writes");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                logger.error("Failed to close database connection", e);
            }
        }
    }
}
