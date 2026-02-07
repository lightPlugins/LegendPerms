package io.nexstudios.legendperms.perms;

import io.nexstudios.legendperms.LegendPerms;
import io.nexstudios.legendperms.perms.model.LegendGroup;
import io.nexstudios.legendperms.perms.model.LegendUser;
import io.nexstudios.legendperms.perms.storage.PermissionDAO;
import io.nexstudios.legendperms.utils.LegendLogger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service class responsible for managing and applying permissions, groups, and related functionalities
 * in the LegendPerms system. This class serves as a central interface for user and group management,
 * permission resolutions, and persistence mechanisms.
 * <p>
 * The service provides methods to create, delete, and modify groups, manage user memberships in groups,
 * resolve effective permissions, handle temporary group expirations, and rebuild user states.
 * Additionally, it integrates with an underlying storage mechanism for persisting data.
 * <p>
 * This class implements {@link PermissionResolver}
 * to provide permission resolution functionality.
 */
public final class LegendPermissionService implements PermissionResolver {

    public static final String DEFAULT_GROUP_NAME = "Default";
    private static final DateTimeFormatter EXPIRATION_FORMATTER =
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    private final LegendLogger logger;

    private final Map<String, LegendGroup> groups = new ConcurrentHashMap<>();
    private final Map<UUID, LegendUser> users = new ConcurrentHashMap<>();

    // Effective permissions cache (already merged from groups by priority)
    private final Map<UUID, Map<String, PermissionDecision>> effective = new ConcurrentHashMap<>();

    private final PermissionDAO repository;
    private volatile boolean bulkLoading = false;

    public LegendPermissionService(LegendLogger logger, PermissionDAO repository) {
        this.logger = logger;
        this.repository = repository;
    }

    /**
     * Ensures that a default group with the predefined name {@code DEFAULT_GROUP_NAME} exists
     * in the system's group collection. If the default group is not already present, it is created
     * and added to the {@code groups} map. This process guarantees the availability of a default group
     * to serve as a fallback or baseline for functionality relying on group definitions.
     * <p>
     * If a repository is configured, the method attempts to insert the default group into the
     * underlying database asynchronously. However, if the group already exists, the operation
     * does not overwrite the existing database entry to preserve its current settings.
     * Any exceptions encountered during the database operation are logged as warnings.
     */
    public void ensureDefaultGroup() {
        groups.computeIfAbsent(DEFAULT_GROUP_NAME, LegendGroup::new);
        if (repository != null) {
            LegendGroup legendGroup = groups.get(DEFAULT_GROUP_NAME);

            // do not overwrite the existing default group in the database
            repository.insertGroupIfAbsent(
                    legendGroup.getName(),
                    legendGroup.getPriority(),
                    legendGroup.getPrefix()
            ).exceptionally(ex -> {
                logger.warning("DB insert default group failed: " + ex);
                return null;
            });
        }
    }

    public void beginBulkLoad() {
        this.bulkLoading = true;
    }

    public void endBulkLoadAndRebuildOnline() {
        this.bulkLoading = false;
        Bukkit.getOnlinePlayers().forEach(p -> rebuildUser(p.getUniqueId()));
    }

    /**
     * Loads all group and group permission data from the storage system into memory. This method
     * initializes the data structures with the records retrieved from the repository and processes
     * each group and its associated permissions to ensure the in-memory state reflects the latest
     * stored data.
     * <p>
     * The method operates in several steps:
     * <p>1. Begins a bulk load operation to manage the in-memory data update.
     * <p>2. Retrieves all groups and their metadata (e.g., name, priority, prefix) from the storage system.
     * <p>3. Updates or creates in-memory `LegendGroup` objects based on the retrieved group metadata.
     * <p>4. Retrieves all permissions for each group from the storage system.
     * <p>5. Populates the permissions of respective in-memory `LegendGroup` objects.
     * <p>6. Ensures that the default group is defined in the system.
     * <p>7. Finalizes the bulk load and rebuilds the online state to reflect changes.
     * <p>
     * If the repository is not configured or available, the method returns without performing any operations.
     * <p>
     * This method interacts with the following:
     * <p>- The repository to load group and permission metadata.
     * <p>- The `groups` in-memory collection to store and update `LegendGroup` objects.
     * <p>
     * Exceptions during the load operations are managed internally to ensure that the bulk load
     * concludes properly and resources are released consistently.
     */
    public void loadAllFromStorage() {
        if (repository == null) return;

        beginBulkLoad();

        try {
            var groupRows = repository.loadAllGroups().join();
            for (var row : groupRows) {
                String name = String.valueOf(row.get("name"));
                int priority = ((Number) row.get("priority")).intValue();
                String prefix = String.valueOf(row.get("prefix"));

                LegendGroup g = groups.computeIfAbsent(name, LegendGroup::new);
                g.setPriority(priority);
                g.setPrefix(prefix);
            }

            var permRows = repository.loadAllGroupPermissions().join();
            for (var row : permRows) {
                String groupName = String.valueOf(row.get("group_name"));
                String node = String.valueOf(row.get("node"));
                PermissionDecision decision = PermissionDAO.decodeDecision(row.get("decision"));

                LegendGroup legendGroup = groups.computeIfAbsent(groupName, LegendGroup::new);
                if (node != null && !node.isBlank()) {
                    legendGroup.getPermissions().put(node, decision);
                }
            }

            ensureDefaultGroup();
        } finally {
            endBulkLoadAndRebuildOnline();
        }
    }

    /**
     * Asynchronously loads user data from a storage repository and updates internal state.
     * This method ensures that the user's groups (both permanent and temporary) are loaded from
     * the storage and the user's offline cache is rebuilt.
     * If the repository is not available, the method assigns the default group and rebuilds the cache directly.
     *
     * @param uuid The unique identifier of the user whose data is to be loaded. Must not be null.
     * @return A CompletableFuture that completes once the user loading operation has finished.
     *         The CompletableFuture’s result is {@code null}, and any exceptions during the loading process
     *         will also be propagated through this future.
     */
    public CompletableFuture<Void> loadUserFromStorageAsync(UUID uuid) {
        if (uuid == null) return CompletableFuture.completedFuture(null);

        if (repository == null) {
            ensureUserHasDefaultGroup(uuid);
            rebuildUser(uuid);
            return CompletableFuture.completedFuture(null);
        }

        return repository.ensureUserRow(uuid)
                .thenCompose(v -> repository.loadUserPermanentGroups(uuid))
                .thenCompose(permanentRows -> {
                    LegendUser legendUser = users.computeIfAbsent(uuid, LegendUser::new);

                    legendUser.getGroups().clear();
                    legendUser.getTemporaryGroups().clear();

                    for (var row : permanentRows) {
                        String groupName = String.valueOf(row.get("group_name"));
                        if (groupName != null && !groupName.isBlank()) {
                            legendUser.getGroups().add(groupName);
                        }
                    }

                    return repository.loadUserActiveTempGroups(uuid, Instant.now());
                })
                .thenCompose(tempRows -> {
                    LegendUser legendUser = users.computeIfAbsent(uuid, LegendUser::new);

                    for (var row : tempRows) {
                        String groupName = String.valueOf(row.get("group_name"));
                        Object expiresRaw = row.get("expires_at");
                        if (groupName == null || groupName.isBlank() || expiresRaw == null) continue;

                        long ms = (expiresRaw instanceof Number n) ? n.longValue() : Long.parseLong(expiresRaw.toString());
                        legendUser.getTemporaryGroups().put(groupName, Instant.ofEpochMilli(ms));
                    }

                    CompletableFuture<Void> done = new CompletableFuture<>();
                    Bukkit.getScheduler().runTask(LegendPerms.getInstance(), () -> {
                        try {
                            ensureUserHasDefaultGroup(uuid);
                            rebuildUser(uuid); // offline cache
                            done.complete(null);
                        } catch (Throwable t) {
                            done.completeExceptionally(t);
                        }
                    });
                    return done;
                })
                .exceptionally(ex -> {
                    logger.error(List.of(
                            "Failed to load user from database async: " + uuid,
                            "Error: " + ex.getClass().getSimpleName() + ": " + (ex.getMessage() == null ? "no message" : ex.getMessage())
                    ));

                    // sync cache rebuild
                    Bukkit.getScheduler().runTask(LegendPerms.getInstance(), () -> {
                        ensureUserHasDefaultGroup(uuid);
                        rebuildUser(uuid);
                    });

                    return null;
                });
    }

    // creates a new group with the given name
    public boolean createGroup(String name) {
        if (name == null || name.isBlank()) return false;
        boolean created = groups.putIfAbsent(name, new LegendGroup(name)) == null;
        if (created && repository != null) {
            LegendGroup legendGroup = groups.get(name);
            repository.upsertGroup(legendGroup.getName(), legendGroup.getPriority(), legendGroup.getPrefix())
                    .exceptionally(ex -> {
                        logger.warning("DB createGroup failed: " + ex);
                        return null;
                    });
        }
        return created;
    }

    /**
     * Updates the priority of a specific group and synchronizes the change with the repository, if available.
     * This method modifies the priority of an existing group, persists the change to the underlying database,
     * and triggers a rebuild of all user configurations associated with the group.
     *
     * @param groupName the name of the group whose priority is to be updated; must not be null
     * @param priority the new priority value to assign to the group
     */
    public void setGroupPriority(String groupName, int priority) {
        LegendGroup legendGroup = requireGroup(groupName);
        legendGroup.setPriority(priority);

        if (repository != null) {
            repository.upsertGroup(legendGroup.getName(), legendGroup.getPriority(), legendGroup.getPrefix())
                    .exceptionally(ex -> {
                        logger.warning("DB setGroupPriority failed: " + ex);
                        return null;
                    });
        }

        rebuildAllUsersWithGroup(groupName);
    }

    /**
     * Deletes a group by its name and updates the associated users and repository.
     * <p>
     * This method removes the specified group from the internal group storage
     * and ensures that all users associated with the group are updated accordingly.
     * It also handles repository updates if a repository is configured.
     * The default group cannot be deleted.
     *
     * @param name the name of the group to be deleted. Must not be null or empty.
     * @return true if the group was successfully deleted, false otherwise.
     */
    public boolean deleteGroup(String name) {
        if (name == null || name.isBlank()) return false;

        if (DEFAULT_GROUP_NAME.equalsIgnoreCase(name)) {
            return false;
        }

        LegendGroup removed = groups.remove(name);
        if (removed == null) {
            return false;
        }

        if (repository != null) {
            repository.deleteGroup(removed.getName())
                    .exceptionally(ex -> {
                        logger.warning("DB deleteGroup failed: " + ex);
                        return null;
                    });
        }

        for (Map.Entry<UUID, LegendUser> e : users.entrySet()) {
            LegendUser legendUser = e.getValue();
            boolean changed = legendUser.getGroups().removeIf(g -> g.equalsIgnoreCase(name));

            boolean tempChanged = legendUser.getTemporaryGroups().keySet().removeIf(g -> g.equalsIgnoreCase(name));
            changed = changed || tempChanged;

            if (changed && legendUser.getGroups().isEmpty() && legendUser.getTemporaryGroups().isEmpty()) {
                legendUser.getGroups().add(DEFAULT_GROUP_NAME);
            }
            if (changed) {
                rebuildUser(e.getKey());
            }
        }

        return true;
    }

    public LegendGroup getGroup(String name) {
        return groups.get(name);
    }

    public void setGroupPrefix(String groupName, String prefix) {
        LegendGroup legendGroup = requireGroup(groupName);
        legendGroup.setPrefix(prefix);

        if (repository != null) {
            repository.upsertGroup(legendGroup.getName(), legendGroup.getPriority(), legendGroup.getPrefix())
                    .exceptionally(ex -> {
                        logger.warning("DB setGroupPrefix failed: " + ex);
                        return null;
                    });
        }

        rebuildAllUsersWithGroup(groupName);
    }

    public void addGroupPermission(String groupName, String node, PermissionDecision decision) {
        if (node == null || node.isBlank()) return;
        LegendGroup legendGroup = requireGroup(groupName);
        legendGroup.getPermissions().put(node, decision);

        if (repository != null) {
            repository.upsertGroupPermission(legendGroup.getName(), node, decision)
                    .exceptionally(ex -> {
                        logger.warning("DB addGroupPermission failed: " + ex);
                        return null;
                    });
        }

        rebuildAllUsersWithGroup(groupName);
    }

    public boolean removeGroupPermission(String groupName, String node) {
        if (node == null || node.isBlank()) return false;

        LegendGroup legendGroup = requireGroup(groupName);

        PermissionDecision removed = legendGroup.getPermissions().remove(node);
        if (removed == null) {
            return false;
        }

        if (repository != null) {
            repository.deleteGroupPermission(legendGroup.getName(), node)
                    .exceptionally(ex -> {
                        logger.warning("DB removeGroupPermission failed: " + ex);
                        return null;
                    });
        }

        rebuildAllUsersWithGroup(groupName);
        return true;
    }

    /**
     * Ensures that the user associated with the given UUID has the default group assigned.
     * If the user does not belong to any group or temporary group, the default group is added to their group list.
     * Additionally, it updates the database repository if applicable.
     *
     * @param uuid the unique identifier of the user whose groups are being checked and potentially updated
     */
    public void ensureUserHasDefaultGroup(UUID uuid) {
        LegendUser legendUser = users.computeIfAbsent(uuid, LegendUser::new);
        if (legendUser.getGroups().isEmpty() && legendUser.getTemporaryGroups().isEmpty()) {
            legendUser.getGroups().add(DEFAULT_GROUP_NAME);
            if (!bulkLoading) rebuildUser(uuid);

            if (repository != null) {
                repository.addUserGroup(uuid, DEFAULT_GROUP_NAME)
                        .exceptionally(ex -> {
                            logger.warning("DB ensure default legendUser group failed: " + ex);
                            return null;
                        });
            }
        }
    }

    /**
     * Retrieves the expiration date for a temporary group associated with a user.
     * If the user's temporary group has already expired, their temporary groups
     * are purged and rebuilt if necessary. If no expiration exists, returns "never".
     *
     * @param uuid the unique identifier of the user
     * @param groupName the name of the temporary group
     * @return a formatted string representing the expiration date, or "never"
     *         if no expiration date exists or the input is invalid
     */
    public String getTemporaryGroupExpiration(UUID uuid, String groupName) {
        if (uuid == null || groupName == null || groupName.isBlank()) return "never";

        LegendUser legendUser = users.get(uuid);
        if (legendUser == null) return "never";

        boolean purged = purgeExpiredTemporaryGroups(legendUser);
        if (purged) rebuildUser(uuid);

        Instant expiresAt = legendUser.getTemporaryGroups().get(groupName);
        if (expiresAt == null) return "never";

        return EXPIRATION_FORMATTER.format(expiresAt);
    }

    /**
     * Adds a user to a specified group. This method ensures the user exists
     * and purges their expired temporary groups before adding the new group.
     * If the group is successfully added, optional operations such as
     * rebuilding the user and updating the repository will be triggered.
     *
     * @param uuid      the unique identifier of the user
     * @param groupName the name of the group to add the user to
     * @return true if the group was successfully added, false if the user was
     *         already a member of the group
     */
    public boolean userAddGroup(UUID uuid, String groupName) {
        requireGroup(groupName);
        LegendUser user = users.computeIfAbsent(uuid, LegendUser::new);
        purgeExpiredTemporaryGroups(user);

        boolean changed = user.getGroups().add(groupName);
        if (changed) {
            if (!bulkLoading) rebuildUser(uuid);

            if (repository != null) {
                repository.addUserGroup(uuid, groupName)
                        .exceptionally(ex -> {
                            logger.warning("DB userAddGroup failed: " + ex);
                            return null;
                        });
            }
        }
        return changed;
    }

    /**
     * Removes a user from a specified group. This method checks for both permanent and temporary
     * group memberships and removes the user from the group accordingly. If the user belongs
     * only to the default group after removal, the method ensures that the default group is maintained.
     *
     * @param uuid      The unique identifier of the user. Cannot be null.
     * @param groupName The name of the group to remove the user from. Cannot be null or blank.
     * @return true if the user was successfully removed from the specified group or temporary groups,
     *         false otherwise.
     */
    public boolean userRemoveGroup(UUID uuid, String groupName) {
        if (uuid == null || groupName == null || groupName.isBlank()) return false;

        LegendUser user = users.computeIfAbsent(uuid, LegendUser::new);
        // check if the temp group already expired
        purgeExpiredTemporaryGroups(user);

        boolean removedPermanent = user.getGroups().removeIf(group -> group != null && group.equalsIgnoreCase(groupName));
        boolean removedTemporary = user.getTemporaryGroups().keySet()
                .removeIf(group -> group != null && group.equalsIgnoreCase(groupName));

        boolean changed = removedPermanent || removedTemporary;

        if (changed && repository != null) {
            if (removedPermanent) {
                repository.removeUserGroup(uuid, groupName)
                        .exceptionally(ex -> {
                            logger.warning("DB userRemoveGroup failed: " + ex);
                            return null;
                        });
            }
            if (removedTemporary) {
                repository.deleteUserTempGroup(uuid, groupName)
                        .exceptionally(ex -> {
                            logger.warning("DB userRemoveTemporaryGroup (via removeGroup) failed: " + ex);
                            return null;
                        });
            }
        }

        if (changed && !bulkLoading) rebuildUser(uuid);

        if (user.getGroups().isEmpty() && user.getTemporaryGroups().isEmpty()) {
            user.getGroups().add(DEFAULT_GROUP_NAME);
            if (!bulkLoading) rebuildUser(uuid);

            if (repository != null) {
                repository.addUserGroup(uuid, DEFAULT_GROUP_NAME)
                        .exceptionally(ex -> {
                            logger.warning("DB keep default group failed: " + ex);
                            return null;
                        });
            }
        }
        return changed;
    }

    public String getUserPrimaryGroupName(UUID uuid) {
        LegendUser user = users.get(uuid);
        if (user == null) return DEFAULT_GROUP_NAME;

        boolean purged = purgeExpiredTemporaryGroups(user);
        if (purged) rebuildUser(uuid);

        LegendGroup primaryGroup = resolvePrimaryGroup(user);
        return primaryGroup == null ? DEFAULT_GROUP_NAME : primaryGroup.getName();
    }

    public List<String> getUserGroupNames(UUID uuid) {
        LegendUser user = users.get(uuid);
        if (user == null) return List.of();

        boolean purged = purgeExpiredTemporaryGroups(user);
        if (purged) rebuildUser(uuid);

        // Returns both permanent + active temporary groups (as "effective" groups)
        List<String> out = new ArrayList<>(getAllActiveGroupNames(user));
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(out);
    }

    public Map<String, PermissionDecision> getEffectivePermissions(UUID uuid) {
        Map<String, PermissionDecision> map = effective.get(uuid);
        if (map == null) return Map.of();
        return Map.copyOf(map);
    }

    public List<String> getAllGroupNames() {
        List<String> out = new ArrayList<>(groups.keySet());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }


    public String getUserPrimaryPrefix(UUID uuid) {
        LegendUser user = users.get(uuid);
        if (user == null) return "";

        boolean purged = purgeExpiredTemporaryGroups(user);
        if (purged) rebuildUser(uuid);

        LegendGroup primaryGroup = resolvePrimaryGroup(user);
        return primaryGroup == null ? "" : primaryGroup.getPrefix();
    }

    public void tickExpirations(UUID uuid) {
        if (uuid == null) return;

        LegendUser user = users.get(uuid);
        if (user == null) return;

        if (user.getTemporaryGroups().isEmpty()) {
            return;
        }

        boolean purged = purgeExpiredTemporaryGroups(user);
        if (purged) {
            if (!bulkLoading) rebuildUser(uuid);

            if (repository != null) {
                repository.cleanupExpiredTempGroups(uuid, Instant.now())
                        .exceptionally(ex -> {
                            logger.warning("DB cleanupExpiredTempGroups failed: " + ex);
                            return null;
                        });
            }
        }
    }

    /**
     * Assigns a user to a temporary group for a specified duration.
     * If the group assignment already exists for the user but with a different duration,
     * it will be updated. Temporary groups for the user that have expired will also
     * be purged before assigning the new group.
     *
     * @param uuid       The unique identifier of the user.
     * @param groupName  The name of the group to add the user to temporarily.
     * @param duration   The duration for the group membership. Must be a positive non-zero value.
     *                   If null, zero, or negative, an IllegalArgumentException is thrown.
     *
     * @throws IllegalArgumentException if the specified duration is null, zero, or negative.
     */
    public void userAddTemporaryGroup(UUID uuid, String groupName, Duration duration) {
        requireGroup(groupName);
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Duration must be > 0");
        }

        LegendUser user = users.computeIfAbsent(uuid, LegendUser::new);
        purgeExpiredTemporaryGroups(user);

        Instant expiresAt = Instant.now().plus(duration);
        Instant previous = user.getTemporaryGroups().put(groupName, expiresAt);

        boolean changed = previous == null || !previous.equals(expiresAt);
        if (changed) {
            if (!bulkLoading) rebuildUser(uuid);

            if (repository != null) {
                repository.upsertUserTempGroup(uuid, groupName, expiresAt)
                        .exceptionally(ex -> {
                            logger.warning("DB userAddTemporaryGroup failed: " + ex);
                            return null;
                        });
            }
        }
    }

    public int getUserPrimaryPriority(UUID uuid) {
        LegendUser user = users.get(uuid);
        if (user == null) return 0;

        boolean purged = purgeExpiredTemporaryGroups(user);
        if (purged) rebuildUser(uuid);

        LegendGroup best = resolvePrimaryGroup(user);
        return best == null ? 0 : best.getPriority();
    }

    public boolean userHasPermanentGroup(UUID uuid, String groupName) {
        if (uuid == null || groupName == null || groupName.isBlank()) return false;
        LegendUser user = users.get(uuid);
        if (user == null) return false;
        return user.getGroups().stream().anyMatch(g -> g.equalsIgnoreCase(groupName));
    }

    public boolean userHasTemporaryGroup(UUID uuid, String groupName) {
        if (uuid == null || groupName == null || groupName.isBlank()) return false;
        LegendUser user = users.get(uuid);
        if (user == null) return false;

        boolean purged = purgeExpiredTemporaryGroups(user);
        if (purged) rebuildUser(uuid);

        return user.getTemporaryGroups().keySet().stream().anyMatch(g -> g.equalsIgnoreCase(groupName));
    }

    /**
     * Removes a user from a specified temporary group. If the removal results in the user having no
     * groups or temporary groups, the default group is added to ensure the user remains in at least one group.
     * This method rebuilds the user's data if necessary and updates the repository accordingly.
     *
     * @param uuid      The unique identifier of the user.
     * @param groupName The name of the temporary group to be removed from the user.
     */
    public void userRemoveTemporaryGroup(UUID uuid, String groupName) {
        LegendUser user = users.computeIfAbsent(uuid, LegendUser::new);
        purgeExpiredTemporaryGroups(user);

        Instant removed = user.getTemporaryGroups().remove(groupName);
        boolean changed = removed != null;

        if (changed) {
            if (!bulkLoading) rebuildUser(uuid);

            if (repository != null) {
                repository.deleteUserTempGroup(uuid, groupName)
                        .exceptionally(ex -> {
                            logger.warning("DB userRemoveTemporaryGroup failed: " + ex);
                            return null;
                        });
            }
        }

        if (user.getGroups().isEmpty() && user.getTemporaryGroups().isEmpty()) {
            user.getGroups().add(DEFAULT_GROUP_NAME);
            if (!bulkLoading) rebuildUser(uuid);

            if (repository != null) {
                repository.addUserGroup(uuid, DEFAULT_GROUP_NAME)
                        .exceptionally(ex -> {
                            logger.warning("DB keep default after temp removal failed: " + ex);
                            return null;
                        });
            }
        }

    }

    /**
     * Determines the permission decision for a specific node associated with the given user's UUID.
     * The method resolves permissions by checking exact node matches, wildcards, and global nodes,
     * in that order. If no applicable permission decision is found, it defaults to NOT_SET.
     *
     * @param uuid the unique identifier of the user whose permission is being checked
     * @param node the permission node to evaluate
     * @return the resolved {@link PermissionDecision} for the given user and node
     *         (ALLOW, DENY, or NOT_SET)
     */
    @Override
    public PermissionDecision decide(UUID uuid, String node) {
        if (uuid == null || node == null || node.isBlank()) return PermissionDecision.NOT_SET;

        LegendUser user = users.get(uuid);
        if (user != null) {
            boolean purged = purgeExpiredTemporaryGroups(user);
            if (purged) rebuildUser(uuid);
        }

        Map<String, PermissionDecision> map = effective.get(uuid);
        if (map == null) {
            // Ensure a baseline state
            ensureUserHasDefaultGroup(uuid);
            map = effective.get(uuid);
            if (map == null) return PermissionDecision.NOT_SET;
        }

        PermissionDecision exact = map.get(node);
        if (exact != null && exact != PermissionDecision.NOT_SET) return exact;

        int index = node.length();
        while (true) {
            index = node.lastIndexOf('.', index - 1);
            if (index < 0) break;

            String wc = node.substring(0, index) + ".*";
            PermissionDecision d = map.get(wc);
            if (d != null && d != PermissionDecision.NOT_SET) return d;
        }

        PermissionDecision global = map.get("*");
        if (global != null && global != PermissionDecision.NOT_SET) return global;

        return PermissionDecision.NOT_SET;
    }

    /**
     * Rebuilds the user's permissions, group associations, and other related data structures
     * based on the specified UUID. This method handles tasks such as purging expired temporary groups,
     * resolving group priorities, updating effective permissions, and refreshing various
     * game-related elements like commands, tablist display, and more.
     *
     * @param uuid the unique identifier of the user whose data is to be rebuilt
     */
    public void rebuildUser(UUID uuid) {
        LegendUser legendUser = users.computeIfAbsent(uuid, LegendUser::new);
        purgeExpiredTemporaryGroups(legendUser);

        if (legendUser.getGroups().isEmpty() && legendUser.getTemporaryGroups().isEmpty()) {
            legendUser.getGroups().add(DEFAULT_GROUP_NAME);
        }

        Set<String> allGroups = getAllActiveGroupNames(legendUser);

        List<LegendGroup> resolved = new ArrayList<>();
        for (String groupName : allGroups) {
            LegendGroup legendGroup = groups.get(groupName);
            if (legendGroup != null) resolved.add(legendGroup);
        }

        resolved.sort(Comparator
                .comparingInt(LegendGroup::getPriority)
                .thenComparing(LegendGroup::getName, String.CASE_INSENSITIVE_ORDER));

        Map<String, PermissionDecision> merged = new HashMap<>();
        for (LegendGroup legendGroup : resolved) {
            merged.putAll(legendGroup.getPermissions());
        }

        effective.put(uuid, new ConcurrentHashMap<>(merged));

        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            
            player.recalculatePermissions();
            
            // command refresh
            player.updateCommands();

            // tablist refresh
            try {
                LegendPerms plugin = LegendPerms.getInstance();
                String playerName = player.getName();

                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        String format = plugin.getSettingsFile().getString("tablist.name-format", "<white><name>");

                        String prefixRaw = getUserPrimaryPrefix(uuid);
                        Component prefixComp = (prefixRaw == null || prefixRaw.isBlank())
                                ? Component.empty()
                                : MiniMessage.miniMessage()
                                .deserialize(prefixRaw)
                                .decoration(TextDecoration.ITALIC, false);

                        Component listName = MiniMessage.miniMessage().deserialize(
                                format,
                                TagResolver.resolver(
                                        Placeholder.component("prefix", prefixComp),
                                        Placeholder.parsed("name", playerName)
                                )
                        ).decoration(TextDecoration.ITALIC, false);

                        Bukkit.getScheduler().runTask(plugin, () -> {
                            Player live = Bukkit.getPlayer(uuid);
                            if (live == null || !live.isOnline()) return;
                            live.playerListName(listName);

                            // update sorting order in tablist on any change (same as -> rebuild user)
                            if (plugin.getTablistPrefixListener() != null) {
                                plugin.getTablistPrefixListener().refreshTablistOrder(live);
                            }
                        });
                    } catch (Throwable ignored) { }
                });
            } catch (Throwable ignored) { }

            // TODO: scoreboard refresh
        }
    }

    /**
     * Resolves the primary group for a given user based on group priority and name.
     * If the user has multiple active groups, the group with the highest priority is selected.
     * In case of a tie, the group with the lexicographically smallest name is chosen.
     * If the user has no active groups or the input user is null, the method returns null.
     *
     * @param user the LegendUser object for whom the primary group is to be resolved
     * @return the LegendGroup object representing the primary group of the user, or null if no eligible group exists
     */
    private LegendGroup resolvePrimaryGroup(LegendUser user) {
        if (user == null) return null;

        Set<String> all = getAllActiveGroupNames(user);
        if (all.isEmpty()) return null;

        LegendGroup best = null;
        for (String groupName : all) {
            if (groupName == null) continue;

            LegendGroup legendGroup = groups.get(groupName);
            if (legendGroup == null) continue;

            if (best == null) {
                best = legendGroup;
                continue;
            }

            if (legendGroup.getPriority() > best.getPriority()) {
                best = legendGroup;
                continue;
            }

            if (legendGroup.getPriority() == best.getPriority()
                    && legendGroup.getName().compareToIgnoreCase(best.getName()) < 0) {
                best = legendGroup;
            }
        }

        return best;
    }

    private void rebuildAllUsersWithGroup(String groupName) {
        if (bulkLoading) return;

        for (Map.Entry<UUID, LegendUser> entry : users.entrySet()) {
            LegendUser legendUser = entry.getValue();

            boolean inPermanent = legendUser.getGroups().stream().anyMatch(g ->
                    g != null && g.equalsIgnoreCase(groupName));
            boolean inTemp = legendUser.getTemporaryGroups().keySet().stream().anyMatch(g ->
                    g != null && g.equalsIgnoreCase(groupName));

            // check if the group is in either permanent or temporary groups
            if (inPermanent || inTemp) {
                rebuildUser(entry.getKey());
            }
        }
    }

    private LegendGroup requireGroup(String groupName) {
        LegendGroup legendGroup = groups.get(groupName);
        if (legendGroup == null) throw new IllegalArgumentException("Group not found: " + groupName);
        return legendGroup;
    }

    private static Set<String> getAllActiveGroupNames(LegendUser user) {
        Set<String> out = new HashSet<>(user.getGroups());
        out.addAll(user.getTemporaryGroups().keySet());
        return out;
    }

    private static boolean purgeExpiredTemporaryGroups(LegendUser user) {
        Instant now = Instant.now();
        return user.getTemporaryGroups().entrySet().removeIf(e -> {
            Instant expiresAt = e.getValue();
            return expiresAt == null || !expiresAt.isAfter(now);
        });
    }
}