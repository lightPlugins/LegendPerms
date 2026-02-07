package io.nexstudios.legendperms.listener;

import io.nexstudios.legendperms.LegendPerms;
import io.nexstudios.legendperms.file.LegendFile;
import io.nexstudios.legendperms.perms.LegendPermissionService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.sign.Side;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;

import java.util.List;
import java.util.Set;

public record SignListener(LegendPerms plugin) implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final MiniMessage MM = MiniMessage.miniMessage();

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        Side side = event.getSide();

        String trigger = plugin.getSettingsFile().getString("sign.trigger-text", "[legendperms]");
        if (trigger == null || trigger.isBlank()) trigger = "[legendperms]";
        trigger = trigger.trim();

        boolean containsTrigger = false;
        List<Component> current = event.lines();
        // search for the trigger line in every row
        for (int i = 0; i < Math.min(4, current.size()); i++) {
            String plain = PLAIN.serialize(current.get(i)).trim();
            if (plain.equalsIgnoreCase(trigger)) {
                // trigger line found
                containsTrigger = true;
                break;
            }
        }
        if (!containsTrigger) return;

        LegendPermissionService perms = plugin.getPermissionService();
        String group = perms.getUserPrimaryGroupName(player.getUniqueId());
        String prefix = perms.getUserPrimaryPrefix(player.getUniqueId());
        int prio = perms.getUserPrimaryPriority(player.getUniqueId());

        // replace placeholder for all signs
        TagResolver resolver = TagResolver.resolver(
                Placeholder.parsed("name", player.getName()),
                Placeholder.parsed("group", group),
                Placeholder.parsed("prefix", prefix == null || prefix.isBlank() ? "NoPrefix" : prefix),
                Placeholder.parsed("prio", String.valueOf(prio))
        );

        List<String> format = plugin.getSettingsFile().getStringList("sign.format");
        for (int i = 0; i < 4; i++) {
            String raw = (format != null && i < format.size() && format.get(i) != null) ? format.get(i) : "";
            Component componentLine = MM.deserialize(raw, resolver);
            event.lines().set(i, componentLine);
        }

        // just for demonstration purposes (simple config saver)
        saveSign(event.getBlock(), side, player.getName(), group, prefix, prio);
    }

    private void saveSign(Block block, Side side, String playerName, String group, String prefix, int prio) {
        LegendFile storage = plugin.getSignStorage();

        Location location = block.getLocation();
        String id = findExistingIdByLocationAndSide(storage, location, side);
        if (id == null) {
            id = String.valueOf(nextId(storage));
        }

        String basePath = "signs." + id;

        storage.set(basePath + ".location", location);
        storage.set(basePath + ".side", side.name());

        storage.set(basePath + ".player", playerName);
        storage.set(basePath + ".group", group);
        storage.set(basePath + ".prefix", prefix == null ? "" : prefix);
        storage.set(basePath + ".priority", prio);
    }

    private String findExistingIdByLocationAndSide(LegendFile storage, Location loc, Side side) {
        ConfigurationSection signs = storage.getConfig().getConfigurationSection("signs");
        if (signs == null) return null;

        Set<String> keys = signs.getKeys(false);
        for (String key : keys) {
            String base = "signs." + key;

            Object rawLoc = storage.getConfig().get(base + ".location");
            if (!(rawLoc instanceof Location storedLoc)) continue;

            String storedSide = storage.getConfig().getString(base + ".side", "");
            if (!side.name().equalsIgnoreCase(storedSide)) continue;

            if (sameBlock(storedLoc, loc)) {
                return key;
            }
        }
        return null;
    }

    private boolean sameBlock(Location locationOne, Location compareLocation) {
        if (locationOne == null || compareLocation == null) return false;
        if (locationOne.getWorld() == null || compareLocation.getWorld() == null) return false;
        if (!locationOne.getWorld().getName().equals(compareLocation.getWorld().getName())) return false;
        return locationOne.getBlockX() == compareLocation.getBlockX()
                && locationOne.getBlockY() == compareLocation.getBlockY()
                && locationOne.getBlockZ() == compareLocation.getBlockZ();
    }

    private int nextId(LegendFile storage) {
        ConfigurationSection signs = storage.getConfig().getConfigurationSection("signs");
        if (signs == null) return 0;

        int max = -1;
        for (String key : signs.getKeys(false)) {
            try {
                max = Math.max(max, Integer.parseInt(key));
            } catch (NumberFormatException ignored) { }
        }
        return max + 1;
    }
}