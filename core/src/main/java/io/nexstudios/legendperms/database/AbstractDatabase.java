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

@Slf4j
public abstract class AbstractDatabase {

    protected final LegendPerms plugin;
    protected final LegendLogger logger;

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
    
    protected void shutdownInfrastructure() {
        scheduler.shutdownNow();
        ThreadPoolExecutor exec = this.asyncSqlExecutor;
        exec.shutdown();
    }

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

    public boolean checkConnection() {
        try (Connection connection = getConnection()) {
            return connection != null && connection.isValid((int) Duration.ofSeconds(2).toSeconds());
        } catch (SQLException ex) {
            logger.debug("Could not check connection: " + ex.getMessage(), 2);
            return false;
        }
    }

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

    private PreparedStatement prepareStatement(Connection connection, String sql, Object... replacements) {
        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            this.replaceQueryParameters(statement, replacements);
            return statement;
        } catch (SQLException e) {
            throw new RuntimeException("[LegendPerms] Could not prepare/read SQL statement: " + sql, e);
        }
    }

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