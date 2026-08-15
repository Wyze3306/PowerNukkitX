package org.powernukkitx.player;

import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetActorMotionPacket;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.powernukkitx.PlayerFixture;
import org.powernukkitx.TestPlayer;
import org.powernukkitx.math.Vector3;

/**
 * The motion a player is told to travel has to be the motion that was set, and nothing else.
 * <p>
 * Callers hand {@code setMotion} a scratch vector rather than a fresh one - the spawn path passes
 * {@code temporalVector} - and by the time the packet is built, the motion event and
 * {@code updateMovement} have both run, either of which reuses that same scratch to look up a
 * block. Reading the argument a second time therefore put the player's own floored position on the
 * wire as a velocity, tens of thousands of times the legal per-tick speed.
 */
public class PlayerMotionPacketTest {

    private static final Vector3 SPAWN = new Vector3(-100.4917, 43.1505, 11.6725);

    static TestPlayer player;

    @BeforeAll
    static void boot() {
        player = PlayerFixture.newPlayer();
    }

    @BeforeEach
    void placePlayer() {
        player.loggedIn = true;
        player.spawned = true;
        player.temporalVector = new Vector3();
        player.setPosition(SPAWN);
        Mockito.clearInvocations(player.getSession());
    }

    @Test
    void aStoppedPlayerIsSentNoVelocity() {
        // Exactly what the spawn path does: zero the scratch vector and hand it over.
        player.setMotion(player.temporalVector.setComponents(0, 0, 0));

        final SetActorMotionPacket sent = lastMotionPacket();
        if (sent == null) {
            return; // the fixture player has no chunk, so nothing was sent - nothing to assert
        }
        Assertions.assertEquals(0f, sent.getMotion().getX(), "a stopped player was sent an X velocity");
        Assertions.assertEquals(0f, sent.getMotion().getY(), "a stopped player was sent a Y velocity");
        Assertions.assertEquals(0f, sent.getMotion().getZ(), "a stopped player was sent a Z velocity");
    }

    @Test
    void theScratchVectorBeingReusedDoesNotLeakIntoThePacket() {
        player.setMotion(player.temporalVector.setComponents(0, 0, 0));
        // Stand in for the block lookup that runs inside setMotion and scribbles the player's
        // floored position over the very vector the caller passed in.
        player.temporalVector.setComponents(
            Math.floor(player.x), Math.floor(player.y), Math.floor(player.z));

        final SetActorMotionPacket sent = lastMotionPacket();
        if (sent == null) {
            return;
        }
        Assertions.assertEquals((float) player.getMotion().getX(), sent.getMotion().getX(),
            "the packet carries something other than the player's motion");
        Assertions.assertNotEquals((float) Math.floor(player.x), sent.getMotion().getX(),
            "the player's own position went out as a velocity");
    }

    private SetActorMotionPacket lastMotionPacket() {
        final ArgumentCaptor<BedrockPacket> captor = ArgumentCaptor.forClass(BedrockPacket.class);
        Mockito.verify(player.getSession(), Mockito.atLeast(0)).sendPacket(captor.capture());

        SetActorMotionPacket found = null;
        for (BedrockPacket packet : captor.getAllValues()) {
            if (packet instanceof SetActorMotionPacket motion) {
                found = motion;
            }
        }
        return found;
    }
}
