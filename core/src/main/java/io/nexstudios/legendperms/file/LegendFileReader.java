package io.nexstudios.legendperms.file;

import lombok.Getter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

@Getter
public class LegendFileReader {

    private final String directoryPath;
    private List<File> files;
    private final List<FileConfiguration> bukkitFiles;
    // Optional: Map to store FileConfiguration by file name without extension
    private final Map<String, FileConfiguration> bukkitFileMap;
    private final JavaPlugin plugin;

    /**
     * Constructor for NexusFileReader
     * <p>IMPORTANT: This Reader will never generate a file, it will only read files from the specified directory.
     * @param directoryPath the path to the directory containing the yml files (folders inside the plugin folder)
     * @param plugin the JavaPlugin instance
     */
    public LegendFileReader(String directoryPath, JavaPlugin plugin) {
        this.plugin = plugin;
        // this.directoryPath = "plugins/" + plugin.getName() + "/" + directoryPath;
        this.directoryPath = new File(plugin.getDataFolder(), directoryPath).getPath();
        this.bukkitFileMap = new HashMap<>();
        this.bukkitFiles = new ArrayList<>();
        loadYmlFiles();
        if(files != null && !files.isEmpty()) {
            readBukkitFiles();
        }
    }

    private void readBukkitFiles() {

        for (File file : files) {
            FileConfiguration config = YamlConfiguration.loadConfiguration(file);
            bukkitFiles.add(config);
            bukkitFileMap.put(getFileNameWithoutExtension(file), config);
        }
    }

    /**
     * Casts an arbitrary value (usually from ConfigurationSection#get(...))
     * into a List<Map<String, Object>>.
     *
     * If the value is not a suitable list, an empty list is returned.
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> castListOfMap(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object element : list) {
                if (element instanceof Map<?, ?> map) {
                    out.add((Map<String, Object>) map);
                }
            }
            return out;
        }
        return List.of();
    }

    /**
     * Convenience: reads a List<Map<String, Object>> from a FileConfiguration path.
     * <p>
     * Typical use case:
     * <pre>
     *     List&lt;Map&lt;String, Object&gt;&gt; costs =
     *         NexusFileReader.getMapList(config, "costs");
     * </pre>
     *
     * If the path does not exist or is not a suitable list, an empty list is returned.
     */
    public static List<Map<String, Object>> getMapList(FileConfiguration config, String path) {
        if (config == null || path == null || path.isEmpty()) {
            return List.of();
        }
        Object raw = config.get(path);
        return castListOfMap(raw);
    }

    /**
     * Load all yml files found in the specified directory
     * Save all found files in the "List<File> yamlFiles" field
     */
    private void loadYmlFiles() {
        files = new ArrayList<>();
        Path directory = Paths.get(directoryPath);
        try  {
            if (!Files.exists(directory)) {
                Files.createDirectories(directory);
            }
            Files.walkFileTree(directory, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                @Override
                public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".yml")) {
                        String fileName = file.getFileName().toString();
                        // Exclude the example file with the name "_example.yml"
                        if (!fileName.equalsIgnoreCase("_example.yml")) {
                            files.add(file.toFile());
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Something went wrong in Nexus File Reader", e);
        }
    }

    /**
     * Reload all yml files found in the specified directory
     */
    public void reload() {
        loadYmlFiles();
    }

    /**
     * Get the file name without the extension (any extension)
     */
    public String getFileNameWithoutExtension(File file) {
        String fileName = file.getName();
        int pos = fileName.lastIndexOf(".");
        if (pos > 0) {
            fileName = fileName.substring(0, pos);
        }
        return fileName;
    }
}
