package io.nexstudios.legendperms.database.model;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Represents the configuration properties for a database connection, typically used
 * in connection pooling frameworks such as HikariCP.
 * This class encapsulates various timeout values, pool size parameters, and
 * connection-related settings.
 *
 * @param idleTimeout The maximum amount of time a connection can remain idle in the pool
 *                    before being eligible for eviction, in milliseconds.
 * @param maxLifetime The maximum lifetime of a connection in the pool, in milliseconds.
 * @param connectionTimeout The maximum number of milliseconds the pool will wait for a connection to be available
 *                          before throwing an exception.
 * @param leakDetectionThreshold The amount of time, in milliseconds, a connection is allowed to remain open without
 *                               being returned to the pool before it is considered a leak.
 * @param keepAliveTime The interval, in milliseconds, for periodic keep-alive activity to maintain valid connections.
 * @param minimumIdle The minimum number of idle connections maintained in the pool at all times.
 * @param maximumPoolSize The maximum number of connections allowed in the pool.
 * @param testQuery The SQL query used to test the validity of a connection, if configured.
 * @param characterEncoding The character encoding used for the database connection.
 */
public record ConnectionProperties(long idleTimeout,
                                   long maxLifetime,
                                   long connectionTimeout,
                                   long leakDetectionThreshold,
                                   long keepAliveTime,
                                   int minimumIdle,
                                   int maximumPoolSize,
                                   String testQuery,
                                   String characterEncoding) {

    public static ConnectionProperties fromConfig(FileConfiguration config) {

        String rootPath = "storage.advanced.";

        long connectionTimeout = config.getLong(rootPath + "connection-timeout", 60000);
        long idleTimeout = config.getLong(rootPath + "idle-timeout", 600000);
        long keepAliveTime = config.getLong(rootPath + "keep-alive-time", 0);
        long maxLifeTime = config.getLong(rootPath + "max-life-time", 1800000);
        int minimumIdle = config.getInt(rootPath + "minimum-idle", 10);
        int maximumPoolSize = config.getInt(rootPath + "maximum-pool-size", 10);
        long leakDetectionThreshold = config.getLong(rootPath + "leak-detection-threshold", 0);
        String characterEncoding = config.getString(rootPath + "character-encoding", "utf8");
        String testQuery = config.getString(rootPath + "connection-test-query", "SELECT 1");

        return new ConnectionProperties(
                idleTimeout,
                maxLifeTime,
                connectionTimeout,
                leakDetectionThreshold,
                keepAliveTime,
                minimumIdle,
                maximumPoolSize,
                testQuery,
                characterEncoding
        );
    }
}