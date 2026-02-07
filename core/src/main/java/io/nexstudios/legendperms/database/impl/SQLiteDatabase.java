package io.nexstudios.legendperms.database.impl;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.nexstudios.legendperms.LegendPerms;
import io.nexstudios.legendperms.database.PooledDatabase;
import io.nexstudios.legendperms.database.model.ConnectionProperties;
import io.nexstudios.legendperms.database.model.DatabaseTypes;
import io.nexstudios.legendperms.utils.LegendLogger;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * This class represents an SQLite database implementation that utilizes HikariCP for connection pooling.
 * It extends the {@link PooledDatabase} class, providing specific functionality to interact with and
 * manage an SQLite database.
 *
 * The database file is created and managed within the plugin's data folder, allowing for embedded
 * database usage. It supports configuring connection settings such as timeouts, maximum pool size,
 * and test queries using a {@link ConnectionProperties} instance.
 */
public class SQLiteDatabase extends PooledDatabase {

    private static final String FILE_NAME = "legendperms.db";

    private final String filePath;
    private final ConnectionProperties connectionProperties;

    public SQLiteDatabase(LegendPerms plugin, LegendLogger logger, ConnectionProperties connectionProperties) {
        super(plugin, logger);
        this.connectionProperties = connectionProperties;
        this.filePath = this.plugin.getDataFolder().getPath() + File.separator + FILE_NAME;
    }

    /**
     * Retrieves the type of database used by the current implementation.
     *
     * @return the {@link DatabaseTypes} enumeration value identifying the database type,
     *         specifically {@code DatabaseTypes.SQLITE} for this implementation.
     */
    @Override
    public DatabaseTypes getDatabaseType() {
        return DatabaseTypes.SQLITE;
    }

    /**
     * Establishes a connection to the SQLite database using HikariCP for connection pooling.
     * <p>
     * This method performs the following tasks:
     * <p>1. Ensures the database file exists by invoking {@code createDBFile()}.
     * <p>2. Configures a new {@link HikariConfig} instance with SQLite-specific settings,
     *    including driver class name, JDBC URL, and connection pool properties
     *    defined in {@code connectionProperties}.
     * <p>3. Sets the connection pool name with a unique identifier.
     * <p>4. Configures database connection settings such as connection timeout, idle timeout,
     *    keepalive time, maximum lifetime, minimum idle connections, and leak detection threshold.
     * <p>5. Optionally sets a connection test query if it is explicitly provided.
     * <p>6. Creates a new {@link HikariDataSource} with the configured settings.
     * <p>7. Replaces the existing data source with the newly created one by calling {@code swapInNewDataSource()}.
     */
    @Override
    public void connect() {

        this.createDBFile();

        final HikariConfig hikari = new HikariConfig();

        hikari.setPoolName("legendperms-sqlite-" + POOL_COUNTER.getAndIncrement());

        hikari.setDriverClassName("org.sqlite.JDBC");
        hikari.setJdbcUrl("jdbc:sqlite:" + this.filePath);

        hikari.setConnectionTimeout(connectionProperties.connectionTimeout());
        hikari.setIdleTimeout(connectionProperties.idleTimeout());
        hikari.setKeepaliveTime(connectionProperties.keepAliveTime());
        hikari.setMaxLifetime(connectionProperties.maxLifetime());
        hikari.setMinimumIdle(connectionProperties.minimumIdle());
        hikari.setMaximumPoolSize(1);
        hikari.setLeakDetectionThreshold(connectionProperties.leakDetectionThreshold());
        if (connectionProperties.testQuery() != null && !connectionProperties.testQuery().isBlank()) {
            hikari.setConnectionTestQuery(connectionProperties.testQuery());
        }

        HikariDataSource newDs = new HikariDataSource(hikari);
        // Atomar tauschen, Executor anpassen, alten Pool schließen
        swapInNewDataSource(newDs);
    }

    /**
     * Ensures the existence of the SQLite database file specified by the file path.
     * <p>
     * This method performs the following steps:
     * <p>1. Checks if the parent directory of the database file exists. If not, it creates the
     *    required directory structure.
     * <p>2. Creates the database file if it does not already exist.
     * <p>
     * If the file creation fails due to an {@link IOException}, an error message is logged
     * and a {@link RuntimeException} is thrown.
     * <p>
     * Exceptions handled:
     * - Logs the error details including the file path and the exception message.
     * - Wraps the original exception in a {@link RuntimeException} that includes the name
     *   of the database file and rethrows it.
     * <p>
     * This method suppresses the compiler warning for ignoring method call results as
     * the results of directory creation and file creation are explicitly checked during
     * execution.
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void createDBFile() {
        File dbFile = new File(this.filePath);
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try {
            if (!dbFile.exists()) {
                dbFile.createNewFile();
            }
        } catch (IOException e) {
            logger.error(List.of(
                    "Failed to create SQLite database file",
                    "File path: " + this.filePath,
                    "Error: " + e.getMessage()
            ));
            throw new RuntimeException("Unable to create " + FILE_NAME, e);
        }
    }
}