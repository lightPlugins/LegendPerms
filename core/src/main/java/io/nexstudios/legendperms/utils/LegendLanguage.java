package io.nexstudios.legendperms.utils;

import io.nexstudios.legendperms.file.LegendFileReader;
import lombok.Getter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;
import java.util.regex.Pattern;

@Getter
public class LegendLanguage {

    private static final Pattern LANGUAGE_TAG_PATTERN = Pattern.compile("^[a-z]{2}_[A-Z]{2}$");
    private final Map<UUID, String> userLanguage = new HashMap<>();
    private final Map<String, File> availableLanguages = new HashMap<>();
    private final Map<String, FileConfiguration> loadedLanguages = new HashMap<>();
    private final LegendFileReader rawLanguageFiles;
    private final LegendLogger logger;
    private final String defaultLanguage;

    // The UUID of the console, used for logging and default language selection
    public UUID consoleUUID = UUID.fromString("c860b2fe-cdd1-4e76-9ce5-874a83c44bc7");

    public LegendLanguage(LegendFileReader rawLanguageFiles, LegendLogger logger, String configuredDefaultLanguage) {
        this.rawLanguageFiles = rawLanguageFiles;
        this.logger = logger;

        rawLanguageFiles.getFiles().forEach(file -> {
            String languageName = file.getName().replace(".yml", "");

            if (!isValidLanguageTag(languageName)) {
                logger.warning(List.of(
                        "Ignoring invalid language file: " + file.getName(),
                        "Expected ISO format like: en_US, de_DE"
                ));
                return;
            }

            availableLanguages.put(languageName, file);
            loadedLanguages.put(languageName, YamlConfiguration.loadConfiguration(file));
        });

        String resolvedDefault = (configuredDefaultLanguage == null || configuredDefaultLanguage.isBlank())
                ? "en_US"
                : configuredDefaultLanguage.trim();

        if (!isValidLanguageTag(resolvedDefault)) {
            logger.error(List.of(
                    "Configured default language '" + resolvedDefault + "' is invalid.",
                    "Expected ISO format like: en_US, de_DE",
                    "Falling back to en_US"
            ));
            resolvedDefault = "en_US";
        }

        if (!availableLanguages.containsKey(resolvedDefault)) {
            logger.error(List.of(
                    "Configured default language '" + resolvedDefault + "' does not exist in plugins/" + "LegendPerms" + "/language/",
                    "Create plugins/LegendPerms/language/" + resolvedDefault + ".yml or select an existing language.",
                    "Falling back to en_US"
            ));
            resolvedDefault = "en_US";
        }

        this.defaultLanguage = resolvedDefault;

        logger.info("Loaded " + availableLanguages.size() + " languages. Default: " + this.defaultLanguage);
    }

    public FileConfiguration getSelectedLanguageConfig(UUID uuid) {
        return loadedLanguages.get(getSelectedLanguage(uuid));
    }

    public String getSelectedLanguage(UUID uuid) {
        return userLanguage.getOrDefault(uuid, defaultLanguage);
    }

    public boolean hasPlayerDefaultLanguage(UUID uuid) {
        return userLanguage.containsKey(uuid) && userLanguage.get(uuid).equals(defaultLanguage);
    }

    public String getPrefix(UUID uuid) {
        String lang = getSelectedLanguage(uuid);
        FileConfiguration languageConfig = loadedLanguages.get(lang);

        if (languageConfig == null) {
            logger.error(List.of(
                    "Language configuration for " + lang + " not found.",
                    "Using fallback language: en_US"
            ));
            languageConfig = loadedLanguages.get("en_US");
        }

        if (languageConfig != null && languageConfig.contains("general.prefix")) {
            return languageConfig.getString("general.prefix", "NotFound");
        }

        return "<red>Null";
    }

    public List<Component> getTranslationList(UUID uuid, String path, boolean withPrefix) {
        return getTranslationListInternal(uuid, path, withPrefix, TagResolver.empty());
    }

    public List<Component> getTranslationList(UUID uuid,
                                              String path,
                                              boolean withPrefix,
                                              TagResolver extraResolver) {
        TagResolver resolver = (extraResolver != null) ? extraResolver : TagResolver.empty();
        return getTranslationListInternal(uuid, path, withPrefix, resolver);
    }

    public Component getTranslation(UUID uuid, String path, boolean withPrefix) {
        return getTranslationInternal(uuid, path, withPrefix, TagResolver.empty());
    }

    public Component getTranslation(UUID uuid, String path, boolean withPrefix, TagResolver extraResolver) {
        TagResolver resolver = (extraResolver != null) ? extraResolver : TagResolver.empty();
        return getTranslationInternal(uuid, path, withPrefix, resolver);
    }


    private List<Component> getTranslationListInternal(UUID uuid, String path, boolean withPrefix, TagResolver resolver) {
        FileConfiguration languageConfig = resolveLanguageConfig(uuid);

        if (languageConfig.contains(path)) {
            List<String> translations = languageConfig.getStringList(path);

            List<Component> components = new ArrayList<>(Math.max(1, translations.size()));
            for (String translation : translations) {
                if (translation == null || translation.trim().isEmpty()) {
                    components.add(Component.text(" ").decoration(TextDecoration.ITALIC, false));
                    continue;
                }

                String text = withPrefix ? (getPrefix(uuid) + " " + translation) : translation;
                components.add(deserialize(text, resolver));
            }
            return components;
        }

        String fallback = withPrefix ? (getPrefix(uuid) + " " + path) : path;
        return List.of(Component.text(fallback).decoration(TextDecoration.ITALIC, false));
    }


    private Component getTranslationInternal(UUID uuid, String path, boolean withPrefix, TagResolver resolver) {
        FileConfiguration languageConfig = resolveLanguageConfig(uuid);

        if (languageConfig.contains(path)) {
            String translation = languageConfig.getString(path);
            if (translation == null) {
                logger.error(List.of(
                        "Translation for path '" + path + "' in language '" + getSelectedLanguage(uuid) + "' is null.",
                        "Using path as fallback."
                ));
                return Component.text(path).decoration(TextDecoration.ITALIC, false);
            }

            String text = withPrefix ? (getPrefix(uuid) + " " + translation) : translation;
            return deserialize(text, resolver);
        }

        String fallback = withPrefix ? (getPrefix(uuid) + " " + path) : path;
        return Component.text(fallback).decoration(TextDecoration.ITALIC, false);
    }


    private Component deserialize(String text, TagResolver resolver) {
        return MiniMessage.miniMessage()
                .deserialize(text, resolver)
                .decoration(TextDecoration.ITALIC, false);
    }


    private FileConfiguration resolveLanguageConfig(UUID uuid) {
        String lang = getSelectedLanguage(uuid);
        FileConfiguration config = loadedLanguages.get(lang);

        if (config != null) {
            return config;
        }

        logger.error(List.of(
                "Language configuration for " + lang + " not found.",
                "Using fallback language: en_US"
        ));

        FileConfiguration fallback = loadedLanguages.get("en_US");
        if (fallback == null) {
            logger.error(List.of(
                    "Default language configuration en_US.yml not found.",
                    "This is a critical problem and should be reported",
                    "to the developer: NexStudios"
            ));
            throw new IllegalArgumentException("en_US.yml -> language/configuration not found");
        }
        return fallback;
    }


    public String selectLanguage(UUID uuid, String language) {
        if (!isValidLanguageTag(language)) {
            logger.error(List.of(
                    "Provided language '" + language + "' is invalid.",
                    "Expected ISO format like: en_US, de_DE",
                    "Selecting fallback language: en_US"
            ));
            language = "en_US";
        }

        if (!availableLanguages.containsKey(language)) {
            if (language.equals("en_US")) {
                logger.error(List.of(
                        "Could not find the default en_US file.",
                        "This is a critical problem and should be reported",
                        "to the developer: NexStudios"
                ));
                throw new IllegalArgumentException("en_US.yml -> language/file not found");
            }
            logger.error(List.of(
                    "Provided language file " + language + " does not exist.",
                    "Selecting fallback language: en_US"
            ));
            language = "en_US";
        }

        userLanguage.put(uuid, language);
        return language;
    }

    private boolean isValidLanguageTag(String languageTag) {
        return languageTag != null && LANGUAGE_TAG_PATTERN.matcher(languageTag).matches();
    }

}
