package io.nexstudios.legendperms.listener;

import io.nexstudios.legendperms.LegendPerms;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.sign.Side;
import org.bukkit.event.block.SignChangeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.List;

@DisplayName("LegendPerms Commands (MockBukkit)")
public class MockCommandTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private ServerMock server;
    private LegendPerms plugin;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(LegendPerms.class);
        player = server.addPlayer("TestPlayer");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // helpers

    private void grant(PlayerMock p, String permission) {
        p.addAttachment(plugin, permission, true);
    }

    private void grantAdmin(PlayerMock p) {
        grant(p, "legendperms.admin");
    }

    private void grantLanguage(PlayerMock p) {
        grant(p, "legendperms.player.language");
    }

    private void assertNextMessageContains(PlayerMock p, String needle) {
        String msg = p.nextMessage();
        Assertions.assertNotNull(msg, "No message was sent (nextMessage() == null).");
        Assertions.assertTrue(
                msg.contains(needle),
                () -> "Expected message '" + msg + "' to contain: '" + needle + "'"
        );
    }

    private static String linePlain(List<Component> lines, int index) {
        if (lines == null || index < 0 || index >= lines.size()) return "";
        return PLAIN.serialize(lines.get(index));
    }

    // Root / Permission

    @Test
    @DisplayName("Without admin permission: command is rejected (Unknown command)")
    void deniesCommandsWithoutAdminPermission() {
        player.performCommand("legendperms group help");
        assertNextMessageContains(player, "Unknown command");
    }

    // /lp reload

    @Nested
    @DisplayName("reload")
    class ReloadCommandTests {

        @Test
        @DisplayName("Sends reload confirmation message")
        void sendsReloadMessage() {
            grantAdmin(player);

            player.performCommand("legendperms reload");
            assertNextMessageContains(player, "reloaded");
        }
    }

    // /lp group ...

    @Nested
    @DisplayName("group")
    class GroupCommandTests {

        @Test
        @DisplayName("help prints the help output")
        void helpPrintsHelp() {
            grantAdmin(player);

            player.performCommand("legendperms group help");
            assertNextMessageContains(player, "LegendPerms Help Command");
        }

        @Test
        @DisplayName("create then info shows group info")
        void createThenInfoShowsGroupInfo() {
            grantAdmin(player);

            player.performCommand("legendperms group create test");
            player.nextMessage(); // consume "created"

            player.performCommand("legendperms group info test");
            assertNextMessageContains(player, "Group Info");
        }

        @Test
        @DisplayName("delete removes an existing group")
        void deleteRemovesGroup() {
            grantAdmin(player);

            player.performCommand("legendperms group create test");
            player.nextMessage();

            player.performCommand("legendperms group delete test");
            assertNextMessageContains(player, "deleted");
        }

        @Test
        @DisplayName("edit prefix set updates the group's prefix")
        void editPrefixSetUpdatesPrefix() {
            grantAdmin(player);

            player.performCommand("legendperms group create test");
            player.nextMessage();

            player.performCommand("legendperms group edit test prefix set <yellow>Test");
            assertNextMessageContains(player, "set the prefix");
        }

        @Test
        @DisplayName("edit prefix remove clears the group's prefix")
        void editPrefixRemoveClearsPrefix() {
            grantAdmin(player);

            player.performCommand("legendperms group create test");
            player.nextMessage();

            player.performCommand("legendperms group edit test prefix set <yellow>Test");
            player.nextMessage();

            player.performCommand("legendperms group edit test prefix remove");
            assertNextMessageContains(player, "set the prefix");
        }

        @Test
        @DisplayName("edit priority set updates the group's priority")
        void editPrioritySetUpdatesPriority() {
            grantAdmin(player);

            player.performCommand("legendperms group create test");
            player.nextMessage();

            player.performCommand("legendperms group edit test priority set 5");
            assertNextMessageContains(player, "5");
        }

        @Test
        @DisplayName("edit permission set allow adds a permission node")
        void editPermissionAllowAddsNode() {
            grantAdmin(player);

            player.performCommand("legendperms group create test");
            player.nextMessage();

            player.performCommand("legendperms group edit test permission set allow example.permission");
            assertNextMessageContains(player, "Successfully added permission");
        }

        @Test
        @DisplayName("edit permission set deny adds a denied permission node")
        void editPermissionDenyAddsNode() {
            grantAdmin(player);

            player.performCommand("legendperms group create test");
            player.nextMessage();

            player.performCommand("legendperms group edit test permission set deny example.permission");
            assertNextMessageContains(player, "Successfully added permission");
        }

        @Test
        @DisplayName("edit permission remove removes a previously added node")
        void editPermissionRemoveRemovesNode() {
            grantAdmin(player);

            player.performCommand("legendperms group create test");
            player.nextMessage();

            player.performCommand("legendperms group edit test permission set allow example.permission");
            player.nextMessage();

            player.performCommand("legendperms group edit test permission remove example.permission");
            assertNextMessageContains(player, "Successfully removed permission");
        }
    }

    // /lp user ...

    @Nested
    @DisplayName("user")
    class UserCommandTests {

        @Test
        @DisplayName("help prints the help output")
        void helpPrintsHelp() {
            grantAdmin(player);

            player.performCommand("legendperms user help");
            assertNextMessageContains(player, "LegendPerms Help Command");
        }

        @Test
        @DisplayName("group add permanent adds a group to an online player")
        void groupAddPermanentAddsGroup() {
            grantAdmin(player);

            server.addPlayer("TargetPlayer");

            player.performCommand("legendperms group create vip");
            player.nextMessage();

            player.performCommand("legendperms user group add TargetPlayer vip permanent");
            assertNextMessageContains(player, "Successfully added");
        }

        @Test
        @DisplayName("group add temporary adds a temporary group to an online player")
        void groupAddTemporaryAddsGroup() {
            grantAdmin(player);

            server.addPlayer("TargetPlayer");

            player.performCommand("legendperms group create vip");
            player.nextMessage();

            player.performCommand("legendperms user group add TargetPlayer vip 5m");
            assertNextMessageContains(player, "Successfully added");
        }

        @Test
        @DisplayName("group remove removes a group from an online player")
        void groupRemoveRemovesGroup() {
            grantAdmin(player);

            server.addPlayer("TargetPlayer");

            player.performCommand("legendperms group create vip");
            player.nextMessage();

            player.performCommand("legendperms user group add TargetPlayer vip permanent");
            player.nextMessage();

            player.performCommand("legendperms user group remove TargetPlayer vip");
            assertNextMessageContains(player, "Successfully removed");
        }
    }
}