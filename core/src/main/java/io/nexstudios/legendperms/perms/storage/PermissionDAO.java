package io.nexstudios.legendperms.perms.storage;

import io.nexstudios.legendperms.database.AbstractDatabase;
import io.nexstudios.legendperms.perms.PermissionDecision;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * A data access object (DAO) designed for operations related to permission management.
 * This class interacts with the underlying database structure to perform CRUD operations
 * on permission-related entities such as groups, group permissions, user group assignments,
 * and temporary groups.
 * <p>
 * This DAO is compatible with multiple database types, including SQLite and MySQL/MariaDB,
 * and handles database-type specific SQL syntax adjustments where necessary.
 * <p>
 * Thread-safe operations are achieved via asynchronous method calls utilizing
 * {@link CompletableFuture}.
 * <p>
 * Constructor Parameters:
 * - {@link AbstractDatabase} db: The abstracted database object responsible for handling
 *   SQL execution and database interactions.
 */
public record PermissionDAO(AbstractDatabase db) {

    /**
     * Migrates the database schema to ensure that all required tables for the permission system
     * exist. The method executes a series of SQL Data Definition Language (DDL) statements
     * asynchronously, creating the necessary database tables if they are not already present.
     * <p>
     * The method executes each SQL statement sequentially using a CompletableFuture chain.
     * <p>
     * @return a CompletableFuture that completes when all SQL statements have been executed successfully.
     */
    public CompletableFuture<Void> migrate() {
        // SQLite/MySQL compatibility
        List<String> dlls = List.of(
                "CREATE TABLE IF NOT EXISTS lp_groups (" +
                        "name VARCHAR(64) PRIMARY KEY," +
                        "priority INT NOT NULL," +
                        "prefix TEXT NOT NULL" +
                        ")",

                "CREATE TABLE IF NOT EXISTS lp_group_permissions (" +
                        "group_name VARCHAR(64) NOT NULL," +
                        "node VARCHAR(255) NOT NULL," +
                        "decision INT NOT NULL," +
                        "PRIMARY KEY (group_name, node)" +
                        ")",

                "CREATE TABLE IF NOT EXISTS lp_users (" +
                        "uuid CHAR(36) PRIMARY KEY" +
                        ")",

                "CREATE TABLE IF NOT EXISTS lp_user_groups (" +
                        "uuid CHAR(36) NOT NULL," +
                        "group_name VARCHAR(64) NOT NULL," +
                        "PRIMARY KEY (uuid, group_name)" +
                        ")",

                "CREATE TABLE IF NOT EXISTS lp_user_temp_groups (" +
                        "uuid CHAR(36) NOT NULL," +
                        "group_name VARCHAR(64) NOT NULL," +
                        "expires_at BIGINT NOT NULL," +
                        "PRIMARY KEY (uuid, group_name)" +
                        ")"
        );

        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        for (String ddl : dlls) {
            future = future.thenCompose(v -> db.executeSqlFuture(ddl).thenApply(x -> null));
        }
        return future;
    }

    /**
     * Loads all group records from the database. Each group record consists of a name, priority,
     * and prefix. The method executes a SQL query to retrieve this data asynchronously and returns
     * it as a future containing a list of maps, where each map represents a row in the result set.
     *
     * @return a CompletableFuture that, when completed, contains a list of maps. Each map represents
     * a group with keys "name", "priority", and "prefix" mapped to their respective values.
     */
    public CompletableFuture<List<Map<String, Object>>> loadAllGroups() {
        return db.queryRowsFuture("SELECT name, priority, prefix FROM lp_groups");
    }

    /**
     * Loads all group permissions from the database. Each permission record includes the group name,
     * permission node, and its corresponding decision. The method executes a SQL query to retrieve
     * this data asynchronously and returns it as a future containing a list of maps, where each map
     * represents a row in the result set.
     *
     * @return a CompletableFuture that, when completed, contains a list of maps. Each map represents
     *         a group permission with keys "group_name", "node", and "decision" mapped to their respective values.
     */
    public CompletableFuture<List<Map<String, Object>>> loadAllGroupPermissions() {
        return db.queryRowsFuture("SELECT group_name, node, decision FROM lp_group_permissions");
    }

    /**
     * Asynchronously loads the list of permanent groups associated with a specific user from the database.
     * Each group is returned as a map containing the column "group_name".
     *
     * @param uuid the unique identifier of the user whose permanent groups are to be loaded
     * @return a CompletableFuture that, when completed, contains a list of maps. Each map represents a
     *         row with the key "group_name" mapped to the group name.
     */
    public CompletableFuture<List<Map<String, Object>>> loadUserPermanentGroups(UUID uuid) {
        return db.queryRowsFuture(
                "SELECT group_name FROM lp_user_groups WHERE uuid = ?",
                uuid.toString()
        );
    }

    /**
     * Asynchronously loads the list of active temporary groups associated with a specific user from the database.
     * Each active temporary group is returned as a map containing the column "group_name" and "expires_at".
     *
     * @param uuid the unique identifier of the user whose active temporary groups are to be loaded
     * @param now the current instant used to filter out expired groups
     * @return a CompletableFuture that, when completed, contains a list of maps.
     * Each map represents a row with keys "group_name" and "expires_at" mapped to their respective values.
     */
    public CompletableFuture<List<Map<String, Object>>> loadUserActiveTempGroups(UUID uuid, Instant now) {
        long nowMs = now.toEpochMilli();
        return db.queryRowsFuture(
                "SELECT group_name, expires_at FROM lp_user_temp_groups WHERE uuid = ? AND expires_at > ?",
                uuid.toString(), nowMs
        );
    }

    /**
     * Ensures that a row exists in the "lp_users" table for the specified user.
     * If the row does not exist, it will be created. This operation is performed
     * asynchronously and uses database-specific SQL commands to handle the insertion.
     *
     * @param uuid the unique identifier of the user whose row should be ensured in the database
     * @return a CompletableFuture that completes when the operation is finished successfully
     *         or exceptionally if an error occurs
     */
    public CompletableFuture<Void> ensureUserRow(UUID uuid) {
        String sql = switch (db.getDatabaseType()) {
            case SQLITE -> "INSERT OR IGNORE INTO lp_users(uuid) VALUES(?)";
            case MYSQL, MARIADB -> "INSERT IGNORE INTO lp_users(uuid) VALUES(?)";
        };
        return db.executeSqlFuture(sql, uuid.toString()).thenApply(x -> null);
    }

    /**
     * Inserts a group into the database if it does not already exist. The group is defined by its
     * unique name, priority value, and prefix. If the prefix is null, it defaults to an empty string.
     * The query behavior depends on the type of database being used.
     * <p>
     * Only for the Default Group -> should not be reset to their default values!
     *
     * @param name the unique name of the group to insert
     * @param priority the priority value associated with the group
     * @param prefix the optional prefix associated with the group, defaults to an empty string if null
     * @return a CompletableFuture representing the asynchronous operation, which completes when the
     *         insertion process is finished
     */
    public CompletableFuture<Void> insertGroupIfAbsent(String name, int priority, String prefix) {
        String safePrefix = (prefix == null) ? "" : prefix;

        String sql = switch (db.getDatabaseType()) {
            case SQLITE -> "INSERT OR IGNORE INTO lp_groups(name, priority, prefix) VALUES(?, ?, ?)";
            case MYSQL, MARIADB -> "INSERT IGNORE INTO lp_groups(name, priority, prefix) VALUES(?, ?, ?)";
        };

        return db.executeSqlFuture(sql, name, priority, safePrefix).thenApply(x -> null);
    }

    /**
     * Inserts or updates a group record in the database with the specified attributes. If a group with
     * the given name already exists, its priority and prefix will be updated. The operation is
     * executed asynchronously and adapts to the SQL dialect of the underlying database.
     *
     * @param name the name of the group to insert or update
     * @param priority the priority level of the group
     * @param prefix the prefix associated with the group; if null, it defaults to an empty string
     * @return a CompletableFuture that completes when the operation is finished successfully
     *         or exceptionally if an error occurs
     */
    public CompletableFuture<Void> upsertGroup(String name, int priority, String prefix) {
        String sql = switch (db.getDatabaseType()) {
            case SQLITE -> "INSERT INTO lp_groups(name, priority, prefix) VALUES(?, ?, ?) " +
                    "ON CONFLICT(name) DO UPDATE SET priority=excluded.priority, prefix=excluded.prefix";
            case MYSQL, MARIADB -> "INSERT INTO lp_groups(name, priority, prefix) VALUES(?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE priority=VALUES(priority), prefix=VALUES(prefix)";
        };
        return db.executeSqlFuture(sql, name, priority, prefix == null ? "" : prefix).thenApply(x -> null);
    }

    /**
     * Deletes a group and all associated data from the database asynchronously. This method removes
     * the group's permissions, user group associations (both permanent and temporary),
     * and the group record itself.
     *
     * @param name the name of the group to delete
     * @return a CompletableFuture that completes when the group and all associated data have been
     *         successfully deleted, or completes exceptionally if an error occurs
     */
    public CompletableFuture<Void> deleteGroup(String name) {
        CompletableFuture<Void> f1 = db.executeSqlFuture("DELETE FROM lp_group_permissions WHERE group_name = ?", name).thenApply(x -> null);
        CompletableFuture<Void> f2 = db.executeSqlFuture("DELETE FROM lp_user_groups WHERE group_name = ?", name).thenApply(x -> null);
        CompletableFuture<Void> f3 = db.executeSqlFuture("DELETE FROM lp_user_temp_groups WHERE group_name = ?", name).thenApply(x -> null);
        CompletableFuture<Void> f4 = db.executeSqlFuture("DELETE FROM lp_groups WHERE name = ?", name).thenApply(x -> null);
        return f1.thenCompose(v -> f2).thenCompose(v -> f3).thenCompose(v -> f4);
    }

    /**
     * Inserts or updates a group permission with the specified group name, permission node, and decision in the database.
     * If the permission already exists, its decision is updated.
     * The implementation uses database-specific SQL commands for conflict resolution.
     *
     * @param groupName the name of the group to which the permission belongs
     * @param node the permission node to be inserted or updated
     * @param decision the permission decision to associate with the node; it can be ALLOW, DENY, or NOT_SET
     * @return a CompletableFuture that completes when the operation is finished successfully
     *         or exceptionally if an error occurs
     */
    public CompletableFuture<Void> upsertGroupPermission(String groupName, String node, PermissionDecision decision) {
        int d = encode(decision);
        String sql = switch (db.getDatabaseType()) {
            case SQLITE -> "INSERT INTO lp_group_permissions(group_name, node, decision) VALUES(?, ?, ?) " +
                    "ON CONFLICT(group_name, node) DO UPDATE SET decision=excluded.decision";
            case MYSQL, MARIADB -> "INSERT INTO lp_group_permissions(group_name, node, decision) VALUES(?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE decision=VALUES(decision)";
        };
        return db.executeSqlFuture(sql, groupName, node, d).thenApply(x -> null);
    }

    /**
     * Asynchronously deletes a specific permission node for a given group from the database.
     * This method removes the entry matching the provided group name and permission node
     * from the "lp_group_permissions" table.
     *
     * @param groupName the name of the group whose permission is to be deleted
     * @param node the permission node to be deleted
     * @return a CompletableFuture that completes when the operation is finished successfully,
     *         or completes exceptionally if an error occurs
     */
    public CompletableFuture<Void> deleteGroupPermission(String groupName, String node) {
        return db.executeSqlFuture(
                "DELETE FROM lp_group_permissions WHERE group_name = ? AND node = ?",
                groupName, node
        ).thenApply(x -> null);
    }

    /**
     * Adds a user to a specified permanent group in the database. This method
     * ensures that the user exists in the "lp_users" table before associating the user
     * with the specified group. The operation is performed asynchronously using database-specific
     * SQL commands to handle the group membership insertion.
     *
     * @param uuid      the unique identifier of the user to be added to the group
     * @param groupName the name of the group to associate the user with
     * @return a CompletableFuture that completes when the operation is finished successfully,
     *         or completes exceptionally if an error occurs
     */
    public CompletableFuture<Void> addUserGroup(UUID uuid, String groupName) {
        String sql = switch (db.getDatabaseType()) {
            case SQLITE -> "INSERT OR IGNORE INTO lp_user_groups(uuid, group_name) VALUES(?, ?)";
            case MYSQL, MARIADB -> "INSERT IGNORE INTO lp_user_groups(uuid, group_name) VALUES(?, ?)";
        };
        return ensureUserRow(uuid).thenCompose(v -> db.executeSqlFuture(sql, uuid.toString(), groupName).thenApply(x -> null));
    }

    /**
     * Removes a user group association from the database for the specified user and group name.
     *
     * @param uuid the unique identifier of the user
     * @param groupName the name of the group to be removed
     * @return a CompletableFuture that completes when the operation is finished
     */
    public CompletableFuture<Void> removeUserGroup(UUID uuid, String groupName) {
        return db.executeSqlFuture(
                "DELETE FROM lp_user_groups WHERE uuid = ? AND group_name = ?",
                uuid.toString(), groupName
        ).thenApply(x -> null);
    }

    /**
     * Inserts or updates a temporary group association for a user in the database. If the user-group
     * pair already exists, the expiration time is updated. Otherwise, a new row is inserted.
     *
     * @param uuid      The unique identifier of the user.
     * @param groupName The name of the temporary group.
     * @param expiresAt The expiration time of the temporary group association.
     * @return A CompletableFuture that completes when the operation finishes.
     */
    public CompletableFuture<Void> upsertUserTempGroup(UUID uuid, String groupName, Instant expiresAt) {
        long ms = expiresAt.toEpochMilli();
        String sql = switch (db.getDatabaseType()) {
            case SQLITE -> "INSERT INTO lp_user_temp_groups(uuid, group_name, expires_at) VALUES(?, ?, ?) " +
                    "ON CONFLICT(uuid, group_name) DO UPDATE SET expires_at=excluded.expires_at";
            case MYSQL, MARIADB -> "INSERT INTO lp_user_temp_groups(uuid, group_name, expires_at) VALUES(?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE expires_at=VALUES(expires_at)";
        };
        return ensureUserRow(uuid).thenCompose(v -> db.executeSqlFuture(sql, uuid.toString(), groupName, ms).thenApply(x -> null));
    }

    /**
     * Deletes a temporary user group from the database based on the provided UUID and group name.
     *
     * @param uuid the unique identifier of the user
     * @param groupName the name of the temporary group to be deleted
     * @return a CompletableFuture representing the completion of the deletion operation
     */
    public CompletableFuture<Void> deleteUserTempGroup(UUID uuid, String groupName) {
        return db.executeSqlFuture(
                "DELETE FROM lp_user_temp_groups WHERE uuid = ? AND group_name = ?",
                uuid.toString(), groupName
        ).thenApply(x -> null);
    }

    /**
     * Cleans up expired temporary groups for the specified user.
     * <p>
     * This method deletes all temporary group entries associated with the user's UUID
     * that have an expiration time less than or equal to the provided timestamp.
     *
     * @param uuid the unique identifier of the user whose expired temporary groups are to be cleaned up
     * @param now the current timestamp used to determine expired entries
     * @return a CompletableFuture that completes when the cleanup operation is finished
     */
    public CompletableFuture<Void> cleanupExpiredTempGroups(UUID uuid, Instant now) {
        return db.executeSqlFuture(
                "DELETE FROM lp_user_temp_groups WHERE uuid = ? AND expires_at <= ?",
                uuid.toString(), now.toEpochMilli()
        ).thenApply(x -> null);
    }

    /**
     * Encodes a given PermissionDecision into an integer value representation.
     *
     * @param permissionDecision the PermissionDecision to encode. It must be one of the predefined
     *                            values: ALLOW, DENY, or NOT_SET.
     * @return an integer corresponding to the provided PermissionDecision:
     *         0 for ALLOW, 1 for DENY, and 2 for NOT_SET.
     */
    public static int encode(PermissionDecision permissionDecision) {
        return switch (permissionDecision) {
            case ALLOW -> 0;
            case DENY -> 1;
            case NOT_SET -> 2;
        };
    }

    /**
     * Decodes a raw input object into a PermissionDecision enum value.
     *
     * @param raw the input object to decode; it can be null, a number, or a string
     *            representing a numeric value.
     * @return the decoded PermissionDecision enum value. Returns PermissionDecision.ALLOW
     *         for 0, PermissionDecision.DENY for 1, and PermissionDecision.NOT_SET for all
     *         other cases, including null input.
     */
    public static PermissionDecision decodeDecision(Object raw) {
        if (raw == null) return PermissionDecision.NOT_SET;
        int value = (raw instanceof Number number) ? number.intValue() : Integer.parseInt(raw.toString());
        return switch (value) {
            case 0 -> PermissionDecision.ALLOW;
            case 1 -> PermissionDecision.DENY;
            default -> PermissionDecision.NOT_SET;
        };
    }
}