package org.powernukkitx.network.process.handler;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.cloudburstmc.protocol.bedrock.packet.InteractPacket;
import org.junit.jupiter.api.Test;
import org.powernukkitx.PlayerFixture;
import org.powernukkitx.PlayerHandle;
import org.powernukkitx.ServerMockFixture;
import org.powernukkitx.TestPlayer;
import org.powernukkitx.inventory.HumanInventory;
import org.powernukkitx.level.Level;
import org.powernukkitx.network.process.PlayerSessionHolder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.mock;

/**
 * The server can keep an inventory marked open after it went away on the client (closed without
 * a ContainerClose reaching the server). Opening it again must clear that state, or every later
 * attempt is turned away and the player cannot use the inventory again until a relog.
 */
class InteractHandlerInventoryTest {

    @Test
    void openingAnInventoryTheServerStillThinksOpenClearsTheStuckState() throws IllegalAccessException {
        ServerMockFixture.boot();
        TestPlayer player = PlayerFixture.newPlayer();
        player.spawned = true;
        if (player.getInventory() == null) {
            // The fixture player never went through Player#initEntity
            FieldUtils.writeField(player, "inventory", new HumanInventory(player), true);
        }
        Level level = spy(ServerMockFixture.level);
        doReturn(player).when(level).getEntity(anyLong());
        FieldUtils.writeField(player, "level", level, true);

        PlayerHandle handle = new PlayerHandle(player);
        handle.setInventoryOpen(true);
        PlayerSessionHolder holder = mock(PlayerSessionHolder.class);
        doReturn(player).when(holder).getPlayer();
        doReturn(handle).when(holder).getPlayerHandle();

        InteractPacket packet = new InteractPacket();
        packet.setAction(InteractPacket.Action.OPEN_INVENTORY);
        packet.setTargetRuntimeID(player.getId());

        new InteractHandler().handle(packet, holder, ServerMockFixture.server);

        assertFalse(player.isInventoryOpen());
    }
}
