package io.nexstudios.legendperms.database.impl;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.nexstudios.legendperms.LegendPerms;
import io.nexstudios.legendperms.database.PooledDatabase;
import io.nexstudios.legendperms.database.model.ConnectionProperties;
import io.nexstudios.legendperms.database.model.DatabaseCredentials;
import io.nexstudios.legendperms.database.model.DatabaseTypes;
import io.nexstudios.legendperms.utils.LegendLogger;

/**
 * Represents a MySQL database implementation that extends the {@link PooledDatabase},
 * specifically for handling connections to a MySQL database using HikariCP connection pooling.
 *
 * This class provides methods for establishing and managing a connection pool to a MySQL database.
 * It also applies specific MySQL connection properties and credentials during initialization.
 */
public class MySQLDatabase extends PooledDatabase {

    private final DatabaseCredentials credentials;
    private final ConnectionProperties connectionProperties;
    private final String poolName = "legendperms-mysql-";

    public MySQLDatabase(LegendPerms parent, LegendLogger logger, DatabaseCredentials credentials, ConnectionProperties connectionProperties) {
        super(parent, logger);
        this.connectionProperties = connectionProperties;
        this.credentials = credentials;
    }

    /**
     * Establishes and configures a connection pool to a MySQL database using HikariCP.
     * <p>
     * The method initializes a new HikariCP configuration, applying database credentials
     * and connection properties specific to the MySQL database. It also ensures that default
     * data source properties are incorporated into the configuration. Once the configuration
     * is complete, a new HikariDataSource instance is created and replaces any existing data
     * source atomically to ensure thread safety and resource cleanup.
     * <p>
     * Internal sub-operations include:
     * - Applying database credentials such as username, password, and JDBC URL to the configuration.
     * - Applying additional connection properties that may affect connection behavior.
     * - Adding default configurations to the data source.
     * - Replacing the existing data source with the newly configured one in a thread-safe manner,
     *   while properly closing the previous pool.
     * <p>
     * This method guarantees that the connection pool is correctly reconfigured every time it is
     * called, preventing resource leaks and ensuring proper synchronization in a multi-threaded
     * environment.
     */
    @Override
    public void connect() {
        HikariConfig hikari = new HikariConfig();

        hikari.setPoolName(poolName + POOL_COUNTER.getAndIncrement());

        this.applyCredentials(hikari, credentials, connectionProperties);
        this.applyConnectionProperties(hikari, connectionProperties);
        this.addDefaultDataSourceProperties(hikari);

        HikariDataSource newDs = new HikariDataSource(hikari);
        // Atomar tauschen, Executor anpassen, alten Pool schließen
        swapInNewDataSource(newDs);
    }

    /**
     * Configures the database connection pool with the provided credentials and connection properties.
     * This method constructs a JDBC URL based on the given database credentials and connection properties,
     * then applies it along with the username and password to the HikariCP configuration.
     *
     * @param hikari The HikariConfig instance to be configured.
     * @param credentials The database credentials containing details such as host, port,
     *                    database name, username, password, and SSL preference.
     * @param connectionProperties The connection properties including settings like character encoding,
     *                              timeout values, and pool size parameters.
     */
    private void applyCredentials(HikariConfig hikari, DatabaseCredentials credentials, ConnectionProperties connectionProperties) {
        String enc = connectionProperties.characterEncoding() == null ? "utf8" : connectionProperties.characterEncoding();
        String url = "jdbc:mysql://" + credentials.host() + ":" + credentials.port() + "/" + credentials.databaseName()
                + "?useUnicode=true"
                + "&characterEncoding=" + enc
                + "&useSSL=" + credentials.useSSL()
                + "&serverTimezone=UTC"
                + "&allowPublicKeyRetrieval=true";
        hikari.setJdbcUrl(url);
        hikari.setUsername(credentials.userName());
        hikari.setPassword(credentials.password());
    }

    /**
     * Applies the given connection properties to the specified HikariConfig instance.
     * This method uses the provided ConnectionProperties to configure various aspects
     * of the connection pool managed by HikariCP, such as timeout values, pool size,
     * and connection settings.
     *
     * @param hikari The HikariConfig instance to be updated with the specified connection properties.
     * @param connectionProperties The ConnectionProperties object containing configuration settings
     *                              for the connection pool, including timeouts, pool sizes, and other
     *                              parameters.
     */
    private void applyConnectionProperties(HikariConfig hikari, ConnectionProperties connectionProperties) {
        ExpertParams.applyConnectionProperties(hikari, connectionProperties);
    }

    /**
     * Adds default data source properties to the given HikariConfig instance.
     * This method delegates the configuration to the ExpertParams utility class,
     * ensuring optimal performance for MySQL/MariaDB connection pools by including
     * pre-configured advanced properties such as statement caching and server-side
     * prepared statements.
     *
     * @param hikari The HikariConfig instance to which default data source properties
     *               will be added.
     */
    private void addDefaultDataSourceProperties(HikariConfig hikari) {
        ExpertParams.addDefaultDataSourceProperties(hikari);
    }

    /**
     * Retrieves the type of database associated with this implementation.
     *
     * @return The database type, represented as an enum constant of {@link DatabaseTypes}.
     */
    @Override
    public DatabaseTypes getDatabaseType() {
        return DatabaseTypes.MYSQL;
    }
}