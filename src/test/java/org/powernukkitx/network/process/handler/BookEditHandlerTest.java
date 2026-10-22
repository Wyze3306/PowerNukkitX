package org.powernukkitx.network.process.handler;

import org.cloudburstmc.protocol.bedrock.data.BookEditAction;
import org.cloudburstmc.protocol.bedrock.packet.BookEditPacket;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.jupiter.api.Test;
import org.powernukkitx.PlayerFixture;
import org.powernukkitx.PlayerHandle;
import org.powernukkitx.ServerMockFixture;
import org.powernukkitx.TestPlayer;
import org.powernukkitx.inventory.HumanInventory;
import org.powernukkitx.item.Item;
import org.powernukkitx.item.ItemWrittenBook;
import org.powernukkitx.network.process.PlayerSessionHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * Signing a book: the author and XUID come from the signing player, never from the packet.
 */
class BookEditHandlerTest {

    @Test
    void signedBookCarriesTheSignersIdentity() throws IllegalAccessException {
        ServerMockFixture.boot();
        TestPlayer player = PlayerFixture.get();
        player.spawned = true;
        if (player.getInventory() == null) {
            // The fixture player never went through Player#initEntity
            FieldUtils.writeField(player, "inventory", new HumanInventory(player), true);
        }
        PlayerHandle handle = new PlayerHandle(player);
        PlayerSessionHolder holder = mock(PlayerSessionHolder.class);
        doReturn(player).when(holder).getPlayer();
        doReturn(handle).when(holder).getPlayerHandle();

        Item previous = player.getInventory().getItem(0);
        player.getInventory().setItem(0, Item.get(Item.WRITABLE_BOOK));
        try {
            BookEditAction.Finalize finalize = new BookEditAction.Finalize();
            finalize.setTitle("Title");
            finalize.setAuthor("Notch");
            finalize.setXuid("2535400000000000");
            BookEditPacket packet = new BookEditPacket();
            packet.setBookSlot(0);
            packet.setOperation(finalize);

            new BookEditHandler().handle(packet, holder, ServerMockFixture.server);

            ItemWrittenBook signed = assertInstanceOf(ItemWrittenBook.class, player.getInventory().getItem(0));
            assertEquals("Title", signed.getTitle());
            assertEquals(player.getName(), signed.getAuthor());
            assertEquals(player.getXUID(), signed.getXUID());
        } finally {
            player.getInventory().setItem(0, previous);
        }
    }
}
