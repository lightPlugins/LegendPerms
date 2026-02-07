package io.nexstudios.legendperms.database.model;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Objects;


/**
 * Represents the credentials required to connect to a database.
 * Encapsulates critical information such as host, database name, username,
 * password, port, and SSL usage flag.
 *
 * This record is immutable and can be easily instantiated through configuration
 * files using the provided factory method.
 *
 * @param host The hostname or IP address of the database server.
 * @param databaseName The name of the database to connect to.
 * @param userName The username for authenticating the database connection.
 * @param password The password for authenticating the database connection.
 * @param port The port number on which the database server is listening.
 * @param useSSL Whether to use SSL for the database connection.
 */
public record DatabaseCredentials(
        String host,
        String databaseName,
        String userName,
        String password,
        int port,
        boolean useSSL) {

    public static DatabaseCredentials fromConfig(FileConfiguration config) {

        String rootPath = "storage.";

        String host = config.getString(rootPath + "host");
        String dbName = config.getString(rootPath + "database");
        String userName = config.getString(rootPath + "username");
        String password = config.getString(rootPath + "password");
        int port = config.getInt(rootPath + "port");
        boolean useSSL = config.getBoolean(rootPath + "use-ssl", false);

        Objects.requireNonNull(host, "Config must not be null!");
        Objects.requireNonNull(dbName, "Database name must not be null!");
        Objects.requireNonNull(userName, "username must not be null!");
        Objects.requireNonNull(password, "password must not be null!");


        return new DatabaseCredentials(host, dbName, userName, password, port, useSSL);
    }

}