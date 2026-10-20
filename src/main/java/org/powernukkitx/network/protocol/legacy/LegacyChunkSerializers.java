package org.powernukkitx.network.protocol.legacy;

import io.netty.buffer.ByteBuf;
import lombok.extern.slf4j.Slf4j;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v2168.serializer.LevelChunkSerializer_v2168;
import org.cloudburstmc.protocol.bedrock.codec.v2192.serializer.SubChunkSerializer_v2192;
import org.cloudburstmc.protocol.bedrock.data.payload.chunk.SubChunkPacketData;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.SubChunkPacket;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.powernukkitx.level.DimensionData;
import org.powernukkitx.level.DimensionEnum;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The 1.26.50 serializers for chunk packets, which translate the chunk data with
 * {@link LegacyChunkTranslator} while writing it. A packet whose data needs no translation is
 * written by the 1.26.50 serializer as it is; otherwise a copy carrying the translated data is,
 * so the packet sent, which may be shared, is never changed.
 * <p>
 * Data sent through the client blob cache is not translated: the cache keys blobs by the hash of
 * their native bytes, so 1.26.50 sessions do not use it, see
 * {@link org.powernukkitx.network.process.handler.ClientCacheStatusHandler}.
 */
@Slf4j
@ApiStatus.Internal
final class LegacyChunkSerializers {
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private LegacyChunkSerializers() {
    }

    static final class LevelChunk extends LevelChunkSerializer_v2168 {
        static final LevelChunk INSTANCE = new LevelChunk();

        @Override
        public void serialize(ByteBuf buffer, BedrockCodecHelper helper, LevelChunkPacket packet) {
            final ByteBuf data = packet.getSerializedChunkData();
            final ByteBuf translated = data == null || packet.isCacheEnabled() ? null : translate(packet, data);
            if (translated == null) {
                super.serialize(buffer, helper, packet);
                return;
            }
            try {
                final LevelChunkPacket copy = new LevelChunkPacket();
                copy.setChunkX(packet.getChunkX());
                copy.setChunkZ(packet.getChunkZ());
                copy.setDimension(packet.getDimension());
                copy.setSubChunksCount(packet.getSubChunksCount());
                copy.setClientNeedsToRequestSubChunks(packet.isClientNeedsToRequestSubChunks());
                copy.setClientRequestSubChunkLimit(packet.getClientRequestSubChunkLimit());
                copy.setCacheEnabled(packet.isCacheEnabled());
                copy.getCacheBlobs().addAll(packet.getCacheBlobs());
                copy.setClientBiomeUpdate(packet.isClientBiomeUpdate());
                copy.setSerializedChunkData(translated);
                super.serialize(buffer, helper, copy);
            } finally {
                translated.release();
            }
        }

        private static @Nullable ByteBuf translate(LevelChunkPacket packet, ByteBuf data) {
            final boolean requested = packet.isClientNeedsToRequestSubChunks();
            final DimensionData dimension = packet.getDimension() == null ? null
                : DimensionEnum.getDataFromId(packet.getDimension().getValue());
            // The server writes one biome palette per sub-chunk it sends, or one per sub-chunk of the
            // dimension when the client requests the sub-chunks itself
            final int biomes = requested ? (dimension == null ? 0 : dimension.getChunkSectionCount()) : packet.getSubChunksCount();
            try {
                return LegacyChunkTranslator.translateLevelChunk(data, requested ? 0 : packet.getSubChunksCount(), biomes,
                    LegacyTables.get());
            } catch (IllegalArgumentException e) {
                warnOnce(e);
                return null;
            }
        }
    }

    static final class SubChunk extends SubChunkSerializer_v2192 {
        static final SubChunk INSTANCE = new SubChunk();

        @Override
        protected void writeSubChunkPacketData(ByteBuf buffer, BedrockCodecHelper helper, SubChunkPacketData data, SubChunkPacket packet) {
            final ByteBuf serialized = data.getSerializedSubChunk();
            ByteBuf translated = null;
            if (serialized != null && data.getBlobId() == null) {
                try {
                    translated = LegacyChunkTranslator.translateSubChunk(serialized, LegacyTables.get());
                } catch (IllegalArgumentException e) {
                    warnOnce(e);
                }
            }
            if (translated == null) {
                super.writeSubChunkPacketData(buffer, helper, data, packet);
                return;
            }
            try {
                // Not released: it would release the height map it shares with the original
                final SubChunkPacketData copy = new SubChunkPacketData();
                copy.setSubChunkPosOffset(data.getSubChunkPosOffset());
                copy.setSubChunkRequestResult(data.getSubChunkRequestResult());
                copy.setSerializedSubChunk(translated);
                copy.setHeightMapData(data.getHeightMapData());
                copy.setBlobId(data.getBlobId());
                super.writeSubChunkPacketData(buffer, helper, copy, packet);
            } finally {
                translated.release();
            }
        }
    }

    private static void warnOnce(IllegalArgumentException e) {
        if (WARNED.compareAndSet(false, true)) {
            log.warn("Sending chunk data to a 1.26.50 client untranslated, some blocks may show wrong: {}", e.getMessage());
        } else {
            log.debug("Sending chunk data to a 1.26.50 client untranslated", e);
        }
    }
}
