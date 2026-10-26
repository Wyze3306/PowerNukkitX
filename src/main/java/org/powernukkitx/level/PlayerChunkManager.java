package org.powernukkitx.level;

import org.powernukkitx.Player;
import org.powernukkitx.Server;
import org.powernukkitx.entity.Entity;
import org.powernukkitx.event.player.PlayerChunkRequestEvent;
import org.powernukkitx.event.player.PlayerPreChunkRequestEvent;
import org.powernukkitx.level.format.IChunk;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayPriorityQueue;
import it.unimi.dsi.fastutil.longs.LongComparator;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import lombok.extern.slf4j.Slf4j;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.packet.NetworkChunkPublisherUpdatePacket;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongPredicate;

@Slf4j
public final class PlayerChunkManager {


    /**
     * Chunks closer than this distance to the player are always considered to be in the field of view.
     */
    private static final double MIN_FOV_CHECK_DISTANCE = 4.0;

    /**
     * Timeout for asynchronously loading a chunk before retrying or generating it, in microseconds.
     */
    private static final long CHUNK_LOAD_TIMEOUT_MICROS = 10L;

    private static final double MIN_FOV_CHECK_DISTANCE_SQUARED = MIN_FOV_CHECK_DISTANCE * MIN_FOV_CHECK_DISTANCE;

    private int comparatorLoaderChunkX;
    private int comparatorLoaderChunkZ;
    private double comparatorDirX;
    private double comparatorDirZ;
    private int comparatorFieldOfView = -1;
    private double comparatorCosFov;

    private final LongComparator chunkDistanceAndFovComparator = new LongComparator() {
        @Override
        public int compare(long chunkHash1, long chunkHash2) {
            int chunkX1 = Level.getHashX(chunkHash1);
            int chunkZ1 = Level.getHashZ(chunkHash1);
            int chunkX2 = Level.getHashX(chunkHash2);
            int chunkZ2 = Level.getHashZ(chunkHash2);

            int dx1 = chunkX1 - comparatorLoaderChunkX;
            int dz1 = chunkZ1 - comparatorLoaderChunkZ;
            int dx2 = chunkX2 - comparatorLoaderChunkX;
            int dz2 = chunkZ2 - comparatorLoaderChunkZ;

            long squaredDist1 = (long) dx1 * dx1 + (long) dz1 * dz1;
            long squaredDist2 = (long) dx2 * dx2 + (long) dz2 * dz2;

            boolean inFov1 = isInPlayerFov(dx1, dz1, squaredDist1);
            boolean inFov2 = isInPlayerFov(dx2, dz2, squaredDist2);

            if (inFov1 && !inFov2) return -1;
            if (!inFov1 && inFov2) return 1;

            return Long.compare(squaredDist1, squaredDist2);
        }

        private boolean isInPlayerFov(int dx, int dz, long squaredDistance) {
            if (squaredDistance < MIN_FOV_CHECK_DISTANCE_SQUARED) return true;

            double len = Math.sqrt(squaredDistance);

            double toChunkX = dx / len;
            double toChunkZ = dz / len;

            double dot = comparatorDirX * toChunkX + comparatorDirZ * toChunkZ;

            return dot >= comparatorCosFov;
        }
    };

    private final Player player;
    //holds all chunk hash values already sent in the previous tick
    private final @NotNull LongOpenHashSet sentChunks;
    //holds all chunk hash values to be sent this tick
    private final @NotNull LongOpenHashSet inRadiusChunks;
    private final int trySendChunkCountPerTick;
    private final ChunkQueue chunkSendQueue;
    private final Long2ObjectOpenHashMap<CompletableFuture<IChunk>> chunkLoadingQueue;
    private final ChunkQueue chunkReadyToSend;
    private final LongOpenHashSet requeueScratch;
    /**
     * Chunks the level replaced while this player had them, to drop from {@link #sentChunks} so they
     * are sent again. Filled by {@link #invalidateSentChunk(long)} from chunk-loading threads that
     * can hold a chunk's lock, and only drained under this manager's own monitor.
     */
    private final ConcurrentLinkedQueue<Long> invalidatedChunks = new ConcurrentLinkedQueue<>();
    private long lastLoaderChunkPosHashed = Long.MAX_VALUE;

    public PlayerChunkManager(Player player) {
        this.player = player;
        this.sentChunks = new SentChunkSet();
        this.inRadiusChunks = new LongOpenHashSet();
        this.chunkSendQueue = new ChunkQueue(player.getViewDistance() * player.getViewDistance(), chunkDistanceAndFovComparator);
        this.chunkLoadingQueue = new Long2ObjectOpenHashMap<>(player.getViewDistance() * player.getViewDistance());
        this.trySendChunkCountPerTick = player.getChunkSendCountPerTick();
        this.chunkReadyToSend = new ChunkQueue(player.getViewDistance() * player.getViewDistance(), chunkDistanceAndFovComparator);
        this.requeueScratch = new LongOpenHashSet();
    }

    /**
     * Handle chunk loading when the player teleported
     */
    public synchronized void handleTeleport() {
        drainInvalidatedChunks();
        if (!player.isConnected()) return;
        refreshComparatorContext();
        int loaderChunkX = player.getChunkX();
        int loaderChunkZ = player.getChunkZ();
        updateInRadiusChunks(1, loaderChunkX, loaderChunkZ);
        removeOutOfRadiusChunks();
        updateInRadiusChunks(8, loaderChunkX, loaderChunkZ);
        pruneLoadingQueueOutOfRadius();
        pruneQueueOutOfRadius(chunkReadyToSend, true);
        updateChunkSendingQueue();
        loadQueuedChunks(8, true);
        sendChunk();
    }

    public synchronized void tick() {
        drainInvalidatedChunks();
        if (!player.isConnected()) return;
        refreshComparatorContext();
        long currentLoaderChunkPosHashed;
        int loaderChunkX = player.getChunkX();
        int loaderChunkZ = player.getChunkZ();
        if ((currentLoaderChunkPosHashed = Level.chunkHash(loaderChunkX, loaderChunkZ)) != lastLoaderChunkPosHashed) {
            lastLoaderChunkPosHashed = currentLoaderChunkPosHashed;
            updateInRadiusChunks(player.getViewDistance(), loaderChunkX, loaderChunkZ);
            removeOutOfRadiusChunks();
            updateChunkSendingQueue();
        }
        loadQueuedChunks(trySendChunkCountPerTick, false);
        sendChunk();
    }

    public synchronized void handleViewDistanceChange() {
        drainInvalidatedChunks();
        if (!player.isConnected()) return;
        refreshComparatorContext();
        updateInRadiusChunks(player.getViewDistance(), player.getChunkX(), player.getChunkZ());
        removeOutOfRadiusChunks();
        pruneQueueOutOfRadius(chunkSendQueue, false);
        pruneQueueOutOfRadius(chunkReadyToSend, true);
        pruneLoadingQueueOutOfRadius();
        updateChunkSendingQueue();
    }

    @ApiStatus.Internal
    public LongOpenHashSet getUsedChunks() {
        return sentChunks;
    }

    /**
     * Marks a chunk as needing to be sent again, without taking this manager's monitor.
     * <p>
     * The level calls this when it replaces a chunk, from whatever thread loaded it, possibly while
     * holding that chunk's lock. {@link #handleTeleport()} takes the monitor first and the chunk's
     * lock second (through {@code Level.getChunkAsync} and {@code Chunk.initChunk}), so taking them
     * the other way round here froze the server for a night (2026-10-02). The removal is applied at
     * the next tick instead.
     */
    @ApiStatus.Internal
    public void invalidateSentChunk(long hash) {
        if (player.isConnected()) {
            invalidatedChunks.add(hash);
        }
    }

    private void drainInvalidatedChunks() {
        Long hash;
        while ((hash = invalidatedChunks.poll()) != null) {
            sentChunks.remove(hash.longValue());
        }
    }

    @ApiStatus.Internal
    public LongOpenHashSet getInRadiusChunks() {
        return inRadiusChunks;
    }

    @ApiStatus.Internal
    public synchronized void addSendChunk(int x, int z) {
        refreshComparatorContext();
        chunkSendQueue.enqueue(Level.chunkHash(x, z));
    }

    /**
     * Whether the client has this chunk. Lock-free with respect to this manager: the set guards
     * itself ({@link SentChunkSet}). Taking this manager's monitor made every caller wait for the
     * player's level thread to finish loading and sending chunks ({@link #tick()} holds it all
     * along), and the callers are often on the main thread ({@code Level.getChunkPlayers} for each
     * block change or entity spawned from a plugin task): 200 to 400 ms freezes of the whole server
     * on Linesia prod, 2026-10-25.
     */
    @ApiStatus.Internal
    public boolean isSentChunk(long hash) {
        return sentChunks.contains(hash);
    }

    private void updateChunkSendingQueue() {
        chunkSendQueue.clear();
        // Blocks that have already been sent will not be sent again
        LongIterator iter = inRadiusChunks.longIterator();
        while (iter.hasNext()) {
            long v = iter.nextLong();
            if (!sentChunks.contains(v)) {
                chunkSendQueue.enqueue(v);
            }
        }
    }

    private void updateInRadiusChunks(int viewDistance, int loaderChunkX, int loaderChunkZ) {
        inRadiusChunks.clear();
        for (int rx = -viewDistance; rx <= viewDistance; rx++) {
            for (int rz = -viewDistance; rz <= viewDistance; rz++) {
                if (ifChunkNotInRadius(rx, rz, viewDistance)) continue;
                var chunkX = loaderChunkX + rx;
                var chunkZ = loaderChunkZ + rz;
                var hashXZ = Level.chunkHash(chunkX, chunkZ);
                inRadiusChunks.add(hashXZ);
            }
        }
    }

    private void removeOutOfRadiusChunks() {
        LongArrayList toRemove = null;
        LongIterator iter = sentChunks.longIterator();
        while (iter.hasNext()) {
            long hash = iter.nextLong();
            if (!inRadiusChunks.contains(hash)) {
                if (toRemove == null) {
                    toRemove = new LongArrayList();
                }
                toRemove.add(hash);
            }
        }
        if (toRemove == null) {
            return;
        }
        // Unload blocks that are out of range
        for (int i = 0; i < toRemove.size(); i++) {
            long hash = toRemove.getLong(i);
            unloadChunkForPlayer(hash);
            // The intersection of the remaining sentChunks and inRadiusChunks
            sentChunks.remove(hash);
        }
    }

    private void loadQueuedChunks(int trySendChunkCountPerTick, boolean force) {
        if (chunkSendQueue.isEmpty()) return;
        int triedSendChunkCount = 0;
        LongOpenHashSet enqueue = requeueScratch;
        enqueue.clear();
        do {
            triedSendChunkCount++;
            long chunkHash = chunkSendQueue.dequeueLong();
            int chunkX = Level.getHashX(chunkHash);
            int chunkZ = Level.getHashZ(chunkHash);
            if (!PlayerPreChunkRequestEvent.getHandlers().isEmpty()) {
                PlayerPreChunkRequestEvent event = new PlayerPreChunkRequestEvent(player, chunkX, chunkZ, force);
                Server.getInstance().getPluginManager().callEvent(event);
                if (event.isCancelled()) {
                    continue;
                }
            }
            var chunkTask = chunkLoadingQueue.computeIfAbsent(chunkHash, (hash) -> player.getLevel().getChunkAsync(chunkX, chunkZ));
            if (chunkTask.isDone()) {
                try {
                    IChunk chunk = chunkTask.get(CHUNK_LOAD_TIMEOUT_MICROS, TimeUnit.MICROSECONDS);
                    // Cached future may be stale - re-check in-memory map
                    if (chunk == null || !chunk.getChunkState().canSend()) {
                        chunk = player.level.getChunkIfLoaded(chunkX, chunkZ);
                    }
                    if (chunk == null || !chunk.getChunkState().canSend()) {
                        player.level.generateChunk(chunkX, chunkZ, force);
                        enqueue.add(chunkHash);
                        chunkLoadingQueue.remove(chunkHash);
                        continue;
                    }
                    chunkLoadingQueue.remove(chunkHash);
                    player.level.registerChunkLoader(player, chunkX, chunkZ, false);
                    chunkReadyToSend.enqueue(chunkHash);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    chunkLoadingQueue.remove(chunkHash);
                    enqueue.add(chunkHash);
                    log.warn("Chunk loading interrupted for chunk ({}, {})", chunkX, chunkZ, e);
                } catch (ExecutionException e) {
                    chunkLoadingQueue.remove(chunkHash);
                    enqueue.add(chunkHash);
                    log.warn("Chunk loading execution failed for chunk ({}, {})", chunkX, chunkZ, e);
                } catch (TimeoutException e) {
                    chunkLoadingQueue.remove(chunkHash);
                    enqueue.add(chunkHash);
                    log.warn("Timeout while loading chunk ({} {})", chunkX, chunkZ);
                }
            } else {
                enqueue.add(chunkHash);
            }
        } while (!chunkSendQueue.isEmpty() && triedSendChunkCount < trySendChunkCountPerTick);
        enqueue.forEach(chunkSendQueue::enqueue);
    }

    private void sendChunk() {
        if (!chunkReadyToSend.isEmpty()) {
            final NetworkChunkPublisherUpdatePacket packet = new NetworkChunkPublisherUpdatePacket();
            packet.setNewPositionForView(Vector3i.from(player.getFloorX(), player.getFloorY(), player.getFloorZ()));
            packet.setNewRadiusForView(player.getViewDistance() << 4);
            player.sendPacket(packet);
            while (!chunkReadyToSend.isEmpty()) {
                long chunkHash = chunkReadyToSend.dequeueLong();
                if (!inRadiusChunks.contains(chunkHash)) {
                    sentChunks.remove(chunkHash);
                    unloadChunkForPlayer(chunkHash);
                    continue;
                }
                int chunkX = Level.getHashX(chunkHash);
                int chunkZ = Level.getHashZ(chunkHash);
                if (!PlayerChunkRequestEvent.getHandlers().isEmpty()) {
                    PlayerChunkRequestEvent ev = new PlayerChunkRequestEvent(player, chunkX, chunkZ);
                    player.getServer().getPluginManager().callEvent(ev);
                }
                player.level.requestChunk(chunkX, chunkZ, player);
                sentChunks.add(chunkHash);
            }
        }
        chunkReadyToSend.clear();
    }

    /**
     * Drops the chunks that left the view radius, in one pass. Emptying the queue with
     * {@code dequeueLong()} and refilling it cost O(n²): every dequeue of a
     * {@link LongArrayPriorityQueue} scans the whole array for the smallest element, and the queue
     * holds up to (2r+1)² chunks. With a large view distance (a cinematic ending, a long teleport)
     * that froze the thread holding this manager for 200 to 450 ms (Linesia prod, 2026-10-25).
     */
    private void pruneQueueOutOfRadius(ChunkQueue queue, boolean unloadChunkLoader) {
        queue.retain(chunkHash -> {
            if (inRadiusChunks.contains(chunkHash)) return true;
            if (unloadChunkLoader) {
                sentChunks.remove(chunkHash);
                unloadChunkForPlayer(chunkHash);
            }
            return false;
        });
    }

    /**
     * The chunks sent to the client. Written only under this manager's monitor (here and in
     * {@code Player}), read from any thread: each single operation takes the set's own monitor, held
     * for microseconds, so a lookup never sees a half-done insertion or rehash. Iterating still needs
     * the manager's monitor, which keeps writers out for the whole loop.
     */
    private static final class SentChunkSet extends LongOpenHashSet {
        @Override
        public synchronized boolean add(long k) {
            return super.add(k);
        }

        @Override
        public synchronized boolean remove(long k) {
            return super.remove(k);
        }

        @Override
        public synchronized boolean contains(long k) {
            return super.contains(k);
        }

        @Override
        public synchronized void clear() {
            super.clear();
        }

        @Override
        public LongIterator iterator() {
            LongIterator inner = super.iterator();
            return new LongIterator() {
                @Override
                public boolean hasNext() {
                    return inner.hasNext();
                }

                @Override
                public long nextLong() {
                    return inner.nextLong();
                }

                @Override
                public void remove() {
                    synchronized (SentChunkSet.this) {
                        inner.remove();
                    }
                }
            };
        }
    }

    /**
     * {@link LongArrayPriorityQueue} that can be filtered in place. The order stays the comparator's:
     * this queue finds its smallest element again at each dequeue, so dropping entries from its
     * array cannot break it.
     */
    private static final class ChunkQueue extends LongArrayPriorityQueue {
        ChunkQueue(int capacity, LongComparator comparator) {
            super(capacity, comparator);
        }

        void retain(LongPredicate keep) {
            int kept = 0;
            for (int i = 0; i < size; i++) {
                long value = array[i];
                if (keep.test(value)) array[kept++] = value;
            }
            size = kept;
            firstIndexValid = false;
        }
    }

    private void pruneLoadingQueueOutOfRadius() {
        LongIterator iterator = chunkLoadingQueue.keySet().iterator();
        while (iterator.hasNext()) {
            long chunkHash = iterator.nextLong();
            if (!inRadiusChunks.contains(chunkHash)) {
                iterator.remove();
            }
        }
    }

    private void refreshComparatorContext() {
        this.comparatorLoaderChunkX = player.getChunkX();
        this.comparatorLoaderChunkZ = player.getChunkZ();

        double yawRadians = Math.toRadians(player.getYaw());
        this.comparatorDirX = -Math.sin(yawRadians);
        this.comparatorDirZ = Math.cos(yawRadians);

        int fieldOfView = player.getServer().getSettings().levelSettings().fieldOfView();
        if (fieldOfView != this.comparatorFieldOfView) {
            this.comparatorFieldOfView = fieldOfView;
            this.comparatorCosFov = Math.cos(Math.toRadians(fieldOfView));
        }
    }

    private void unloadChunkForPlayer(long hash) {
        int x = Level.getHashX(hash);
        int z = Level.getHashZ(hash);
        if (player.level.unregisterChunkLoader(player, x, z)) {
            for (Entity entity : player.level.getChunkEntities(x, z).values()) {
                if (entity != player) {
                    entity.despawnFrom(player);
                }
            }
        }
    }

    private boolean ifChunkNotInRadius(int chunkX, int chunkZ, int radius) {
        return chunkX * chunkX + chunkZ * chunkZ > radius * radius;
    }
}
