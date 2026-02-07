package io.nexstudios.legendperms.listener;

import io.nexstudios.legendperms.LegendPerms;
import io.nexstudios.legendperms.perms.LegendPermissionService;
import io.nexstudios.legendperms.perms.model.LegendGroup;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;

import java.util.List;
import java.util.Map;

public record SignListener(LegendPerms plugin) implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final MiniMessage MM = MiniMessage.miniMessage();

    @EventHandler
    public void onSignChange(SignChangeEvent event) {

        Player player = event.getPlayer();
        Side side = event.getSide();

        if (!player.hasPermission("legendperms.sign")) return;

        List<Map<?, ?>> templates = plugin.getSettingsFile().getConfig().getMapList("signs");
        if (templates.isEmpty()) return;

        List<Component> current = event.lines();

        TemplateMatch match = null;
        for (int row = 0; row < Math.min(4, current.size()); row++) {
            String plain = PLAIN.serialize(current.get(row)).trim();
            if (plain.isEmpty()) continue;

            match = matchTemplate(templates, plain);
            if (match != null) break;
        }
        if (match == null) return;

        LegendPermissionService perms = plugin.getPermissionService();

        String outName;
        String outGroup;
        String outPrefix;
        int outPrio;

        if (match.type == TemplateType.USER) {
            outName = player.getName();
            outGroup = perms.getUserPrimaryGroupName(player.getUniqueId());
            outPrefix = perms.getUserPrimaryPrefix(player.getUniqueId());
            outPrio = perms.getUserPrimaryPriority(player.getUniqueId());
        } else {
            String groupName = match.capturedName;
            if (groupName == null || groupName.isBlank()) return;

            LegendGroup g = perms.getGroup(groupName);
            if (g == null) return;

            outName = groupName;
            outGroup = g.getName();
            outPrefix = g.getPrefix();
            outPrio = g.getPriority();
        }

        TagResolver resolver = TagResolver.resolver(
                Placeholder.parsed("name", outName),
                Placeholder.parsed("group", outGroup),
                Placeholder.parsed("prefix", outPrefix == null || outPrefix.isBlank() ? "NoPrefix" : outPrefix),
                Placeholder.parsed("prio", String.valueOf(outPrio))
        );

        List<String> format = match.format;
        for (int i = 0; i < 4; i++) {
            String raw = (format != null && i < format.size() && format.get(i) != null) ? format.get(i) : "";
            event.lines().set(i, MM.deserialize(raw, resolver));
        }

        // TODO: save signs into signs.yml
    }

    private TemplateMatch matchTemplate(List<Map<?, ?>> templates, String plainLine) {

        for (Map<?, ?> raw : templates) {
            if (raw == null) continue;

            Object triggerObj = raw.get("trigger");
            String trigger = triggerObj != null ? String.valueOf(triggerObj).trim() : null;
            if (trigger == null || trigger.isBlank()) continue;

            @SuppressWarnings("unchecked")
            List<String> format = (List<String>) raw.get("format");

            if (!trigger.contains("<name>")) {
                if (plainLine.equalsIgnoreCase(trigger)) {
                    return new TemplateMatch(TemplateType.USER, trigger, null, format);
                }
                continue;
            }

            String[] parts = trigger.split("<name>", -1);
            String prefix = parts.length > 0 ? parts[0] : "";
            String suffix = parts.length > 1 ? parts[1] : "";

            if (!plainLine.regionMatches(true, 0, prefix, 0, prefix.length())) continue;
            if (!plainLine.toLowerCase().endsWith(suffix.toLowerCase())) continue;

            String captured = plainLine.substring(prefix.length(), plainLine.length() - suffix.length()).trim();
            if (captured.isEmpty()) continue;

            return new TemplateMatch(TemplateType.GROUP, trigger, captured, format);
        }
        return null;
    }

    private enum TemplateType {
        USER,
        GROUP
    }

    private record TemplateMatch(TemplateType type, String trigger, String capturedName, List<String> format) {
    }
}