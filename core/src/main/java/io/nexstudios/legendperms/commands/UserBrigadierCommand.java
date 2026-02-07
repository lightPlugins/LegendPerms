package io.nexstudios.legendperms.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.nexstudios.legendperms.LegendPerms;
import io.nexstudios.legendperms.utils.LegendMessageSender;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public record UserBrigadierCommand(LegendPerms plugin) implements LegendSubCommand {


    @Override
    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("user")
                .requires(src -> {
                    CommandSender sender = src.getSender();
                    return sender.hasPermission("legendperms.admin");
                })
                .then(Commands.literal("info")
                        .then(Commands.argument("username", StringArgumentType.word())
                                .suggests(this::suggestOnlinePlayers)
                                .executes(this::showUserInfo)
                        )
                )
                .then(Commands.literal("help")
                        .requires(src -> {
                            CommandSender sender = src.getSender();
                            return sender.hasPermission("legendperms.admin");
                        })
                        .executes(ctx -> {
                            plugin.getMessageSender().sendChatMessage(
                                    ctx.getSource().getSender(),
                                    "permission.user-help-command",
                                    false,
                                    null
                            );
                            return 1;
                        }))
                .then(Commands.literal("group")
                        .then(Commands.literal("add")
                                .then(Commands.argument("username", StringArgumentType.word())
                                        .suggests(this::suggestOnlinePlayers)
                                        .then(Commands.argument("groupName", StringArgumentType.word())
                                                .suggests(this::suggestGroups)
                                                .then(Commands.literal("permanent")
                                                        .executes(this::addPermanent)
                                                )
                                                .then(Commands.argument("time", StringArgumentType.word())
                                                        .suggests(this::suggestDurations)
                                                        .executes(this::addTemporary)
                                                )
                                        )
                                )
                        )
                        .then(Commands.literal("remove")
                                .then(Commands.argument("username", StringArgumentType.word())
                                        .suggests(this::suggestOnlinePlayers)
                                        .then(Commands.argument("groupName", StringArgumentType.word())
                                                .suggests(this::suggestGroups)
                                                .executes(this::removeGroup)
                                        )
                                )
                        )
                );
    }

    private int addPermanent(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Player target = resolveOnlineTarget(ctx, sender);
        if (target == null) return 0;

        String groupName = StringArgumentType.getString(ctx, "groupName");

        if(groupName.equalsIgnoreCase("Default")) {
            plugin.getMessageSender().sendChatMessage(
                    sender,
                    "permission.user-group-add-default",
                    true,
                    null
            );
            return 0;
        }

        UUID uuid = target.getUniqueId();

        try {
            boolean changed = plugin.getPermissionService().userAddGroup(uuid, groupName);

            if (changed) {
                // If a user had a temporary group, remove it and make it permanent
                boolean hadTemp = plugin.getPermissionService().userHasTemporaryGroup(uuid, groupName);
                if (hadTemp) {
                    plugin.getPermissionService().userRemoveTemporaryGroup(uuid, groupName);
                }
            }

            plugin.getMessageSender().sendChatMessage(
                    sender,
                    "permission.user-group-added",
                    true,
                    TagResolver.resolver(
                            Placeholder.parsed("player", target.getName()),
                            Placeholder.parsed("group", groupName),
                            Placeholder.parsed("temporary", "permanent"),
                            Placeholder.parsed("expiration", "never"))
            );
            return changed ? 1 : 0;
        } catch (IllegalArgumentException ex) {
            plugin.getMessageSender().sendChatMessage(
                    sender,
                    "permission.group-not-exists",
                    true,
                    TagResolver.resolver(
                            Placeholder.parsed("group", groupName)
                    )
            );
            return 0;
        }
    }

    private int showUserInfo(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String username = StringArgumentType.getString(ctx, "username");

        OfflinePlayer target = resolveTargetLookup(username);
        if (target == null) {
            plugin.getMessageSender().sendChatMessage(
                    sender,
                    "general.player-not-found",
                    true,
                    TagResolver.resolver(Placeholder.parsed("player", username))
            );
            return 0;
        }

        loadAndSendUserInfo(sender, target, username);
        return 1;
    }

    private OfflinePlayer resolveTargetLookup(String username) {
        if (username == null || username.isBlank()) return null;

        Player online = Bukkit.getPlayerExact(username);
        if (online != null && online.isOnline()) {
            return online;
        }

        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(username);
        if (cached == null) return null;

        if (!cached.hasPlayedBefore() && !cached.isOnline()) {
            return null;
        }

        return cached;
    }

    private void loadAndSendUserInfo(CommandSender sender, OfflinePlayer target, String username) {
        UUID uuid = target.getUniqueId();

        plugin.getPermissionService()
                .loadUserFromStorageAsync(uuid)
                .thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (sender instanceof Player p && !p.isOnline()) {
                        return;
                    }
                    sendUserInfoMessage(sender, target, username);
                }));
    }

    private void sendUserInfoMessage(CommandSender sender, OfflinePlayer target, String username) {
        UUID uuid = target.getUniqueId();

        String primaryGroup = plugin.getPermissionService().getUserPrimaryGroupName(uuid);
        String prefix = plugin.getPermissionService().getUserPrimaryPrefix(uuid);
        if (prefix == null || prefix.isBlank()) {
            prefix = "<gray>None";
        }

        boolean primaryTemporary = plugin.getPermissionService().userHasTemporaryGroup(uuid, primaryGroup);
        String primaryExpiration = primaryTemporary
                ? plugin.getPermissionService().getTemporaryGroupExpiration(uuid, primaryGroup)
                : "never";

        List<String> groups = plugin.getPermissionService().getUserGroupNames(uuid);
        String prio = plugin.getPermissionService().getUserPrimaryPriority(uuid) + "";

        List<String> subgroups = new ArrayList<>();
        for (String g : groups) {
            if (g == null) continue;
            if (g.equalsIgnoreCase(primaryGroup)) continue;

            boolean isTempGroup = plugin.getPermissionService().userHasTemporaryGroup(uuid, g);
            String state = isTempGroup ? "<aqua>temporary</aqua>" : "<gray>permanent</gray>";
            String exp = isTempGroup
                    ? plugin.getPermissionService().getTemporaryGroupExpiration(uuid, g)
                    : "never";

            subgroups.add("<blue>" + g + "</blue> " + state + " <gray>(expires: <blue>" + exp + "</blue><gray>)");
        }

        if (subgroups.isEmpty()) {
            subgroups = List.of("<gray>None");
        }

        String displayName = (target.getName() != null ? target.getName() : username);

        TagResolver resolver = TagResolver.resolver(
                Placeholder.parsed("name", displayName),
                Placeholder.parsed("primary-group", primaryGroup),
                Placeholder.parsed("priority", prio),
                Placeholder.parsed("prefix", prefix),
                Placeholder.parsed("temporary", primaryTemporary ? "<blue>yes</blue>" : "<blue>permanent</blue>"),
                Placeholder.parsed("expiration", primaryExpiration)
        );

        plugin.getMessageSender().sendChatMessage(
                sender,
                "permission.user-info",
                false,
                resolver,
                LegendMessageSender.expandTokenInLine("#subgroups#", subgroups)
        );
    }

    private int addTemporary(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Player target = resolveOnlineTarget(ctx, sender);
        if (target == null) return 0;

        String groupName = StringArgumentType.getString(ctx, "groupName");

        if(groupName.equalsIgnoreCase("Default")) {
            plugin.getMessageSender().sendChatMessage(
                    sender,
                    "permission.user-group-add-default",
                    true,
                    null
            );
            return 0;
        }

        String raw = StringArgumentType.getString(ctx, "time");

        Duration d = parseDuration(raw);
        if (d == null) {
            sender.sendMessage("Ungültige Zeit: " + raw + " (Beispiele: 5s, 10m, 2h, 7d)");
            plugin.getMessageSender().sendChatMessage(
                    sender,
                    "general.wrong-time-format",
                    true,
                    null
            );
            return 0;
        }

        UUID uuid = target.getUniqueId();

        // if the player has this group permanently -> cannot add it temporarily!
        if (plugin.getPermissionService().userHasPermanentGroup(uuid, groupName)) {

            plugin.getMessageSender().sendChatMessage(
                    sender,
                    "permission.user-group-add-already-perm",
                    true,
                    TagResolver.resolver(
                            Placeholder.parsed("group", groupName),
                            Placeholder.parsed("player", target.getName())
                    )
            );
            return 0;
        }

        // if the player has this group temporary -> reset the timer to the new duration
        boolean hadTemp = plugin.getPermissionService().userHasTemporaryGroup(uuid, groupName);

        try {
            plugin.getPermissionService().userAddTemporaryGroup(uuid, groupName, d);

            String expiration = plugin.getPermissionService().getTemporaryGroupExpiration(uuid, groupName);
            plugin.getMessageSender().sendChatMessage(
                    sender,
                    "permission.user-group-added",
                    true,
                    TagResolver.resolver(
                            Placeholder.parsed("player", target.getName()),
                            Placeholder.parsed("group", groupName),
                            Placeholder.parsed("temporary", "temporary"),
                            Placeholder.parsed("expiration", expiration))
            );

            return 1;
        } catch (IllegalArgumentException ex) {
            plugin.getMessageSender().sendChatMessage(
                    sender,
                    "permission.group-not-exists",
                    true,
                    TagResolver.resolver(
                            Placeholder.parsed("group", groupName)
                    )
            );
            return 0;
        }
    }

    private int removeGroup(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Player target = resolveOnlineTarget(ctx, sender);
        if (target == null) return 0;

        String groupName = StringArgumentType.getString(ctx, "groupName");

        if(groupName.equalsIgnoreCase("Default")) {
            plugin.getMessageSender().sendChatMessage(
                    sender,
                    "permission.user-group-removed-default",
                    true,
                    null
            );
            return 0;
        }

        boolean changed = plugin.getPermissionService().userRemoveGroup(target.getUniqueId(), groupName);

        plugin.getMessageSender().sendChatMessage(
                sender,
                changed ? "permission.user-group-removed" : "permission.user-not-in-group",
                true,
                TagResolver.resolver(
                        Placeholder.parsed("player", target.getName()),
                        Placeholder.parsed("group", groupName)
                )
        );
        return changed ? 1 : 0;
    }

    private Player resolveOnlineTarget(CommandContext<CommandSourceStack> ctx, CommandSender sender) {
        String username = StringArgumentType.getString(ctx, "username");
        Player target = Bukkit.getPlayerExact(username);
        if (target == null || !target.isOnline()) {
            sender.sendMessage("Spieler nicht online: " + username);
            plugin.getMessageSender().sendChatMessage(
                    sender,
                    "general.player-not-found",
                    true,
                    TagResolver.resolver(
                            Placeholder.parsed("player", username)
                    )
            );
            return null;
        }
        return target;
    }

    private CompletableFuture<Suggestions> suggestOnlinePlayers(
            CommandContext<CommandSourceStack> ctx,
            SuggestionsBuilder builder
    ) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (Player p : Bukkit.getOnlinePlayers()) {
            String name = p.getName();
            if (remaining.isEmpty() || name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(name);
            }
        }
        return builder.buildFuture();
    }

    private CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestGroups(
            CommandContext<CommandSourceStack> ctx,
            SuggestionsBuilder builder
    ) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (String g : plugin.getPermissionService().getAllGroupNames()) {
            if (remaining.isEmpty() || g.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(g);
            }
        }
        return builder.buildFuture();
    }

    private CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestDurations(
            CommandContext<CommandSourceStack> ctx,
            SuggestionsBuilder builder
    ) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        String[] samples = {"5s", "30s", "5m", "10m", "1h", "12h", "1d", "7d"};
        for (String s : samples) {
            if (remaining.isEmpty() || s.startsWith(remaining)) {
                builder.suggest(s);
            }
        }
        return builder.buildFuture();
    }

    private static Duration parseDuration(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.length() < 2) return null;

        char unit = s.charAt(s.length() - 1);
        String numPart = s.substring(0, s.length() - 1).trim();
        long n;
        try {
            n = Long.parseLong(numPart);
        } catch (NumberFormatException e) {
            return null;
        }
        if (n <= 0) return null;

        return switch (unit) {
            case 's' -> Duration.ofSeconds(n);
            case 'm' -> Duration.ofMinutes(n);
            case 'h' -> Duration.ofHours(n);
            case 'd' -> Duration.ofDays(n);
            default -> null;
        };
    }
}
