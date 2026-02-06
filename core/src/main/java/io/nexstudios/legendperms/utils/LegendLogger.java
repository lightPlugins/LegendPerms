package io.nexstudios.legendperms.utils;

import lombok.Getter;
import lombok.Setter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;

import java.util.List;

@Getter
@Setter
public class LegendLogger {

    private final String prefix;
    private boolean isDebugEnabled;
    private int debugLevel;
    private String themeColor;
    private final MiniMessage miniMessage;

    public LegendLogger(String prefix, boolean isDebugEnabled, int debugLevel, String themeColor) {
        this.prefix = prefix;
        this.isDebugEnabled = isDebugEnabled;
        this.debugLevel = debugLevel;
        this.themeColor = themeColor;
        this.miniMessage = MiniMessage.miniMessage();
    }

    private void sendEmptyLine() {
        Bukkit.getConsoleSender().sendMessage(Component.text(" "));
    }

    public void info(String message) {
        Bukkit.getConsoleSender().sendMessage(miniMessage.deserialize(prefix
                + " <gray>[<white>INFO<gray>] <reset>" + message));
    }

    public void info(List<String> messages) {
        sendEmptyLine();
        for (String message : messages) {
            Bukkit.getConsoleSender().sendMessage(miniMessage.deserialize(prefix
                    + " <gray>[<white>INFO<gray>] <reset>" + message));
        }
        sendEmptyLine();
    }

    public void warning(String message) {
        Bukkit.getConsoleSender().sendMessage(miniMessage.deserialize(prefix
                + " <yellow>[WARNING] <reset>" + message));
    }

    public void warning(List<String> messages) {
        sendEmptyLine();
        Bukkit.getConsoleSender().sendMessage(miniMessage.deserialize(
                " <gold># <yellow>############## <dark_red>WARNING <yellow>############## <gold>#"));
        sendEmptyLine();
        for (String message : messages) {
            Bukkit.getConsoleSender().sendMessage(miniMessage.deserialize(prefix
                    + " <yellow>[<gold>WARNING<yellow>] <reset>" + message));
        }
        sendEmptyLine();
        Bukkit.getConsoleSender().sendMessage(miniMessage.deserialize(
                " <gold># <yellow>################################### <gold>#"));
        sendEmptyLine();
    }

    public void error(String message) {
        Bukkit.getConsoleSender().sendMessage(miniMessage.deserialize(prefix
                + " <red>[ERROR] <reset>" + message));
    }

    public void error(List<String> messages) {
        sendEmptyLine();
        Bukkit.getConsoleSender().sendMessage(miniMessage.deserialize(
                " <dark_red># <red>############## <dark_red>ERROR <red>############## <dark_red>#"));
        sendEmptyLine();
        for (String message : messages) {
            Bukkit.getConsoleSender().sendMessage(miniMessage.deserialize(prefix
                    + " <red>[<dark_red>ERROR<red>] <reset>" + message));
        }
        sendEmptyLine();
        Bukkit.getConsoleSender().sendMessage(miniMessage.deserialize(
                " <dark_red># <red>################################### <dark_red>#"));
        sendEmptyLine();
    }

    public void custom(String message) {
        Bukkit.getConsoleSender().sendMessage(miniMessage.deserialize(prefix + message));
    }

    public void custom(List<String> messages) {
        for (String message : messages) {
            Bukkit.getConsoleSender().sendMessage(miniMessage.deserialize(prefix + message));
        }
    }

    public void debug(String message, int debugLevel) {
        if (!isDebugEnabled || debugLevel > this.debugLevel) {
            return;
        }
        Bukkit.getConsoleSender().sendMessage(miniMessage.deserialize(prefix
                + " <blue>[<aqua>DEBUG<blue>] <reset>" + message));
    }

    public void debug(List<String> messages, int debugLevel) {
        if (!isDebugEnabled || debugLevel > this.debugLevel) {
            return;
        }
        for (String message : messages) {
            Bukkit.getConsoleSender().sendMessage(miniMessage.deserialize(prefix
                    + " <blue>[<aqua>DEBUG<blue>] <reset>" + message));
        }
    }
}
