package io.nexstudios.legendperms.perms;

import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissibleBase;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.UUID;

public final class LegendPermissible extends PermissibleBase {

    private final Player player;
    private final PermissionResolver resolver;

    public LegendPermissible(Player player, PermissionResolver resolver) {
        super(player);
        this.player = player;
        this.resolver = resolver;
    }

    @Override
    public boolean hasPermission(@NotNull String inName) {
        if (inName.isBlank()) return true;

        // if (player.isOp()) return true;

        UUID uuid = player.getUniqueId();

        PermissionDecision decision = resolver.decide(uuid, inName);
        if (decision != PermissionDecision.NOT_SET) {
            return decision == PermissionDecision.ALLOW;
        }

        // Fallback
        return super.hasPermission(inName);
    }

    @Override
    public boolean hasPermission(@NotNull Permission perm) {
        return hasPermission(perm.getName());
    }

    @Override
    public @NotNull Set<PermissionAttachmentInfo> getEffectivePermissions() {
        return super.getEffectivePermissions();
    }
}
