package io.nexstudios.legendperms.database;

import com.zaxxer.hikari.HikariDataSource;
import io.nexstudios.legendperms.LegendPerms;
import io.nexstudios.legendperms.utils.LegendLogger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class PooledDatabase extends AbstractDatabase {

    protected static final AtomicInteger POOL_COUNTER = new AtomicInteger(0);
    protected volatile HikariDataSource hikari;
    private final Object poolLock = new Object();

    public PooledDatabase(LegendPerms plugin, LegendLogger logger) {
        super(plugin, logger);
    }

    public DataSource getDataSource() {
        HikariDataSource ds = this.hikari;
        if (ds == null) {
            throw new IllegalStateException("HikariCP pool is not initialized. Did you call connect()?");
        }
        return ds;
    }

    @Override
    public void close() {
        logger.info("Closing HikariCP connection pool...");
        synchronized (poolLock) {
            HikariDataSource dataSource = this.hikari;
            this.hikari = null;
            if (dataSource != null) {
                try {
                    dataSource.close();
                } catch (Exception e) {
                    logger.error(List.of(
                            "Error while closing HikariCP pool",
                            "Error: " + e.getMessage()
                    ));
                }
            }
        }

        shutdownInfrastructure();
    }

    @Override
    public Connection getConnection() {
        HikariDataSource dataSource = this.hikari;
        if (dataSource == null) {
            throw new IllegalStateException("HikariCP pool is not initialized. Did you call connect()?");
        }
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            logger.error(List.of(
                    "Failed to get connection from HikariCP",
                    "Error: " + e.getMessage()
            ));
            throw new RuntimeException("Unable to obtain DB connection from pool", e);
        }
    }


    protected void swapInNewDataSource(HikariDataSource newDs) {
        synchronized (poolLock) {
            HikariDataSource old = this.hikari;
            this.hikari = newDs;
            // Executor size coupling to pool size
            adjustAsyncExecutorForPool(newDs.getMaximumPoolSize());
            if (old != null) {
                try {
                    old.close();
                } catch (Exception e) {
                    logger.error(List.of(
                            "Error while closing previous HikariCP pool",
                            "Error: " + e.getMessage()
                    ));
                }
            }
        }
    }
}