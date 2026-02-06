package io.nexstudios.legendperms.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.nexstudios.legendperms.LegendPerms;
import io.nexstudios.legendperms.perms.LegendPermissionService;
import io.nexstudios.legendperms.perms.PermissionDecision;
import io.nexstudios.legendperms.perms.model.LegendGroup;
import io.nexstudios.legendperms.utils.LegendMessageSender;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public record GroupBrigadierCommand(LegendPerms plugin) implements LegendSubCommand {

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("group")
                .requires(src -> {
                    CommandSender sender = src.getSender();
                    return sender.hasPermission("legendperms.admin");
                })
                .then(Commands.literal("create")
                        .requires(src -> {
                            CommandSender sender = src.getSender();
                            return sender.hasPermission("legendperms.admin");
                        })
                        .then(Commands.argument("groupName", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    builder.suggest("SomeGroupName");
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    String groupName = StringArgumentType.getString(ctx, "groupName");

                                    boolean created = plugin.getPermissionService().createGroup(groupName);
                                    if (!created) {
                                        plugin.getMessageSender().sendChatMessage(
                                                ctx.getSource().getSender(),
                                                "permission.group-already-exists",
                                                true,
                                                null
                                        );
                                        return 0;
                                    }

                                    plugin.getMessageSender().sendChatMessage(
                                            ctx.getSource().getSender(),
                                            "permission.group-created",
                                            true,
                                            TagResolver.resolver(
                                                    Placeholder.parsed("group", groupName)
                                            )
                                    );
                                    return 1;
                                })
                        )
                )
                .then(Commands.literal("delete")
                        .requires(src -> {
                            CommandSender sender = src.getSender();
                            return sender.hasPermission("legendperms.admin");
                        })
                        .then(Commands.argument("groupName", StringArgumentType.word())
                                .suggests(this::suggestGroups)
                                .executes(ctx -> {
                                    String groupName = StringArgumentType.getString(ctx, "groupName");

                                    boolean ok = plugin.getPermissionService().deleteGroup(groupName);
                                    if (!ok) {
                                        if (LegendPermissionService.DEFAULT_GROUP_NAME.equalsIgnoreCase(groupName)) {
                                            plugin.getMessageSender().sendChatMessage(
                                                    ctx.getSource().getSender(),
                                                    "permission.group-delete-default",
                                                    true,
                                                    null
                                            );
                                            return 0;
                                        }

                                        plugin.getMessageSender().sendChatMessage(
                                                ctx.getSource().getSender(),
                                                "permission.group-not-exists",
                                                true,
                                                TagResolver.resolver(
                                                        Placeholder.parsed("group", groupName)
                                                )
                                        );
                                        return 0;
                                    }

                                    plugin.getMessageSender().sendChatMessage(
                                            ctx.getSource().getSender(),
                                            "permission.group-deleted",
                                            true,
                                            TagResolver.resolver(
                                                    Placeholder.parsed("group", groupName)
                                            )
                                    );
                                    return 1;
                                })
                        )
                )
                .then(Commands.literal("info")
                        .requires(src -> {
                            CommandSender sender = src.getSender();
                            return sender.hasPermission("legendperms.admin");
                        })
                        .then(Commands.argument("groupName", StringArgumentType.word())
                                .suggests(this::suggestGroups)
                                .executes(this::showGroupInfo)
                        )
                )
                .then(Commands.literal("edit")
                        .requires(src -> {
                            CommandSender sender = src.getSender();
                            return sender.hasPermission("legendperms.admin");
                        })
                        .then(Commands.argument("groupName", StringArgumentType.word())
                                .suggests(this::suggestGroups)

                                .then(Commands.literal("priority")
                                        .then(Commands.literal("set")
                                                .then(Commands.argument("value", IntegerArgumentType.integer())
                                                        .executes(this::setGroupPriority)
                                                )
                                        )
                                )

                                .then(Commands.literal("permission")
                                        .then(Commands.literal("set")
                                                .then(Commands.literal("allow")
                                                        // greedyString for .* permission nodes
                                                        .then(Commands.argument("node", StringArgumentType.greedyString())
                                                                .suggests(this::suggestPermissionNodePlaceholder)
                                                                .executes(ctx ->
                                                                        setGroupPermission(ctx, PermissionDecision.ALLOW))
                                                        )
                                                )
                                                .then(Commands.literal("deny")
                                                        // greedyString for .* permission nodes
                                                        .then(Commands.argument("node", StringArgumentType.greedyString())
                                                                .suggests(this::suggestPermissionNodePlaceholder)
                                                                .executes(ctx ->
                                                                        setGroupPermission(ctx, PermissionDecision.DENY))
                                                        )
                                                )
                                        )
                                        .then(Commands.literal("remove")
                                                .then(Commands.argument("node", StringArgumentType.greedyString())
                                                        .suggests(this::suggestExistingGroupPermissionNodes)
                                                        .executes(this::removeGroupPermission)
                                                )
                                        )
                                )

                                .then(Commands.literal("prefix")
                                        .then(Commands.literal("set")
                                                // greedyString for special symbols such as color codes (<blue>PREFIX)
                                                .then(Commands.argument("prefix", StringArgumentType.greedyString())
                                                        .executes(this::setGroupPrefix)
                                                )
                                        )
                                        .then(Commands.literal("remove")
                                                .executes(this::removeGroupPrefix)
                                        )
                                )
                        )
                );
    }

    private int setGroupPriority(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String groupName = StringArgumentType.getString(ctx, "groupName");
        int value = IntegerArgumentType.getInteger(ctx, "value");

        try {
            plugin.getPermissionService().setGroupPriority(groupName, value);
        } catch (IllegalArgumentException ex) {
            plugin.getMessageSender().sendChatMessage(
                    sender,
                    "permission.group-not-exists",
                    true,
                    TagResolver.resolver(Placeholder.parsed("group", groupName))
            );
            return 0;
        }

        plugin.getMessageSender().sendChatMessage(
                sender,
                "permission.group-priority-set",
                true,
                TagResolver.resolver(
                        Placeholder.parsed("group", groupName),
                        Placeholder.parsed("priority", String.valueOf(value))
                )
        );
        return 1;
    }

    private CompletableFuture<Suggestions> suggestGroups(
            CommandContext<CommandSourceStack> ctx,
            SuggestionsBuilder builder
    ) {
        List<String> groups = plugin.getPermissionService().getAllGroupNames();

        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (String g : groups) {
            if (remaining.isEmpty() || g.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(g);
            }
        }
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestPermissionNodePlaceholder(
            CommandContext<CommandSourceStack> ctx,
            SuggestionsBuilder builder
    ) {
        String remaining = builder.getRemaining().trim();
        if (remaining.isEmpty()) {
            // Dummy/Hint, damit TAB nicht direkt zu allow/deny springt
            builder.suggest("your.permission.node");
            builder.suggest("example.permission");
        }
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestExistingGroupPermissionNodes(
            CommandContext<CommandSourceStack> ctx,
            SuggestionsBuilder builder
    ) {
        String groupName;
        try {
            groupName = StringArgumentType.getString(ctx, "groupName");
        } catch (IllegalArgumentException ex) {
            return builder.buildFuture();
        }

        LegendGroup group = plugin.getPermissionService().getGroup(groupName);
        if (group == null) {
            return builder.buildFuture();
        }

        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);

        group.getPermissions().keySet().stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(node -> {
                    if (remaining.isEmpty() || node.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                        builder.suggest(node);
                    }
                });

        return builder.buildFuture();
    }

    private int showGroupInfo(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String groupName = StringArgumentType.getString(ctx, "groupName");

        LegendGroup group = plugin.getPermissionService().getGroup(groupName);
        if (group == null) {
            plugin.getMessageSender().sendChatMessage(
                    sender,
                    "permission.group-not-exists",
                    true,
                    TagResolver.resolver(Placeholder.parsed("group", groupName))
            );
            return 0;
        }

        String prefix = group.getPrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = "<gray>None";
        }

        List<String> permissionLines = group.getPermissions().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .map(e -> {
                    String node = e.getKey();
                    PermissionDecision decision = e.getValue();
                    String dec = (decision != null) ? decision.name().toLowerCase(Locale.ROOT) : "unknown";
                    return "<dark_gray>" + node + " <gray>(" + dec + ")";
                })
                .toList();

        if (permissionLines.isEmpty()) {
            permissionLines = List.of("<gray>None");
        }

        TagResolver resolver = TagResolver.resolver(
                Placeholder.parsed("name", group.getName()),
                Placeholder.parsed("prefix", prefix),
                Placeholder.parsed("priority", String.valueOf(group.getPriority()))
        );

        plugin.getMessageSender().sendChatMessage(
                sender,
                "permission.group-info",
                false,
                resolver,
                LegendMessageSender.expandTokenInLine("#permissions#", permissionLines)
        );
        return 1;
    }

    private int setGroupPrefix(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String groupName = StringArgumentType.getString(ctx, "groupName");
        String prefix = StringArgumentType.getString(ctx, "prefix");

        try {
            plugin.getPermissionService().setGroupPrefix(groupName, prefix);
        } catch (IllegalArgumentException ex) {
            plugin.getMessageSender().sendChatMessage(
                    sender,
                    "permission.group-not-exists",
                    true,
                    TagResolver.resolver(Placeholder.parsed("group", groupName))
            );
            return 0;
        }

        plugin.getMessageSender().sendChatMessage(
                sender,
                "permission.group-prefix-set",
                true,
                TagResolver.resolver(
                        Placeholder.parsed("group", groupName),
                        Placeholder.parsed("prefix", prefix)
                )
        );
        return 1;
    }

    private int removeGroupPrefix(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String groupName = StringArgumentType.getString(ctx, "groupName");

        try {
            plugin.getPermissionService().setGroupPrefix(groupName, "");
        } catch (IllegalArgumentException ex) {
            plugin.getMessageSender().sendChatMessage(
                    sender,
                    "permission.group-not-exists",
                    true,
                    TagResolver.resolver(Placeholder.parsed("group", groupName))
            );
            return 0;
        }

        plugin.getMessageSender().sendChatMessage(
                sender,
                "permission.group-prefix-set",
                true,
                TagResolver.resolver(
                        Placeholder.parsed("group", groupName),
                        Placeholder.parsed("prefix", "")
                )
        );
        return 1;
    }

    private int setGroupPermission(CommandContext<CommandSourceStack> ctx, PermissionDecision decision) {
        CommandSender sender = ctx.getSource().getSender();
        String groupName = StringArgumentType.getString(ctx, "groupName");
        String node = StringArgumentType.getString(ctx, "node");

        try {
            plugin.getPermissionService().addGroupPermission(groupName, node, decision);
        } catch (IllegalArgumentException ex) {
            plugin.getMessageSender().sendChatMessage(
                    sender,
                    "permission.group-not-exists",
                    true,
                    TagResolver.resolver(Placeholder.parsed("group", groupName))
            );
            return 0;
        }

        plugin.getMessageSender().sendChatMessage(
                sender,
                "permission.group-permission-added",
                true,
                TagResolver.resolver(
                        Placeholder.parsed("node", node),
                        Placeholder.parsed("decision", decision.name().toLowerCase(Locale.ROOT)),
                        Placeholder.parsed("group", groupName)
                )
        );
        return 1;
    }

    private int removeGroupPermission(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String groupName = StringArgumentType.getString(ctx, "groupName");
        String node = StringArgumentType.getString(ctx, "node");

        boolean removed;
        try {
            removed = plugin.getPermissionService().removeGroupPermission(groupName, node);
        } catch (IllegalArgumentException ex) {
            plugin.getMessageSender().sendChatMessage(
                    sender,
                    "permission.group-not-exists",
                    true,
                    TagResolver.resolver(Placeholder.parsed("group", groupName))
            );
            return 0;
        }

        if (!removed) {
            plugin.getMessageSender().sendChatMessage(
                    sender,
                    "permission.group-permission-not-found",
                    true,
                    TagResolver.resolver(
                            Placeholder.parsed("group", groupName),
                            Placeholder.parsed("node", node)
                    )
            );
            return 0;
        }

        plugin.getMessageSender().sendChatMessage(
                sender,
                "permission.group-permission-removed",
                true,
                TagResolver.resolver(
                        Placeholder.parsed("group", groupName),
                        Placeholder.parsed("node", node)
                )
        );
        return 1;
    }
}