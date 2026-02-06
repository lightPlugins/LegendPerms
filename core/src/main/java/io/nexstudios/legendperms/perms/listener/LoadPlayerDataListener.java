package io.nexstudios.legendperms.perms.listener;

import io.nexstudios.legendperms.perms.LegendPermissionService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public record LoadPlayerDataListener(LegendPermissionService permissionService) implements Listener {

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        permissionService.loadUserFromStorageAsync(e.getPlayer().getUniqueId());
    }
}