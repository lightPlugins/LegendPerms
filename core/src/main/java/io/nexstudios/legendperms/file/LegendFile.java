package io.nexstudios.legendperms.file;

import io.nexstudios.legendperms.utils.LegendLogger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class LegendFile {

    private final JavaPlugin plugin;
    private FileConfiguration bukkitFile = null;
    private File configFile = null;
    private final String configName;
    private final boolean loadDefaultsOneReload;
    private final LegendLogger logger;

    public LegendFile(JavaPlugin plugin, String configName, LegendLogger logger, boolean loadDefaultsOnReload) {
        this.plugin = plugin;
        this.loadDefaultsOneReload = loadDefaultsOnReload;
        this.configName = configName;
        this.logger = logger;
        saveDefaultConfig(configName);
    }

    public void reloadConfig(String configName) {
        if(this.configFile == null)
            this.configFile = new File(this.plugin.getDataFolder(), configName);

        this.plugin.reloadConfig();

        this.bukkitFile = YamlConfiguration.loadConfiguration(this.configFile);

        InputStream defaultStream = this.plugin.getResource(configName);
        if(defaultStream != null) {
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultStream));
            this.bukkitFile.setDefaults(defaultConfig);
        }
    }

    public FileConfiguration getConfig() {
        if(this.bukkitFile == null)
            reloadConfig(configName);

        return this.bukkitFile;

    }

    public void saveConfig() {
        if(this.bukkitFile == null || this.configFile == null)
            return;

        try {
            this.getConfig().save(this.configFile);
        } catch (IOException e) {
            throw new RuntimeException("Could not save config", e);
        }
    }
    /**
     * Check and insert new keys into existing config from a ConfigurationSection
     * @param configName the name of the config file
     * @param section the section to check for new keys
     */
    public void addNonExistingKeys(String configName, String section) {
        FileConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                new InputStreamReader(Objects.requireNonNull(this.plugin.getResource(configName))));
        FileConfiguration existingConfig = getConfig();
        ConfigurationSection defaultSection = defaultConfig.getConfigurationSection(section);
        ConfigurationSection existingSection = existingConfig.getConfigurationSection(section);

        if (defaultSection != null && existingSection != null) {
            for (String key : defaultSection.getKeys(true)) {
                if (!existingSection.getKeys(true).contains(key)) {
                    logger.info(
                            "Found non existing config key in section "
                                    + logger.getThemeColor() + section + "<reset>. Adding "
                                    + logger.getThemeColor() + key + "<reset> into "
                                    + logger.getThemeColor() + configName);
                    existingSection.set(key, defaultSection.get(key));
                }
            }

            try {
                existingConfig.save(configFile);
                logger.info(
                        "Your config section "
                                + logger.getThemeColor() + section + "<reset> in "
                                + logger.getThemeColor() + configName + "<reset> is up to date.");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            saveConfig();
        } else {
            logger.error(
                    "Section "
                            + logger.getThemeColor() + section + "<reset> not found in "
                            + logger.getThemeColor() + configName);
        }
    }

    /**
     * Save the default config file
     * @param configName the name of the config file
     * If in any config section a key found with "generate-defaults: true" it will be ignored
     * and not added to the existing config on a reload.
     */
    private void saveDefaultConfig(String configName) {
        if (this.configFile == null)
            this.configFile = new File(this.plugin.getDataFolder(), this.configName);

        if (!this.configFile.exists()) {
            this.plugin.saveResource(configName, false);
        } else {
            // Merge the default config into the existing config
            if(loadDefaultsOneReload) {

                saveConfig();

                FileConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(Objects.requireNonNull(this.plugin.getResource(configName))));
                FileConfiguration existingConfig = getConfig();

                List<String> blockedKey = new ArrayList<>();
                // Check for existing keys that are blocked
                // and should not be added to the existing config
                // if they have "generate-defaults: true" set
                for (String test : existingConfig.getKeys(true)) {
                    if(existingConfig.contains(test + ".generate-defaults")) {
                        logger.info(
                                "Found blocked config key. Skipping all entrys for "
                                        + logger.getThemeColor() + test + "<reset> in "
                                        + logger.getThemeColor() + configName);
                        if(!blockedKey.contains(test)) {
                            blockedKey.add(test);
                        }
                    }
                }

                for (String key : defaultConfig.getKeys(true)) {

                    boolean isBlocked = blockedKey.stream().anyMatch(key::startsWith);

                    if (isBlocked) {
                        continue;
                    }

                    if (!existingConfig.getKeys(true).contains(key)) {
                        logger.info(
                                "Found non existing config key. Adding "
                                        + logger.getThemeColor() + key + "<reset> into "
                                        + logger.getThemeColor() + configName);
                        existingConfig.set(key, defaultConfig.get(key));
                    }
                }

                try {

                    existingConfig.save(configFile);
                    logger.info(
                            "Your config " + logger.getThemeColor() + configName + "<reset> is up to date.");

                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

                saveConfig();
            }
        }
    }

    public String getString(String path) {
        return getConfig().getString(path);
    }

    public String getString(String path, String defaultValue) {
        String value = getConfig().getString(path);
        if (value == null || value.isEmpty()) {
            logger.debug("No value found for path: " + path + ", returning default value: " + defaultValue, 3);
            return defaultValue;
        }
        return value;
    }

    public int getInt(String path) {
        return getConfig().getInt(path);
    }

    public int getInt(String path, int defaultValue) {
        int value = getConfig().getInt(path);
        if (value == 0) {
            logger.debug("No value found for path: " + path + ", returning default value: " + defaultValue, 3);
            return defaultValue;
        }
        return value;
    }

    public boolean getBoolean(String path) {
        return getConfig().getBoolean(path);
    }

    public boolean getBoolean(String path, boolean defaultValue) {
        if(!getConfig().contains(path)) {
            logger.debug("No value found for path: " + path + ", returning default value: " + defaultValue, 3);
            return defaultValue;
        }
        return getConfig().getBoolean(path);
    }

    public List<String> getStringList(String path) {
        return getConfig().getStringList(path);
    }

    public List<String> getStringList(String path, List<String> defaultValue) {
        List<String> value = getConfig().getStringList(path);
        if (value.isEmpty()) {
            logger.debug("No value found for path: " + path + ", returning default value: " + defaultValue, 3);
            return defaultValue;
        }
        return value;
    }

    public double getDouble(String path) {
        return getConfig().getDouble(path);
    }

    public double getDouble(String path, double defaultValue) {
        double value = getConfig().getDouble(path);
        if (value == 0.0) {
            logger.debug("No value found for path: " + path + ", returning default value: " + defaultValue, 3);
            return defaultValue;
        }
        return value;
    }

    @Nullable
    public ConfigurationSection getConfigurationSection(String path) {
        ConfigurationSection section = getConfig().getConfigurationSection(path);
        if (section == null) {
            logger.error("No configuration section found at path: " + path);
            return null;
        }
        return section;
    }

    public boolean set(String path, Object value) {
        if (value == null) {
            logger.error("Cannot set null value for path: " + path);
            return false;
        }
        getConfig().set(path, value);
        saveConfig();
        return true;
    }

    public float getFloat(String path) {
        return (float) getConfig().getDouble(path);
    }

}
