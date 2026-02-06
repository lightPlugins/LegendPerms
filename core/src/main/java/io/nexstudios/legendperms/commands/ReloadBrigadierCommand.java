package io.nexstudios.legendperms.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.nexstudios.legendperms.LegendPerms;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;

public record ReloadBrigadierCommand(LegendPerms plugin) implements LegendSubCommand {

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("reload")
                .requires(src -> {
                    CommandSender sender = src.getSender();
                    return sender.hasPermission("legendperms.player.reload");
                })
                .executes(ctx -> {
                    plugin.onReload();
                    plugin.getMessageSender().sendChatMessage(
                            ctx.getSource().getSender(),
                            "general.reload",
                            true,
                            null
                    );
                    return 1;
                });
    }
}