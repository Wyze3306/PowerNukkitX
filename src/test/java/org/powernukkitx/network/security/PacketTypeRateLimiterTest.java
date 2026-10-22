package org.powernukkitx.network.security;

import org.cloudburstmc.protocol.bedrock.packet.BookEditPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketTypeRateLimiterTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void aBurstIsAllowedThenTheRate() {
        long now = 1_000L;
        PacketTypeRateLimiter limiter = new PacketTypeRateLimiter(now);
        for (int i = 0; i < 120; i++) {
            assertTrue(limiter.tryAcquire(ItemStackRequestPacket.class, now), "packet " + i);
        }
        assertFalse(limiter.tryAcquire(ItemStackRequestPacket.class, now));

        // A hair over a second: exactly one second refills 59.99... tokens in floating point
        now += SECOND + SECOND / 1000;
        int allowed = 0;
        while (limiter.tryAcquire(ItemStackRequestPacket.class, now)) {
            allowed++;
        }
        assertEquals(60, allowed);
    }

    @Test
    void inputsCaughtUpAfterAStallAreNeverDropped() {
        PacketTypeRateLimiter limiter = new PacketTypeRateLimiter(0L);
        // Ten seconds of ticks arriving together, the way a client sends them once it recovers
        for (int tick = 0; tick < 20 * 10; tick++) {
            assertTrue(limiter.tryAcquire(PlayerAuthInputPacket.class, 0L));
        }
    }

    @Test
    void bucketsAreIndependent() {
        long now = 0L;
        PacketTypeRateLimiter limiter = new PacketTypeRateLimiter(now);
        while (limiter.tryAcquire(BookEditPacket.class, now)) {
            // drain the book bucket
        }
        assertTrue(limiter.tryAcquire(ItemStackRequestPacket.class, now));
    }

    @Test
    void unlistedPacketsAreNotLimitedHere() {
        PacketTypeRateLimiter limiter = new PacketTypeRateLimiter(0L);
        for (int i = 0; i < 10_000; i++) {
            assertTrue(limiter.tryAcquire(TextPacket.class, 0L));
        }
        assertEquals(-1, PacketTypeRateLimiter.perSecond(TextPacket.class));
        assertEquals(-1, PacketTypeRateLimiter.perSecond(PlayerAuthInputPacket.class));
    }

    @Test
    void reportsAtMostEveryFewSeconds() {
        PacketTypeRateLimiter limiter = new PacketTypeRateLimiter();
        assertTrue(limiter.reportDue());
        assertFalse(limiter.reportDue());
    }
}
