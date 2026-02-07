package io.nexstudios.legendperms.perms;

import io.nexstudios.legendperms.utils.LegendLogger;
import lombok.Getter;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Responsible for managing the injection and restoration of custom permissible objects
 * for {@link Player} instances, enabling enhanced permission handling through a
 * custom {@link LegendPermissible}.
 * <p>
 * This class leverages CraftBukkit internals to replace and store default permissible
 * objects with custom ones dynamically at runtime. It provides functionality for both
 * injection and restoration, as well as handling potential incompatibilities or errors
 * due to changing server internals across different versions.
 * <p>
 * Designed to safely handle injection failures by disabling further injections while
 * retaining minimal fallback behaviors to maintain server stability.
 */
public final class PermissibleInjector {

    private final PermissionResolver resolver;
    private final LegendLogger logger;

    // Resolved lazily, because CraftBukkit internals can differ across versions.
    private volatile Field permField;

    // Stores original permissible
    private final Map<UUID, Object> originalPermissible = new ConcurrentHashMap<>();

    @Getter
    private volatile boolean injectionEnabled = true;
    private volatile boolean failureLogged = false;

    public PermissibleInjector(PermissionResolver resolver, LegendLogger logger) {
        this.resolver = resolver;
        this.logger = logger;
    }

    public void inject(Player player) {
        if (!injectionEnabled) return;

        try {
            Field f = resolvePermField(player);
            Object current = f.get(player);

            if (current instanceof LegendPermissible) {
                // Already injected
                return;
            }

            originalPermissible.put(player.getUniqueId(), current);
            f.set(player, new LegendPermissible(player, resolver));
            player.recalculatePermissions();
        } catch (Throwable t) {
            disableInjection("inject", player, t);
        }
    }

    public void uninject(Player player) {
        // Even if injection is disabled, we still try to restore if we have a stored original
        try {
            Object original = originalPermissible.remove(player.getUniqueId());
            if (original == null) return;

            Field f = resolvePermField(player);
            f.set(player, original);
            player.recalculatePermissions();
        } catch (Throwable t) {
            disableInjection("uninject", player, t);
        }
    }

    public void uninjectAll(Iterable<? extends Player> players) {
        for (Player p : players) {
            try {
                uninject(p);
            } catch (Throwable ignored) { }
        }
    }

    private void disableInjection(String phase, Player player, Throwable t) {
        injectionEnabled = false;

        if (!failureLogged) {
            failureLogged = true;

            String playerName = (player != null ? player.getName() : "unknown");
            logger.warning(List.of(
                    "Permissible injection failed (" + phase + ") and will be disabled for this session.",
                    "Server/version: Paper 1.21.11 (CraftBukkit internals may have changed).",
                    "Fallback mode: LegendPerms will continue without injection (wildcards may be incomplete).",
                    "First failure on player: " + playerName,
                    "Error: " + t.getClass().getSimpleName() + ": " + (t.getMessage() == null ? "no message" : t.getMessage())
            ));
        }

        logger.debug("Injection error (" + phase + "): " + t, 3);
    }

    /**
     * Resolves and retrieves the "perm" field associated with the given player's class.
     * If the field has been resolved already, it returns the cached field. Otherwise, it
     * synchronizes access to resolve and cache the field by searching for it through the player's
     * class hierarchy.
     *
     * @param player the Player instance whose class is used to locate the "perm" field
     * @return the Field instance representing the "perm" field
     * @throws NoSuchFieldException if the "perm" field is not found in the player's class hierarchy
     */
    private Field resolvePermField(Player player) throws NoSuchFieldException {
        Field f = this.permField;
        if (f != null) return f;

        synchronized (this) {
            f = this.permField;
            if (f != null) return f;

            Field resolved = findPermField(player.getClass());
            resolved.setAccessible(true);
            this.permField = resolved;
            return resolved;
        }
    }

    /**
     * Attempts to locate the field named "perm" starting from the given class and traversing
     * its class hierarchy. The method searches in the current class and continues upward
     * through its superclasses until the field is found or the hierarchy is exhausted.
     *
     * @param startClass the class from which to begin the search for the "perm" field
     * @return the {@link Field} instance representing the "perm" field if found
     * @throws NoSuchFieldException if the "perm" field is not found in the class hierarchy
     */
    private static Field findPermField(Class<?> startClass) throws NoSuchFieldException {
        Class<?> c = startClass;
        while (c != null) {
            try {
                return c.getDeclaredField("perm");
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field 'perm' not found in class hierarchy of " + startClass.getName());
    }
}