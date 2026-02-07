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
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.List;

public class MockSignTest {

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

    private static String linePlain(List<Component> lines, int index) {
        if (lines == null || index < 0 || index >= lines.size()) return "";
        return PLAIN.serialize(lines.get(index));
    }

    private void grant(PlayerMock p, String permission) {
        p.addAttachment(plugin, permission, true);
    }

    // Signs

    @Nested
    @DisplayName("signs")
    class SignTests {

        @Test
        @DisplayName("Recognizes [lp-user] and rewrites the sign lines")
        void recognizesLpUserTriggerAndRewritesLines() {
            grant(player, "legendperms.sign");

            World world = server.addSimpleWorld("world");
            Location loc = new Location(world, 0, 64, 0);

            Block block = world.getBlockAt(loc);
            block.setType(Material.OAK_SIGN);

            List<Component> out = getComponents(block);

            Assertions.assertNotEquals("[lp-user]", linePlain(out, 0), "Trigger line should be replaced.");
            Assertions.assertTrue(linePlain(out, 0).contains("LegendPerms"), "First line should contain 'LegendPerms'.");
            Assertions.assertTrue(
                    out.stream().map(PLAIN::serialize).anyMatch(s -> s.contains(player.getName())),
                    "At least one sign line should contain the player's name."
            );
        }

        private @NotNull List<Component> getComponents(Block block) {
            SignChangeEvent event = new SignChangeEvent(
                    block,
                    player,
                    new ArrayList<>(List.of(
                            Component.text("[lp-user]"),
                            Component.empty(),
                            Component.empty(),
                            Component.empty()
                    )),
                    Side.FRONT
            );

            new SignListener(plugin).onSignChange(event);

            return event.lines();
        }

        @Test
        @DisplayName("[lp-info] is NOT recognized unless configured in settings.yml")
        void lpInfoIsNotRecognizedUnlessConfigured() {
            grant(player, "legendperms.sign");

            World world = server.addSimpleWorld("world");
            Location loc = new Location(world, 1, 64, 0);

            Block block = world.getBlockAt(loc);
            block.setType(Material.OAK_SIGN);

            SignChangeEvent event = new SignChangeEvent(
                    block,
                    player,
                    new ArrayList<>(List.of(
                            Component.text("[lp-info]"),
                            Component.empty(),
                            Component.empty(),
                            Component.empty()
                    )),
                    Side.FRONT
            );

            new SignListener(plugin).onSignChange(event);

            Assertions.assertEquals("[lp-info]", linePlain(event.lines(), 0));
        }
    }


}
