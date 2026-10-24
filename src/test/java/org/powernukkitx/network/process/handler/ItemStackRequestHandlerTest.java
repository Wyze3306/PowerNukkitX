package org.powernukkitx.network.process.handler;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerEnumName;
import org.cloudburstmc.protocol.bedrock.data.inventory.FullContainerName;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequest;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequestSlotInfo;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.TakeAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackNetResult;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseInfo;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackResponsePacket;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.powernukkitx.PlayerFixture;
import org.powernukkitx.PlayerHandle;
import org.powernukkitx.ServerMockFixture;
import org.powernukkitx.TestPlayer;
import org.powernukkitx.inventory.HumanInventory;
import org.powernukkitx.item.Item;
import org.powernukkitx.network.process.PlayerSessionHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * A request the server refuses or fails to handle gets exactly one answer, ERROR. A SUCCESS sent
 * after it for the same request id made the client keep the move it predicted: every later click
 * on that slot was then refused for a stack net id the server never had, until the player relogged.
 */
class ItemStackRequestHandlerTest {

    @Test
    void aRefusedRequestIsAnsweredWithErrorOnly() throws IllegalAccessException {
        TestPlayer player = spawnedPlayer();
        player.getInventory().setItem(9, Item.AIR);
        // Taking from an empty slot is refused by the transfer processor
        List<ItemStackResponseInfo> responses = send(player, 7, take(ContainerEnumName.INVENTORY_CONTAINER, 9));

        assertEquals(1, responses.size());
        assertEquals(ItemStackNetResult.ERROR, responses.getFirst().getResult());
        assertEquals(7, responses.getFirst().getClientRequestId().getID());
    }

    @Test
    void aRequestOnAContainerNoLongerOpenIsAnsweredWithErrorOnly() throws IllegalAccessException {
        TestPlayer player = spawnedPlayer();
        // No window open: the click was in flight when the server closed the chest, and resolving
        // the container throws
        List<ItemStackResponseInfo> responses = send(player, 8, take(ContainerEnumName.LEVEL_ENTITY_CONTAINER, 0));

        assertEquals(1, responses.size());
        assertEquals(ItemStackNetResult.ERROR, responses.getFirst().getResult());
        assertEquals(8, responses.getFirst().getClientRequestId().getID());
    }

    private static TakeAction take(ContainerEnumName source, int slot) {
        ItemStackRequestSlotInfo from = new ItemStackRequestSlotInfo(source, slot, 1, new FullContainerName(source, null));
        ItemStackRequestSlotInfo to = new ItemStackRequestSlotInfo(ContainerEnumName.INVENTORY_CONTAINER, 10, 0,
                new FullContainerName(ContainerEnumName.INVENTORY_CONTAINER, null));
        return new TakeAction(1, from, to);
    }

    private static List<ItemStackResponseInfo> send(TestPlayer player, int requestId, ItemStackRequestAction action) {
        PlayerSessionHolder holder = mock(PlayerSessionHolder.class);
        doReturn(player).when(holder).getPlayer();
        doReturn(new PlayerHandle(player)).when(holder).getPlayerHandle();

        ItemStackRequestPacket packet = new ItemStackRequestPacket();
        packet.getRequests().add(new ItemStackRequest(requestId, new ItemStackRequestAction[]{action}, new String[0]));

        BedrockServerSession session = player.getSession();
        clearInvocations(session);
        new ItemStackRequestHandler().handle(packet, holder, ServerMockFixture.server);

        ArgumentCaptor<BedrockPacket> sent = ArgumentCaptor.forClass(BedrockPacket.class);
        verify(session, atLeastOnce()).sendPacket(sent.capture());
        return sent.getAllValues().stream()
                .filter(ItemStackResponsePacket.class::isInstance)
                .map(ItemStackResponsePacket.class::cast)
                .flatMap(p -> p.getResponses().stream())
                .toList();
    }

    private static TestPlayer spawnedPlayer() throws IllegalAccessException {
        ServerMockFixture.boot();
        TestPlayer player = PlayerFixture.newPlayer();
        player.spawned = true;
        if (player.getInventory() == null) {
            // The fixture player never went through Player#initEntity
            FieldUtils.writeField(player, "inventory", new HumanInventory(player), true);
        }
        doReturn(true).when(player.getSession()).isConnected();
        return player;
    }
}
