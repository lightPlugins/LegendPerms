package io.nexstudios.legendperms.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.nexstudios.legendperms.LegendPerms;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Comparator;
import java.util.concurrent.CompletableFuture;

public record LanguageBrigadierCommand(LegendPerms plugin) implements LegendSubCommand {

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("language")
                .requires(src -> {
                    CommandSender sender = src.getSender();
                    return sender.hasPermission("legendperms.player.language");
                })
                .then(Commands.argument("language", StringArgumentType.string())
                        .suggests((ctx, builder) -> suggestAvailableLanguages(builder))
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();

                            if (!(sender instanceof Player player)) {
                                plugin.getMessageSender().sendChatMessage(
                                        sender,
                                        "general.only-players",
                                        false,
                                        null
                                );
                                return 0;
                            }

                            String requested = StringArgumentType.getString(ctx, "language");
                            String applied = plugin.getLanguage().selectLanguage(player.getUniqueId(), requested);

                            plugin.getMessageSender().sendChatMessage(
                                    sender,
                                    "general.language-switch",
                                    true,
                                    TagResolver.resolver(
                                            Placeholder.parsed("language", applied)
                                    )
                            );

                            if (!requested.equals(applied)) {
                                plugin.getMessageSender().sendChatMessage(
                                        sender,
                                        "general.language-not-found",
                                        true,
                                        TagResolver.resolver(
                                                Placeholder.parsed("language", requested)
                                        )
                                );
                            }

                            return 1;
                        })
                );
    }

    private CompletableFuture<Suggestions> suggestAvailableLanguages(SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        String remainingLower = remaining == null ? "" : remaining.toLowerCase();

        plugin.getLanguage().getAvailableLanguages().keySet().stream()
                .sorted(Comparator.naturalOrder())
                .filter(lang -> remainingLower.isEmpty() || lang.toLowerCase().startsWith(remainingLower))
                .forEach(builder::suggest);

        return builder.buildFuture();
    }
}