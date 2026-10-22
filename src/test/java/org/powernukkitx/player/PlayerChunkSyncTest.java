package org.powernukkitx.player;

import org.powernukkitx.PlayerFixture;
import org.powernukkitx.ServerMockFixture;
import org.powernukkitx.TestPlayer;
import org.powernukkitx.event.player.PlayerTeleportEvent;
import org.powernukkitx.level.ChunkLoader;
import org.powernukkitx.level.DimensionEnum;
import org.powernukkitx.level.Level;
import org.powernukkitx.level.Location;
import org.powernukkitx.level.format.IChunk;
import org.powernukkitx.level.format.LevelConfig;
import org.powernukkitx.level.format.leveldb.LevelDBProvider;
import org.powernukkitx.math.Vector3;
import org.powernukkitx.utils.GameLoop;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.apache.commons.io.FileUtils;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.File;
import java.util.HashMap;

/**
 * "The player has this chunk" is recorded in two places: {@code PlayerChunkManager}'s sent-chunk
 * set, and the level's chunk loader registration. They have to say the same thing. A player listed
 * as having a chunk they no longer load gets no entity spawns and no block updates for it - the
 * level filters both on the loader registration - and nothing puts them back until the chunk
 * leaves the view distance and comes back.
 * <p>
 * The gap that breaks them apart is time: a chunk is serialised and sent a tick or more after the
 * request that asked for it, and anything that shrinks the player's view in between (a view
 * distance change, a fast walk, a teleport) drops it from the manager while the request is still
 * in the level's queue.
 */
class PlayerChunkSyncTest {

    private static TestPlayer player;
    private static Level homeLevel;
    private static Level otherLevel;
    private static File otherLevelDir;

    @BeforeAll
    static void boot() throws Exception {
        // A player of this test's own: it ends up in another level, holding that level's chunks,
        // and the shared fixture player would carry all of that into the next test class of the
        // same JVM fork.
        player = PlayerFixture.newPlayer();
        homeLevel = ServerMockFixture.level;

        // Both the chunk manager and the level's chunk delivery bail out on a disconnected player,
        // and the fixture's session mock reports itself disconnected by default.
        Mockito.doReturn(true).when(player.getSession()).isConnected();
        player.loggedIn = true;
        player.spawned = true;
        // The fixture player never goes through Entity#init, which is what normally hands out the
        // scratch vector Player#teleport writes its zeroed motion into.
        player.temporalVector = new Vector3();

        otherLevel = openSecondLevel();
    }

    @AfterAll
    static void tearDown() {
        if (player != null) {
            player.unloadAllUsedChunk();
            Mockito.doReturn(false).when(player.getSession()).isConnected();
        }
        if (otherLevel != null) {
            try {
                otherLevel.close();
            } catch (Throwable ignore) {
            }
            otherLevel = null;
        }
        if (otherLevelDir != null) {
            FileUtils.deleteQuietly(otherLevelDir);
            otherLevelDir = null;
        }
    }

    /**
     * The delivery half, isolated: a chunk the manager is no longer tracking must not be recorded
     * as received just because a request made before it went out of view finally came back.
     */
    @Test
    void aChunkTheManagerStoppedTrackingIsNotRecordedAsReceived() {
        player.setLevel(homeLevel);
        player.setPosition(new Vector3(0.5, 80, 0.5));

        // Far outside any view distance, so the manager has certainly never sent it.
        int chunkX = player.getChunkX() + 64;
        int chunkZ = player.getChunkZ() + 64;
        long hash = Level.chunkHash(chunkX, chunkZ);
        Assertions.assertFalse(player.getPlayerChunkManager().isSentChunk(hash));

        player.sendChunk(chunkX, chunkZ, new LevelChunkPacket());

        Assertions.assertFalse(player.getUsedChunks().contains(hash),
                "a chunk the player is not registered as a loader for must not be marked as received");
    }

    /**
     * The whole path: a teleport into another level prepares the destination's chunks and forces
     * the client to purge what it was rendering. Neither step may leave the player holding chunks
     * they no longer load.
     */
    @Test
    void aCrossLevelTeleportLeavesEveryReceivedChunkRegistered() throws InterruptedException {
        // The destination has to be in memory: a teleport onto unloaded terrain hands the client
        // nothing at all, and it is what the teleport manages to send in one go that the client
        // render refresh can take back.
        generateAround(otherLevel, player.getViewDistance() + 1);

        player.setLevel(homeLevel);
        player.setPosition(new Vector3(0.5, 80, 0.5));

        Assertions.assertTrue(player.teleport(
                new Location(0.5, 80, 0.5, otherLevel),
                PlayerTeleportEvent.TeleportCause.PLUGIN));
        Assertions.assertSame(otherLevel, player.getLevel());
        Assertions.assertFalse(player.getUsedChunks().isEmpty(),
                "the teleport should have sent the player chunks of the destination");
        assertEveryReceivedChunkIsRegistered();

        // Hand over what the teleport queued. A chunk the player stopped loading between the
        // request and the delivery comes back here, and this is where it would be recorded as
        // received again behind the manager's back.
        final GameLoop loop = GameLoop.builder().build();
        for (int i = 0; i < 20; i++) {
            otherLevel.subTick(loop);
            assertEveryReceivedChunkIsRegistered();
            player.getPlayerChunkManager().tick();
            assertEveryReceivedChunkIsRegistered();
        }
    }

    private static void assertEveryReceivedChunkIsRegistered() {
        final Level level = player.getLevel();
        final LongOpenHashSet received;
        synchronized (player.getPlayerChunkManager()) {
            received = new LongOpenHashSet(player.getPlayerChunkManager().getUsedChunks());
        }
        for (long hash : received) {
            int chunkX = Level.getHashX(hash);
            int chunkZ = Level.getHashZ(hash);
            boolean registered = false;
            for (ChunkLoader loader : level.getChunkLoaders(chunkX, chunkZ)) {
                if (loader == player) {
                    registered = true;
                    break;
                }
            }
            Assertions.assertTrue(registered, "chunk " + chunkX + ", " + chunkZ
                    + " is marked as received but the player is not registered as a loader for it");
        }
    }

    /** Generate and keep in memory every chunk in the square of the given radius around 0, 0. */
    private static void generateAround(Level level, int radius) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 60_000;
        for (int chunkX = -radius; chunkX <= radius; chunkX++) {
            for (int chunkZ = -radius; chunkZ <= radius; chunkZ++) {
                IChunk chunk = level.getChunk(chunkX, chunkZ, true);
                while (!chunk.getChunkState().canSend() || !chunk.isInitiated()) {
                    Assertions.assertTrue(System.currentTimeMillis() < deadline,
                            "timed out generating chunk " + chunkX + ", " + chunkZ);
                    level.generateChunk(chunkX, chunkZ, true);
                    Thread.sleep(5);
                    chunk = level.getChunk(chunkX, chunkZ, true);
                }
            }
        }
    }

    private static Level openSecondLevel() throws Exception {
        // LevelDB takes a process file lock and Gradle runs test workers in parallel, so the
        // directory has to be unique per fork - same reason ServerMockFixture does it.
        String name = "chunk_sync_" + ProcessHandle.current().pid() + "_" + System.nanoTime();
        otherLevelDir = new File("src/test/resources/" + name);
        FileUtils.copyDirectory(new File("src/test/resources/level"), otherLevelDir);

        Level level = new Level(ServerMockFixture.server, name, otherLevelDir.getPath(), 1,
                LevelDBProvider.class,
                new LevelConfig.GeneratorConfig("flat", 114514L, false, LevelConfig.AntiXrayMode.LOW,
                        true, DimensionEnum.OVERWORLD.getDimensionData(), new HashMap<>()));
        level.initLevel();
        return level;
    }
}
