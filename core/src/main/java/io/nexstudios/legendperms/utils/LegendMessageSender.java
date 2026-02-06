package io.nexstudios.legendperms.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

public record LegendMessageSender(LegendLanguage language, LegendLogger logger) {

    public LegendMessageSender {
        Objects.requireNonNull(language, "Language cannot be null.");
        Objects.requireNonNull(logger, "Logger cannot be null.");
    }

    public void sendChatMessage(CommandSender sender, String configPath, boolean withPrefix, TagResolver tagResolver) {

        if (sender == null) {
            logger.warning(List.of(
                    "Cannot send message: CommandSender is null."
            ));
            return;
        }

        TagResolver safeResolver = tagResolver != null ? tagResolver : TagResolver.empty();

        if (sender instanceof Player player) {
            UUID playerUUID = player.getUniqueId();

            // exclude prefix if the message is a List<String>!
            if (isListPath(playerUUID, configPath)) {
                List<Component> components = language.getTranslationList(playerUUID, configPath, false, safeResolver);
                for (Component component : components) {
                    player.sendMessage(component);
                }
            } else {
                Component component = language.getTranslation(playerUUID, configPath, withPrefix, safeResolver);
                player.sendMessage(component);
            }

            return;
        }

        UUID consoleUUID = language.getConsoleUUID();

        // consoles never get the prefix, because the logger has his own prefix!
        if (isListPath(consoleUUID, configPath)) {
            List<Component> components = language.getTranslationList(consoleUUID, configPath, false, safeResolver);
            for (Component component : components) {
                logger.info(MiniMessage.miniMessage().serialize(component));
            }
        } else {
            Component component = language.getTranslation(consoleUUID, configPath, false, safeResolver);
            logger.info(MiniMessage.miniMessage().serialize(component));
        }
    }

    public void sendChatMessage(CommandSender sender,
                                String configPath,
                                boolean withPrefix,
                                TagResolver tagResolver,
                                Function<List<String>, List<String>> lineTransformer) {

        if (sender == null) {
            logger.warning(List.of(
                    "Cannot send message: CommandSender is null."
            ));
            return;
        }

        TagResolver safeResolver = (tagResolver != null) ? tagResolver : TagResolver.empty();

        UUID uuid = (sender instanceof Player p) ? p.getUniqueId() : language.getConsoleUUID();

        List<String> rawLines = getRawLines(uuid, configPath, withPrefix);
        if (lineTransformer != null) {
            rawLines = Objects.requireNonNullElseGet(lineTransformer.apply(rawLines), List::of);
        }

        List<Component> components = toComponents(rawLines, safeResolver);

        if (sender instanceof Player player) {
            for (Component component : components) {
                player.sendMessage(component);
            }
        } else {
            for (Component component : components) {
                logger.info(MiniMessage.miniMessage().serialize(component));
            }
        }
    }

    public static Function<List<String>, List<String>> replaceLinePlaceholder(String placeholderToken,
                                                                              List<String> replacementLines) {
        return lines -> {
            if (lines == null || lines.isEmpty()) return List.of();

            List<String> out = new ArrayList<>();
            for (String line : lines) {
                if (line != null && line.trim().equals(placeholderToken)) {
                    if (replacementLines != null && !replacementLines.isEmpty()) {
                        out.addAll(replacementLines);
                    }
                } else {
                    out.add(line);
                }
            }
            return out;
        };
    }

    public static Function<List<String>, List<String>> expandTokenInLine(String token,
                                                                         List<String> replacementLines) {
        return lines -> {
            if (lines == null || lines.isEmpty()) return List.of();

            List<String> out = new ArrayList<>();
            for (String line : lines) {
                if (line == null || token == null || token.isEmpty()) {
                    out.add(line);
                    continue;
                }

                int idx = line.indexOf(token);
                if (idx < 0) {
                    out.add(line);
                    continue;
                }

                String before = line.substring(0, idx);
                String after = line.substring(idx + token.length());

                if (replacementLines == null || replacementLines.isEmpty()) {
                    // no Token line found -> skip
                    continue;
                }

                for (String repl : replacementLines) {
                    String safeRepl = (repl == null) ? "" : repl;
                    out.add(before + safeRepl + after);
                }
            }
            return out;
        };
    }

    private List<String> getRawLines(UUID uuid, String path, boolean withPrefix) {
        var languageConfig = resolveLanguageConfigForSender(uuid);

        if (languageConfig != null && languageConfig.contains(path)) {
            if (languageConfig.isList(path)) {
                List<String> list = languageConfig.getStringList(path);
                return applyPrefixToLines(uuid, list, withPrefix);
            }

            String single = languageConfig.getString(path);
            List<String> list = List.of(single != null ? single : path);
            return applyPrefixToLines(uuid, list, withPrefix);
        }

        return applyPrefixToLines(uuid, List.of(path), withPrefix);
    }

    private List<Component> toComponents(List<String> lines, TagResolver resolver) {
        List<Component> components = new ArrayList<>(Math.max(1, lines != null ? lines.size() : 0));

        if (lines == null || lines.isEmpty()) {
            components.add(Component.text(" ").decoration(TextDecoration.ITALIC, false));
            return components;
        }

        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) {
                components.add(Component.text(" ").decoration(TextDecoration.ITALIC, false));
                continue;
            }

            components.add(
                    MiniMessage.miniMessage()
                            .deserialize(line, resolver)
                            .decoration(TextDecoration.ITALIC, false)
            );
        }

        return components;
    }

    private FileConfiguration resolveLanguageConfigForSender(UUID uuid) {
        String lang = language.getSelectedLanguage(uuid);
        var languageConfig = language.getLoadedLanguages().get(lang);

        if (languageConfig == null) {
            languageConfig = language.getLoadedLanguages().get("en_US");
        }

        return languageConfig;
    }

    private List<String> applyPrefixToLines(UUID uuid, List<String> lines, boolean withPrefix) {
        if (!withPrefix) {
            return lines != null ? lines : List.of();
        }

        String prefix = language.getPrefix(uuid);
        List<String> out = new ArrayList<>(lines != null ? lines.size() : 0);
        if (lines != null) {
            for (String line : lines) {
                if (line == null || line.trim().isEmpty()) {
                    out.add(" ");
                } else {
                    out.add(prefix + " " + line);
                }
            }
        }
        return out;
    }

    private boolean isListPath(UUID uuid, String path) {
        String lang = language.getSelectedLanguage(uuid);
        var languageConfig = language.getLoadedLanguages().get(lang);

        if (languageConfig == null) {
            languageConfig = language.getLoadedLanguages().get("en_US");
        }

        if (languageConfig != null && languageConfig.contains(path)) {
            return languageConfig.isList(path);
        }

        return false;
    }
}
