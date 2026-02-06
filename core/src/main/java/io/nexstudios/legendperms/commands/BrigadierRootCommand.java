package io.nexstudios.legendperms.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.nexstudios.legendperms.LegendPerms;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public final class BrigadierRootCommand {

    private final LegendPerms plugin;
    private final List<LegendSubCommand> subCommands = new ArrayList<>();

    public BrigadierRootCommand(LegendPerms plugin) {
        this.plugin = plugin;
    }

    public void add(LegendSubCommand subCommand) {
        this.subCommands.add(subCommand);
    }

    public void register() {
        final LifecycleEventManager<@NotNull Plugin> lifecycle = plugin.getLifecycleManager();

        lifecycle.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();

            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("lp")
                    .requires(src -> {
                        CommandSender sender = src.getSender();
                        return sender.hasPermission("legendperms.admin");
                    });

            for (LegendSubCommand sc : subCommands) {
                root.then(sc.build());
            }

            commands.register(root.build(), "LegendPerms root command", List.of("legendperms"));
        });
    }
}