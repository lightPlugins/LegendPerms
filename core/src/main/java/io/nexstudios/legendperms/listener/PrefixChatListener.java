package io.nexstudios.legendperms.listener;

import io.nexstudios.legendperms.LegendPerms;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public record PrefixChatListener(LegendPerms plugin) implements Listener {

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        event.renderer((source, sourceDisplayName, message, viewer) -> {
            String format = plugin.getSettingsFile().getString(
                    "chat.format",
                    "<gray><name><reset>: <message>"
            );

            String prefixRaw = plugin.getPermissionService().getUserPrimaryPrefix(source.getUniqueId());
            if (prefixRaw == null || prefixRaw.isBlank()) {
                prefixRaw = "";
            }

            Component prefixComponent = prefixRaw.isBlank()
                    ? Component.empty()
                    : MiniMessage.miniMessage()
                    .deserialize(prefixRaw)
                    .decoration(TextDecoration.ITALIC, false);

            TagResolver resolver = TagResolver.resolver(
                    Placeholder.component("prefix", prefixComponent),
                    Placeholder.parsed("name", source.getName()),
                    Placeholder.component("message", message)
            );

            return MiniMessage.miniMessage()
                    .deserialize(format, resolver)
                    .decoration(TextDecoration.ITALIC, false);
        });
    }

}
