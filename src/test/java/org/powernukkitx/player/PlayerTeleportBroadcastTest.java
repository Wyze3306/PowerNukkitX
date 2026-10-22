package org.powernukkitx.player;

import org.cloudburstmc.protocol.bedrock.data.actor.MoveActorAbsoluteData;
import org.cloudburstmc.protocol.bedrock.data.payload.move.PositionMode;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveActorAbsolutePacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.powernukkitx.PlayerFixture;
import org.powernukkitx.TestPlayer;
import org.powernukkitx.event.player.PlayerTeleportEvent;
import org.powernukkitx.level.Location;
import org.powernukkitx.math.Vector3;

import java.util.List;

/**
 * What the players watching somebody teleport are told.
 * <p>
 * The client mishandles the teleport flag of {@link MoveActorAbsolutePacket} on a player: bystanders
 * see the player land, then get put down again a little further once their next move comes in, as
 * if they had been teleported twice. A player's teleport has to reach them as a
 * {@link MovePlayerPacket} in teleport mode instead.
 */
public class PlayerTeleportBroadcastTest {

    // Both in chunk 0,0: the bystander loads no chunk, so a chunk change would despawn the player
    // from them before the teleport is broadcast, and there would be nothing left to observe.
    private static final Vector3 ORIGIN = new Vector3(0.5, 80, 0.5);
    private static final Vector3 DESTINATION = new Vector3(12.5, 81, 4.5);

    static TestPlayer player;
    static TestPlayer bystander;

    @BeforeAll
    static void boot() {
        player = PlayerFixture.newPlayer();
        bystander = PlayerFixture.newPlayer();
    }

    @BeforeEach
    void teleportInFrontOfABystander() {
        player.loggedIn = true;
        player.spawned = true;
        player.setHealth(20);
        player.temporalVector = new Vector3();
        player.setPosition(ORIGIN);

        Mockito.doReturn(true).when(bystander.getSession()).isConnected();
        player.getViewers().put(bystander.getLoaderId(), bystander);
        Mockito.clearInvocations(bystander.getSession());

        Assertions.assertTrue(player.teleport(
                Location.fromObject(DESTINATION, player.getLevel()),
                PlayerTeleportEvent.TeleportCause.ENDER_PEARL));
    }

    @Test
    void bystandersAreSentTheDestinationAsAPlayerTeleport() {
        final MovePlayerPacket move = sentToBystander(MovePlayerPacket.class).stream()
                .filter(packet -> packet.getPlayerRuntimeID() == player.getId())
                .filter(packet -> packet.getPositionMode() == PositionMode.TELEPORT)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the bystander was never told about the teleport"));

        Assertions.assertEquals(DESTINATION.x, move.getPosition().getX(), 1e-4);
        Assertions.assertEquals(DESTINATION.y + player.getBaseOffset(), move.getPosition().getY(), 1e-4);
        Assertions.assertEquals(DESTINATION.z, move.getPosition().getZ(), 1e-4);
    }

    @Test
    void bystandersAreNeverSentTheActorTeleportFlag() {
        for (MoveActorAbsolutePacket packet : sentToBystander(MoveActorAbsolutePacket.class)) {
            final MoveActorAbsoluteData data = packet.getMoveData();
            Assertions.assertFalse(data.getActorRuntimeID() == player.getId() && data.isTeleported(),
                    "the player's teleport went out as a teleported actor move");
        }
    }

    private static <T extends BedrockPacket> List<T> sentToBystander(Class<T> type) {
        final ArgumentCaptor<BedrockPacket> captor = ArgumentCaptor.forClass(BedrockPacket.class);
        Mockito.verify(bystander.getSession(), Mockito.atLeast(0)).sendPacket(captor.capture());
        return captor.getAllValues().stream().filter(type::isInstance).map(type::cast).toList();
    }
}
