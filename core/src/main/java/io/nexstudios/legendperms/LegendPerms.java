package io.nexstudios.legendperms;

import com.zaxxer.hikari.HikariDataSource;
import io.nexstudios.legendperms.listener.PrefixChatListener;
import io.nexstudios.legendperms.listener.TablistPrefixListener;
import io.nexstudios.legendperms.commands.BrigadierRootCommand;
import io.nexstudios.legendperms.commands.GroupBrigadierCommand;
import io.nexstudios.legendperms.commands.ReloadBrigadierCommand;
import io.nexstudios.legendperms.commands.UserBrigadierCommand;
import io.nexstudios.legendperms.database.AbstractDatabase;
import io.nexstudios.legendperms.database.PooledDatabase;
import io.nexstudios.legendperms.database.impl.MariaDatabase;
import io.nexstudios.legendperms.database.impl.MySQLDatabase;
import io.nexstudios.legendperms.database.impl.SQLiteDatabase;
import io.nexstudios.legendperms.database.model.ConnectionProperties;
import io.nexstudios.legendperms.database.model.DatabaseCredentials;
import io.nexstudios.legendperms.file.LegendFile;
import io.nexstudios.legendperms.file.LegendFileReader;
import io.nexstudios.legendperms.perms.LegendPermissionService;
import io.nexstudios.legendperms.perms.PermissibleInjector;
import io.nexstudios.legendperms.perms.listener.PlayerInjectorListener;
import io.nexstudios.legendperms.perms.listener.LoadPlayerDataListener;
import io.nexstudios.legendperms.perms.storage.PermissionDAO;
import io.nexstudios.legendperms.utils.LegendLanguage;
import io.nexstudios.legendperms.utils.LegendLogger;
import io.nexstudios.legendperms.utils.LegendMessageSender;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

@Getter
public class LegendPerms extends JavaPlugin {

    @Getter
    private static LegendPerms instance;
    public LegendLogger legendLogger;
    public LegendLanguage language;
    public LegendMessageSender messageSender;

    private LegendFile settingsFile;
    private LegendFileReader languageFiles;

    private AbstractDatabase abstractDatabase;
    public HikariDataSource hikariDataSource;

    private LegendPermissionService permissionService;
    private TablistPrefixListener tablistPrefixListener;
    private PermissibleInjector permissibleInjector;
    private PermissionDAO permsRepository;

    private BrigadierRootCommand brigadierRootCommand;


    @Override
    public void onLoad() {
        instance = this;
        legendLogger = new LegendLogger("<reset>[<blue>LegendPerms<reset>]", true, 1, "<blue>");
        legendLogger.info("Loading LegendPerms ...");
        onReload();
        legendLogger.info("Initializing database connection ...");
        initDatabase();
        legendLogger.info("Loading phase completed. waiting for enable phase ...");
    }


    @Override
    public void onEnable() {
        legendLogger.info("Starting up ...");
        legendLogger.info("Load default files ...");

        legendLogger.info("Initializing permission system ...");
        initPermissionSystem();
        permsRepository.migrate().join();
        permissionService.loadAllFromStorage();

        legendLogger.info("Registering Commands ...");
        registerBrigadierCommands();
        legendLogger.info("Registering Events ...");
        registerEvents();
        legendLogger.info("Registering Services ...");
        registerDatabaseService();

        // Scheduler for checking expirations (temp ranks)
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (permissionService == null) return;
            Bukkit.getOnlinePlayers().forEach(p -> permissionService.tickExpirations(p.getUniqueId()));
        }, 20L, 20L);

        legendLogger.info("LegendPerms successfully enabled");
    }

    @Override
    public void onDisable() {
        legendLogger.info("Disabling LegendPerms...");

        if (permissibleInjector != null) {
            permissibleInjector.uninjectAll(Bukkit.getOnlinePlayers());
        }

        legendLogger.info("Shutting down Database connection ...");

        if (abstractDatabase != null) {
            try {
                abstractDatabase.close();
            } catch (Exception e) {
                legendLogger.error(List.of(
                        "Error while closing database.",
                        "Error: " + e.getMessage()
                ));
            }
            abstractDatabase = null;
        }

        hikariDataSource = null;

        legendLogger.info("LegendPerms has been disabled!");
    }

    public void onReload() {
        loadLegendFiles();
    }

    private void registerBrigadierCommands() {
        this.brigadierRootCommand = new BrigadierRootCommand(this);

        this.brigadierRootCommand.add(new ReloadBrigadierCommand(this));
        this.brigadierRootCommand.add(new GroupBrigadierCommand(this));
        this.brigadierRootCommand.add(new UserBrigadierCommand(this));

        this.brigadierRootCommand.register();
    }

    private void registerEvents() {
        Bukkit.getPluginManager().registerEvents(new PlayerInjectorListener(permissibleInjector), this);
        Bukkit.getPluginManager().registerEvents(new LoadPlayerDataListener(permissionService), this);
        Bukkit.getPluginManager().registerEvents(new PrefixChatListener(this), this);
        this.tablistPrefixListener = new TablistPrefixListener(this);
        Bukkit.getPluginManager().registerEvents(this.tablistPrefixListener, this);
    }

    private void initPermissionSystem() {
        this.permsRepository = new PermissionDAO(this.abstractDatabase);

        this.permissionService = new LegendPermissionService(legendLogger, permsRepository);
        this.permissionService.ensureDefaultGroup();

        this.permissibleInjector = new PermissibleInjector(permissionService, legendLogger);
    }


    private void initDatabase() {
        try {
            String databaseType = settingsFile.getConfig().getString("storage.type");
            ConnectionProperties connectionProperties = ConnectionProperties.fromConfig(settingsFile.getConfig());
            DatabaseCredentials credentials = DatabaseCredentials.fromConfig(settingsFile.getConfig());

            if (databaseType == null) {
                legendLogger.error(List.of(
                        "Database type not specified in config. Disabling plugin.",
                        "Please specify the database type in the config file.",
                        "Valid database types are: SQLite, MySQL, MariaDB.",
                        "Disabling all nexus related plugins."));
                onDisable();
                return;
            }

            switch (databaseType.toLowerCase()) {
                case "sqlite":
                    this.abstractDatabase = new SQLiteDatabase(this, legendLogger, connectionProperties);
                    legendLogger.info("Using SQLite (local) database.");
                    break;
                case "mysql":
                    this.abstractDatabase = new MySQLDatabase(this, legendLogger, credentials, connectionProperties);
                    legendLogger.info("Using MySQL (remote) database.");
                    break;
                case "mariadb":
                    this.abstractDatabase = new MariaDatabase(this, legendLogger, credentials, connectionProperties);
                    legendLogger.info("Using MariaDB (remote) database.");
                    break;
                default:
                    legendLogger.error(List.of(
                            "Database type not specified in config. Disabling plugin.",
                            "Please specify the database type in the config file.",
                            "Valid database types are: SQLite, MySQL, MariaDB.",
                            "Disabling all nexus related plugins."));
                    return;
            }

            this.abstractDatabase.connect();

        } catch (Exception e) {
            legendLogger.error(List.of(
                    "Could not maintain Database Connection.",
                    "Please check your database connection & settings in the config file.",
                    "Disabling nexus related plugins."));
            throw new RuntimeException("Could not maintain Database Connection.", e);
        }
    }

    private void registerDatabaseService() {
        if (this.abstractDatabase instanceof PooledDatabase pooled) {
            try {
                this.hikariDataSource = (HikariDataSource) pooled.getDataSource();
                legendLogger.info("Database registered successfully -> " + pooled.getDatabaseType());
            } catch (Exception e) {
                legendLogger.error(List.of(
                        "Failed to register NexusDatabaseService.",
                        "Error: " + e.getMessage()
                ));
            }
        } else {
            legendLogger.warning("AbstractDatabase is not a PooledDatabase. NexusDatabaseService will not be registered.");
        }
    }

    private void loadLegendFiles() {
        settingsFile = new LegendFile(this, "settings.yml", legendLogger, true);
        // generate the default language file
        new LegendFile(this, "language/en_US.yml", legendLogger, true);
        legendLogger.setDebugEnabled(settingsFile.getBoolean("logging.debug.enabled", true));
        legendLogger.setDebugLevel(settingsFile.getInt("logging.debug.level", 3));

        legendLogger.info("Loading language system ...");
        languageFiles = new LegendFileReader("language", this);
        language = new LegendLanguage(languageFiles, legendLogger);

        messageSender = new LegendMessageSender(language, legendLogger);

        legendLogger.info("All files have been loaded.");

    }
}
