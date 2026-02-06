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
     *
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

    public boolean userRemoveGroup(UUID uuid, String groupName) {
        LegendUser user = users.computeIfAbsent(uuid, LegendUser::new);
        boolean changed = user.getGroups().remove(groupName);

        if (changed) {
            if (repository != null) {
                repository.removeUserGroup(uuid, groupName)
                        .exceptionally(ex -> {
                            logger.warning("DB userRemoveGroup failed: " + ex);
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
     * Rebuilds the state of a user's permissions, groups, and other related configurations.
     * Ensures the user has necessary default settings, resolves and merges permissions,
     * refreshes in-game components such as the tablist and commands, and purges expired temporary groups.
     *
     * @param uuid the UUID of the user for whom the state is to be rebuilt
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