package io.nexstudios.legendperms.database;

import com.zaxxer.hikari.HikariDataSource;
import io.nexstudios.legendperms.LegendPerms;
import io.nexstudios.legendperms.utils.LegendLogger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An abstract class that provides the structure for database implementations that utilize
 * connection pooling through HikariCP. This class serves as a common base for specific
 * pooled database implementations, such as MySQL and SQLite databases.
 * <p>
 * Features of this class include:
 * - Management of a HikariCP connection pool.
 * - Methods for acquiring a database connection from the pool.
 * - Swapping in a new HikariCP data source seamlessly.
 * - Proper cleanup and shutdown of the connection pool when the database is closed.
 */
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

    /**
     * Closes the HikariCP connection pool and performs necessary cleanup.
     *
     * This method logs the closure process, ensures thread safety with a synchronization
     * block on the {@code poolLock} object, and shuts down the {@link HikariDataSource}
     * if it exists. If an error occurs during the closure of the connection pool, it is
     * logged as an error.
     *
     * Additionally, after shutting down the connection pool, other related infrastructure,
     * such as schedulers and asynchronous SQL executors, is also shut down.
     */
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

    /**
     * Retrieves a database connection from the HikariCP connection pool.
     *
     * This method ensures that the HikariCP pool is properly initialized before attempting to obtain
     * a connection. If the pool is uninitialized, an {@code IllegalStateException} is thrown.
     * If an error occurs while retrieving the connection, it is logged as an error and a
     * {@code RuntimeException} is thrown.
     *
     * @return a valid {@code Connection} object from the HikariCP connection pool.
     * @throws IllegalStateException if the HikariCP connection pool is not initialized.
     * @throws RuntimeException if a {@code SQLException} occurs while attempting to retrieve a connection.
     */
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


    /**
     * Replaces the current HikariCP data source with a new instance, ensuring proper
     * cleanup of resources and reconfiguration of the asynchronous executor.
     *
     * This method is synchronized on the {@code poolLock} to guarantee thread safety during
     * the replacement process. The previous data source is closed if it exists, and any
     * errors encountered during its closure are logged. Additionally, the asynchronous
     * executor is adjusted to align with the maximum pool size of the new data source.
     *
     * @param newDs the new {@code HikariDataSource} instance to replace the existing data source
     */
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