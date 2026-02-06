package io.nexstudios.legendperms.database.impl;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.nexstudios.legendperms.LegendPerms;
import io.nexstudios.legendperms.database.PooledDatabase;
import io.nexstudios.legendperms.database.model.ConnectionProperties;
import io.nexstudios.legendperms.database.model.DatabaseCredentials;
import io.nexstudios.legendperms.database.model.DatabaseTypes;
import io.nexstudios.legendperms.utils.LegendLogger;

public class MariaDatabase extends PooledDatabase {

    private final DatabaseCredentials credentials;
    private final ConnectionProperties connectionProperties;
    private final String poolName = "legendperms-mariadb-";

    public MariaDatabase(LegendPerms parent, LegendLogger logger, DatabaseCredentials credentials, ConnectionProperties connectionProperties) {
        super(parent, logger);
        this.connectionProperties = connectionProperties;
        this.credentials = credentials;
    }

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

    private void applyConnectionProperties(HikariConfig hikari, ConnectionProperties connectionProperties) {
        ExpertParams.applyConnectionProperties(hikari, connectionProperties);
    }

    private void addDefaultDataSourceProperties(HikariConfig hikari) {
        ExpertParams.addDefaultDataSourceProperties(hikari);
    }

    @Override
    public DatabaseTypes getDatabaseType() {
        return DatabaseTypes.MARIADB;
    }
}