package org.powernukkitx.network.security;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import org.cloudburstmc.protocol.bedrock.packet.ActorPickRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BlockActorDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.BlockPickRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.BookEditPacket;
import org.cloudburstmc.protocol.bedrock.packet.CommandBlockUpdatePacket;
import org.cloudburstmc.protocol.bedrock.packet.NpcRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerSkinPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerToggleCrafterSlotRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.PositionTrackingDBClientRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestAbilityPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestChunkRadiusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestPermissionsPacket;
import org.cloudburstmc.protocol.bedrock.packet.RespawnPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerSettingsRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetDifficultyPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetPlayerGameTypePacket;
import org.cloudburstmc.protocol.bedrock.packet.ShowCreditsPacket;
import org.cloudburstmc.protocol.bedrock.packet.StructureBlockUpdatePacket;
import org.cloudburstmc.protocol.bedrock.packet.StructureTemplateDataRequestPacket;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Per-session token buckets for the inbound packets that cost the server the most to handle, so a
 * client cannot spend the whole session-wide packet budget on one of them. A packet beyond its
 * bucket is dropped.
 * <p>
 * Each bucket refills at its rate and holds at most its burst. The limits are well above what an
 * unmodified client sends. {@code PlayerAuthInput} has no bucket: after a stall the client sends
 * every tick it held back at once, and each of them carries movement the server has to replay.
 * Chat, commands, forms, movement and the world interactions are limited per
 * player by {@link PacketRateLimiter}, and sub-chunk requests by their own budget.
 * <p>
 * Allocation free per packet. Meant for the session's event loop; synchronised all the same.
 */
@ApiStatus.Internal
public final class PacketTypeRateLimiter {
    private static final double NANOS_PER_SECOND = 1_000_000_000d;
    private static final long REPORT_INTERVAL_NANOS = 5_000_000_000L;

    private static final Reference2IntOpenHashMap<Class<? extends BedrockPacket>> BUCKETS = new Reference2IntOpenHashMap<>();
    private static final double[] TOKENS_PER_NANO;
    private static final double[] BURSTS;

    static {
        BUCKETS.defaultReturnValue(-1);
        final List<double[]> limits = new ArrayList<>();
        // PlayerAuthInputPacket is left to the session limits: a client that comes back from a stall
        // sends all the ticks it held back at once, and dropping any of them loses its movement
        // Attacks, item uses, hotbar and inventory packets are left to the session limits: they are
        // what combat is made of, and dropping one is a hit that does nothing
        limit(limits, PlayerToggleCrafterSlotRequestPacket.class, 20, 40);
        // Writes into the world or into items, or answered with a lookup
        limit(limits, BookEditPacket.class, 20, 40);
        limit(limits, BlockActorDataPacket.class, 10, 20);
        limit(limits, CommandBlockUpdatePacket.class, 10, 20);
        limit(limits, StructureBlockUpdatePacket.class, 10, 20);
        limit(limits, StructureTemplateDataRequestPacket.class, 5, 10);
        limit(limits, BlockPickRequestPacket.class, 10, 20);
        limit(limits, ActorPickRequestPacket.class, 10, 20);
        limit(limits, PositionTrackingDBClientRequestPacket.class, 10, 20);
        limit(limits, NpcRequestPacket.class, 10, 20);
        // Sent when a setting changes, a handful of times per session
        limit(limits, PlayerSkinPacket.class, 2, 4);
        limit(limits, RequestChunkRadiusPacket.class, 5, 10);
        limit(limits, RespawnPacket.class, 5, 10);
        limit(limits, ServerSettingsRequestPacket.class, 5, 10);
        limit(limits, SetDifficultyPacket.class, 5, 10);
        limit(limits, SetPlayerGameTypePacket.class, 5, 10);
        limit(limits, RequestAbilityPacket.class, 10, 20);
        limit(limits, RequestPermissionsPacket.class, 5, 10);
        limit(limits, ShowCreditsPacket.class, 2, 4);

        TOKENS_PER_NANO = new double[limits.size()];
        BURSTS = new double[limits.size()];
        for (int i = 0; i < limits.size(); i++) {
            TOKENS_PER_NANO[i] = limits.get(i)[0] / NANOS_PER_SECOND;
            BURSTS[i] = limits.get(i)[1];
        }
    }

    private static void limit(List<double[]> limits, Class<? extends BedrockPacket> type, int perSecond, int burst) {
        BUCKETS.put(type, limits.size());
        limits.add(new double[]{perSecond, burst});
    }

    private final double[] tokens;
    private final long[] refilledAt;
    private long lastReport;

    public PacketTypeRateLimiter() {
        this(System.nanoTime());
    }

    PacketTypeRateLimiter(long now) {
        this.tokens = BURSTS.clone();
        this.refilledAt = new long[BURSTS.length];
        Arrays.fill(this.refilledAt, now);
        this.lastReport = now - REPORT_INTERVAL_NANOS;
    }

    /**
     * Takes a token for the packet.
     *
     * @return {@code false} when the packet's bucket is empty and the packet should be dropped
     */
    public boolean tryAcquire(BedrockPacket packet) {
        return this.tryAcquire(packet.getClass(), System.nanoTime());
    }

    synchronized boolean tryAcquire(Class<? extends BedrockPacket> type, long now) {
        final int bucket = BUCKETS.getInt(type);
        if (bucket < 0) {
            return true;
        }
        final double available = Math.min(BURSTS[bucket],
            this.tokens[bucket] + Math.max(0L, now - this.refilledAt[bucket]) * TOKENS_PER_NANO[bucket]);
        this.refilledAt[bucket] = now;
        if (available < 1d) {
            this.tokens[bucket] = available;
            return false;
        }
        this.tokens[bucket] = available - 1d;
        return true;
    }

    /**
     * @return whether a dropped packet should be logged now, at most once every five seconds
     */
    public synchronized boolean reportDue() {
        final long now = System.nanoTime();
        if (now - this.lastReport < REPORT_INTERVAL_NANOS) {
            return false;
        }
        this.lastReport = now;
        return true;
    }

    /**
     * @return the sustained rate allowed for a packet type, or -1 when it is not limited here
     */
    public static int perSecond(Class<? extends BedrockPacket> type) {
        final int bucket = BUCKETS.getInt(type);
        return bucket < 0 ? -1 : (int) Math.round(TOKENS_PER_NANO[bucket] * NANOS_PER_SECOND);
    }
}
