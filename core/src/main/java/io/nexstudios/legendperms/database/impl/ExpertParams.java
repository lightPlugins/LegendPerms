package io.nexstudios.legendperms.database.impl;

import com.zaxxer.hikari.HikariConfig;
import io.nexstudios.legendperms.database.model.ConnectionProperties;

public class ExpertParams {

    public static void applyConnectionProperties(HikariConfig hikari, ConnectionProperties connectionProperties) {
        hikari.setConnectionTimeout(connectionProperties.connectionTimeout());
        hikari.setIdleTimeout(connectionProperties.idleTimeout());
        hikari.setKeepaliveTime(connectionProperties.keepAliveTime());
        hikari.setMaxLifetime(connectionProperties.maxLifetime());
        hikari.setMinimumIdle(connectionProperties.minimumIdle());
        hikari.setMaximumPoolSize(connectionProperties.maximumPoolSize());
        hikari.setLeakDetectionThreshold(connectionProperties.leakDetectionThreshold());
        // JDBC4 Validation bevorzugen; nur setzen, wenn explizit konfiguriert
        if (connectionProperties.testQuery() != null && !connectionProperties.testQuery().isBlank()) {
            hikari.setConnectionTestQuery(connectionProperties.testQuery());
        }
        hikari.setValidationTimeout(Math.min(connectionProperties.connectionTimeout(), 5000)); // z.B. 5s
        // Optional: schneller Fail bei Fehlkonfiguration
        hikari.setInitializationFailTimeout(10_000);
    }

    public static void addDefaultDataSourceProperties(HikariConfig hikari) {
        // MySQL/MariaDB: Statement-Cache etc.
        hikari.addDataSourceProperty("cachePrepStmts", true);
        hikari.addDataSourceProperty("prepStmtCacheSize", 250);
        hikari.addDataSourceProperty("prepStmtCacheSqlLimit", 2048);
        hikari.addDataSourceProperty("useServerPrepStmts", true);
        hikari.addDataSourceProperty("useLocalSessionState", true);
        hikari.addDataSourceProperty("rewriteBatchedStatements", true);
        hikari.addDataSourceProperty("cacheResultSetMetadata", true);
        hikari.addDataSourceProperty("cacheServerConfiguration", true);
        hikari.addDataSourceProperty("elideSetAutoCommits", true);
        // maintainTimeStats ist bei neueren Treibern obsolet; weglassen oder auf false belassen
        hikari.addDataSourceProperty("maintainTimeStats", false);
    }

}