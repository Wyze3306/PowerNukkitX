package org.powernukkitx;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.powernukkitx.level.Location;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The client movement queue is polled by the level thread while {@code teleport} and
 * {@code switchLevel} clear it from whatever thread moves the player. With netty's MPSC queue the
 * level thread ended up spinning forever in {@code poll} and the whole level froze (prod,
 * 2026-10-24). Three threads hammer the queue the same way here; it has to keep going.
 */
class PlayerMovementQueueTest {

    @Test
    void pollingWhileAnotherThreadClearsNeverHangs() {
        Queue<Location> queue = Player.newClientMovementQueue();
        AtomicBoolean stop = new AtomicBoolean();
        Location loc = new Location(1, 2, 3);

        Thread producer = new Thread(() -> {
            while (!stop.get()) queue.offer(loc);
        });
        Thread clearer = new Thread(() -> {
            while (!stop.get()) queue.clear();
        });
        producer.start();
        clearer.start();
        try {
            Assertions.assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
                long end = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                long polled = 0;
                while (System.nanoTime() < end) {
                    Location movement;
                    while ((movement = queue.poll()) != null) {
                        Assertions.assertSame(loc, movement);
                        polled++;
                    }
                }
                Assertions.assertTrue(polled > 0);
            });
        } finally {
            stop.set(true);
        }
    }

    @Test
    void staysBounded() {
        Queue<Location> queue = Player.newClientMovementQueue();
        Location loc = new Location(0, 0, 0);
        int accepted = 0;
        for (int i = 0; i < 5000; i++) if (queue.offer(loc)) accepted++;
        Assertions.assertEquals(2048, accepted);
    }
}
