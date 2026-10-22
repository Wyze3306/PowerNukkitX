package org.powernukkitx.network.process.handler;

import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.data.PlayerBlockActionData;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Only the first few block actions of an input are handled: each may load a chunk or resend the
 * inventory, and a packet can carry thousands.
 */
class PlayerAuthInputBlockActionsTest {

    private static PlayerAuthInputPacket withActions(int count) {
        PlayerAuthInputPacket packet = new PlayerAuthInputPacket();
        for (int i = 0; i < count; i++) {
            PlayerBlockActionData action = new PlayerBlockActionData();
            action.setPlayerActionType(PlayerActionType.PREDICT_DESTROY_BLOCK);
            action.setBlockPosition(Vector3i.from(i * 1000, 64, 0));
            packet.getPlayerBlockActions().add(action);
        }
        return packet;
    }

    @Test
    void aFloodOfActionsIsCut() {
        PlayerAuthInputPacket packet = withActions(5_000);
        List<PlayerBlockActionData> handled = PlayerAuthInputHandler.blockActionsToHandle(packet);
        assertEquals(PlayerAuthInputHandler.MAX_BLOCK_ACTIONS, handled.size());
        assertSame(packet.getPlayerBlockActions().getFirst(), handled.getFirst());
    }

    @Test
    void aVanillaTickIsHandledWhole() {
        PlayerAuthInputPacket packet = withActions(3);
        assertEquals(3, PlayerAuthInputHandler.blockActionsToHandle(packet).size());
    }
}
