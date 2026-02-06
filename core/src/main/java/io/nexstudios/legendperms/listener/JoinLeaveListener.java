package io.nexstudios.legendperms.listener;

import io.nexstudios.legendperms.LegendPerms;
import io.nexstudios.legendperms.perms.model.LegendGroup;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

public record JoinLeaveListener(LegendPerms plugin) implements Listener {

    @EventHandler
    public void onPrintJoinMessage(PlayerJoinEvent e) {

        String newJoinMessageRaw = plugin.getSettingsFile().
                getString("chat.join-message", "<yellow><name> joined the server.");

        LegendGroup group = getGroup(e.getPlayer().getUniqueId());
        if(group == null) return;

        Component newJoinMessage = MiniMessage.miniMessage().deserialize(newJoinMessageRaw, TagResolver.resolver(
                Placeholder.parsed("name", e.getPlayer().getName()),
                Placeholder.parsed("prefix", group.getPrefix())
        )).decoration(TextDecoration.ITALIC, false);


        e.joinMessage(newJoinMessage);

    }

    @EventHandler
    public void onPrintQuitMessage(PlayerQuitEvent e) {

        String newQuitMessageRaw = plugin.getSettingsFile().
                getString("chat.quit-message", "<yellow><name> left the server.");

        LegendGroup group = getGroup(e.getPlayer().getUniqueId());

        Component newQuitMessage = MiniMessage.miniMessage().deserialize(newQuitMessageRaw, TagResolver.resolver(
                Placeholder.parsed("name", e.getPlayer().getName()),
                Placeholder.parsed("prefix", group.getPrefix())
        )).decoration(TextDecoration.ITALIC, false);

        e.quitMessage(newQuitMessage);
    }


    private LegendGroup getGroup(UUID uuid) {
        String currentGroupName = plugin.getPermissionService().getUserPrimaryGroupName(uuid);
        return plugin.getPermissionService().getGroup(currentGroupName);
    }

}
