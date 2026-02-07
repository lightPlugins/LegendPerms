package io.nexstudios.legendperms.listener;

import io.nexstudios.legendperms.LegendPerms;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;


public class MockBukkitTest {

    private ServerMock server;
    private LegendPerms plugin;
    private PlayerMock player;

    @BeforeEach
    void SetUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(LegendPerms.class);
        player = server.addPlayer("TestPlayer");
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void checkPermission() {
        this.player.performCommand("legendperms group help");
        String message = this.player.nextMessage();
        Assertions.assertTrue(message.contains("Unknown command"));
    }

    @Test
    public void checkHelpCommand() {
        this.player.addAttachment(plugin, "legendperms.admin", true);
        this.player.performCommand("legendperms group help");
        String message = this.player.nextMessage();
        Assertions.assertTrue(message.contains("LegendPerms Help Command"));
    }

    @Test
    public void checkReloadCommand() {
        this.player.addAttachment(plugin, "legendperms.admin", true);
        this.player.performCommand("legendperms reload");
        String message = this.player.nextMessage();
        Assertions.assertTrue(message.contains("reloaded"));
    }

    @Test
    public void removeGroup() {
        this.player.addAttachment(plugin, "legendperms.admin", true);
        this.player.performCommand("legendperms group create test");
        this.player.nextMessage();
        this.player.performCommand("legendperms group delete test");
        String message = this.player.nextMessage();
        System.out.println(message);
        Assertions.assertTrue(message.contains("deleted"));
    }

    @Test
    public void setGroupPrefix() {
        this.player.addAttachment(plugin, "legendperms.admin", true);
        this.player.performCommand("legendperms group create test");
        this.player.nextMessage();
        this.player.performCommand("legendperms group edit test prefix set <yellow>Test");
        String message = this.player.nextMessage();
        System.out.println(message);
        Assertions.assertTrue(message.contains("set the prefix"));
    }

}
