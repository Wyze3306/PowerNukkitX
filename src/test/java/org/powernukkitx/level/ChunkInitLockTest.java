package org.powernukkitx.level;

import org.powernukkitx.GameMockExtension;
import org.powernukkitx.level.format.IChunk;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * {@link Level#getChunkAsync} must not wait for a chunk another thread is initialising: the player's
 * chunk manager calls it under its own monitor, while the initialising thread can need that monitor
 * (block entities spawning to the chunk's players). Both waiting froze the server on a teleport.
 */
@ExtendWith(GameMockExtension.class)
public class ChunkInitLockTest {

    @Test
    void getChunkAsyncDoesNotWaitForAChunkBeingInitialised(Level level) throws Exception {
        int chunkX = 4093, chunkZ = -4093;
        IChunk chunk = level.getProvider().getChunk(chunkX, chunkZ, true);
        Assertions.assertNotNull(chunk);
        Assertions.assertFalse(chunk.isInitiated(), "the provider loads a chunk without initialising it");

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread initialiser = new Thread(() -> {
            synchronized (chunk) {
                held.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "chunk-initialiser");
        initialiser.setDaemon(true);
        initialiser.start();
        Assertions.assertTrue(held.await(5, TimeUnit.SECONDS));

        try {
            CompletableFuture<IChunk> future = Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2),
                    () -> level.getChunkAsync(chunkX, chunkZ));
            Assertions.assertFalse(future.isDone(), "the chunk cannot be initialised while its lock is held");

            release.countDown();
            IChunk loaded = future.get(10, TimeUnit.SECONDS);
            Assertions.assertSame(chunk, loaded);
            Assertions.assertTrue(loaded.isInitiated());
        } finally {
            release.countDown();
            initialiser.join(5000);
        }
    }
}
