package io.nexstudios.legendperms.listener;

import io.nexstudios.legendperms.LegendPerms;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

public final class TablistPrefixListener implements Listener {

    private static final int TEAM_BUCKET_MAX = 99999;
    private static final String TEAM_PREFIX = "legendperms_";

    private final LegendPerms plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public TablistPrefixListener(LegendPerms plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        applyTablistName(e.getPlayer());
    }

    public void applyTablistName(Player player) {
        applySortingTeam(player);

        String format = plugin.getSettingsFile().getString(
                "tablist.name-format",
                "<white><name>"
        );

        String prefixRaw = plugin.getPermissionService().getUserPrimaryPrefix(player.getUniqueId());
        Component prefix = (prefixRaw == null || prefixRaw.isBlank())
                ? Component.empty()
                : mm.deserialize(prefixRaw).decoration(TextDecoration.ITALIC, false);

        TagResolver resolver = TagResolver.resolver(
                Placeholder.component("prefix", prefix),
                Placeholder.parsed("name", player.getName())
        );

        Component listName = mm.deserialize(format, resolver).decoration(TextDecoration.ITALIC, false);
        player.playerListName(listName);
    }

    public void refreshTablistOrder(Player player) {
        if (player == null || !player.isOnline()) return;
        // check if the sorting feature is enabled
        if (!isRankSortingEnabled()) return;
        applySortingTeam(player);
    }

    private void applySortingTeam(Player player) {
        // check if the sorting feature is enabled
        if (!isRankSortingEnabled()) return;

        int prio = plugin.getPermissionService().getUserPrimaryPriority(player.getUniqueId());

        int bucket = TEAM_BUCKET_MAX - prio;
        if (bucket < 0) bucket = 0;
        if (bucket > TEAM_BUCKET_MAX) bucket = TEAM_BUCKET_MAX;

        String teamName = TEAM_PREFIX + String.format("%05d", bucket);

        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();

        Team team = board.getTeam(teamName);
        if (team == null) {
            team = board.registerNewTeam(teamName);
        }

        for (Team singleTeam : board.getTeams()) {
            if (!singleTeam.getName().startsWith(TEAM_PREFIX)) continue;
            if (singleTeam.equals(team)) continue;
            singleTeam.removeEntry(player.getName());
        }

        team.addEntry(player.getName());
    }

    private boolean isRankSortingEnabled() {
        return plugin.getSettingsFile().getBoolean("tablist.sort-players-by-ranks", true);
    }
}