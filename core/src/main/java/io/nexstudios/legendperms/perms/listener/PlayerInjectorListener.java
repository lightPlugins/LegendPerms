package io.nexstudios.legendperms.perms.listener;

import io.nexstudios.legendperms.perms.PermissibleInjector;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public record PlayerInjectorListener(PermissibleInjector injector) implements Listener {

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!injector.isInjectionEnabled()) {
            return;
        }
        injector.inject(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        injector.uninject(e.getPlayer());
    }
}
