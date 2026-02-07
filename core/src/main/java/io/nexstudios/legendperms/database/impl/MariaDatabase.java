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
 * MariaDatabase represents a concrete implementation of the {@link PooledDatabase}
 * class for interacting with a MariaDB database through a connection pool.
 * <p>
 * This class provides functionalities specific to MariaDB, including establishing
 * a connection to the database, managing credentials, and setting connection properties.
 * It uses HikariCP as the connection pooling library and ensures efficient management
 * of database connections.
 */
public class MariaDatabase extends PooledDatabase {

    private final DatabaseCredentials credentials;
    private final ConnectionProperties connectionProperties;
    private final String poolName = "legendperms-mariadb-";

    public MariaDatabase(LegendPerms parent, LegendLogger logger, DatabaseCredentials credentials, ConnectionProperties connectionProperties) {
        super(parent, logger);
        this.connectionProperties = connectionProperties;
        this.credentials = credentials;
    }

    /**
     * Establishes a connection to the MariaDB database using HikariCP connection pooling.
     * <p>
     * This method configures a new {@code HikariConfig} instance with the database connection
     * details and properties, including credentials, connection-specific configurations, and
     * default data source properties. It assigns a unique pool name to the configuration using
     * an atomic counter for pool identification.
     * <p>
     * After setting up the configuration, a new {@code HikariDataSource} is instantiated and
     * swapped in as the active data source for this class. The previous data source, if present,
     * is closed to release resources and prevent memory leaks.
     * <p>
     * Thread safety is ensured during the replacement process, and any necessary adjustments
     * to the asynchronous executor are performed to align with the new pool configuration.
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
     * Configures the database credentials and connection URL for the given HikariConfig instance.
     * Constructs the JDBC URL using the provided database credentials and connection properties.
     * Sets the username and password for the database connection.
     *
     * @param hikari               The HikariConfig instance to configure.
     * @param credentials          The database credentials including host, port, database name,
     *                              username, password, and SSL usage.
     * @param connectionProperties The connection properties including character encoding and
     *                              other connection-specific configurations.
     */
    private void applyCredentials(HikariConfig hikari, DatabaseCredentials credentials, ConnectionProperties connectionProperties) {
        String enc = connectionProperties.characterEncoding() == null ? "utf8" : connectionProperties.characterEncoding();
        String url = "jdbc:mariadb://" + credentials.host() + ":" + credentials.port() + "/" + credentials.databaseName()
                + "?useUnicode=true"
                + "&characterEncoding=" + enc
                + "&useSsl=" + credentials.useSSL()
                + "&serverTimezone=UTC";
        hikari.setJdbcUrl(url);
        hikari.setUsername(credentials.userName());
        hikari.setPassword(credentials.password());
    }

    /**
     * Applies the specified connection properties to the given HikariConfig instance.
     * This method configures connection pooling settings such as timeouts, pool size,
     * and other advanced properties to optimize database connections.
     *
     * @param hikari               The HikariConfig instance to which the connection properties will be applied.
     * @param connectionProperties The connection properties, including timeout values, pool size limits,
     *                              leak detection thresholds, and other relevant configurations.
     */
    private void applyConnectionProperties(HikariConfig hikari, ConnectionProperties connectionProperties) {
        ExpertParams.applyConnectionProperties(hikari, connectionProperties);
    }

    /**
     * Adds default data source properties to the provided HikariConfig instance.
     * These properties are optimized for performance and compatibility with
     * MySQL/MariaDB databases, configuring settings such as statement caching,
     * batch statement rewriting, and session state management.
     *
     * @param hikari The HikariConfig instance to which the default data source properties will be added.
     */
    private void addDefaultDataSourceProperties(HikariConfig hikari) {
        ExpertParams.addDefaultDataSourceProperties(hikari);
    }

    /**
     * Retrieves the type of the database associated with this implementation.
     * Specifically, this method identifies the database type as MariaDB.
     *
     * @return The database type as an enumerated value of {@code DatabaseTypes.MARIADB}.
     */
    @Override
    public DatabaseTypes getDatabaseType() {
        return DatabaseTypes.MARIADB;
    }
}