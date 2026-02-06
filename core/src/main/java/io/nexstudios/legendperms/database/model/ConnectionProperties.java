package io.nexstudios.legendperms.database.model;

import org.bukkit.configuration.file.FileConfiguration;

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