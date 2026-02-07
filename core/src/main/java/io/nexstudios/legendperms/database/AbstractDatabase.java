package io.nexstudios.legendperms.database;


import io.nexstudios.legendperms.LegendPerms;
import io.nexstudios.legendperms.database.model.DatabaseTypes;
import io.nexstudios.legendperms.utils.LegendLogger;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * An abstract class representing the base implementation for database management.
 * This class is designed to handle database connections, asynchronous SQL execution,
 * and monitoring to ensure database connectivity is maintained. It must be extended
 * by specific database implementations.
 */
@Slf4j
public abstract class AbstractDatabase {

    protected final LegendPerms plugin;
    protected final LegendLogger logger;

    /**
     * A {@code ScheduledExecutorService} used for scheduling and executing periodic database monitoring tasks
     * in the background. This scheduler is configured with a single-threaded thread pool and creates daemon
     * threads for its tasks, ensuring that the executor does not block JVM termination when active.
     * <p>
     * The thread created by this scheduler is named "legendperms-db-monitor" to aid in debugging and
     * monitoring. The daemon nature of the threads ensures that the scheduled tasks do not prevent
     * the program from shutting down.
     * <p>
     * This scheduler is primarily used for tasks such as monitoring the state of the database connection
     * or handling periodic maintenance tasks associated with the database.
     * <p>
     * Note:
     * - The thread pool size is fixed at one, meaning tasks will execute sequentially.
     * - As it uses daemon threads, it should be stopped explicitly when the associated infrastructure is
     *   being shut down to ensure proper cleanup.
     */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread thread = new Thread(r, "legendperms-db-monitor");
        thread.setDaemon(true);
        return thread;
    });

    // Bounded ThreadPool -> Größe wird dynamisch an Hikari angepasst
    private volatile ThreadPoolExecutor asyncSqlExecutor = new ThreadPoolExecutor(
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(512),
            runnable -> {
                Thread t = new Thread(runnable, "nexus-db-async");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    AbstractDatabase(LegendPerms plugin, LegendLogger logger) {
        this.plugin = plugin;
        this.logger = logger;
        startMonitoring();
    }

    public abstract DatabaseTypes getDatabaseType();
    public abstract void connect();
    public abstract void close();
    public abstract Connection getConnection();


    /**
     * Adjusts the settings of the asynchronous SQL executor to align with the specified maximum pool size.
     * The method ensures optimal thread pool configuration based on the system's available CPU cores,
     * provided pool size, and a calculated queue capacity.
     *
     * @param maxPoolSize the maximum pool size to be used as the basis for configuring the thread pool
     */
    protected void adjustAsyncExecutorForPool(int maxPoolSize) {
        int cores = Runtime.getRuntime().availableProcessors();
        int target = Math.max(2, Math.min(cores * 2, Math.max(cores, maxPoolSize * 2)));
        int queueCap = Math.max(256, maxPoolSize * 128);

        ThreadPoolExecutor current = this.asyncSqlExecutor;
        // Nur neu, wenn es sich auch lohnt :o
        if (current.getCorePoolSize() 
                == target && current.getMaximumPoolSize() 
                == target && current.getQueue().remainingCapacity() + current.getQueue().size() 
                == queueCap) {
            return;
        }

        ThreadPoolExecutor replacement = new ThreadPoolExecutor(
                target, target,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCap),
                r -> {
                    Thread t = new Thread(r, "nexus-db-async");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        // alten Executor geordnet herunterfahren
        ThreadPoolExecutor old = this.asyncSqlExecutor;
        this.asyncSqlExecutor = replacement;
        old.shutdown();
    }
    
    /**
     * Shuts down infrastructure components used by the database implementation.
     * <p>
     * This method ensures the orderly termination of the scheduled tasks and asynchronous
     * SQL executor. It performs the following steps:
     * <p>
     * - Immediately shuts down all tasks managed by the scheduler using {@code scheduler.shutdownNow()}.
     * - Shuts down the asynchronous SQL executor by invoking {@code shutdown()} on the thread pool.
     * <p>
     * Proper use of this method is critical for resource cleanup and avoiding memory leaks,
     * particularly when the database is being closed or the application is shutting down.
     */
    protected void shutdownInfrastructure() {
        scheduler.shutdownNow();
        ThreadPoolExecutor exec = this.asyncSqlExecutor;
        exec.shutdown();
    }

    /**
     * Executes an SQL update operation asynchronously using the provided SQL statement and parameters.
     * The method returns a CompletableFuture that will be completed with the number of rows
     * affected by the SQL update, or exceptionally completed if an error occurs during execution.
     *
     * @param sql the SQL statement to execute, typically an update or insert statement
     * @param replacements the replacement parameters to set in the SQL statement, represented as varargs
     * @return a CompletableFuture that completes with the number of rows affected by the update operation,
     *         or completes exceptionally with an SQLException in case of errors
     */
    public CompletableFuture<Integer> executeSqlFuture(String sql, Object... replacements) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try (Connection connection = getConnection(); PreparedStatement statement = prepareStatement(connection, sql, replacements)) {
                int affectedLines = statement.executeUpdate();
                future.complete(affectedLines);
            } catch (SQLException ex) {
                future.completeExceptionally(ex);
            }
        }, asyncSqlExecutor);
        return future;
    }

    /**
     * Checks the validity of a database connection.
     * This method attempts to obtain a database connection and verifies whether it is valid.
     * If the connection cannot be established or an error occurs during the validation,
     * the method logs the exception at a debug level and returns false.
     *
     * @return true if a valid connection is established, false otherwise
     */
    public boolean checkConnection() {
        try (Connection connection = getConnection()) {
            return connection != null && connection.isValid((int) Duration.ofSeconds(2).toSeconds());
        } catch (SQLException ex) {
            logger.debug("Could not check connection: " + ex.getMessage(), 2);
            return false;
        }
    }

    /**
     * Executes an SQL query asynchronously using the provided SQL statement and parameters.
     * This method retrieves rows from the database and returns a CompletableFuture
     * that will be completed with a list of rows, where each row is represented as a
     * map of column names to their corresponding values. If an error occurs during
     * execution, the CompletableFuture will be exceptionally completed with an SQLException.
     *
     * @param sql the SQL query to execute, typically a SELECT statement
     * @param replacements the replacement parameters for the SQL query, provided as varargs
     * @return a CompletableFuture that completes with a list of rows (each represented as a map),
     *         or completes exceptionally with an SQLException in case of execution failure
     */
    public CompletableFuture<List<Map<String, Object>>> queryRowsFuture(String sql, Object... replacements) {
        CompletableFuture<List<Map<String, Object>>> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = prepareStatement(connection, sql, replacements);
                 ResultSet resultSet = statement.executeQuery()) {

                List<Map<String, Object>> rows = new ArrayList<>();
                var meta = resultSet.getMetaData();
                int cols = meta.getColumnCount();

                while (resultSet.next()) {
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 1; i <= cols; i++) {
                        String key = meta.getColumnLabel(i);
                        if (key == null || key.isBlank()) key = meta.getColumnName(i);
                        row.put(key, resultSet.getObject(i));
                    }
                    rows.add(row);
                }

                future.complete(rows);
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        }, asyncSqlExecutor);

        return future;
    }

    /**
     * Prepares a SQL statement using the provided connection, SQL query, and parameters.
     * Replaces placeholder variables in the query with the provided replacement values.
     *
     * @param connection the database connection to be used for preparing the statement
     * @param sql the SQL query to prepare, containing placeholder variables for parameters
     * @param replacements an optional varargs array of parameters to replace placeholder variables in the query
     * @return a {@code PreparedStatement} object representing the prepared SQL statement
     * @throws RuntimeException if an {@code SQLException} occurs while preparing the statement
     */
    private PreparedStatement prepareStatement(Connection connection, String sql, Object... replacements) {
        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            this.replaceQueryParameters(statement, replacements);
            return statement;
        } catch (SQLException e) {
            throw new RuntimeException("[LegendPerms] Could not prepare/read SQL statement: " + sql, e);
        }
    }

    /**
     * Replaces the placeholder parameters in the provided {@code PreparedStatement} with the given replacement values.
     * Each element in the {@code replacements} array corresponds to a parameter in the SQL statement.
     * A {@code RuntimeException} is thrown if setting a parameter value fails due to an {@code SQLException}.
     *
     * @param statement the {@code PreparedStatement} whose parameters are to be replaced
     * @param replacements an array of parameter values to replace placeholders in the SQL statement, can be {@code null}
     */
    private void replaceQueryParameters(PreparedStatement statement, Object[] replacements) {
        if (replacements != null) {
            for (int i = 0; i < replacements.length; i++) {
                int position = i + 1;
                Object value = replacements[i];
                try {
                    statement.setObject(position, value);
                } catch (SQLException e) {
                    throw new RuntimeException("Unable to set query parameter at position " + position +
                            " to " + value + " for query: " + statement, e);
                }
            }
        }
    }

    /**
     * Starts a scheduled task to monitor the validity of the database connection at regular intervals.
     * <p>
     * This method uses the scheduler to repeatedly check the database connection status every 5 minutes.
     * If a connection failure is detected, an attempt is made to re-establish the connection. The logic includes:
     * - Validating the current database connection.
     * - Logging an error and attempting to reconnect if the connection is found invalid.
     * - Upon re-establishing the connection, logging that it has been successfully restored.
     * - Logging an error if the reconnection attempt fails.
     * - In case of unexpected exceptions during connection checks or reconnection attempts, logging an error with the exception message.
     * <p>
     * This ensures that the application maintains a stable connection to the database and logs necessary issues
     * so they can be diagnosed and addressed effectively.
     * <p>
     * Tasks are executed at a fixed rate of 5 minutes. Faults during execution do not terminate the monitoring process.
     */
    public void startMonitoring() {
        scheduler.scheduleAtFixedRate(() -> {
            Date date = new Date();
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat("dd.MM.yyyy - HH:mm:ss");
            String currentTime = simpleDateFormat.format(date);

            try (Connection connection = getConnection()) {
                boolean valid = connection != null && connection.isValid((int) Duration.ofSeconds(2).toSeconds());
                if (!valid) {
                    logger.error(List.of(
                            "Database connection is not valid at " + currentTime + ".",
                            "Attempting to reconnect..."
                    ));
                    connect();
                    try (Connection after = getConnection()) {
                        if (after != null && after.isValid((int) Duration.ofSeconds(2).toSeconds())) {
                            logger.info(List.of("Database connection has been re-established."));
                        } else {
                            logger.error("Database connection could not be re-established.");
                        }
                    }
                }
            } catch (SQLException | RuntimeException e) {
                logger.error(List.of(
                        "Error while checking database connection: " + e.getMessage(),
                        "Attempting to reconnect..."
                ));
                try {
                    connect();
                    if (checkConnection()) {
                        logger.info("Database connection has been re-established.");
                    } else {
                        logger.error("Database connection could not be re-established.");
                    }
                } catch (Throwable t) {
                    logger.error("Reconnect attempt failed: " + t.getMessage());
                }
            }
        }, 5, 5, TimeUnit.MINUTES);
    }
}