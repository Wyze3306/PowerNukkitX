package org.powernukkitx.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboundBacklogGuardTest {

    @Test
    void aClientThatStopsReadingIsClosed() {
        EmbeddedChannel channel = new EmbeddedChannel(new OutboundBacklogGuard(1_000));
        // Nothing is flushed, as when the transport cannot take any more
        channel.write(Unpooled.buffer().writeZero(600));
        channel.write(Unpooled.buffer().writeZero(600));
        assertTrue(channel.isOpen());

        ByteBuf rejected = Unpooled.buffer().writeZero(600);
        channel.write(rejected);
        assertFalse(channel.isOpen());
        assertEquals(0, rejected.refCnt(), "the refused write is released");
    }

    @Test
    void aClientThatReadsIsLeftAlone() {
        EmbeddedChannel channel = new EmbeddedChannel(new OutboundBacklogGuard(1_000));
        for (int i = 0; i < 100; i++) {
            channel.writeAndFlush(Unpooled.buffer().writeZero(600));
            ((ByteBuf) channel.readOutbound()).release();
        }
        assertTrue(channel.isOpen());
        channel.finishAndReleaseAll();
    }
}
