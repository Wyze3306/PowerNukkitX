package org.powernukkitx.network.protocol.legacy;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v2192.Bedrock_v2192;
import org.cloudburstmc.protocol.bedrock.packet.ActorEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddActorPacket;
import org.cloudburstmc.protocol.bedrock.packet.AvailableActorIdentifiersPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BiomeDefinitionListPacket;
import org.cloudburstmc.protocol.bedrock.packet.CompletedUsingItemPacket;
import org.cloudburstmc.protocol.bedrock.packet.CraftingDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.CreativeContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemRegistryPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelEventGenericPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelSoundEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.SubChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockSyncedPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateSubChunkBlocksPacket;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The codec of Minecraft 1.26.50 (protocol 2193) sessions.
 * <p>
 * The wire format is that of {@link Bedrock_v2192}, which the protocol library already provides.
 * What this adds is the content: the server runs 1.26.60 content, so block states, item ids, entity
 * identifiers, biomes, sounds and particles that 1.26.50 does not know, or knows under other ids,
 * are translated while packets are encoded, see {@link LegacyTables}. Translating at encode time
 * covers every way a packet is sent, broadcasts and plugin packets included, and leaves the
 * packets themselves untouched, which matters since one instance may go to sessions of both
 * versions.
 * <p>
 * Items and entity data are translated by the codec helper, {@link LegacyCodecHelper_v2193}; the
 * other ids by the serializers of the packets that carry them, see {@link LegacyRewriters}, and
 * chunk data by {@link LegacyChunkSerializers}. The ids a 1.26.50 client sends are resolved by the
 * definition registries its session is given, and by the inbound rewrites of the few packets that
 * carry raw ids.
 * <p>
 * Extends {@link Bedrock_v2192} only to reach its protected type maps.
 */
@ApiStatus.Internal
public final class LegacyCodec_v2193 extends Bedrock_v2192 {
    public static final int PROTOCOL_VERSION = 2193;

    public static final BedrockCodec CODEC = Bedrock_v2192.CODEC.toBuilder()
        .protocolVersion(PROTOCOL_VERSION)
        .minecraftVersion("1.26.50")
        .helper(() -> new LegacyCodecHelper_v2193(ACTOR_DATA, GAME_RULE_TYPES, ITEM_STACK_REQUEST_TYPES, CONTAINER_SLOT_TYPES,
            PLAYER_ABILITIES, TEXT_PROCESSING_ORIGINS, SOUND_EVENTS, PARTICLE_TYPES))
        .updateSerializer(LevelChunkPacket.class, LegacyChunkSerializers.LevelChunk.INSTANCE)
        .updateSerializer(SubChunkPacket.class, LegacyChunkSerializers.SubChunk.INSTANCE)
        .updateSerializer(UpdateBlockPacket.class, rewrite(UpdateBlockPacket.class, LegacyRewriters::updateBlock, null))
        .updateSerializer(UpdateBlockSyncedPacket.class, rewrite(UpdateBlockSyncedPacket.class, LegacyRewriters::updateBlock, null))
        .updateSerializer(UpdateSubChunkBlocksPacket.class,
            rewrite(UpdateSubChunkBlocksPacket.class, LegacyRewriters::updateSubChunkBlocks, null))
        .updateSerializer(LevelEventPacket.class,
            rewrite(LevelEventPacket.class, packet -> LegacyRewriters.levelEvent(packet, LEVEL_EVENTS), null))
        .updateSerializer(LevelEventGenericPacket.class,
            rewrite(LevelEventGenericPacket.class, packet -> LegacyRewriters.levelEventGeneric(packet, LEVEL_EVENTS), null))
        .updateSerializer(LevelSoundEventPacket.class, rewrite(LevelSoundEventPacket.class,
            packet -> LegacyRewriters.levelSoundEvent(packet, SOUND_EVENTS), LegacyRewriters::upgradeLevelSoundEvent))
        .updateSerializer(ActorEventPacket.class,
            rewrite(ActorEventPacket.class, LegacyRewriters::actorEvent, LegacyRewriters::upgradeActorEvent))
        .updateSerializer(CompletedUsingItemPacket.class,
            rewrite(CompletedUsingItemPacket.class, LegacyRewriters::completedUsingItem, null))
        .updateSerializer(AddActorPacket.class, rewrite(AddActorPacket.class, LegacyRewriters::addActor, null))
        .updateSerializer(CraftingDataPacket.class, rewrite(CraftingDataPacket.class, LegacyRewriters::craftingData, null))
        .updateSerializer(CreativeContentPacket.class, rewrite(CreativeContentPacket.class, LegacyRewriters::creativeContent, null))
        .updateSerializer(ItemRegistryPacket.class, rewrite(ItemRegistryPacket.class, LegacyRewriters::itemRegistry, null))
        .updateSerializer(BiomeDefinitionListPacket.class,
            rewrite(BiomeDefinitionListPacket.class, LegacyRewriters::biomeDefinitionList, null))
        .updateSerializer(AvailableActorIdentifiersPacket.class,
            rewrite(AvailableActorIdentifiersPacket.class, LegacyRewriters::availableActorIdentifiers, null))
        .build();

    private LegacyCodec_v2193() {
    }

    private static <T extends BedrockPacket> RewritingSerializer<T> rewrite(Class<T> type, UnaryOperator<T> outbound,
                                                                           @Nullable Consumer<T> inbound) {
        return RewritingSerializer.wrap(Bedrock_v2192.CODEC, type, outbound, inbound);
    }
}
