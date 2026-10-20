package org.powernukkitx.network.protocol.legacy;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.ActorDataTypeMap;
import org.cloudburstmc.protocol.bedrock.codec.v2192.BedrockCodecHelper_v2192;
import org.cloudburstmc.protocol.bedrock.data.AbilitiesIndex;
import org.cloudburstmc.protocol.bedrock.data.ParticleType;
import org.cloudburstmc.protocol.bedrock.data.SoundEvent;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorDataMap;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorDataType;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorDataTypes;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerEnumName;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.TextProcessingEventOrigin;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestActionType;
import org.cloudburstmc.protocol.bedrock.data.payload.skin.PieceType;
import org.cloudburstmc.protocol.bedrock.data.payload.skin.SerializedPersonaPieceHandle;
import org.cloudburstmc.protocol.bedrock.data.payload.skin.SerializedSkin;
import org.cloudburstmc.protocol.bedrock.data.payload.skin.TintMapColor;
import org.cloudburstmc.protocol.common.util.TypeMap;
import org.jetbrains.annotations.ApiStatus;
import org.powernukkitx.utils.RuntimeBlockDefinition;

import java.util.Map;

/**
 * The codec helper of {@link LegacyCodec_v2193}: writes what several packets share the 1.26.50
 * way. Items are translated by identifier, see {@link ItemDowngrade#downgrade(ItemData)}, block
 * states and sounds inside entity data are translated, and skins lose the persona pieces 1.26.50
 * has no type for.
 */
@ApiStatus.Internal
public class LegacyCodecHelper_v2193 extends BedrockCodecHelper_v2192 {
    private final TypeMap<SoundEvent> soundEvents;
    private final TypeMap<ParticleType> particleTypes;

    public LegacyCodecHelper_v2193(ActorDataTypeMap entityData, TypeMap<Class<?>> gameRulesTypes,
                                   TypeMap<ItemStackRequestActionType> stackRequestActionTypes,
                                   TypeMap<ContainerEnumName> containerSlotTypes, TypeMap<AbilitiesIndex> abilities,
                                   TypeMap<TextProcessingEventOrigin> textProcessingEventOrigins,
                                   TypeMap<SoundEvent> soundEvents, TypeMap<ParticleType> particleTypes) {
        super(entityData, gameRulesTypes, stackRequestActionTypes, containerSlotTypes, abilities, textProcessingEventOrigins);
        this.soundEvents = soundEvents;
        this.particleTypes = particleTypes;
    }

    @Override
    public void writeNetItem(ByteBuf buffer, ItemData item) {
        super.writeNetItem(buffer, downgrade(item));
    }

    @Override
    public void writeItem(ByteBuf buffer, ItemData item) {
        super.writeItem(buffer, downgrade(item));
    }

    @Override
    public void writeItemInstance(ByteBuf buffer, ItemData item) {
        super.writeItemInstance(buffer, downgrade(item));
    }

    @Override
    public void writeNetworkItemStackDescriptor(ByteBuf buffer, ItemData item) {
        super.writeNetworkItemStackDescriptor(buffer, downgrade(item));
    }

    @Override
    public void writeNetworkItemInstanceDescriptor(ByteBuf buffer, ItemData item) {
        super.writeNetworkItemInstanceDescriptor(buffer, downgrade(item));
    }

    private static ItemData downgrade(ItemData item) {
        return item == null ? null : LegacyTables.get().items().downgrade(item);
    }

    /**
     * Writes entity data with the block states, sounds and particles 1.26.50 knows. The map is
     * copied when something changes, since the entity keeps using it.
     */
    @Override
    public void writeActorData(ByteBuf buffer, ActorDataMap actorDataMap) {
        super.writeActorData(buffer, this.downgrade(actorDataMap));
    }

    private ActorDataMap downgrade(ActorDataMap map) {
        if (map == null) {
            return null;
        }
        final BlockDowngrade blocks = LegacyTables.get().blocks();
        // Values are read untyped: a typed read casts, and a plugin may have stored another type
        ActorDataMap copy = null;
        copy = replaceBlock(map, copy, ActorDataTypes.BLOCK, blocks);
        copy = replaceBlock(map, copy, ActorDataTypes.DISPLAY_TILE_RUNTIME_ID, blocks);
        copy = replaceBlock(map, copy, ActorDataTypes.CARRY_BLOCK_RUNTIME_ID, blocks);
        // A falling block carries its state as its variant; any other variant is a small number
        // that no changed hash can be equal to
        if (map.get((Object) ActorDataTypes.VARIANT) instanceof Integer variant && blocks.isChanged(variant)) {
            copy = copyOf(map, copy);
            copy.put(ActorDataTypes.VARIANT, blocks.downgrade(variant));
        }
        if (map.get((Object) ActorDataTypes.HEARTBEAT_SOUND_EVENT) instanceof SoundEvent sound && this.soundEvents.getIdUnsafe(sound) == -1) {
            copy = copyOf(map, copy);
            copy.put(ActorDataTypes.HEARTBEAT_SOUND_EVENT, LegacyRewriters.SOUND_FALLBACKS.getOrDefault(sound, SoundEvent.UNDEFINED));
        }
        if (map.get((Object) ActorDataTypes.DATA_PARTICLE) instanceof ParticleType particle && this.particleTypes.getIdUnsafe(particle) == -1) {
            copy = copyOf(map, copy);
            copy.put(ActorDataTypes.DATA_PARTICLE, LegacyRewriters.PARTICLE_FALLBACKS.getOrDefault(particle, ParticleType.UNDEFINED));
        }
        return copy == null ? map : copy;
    }

    private static ActorDataMap replaceBlock(ActorDataMap map, ActorDataMap copy, ActorDataType<BlockDefinition> type,
                                             BlockDowngrade blocks) {
        if (map.get((Object) type) instanceof BlockDefinition block && blocks.isChanged(block.getRuntimeId())) {
            copy = copyOf(map, copy);
            copy.put(type, new RuntimeBlockDefinition(blocks.downgrade(block.getRuntimeId())));
        }
        return copy;
    }

    private static ActorDataMap copyOf(ActorDataMap map, ActorDataMap copy) {
        if (copy != null) {
            return copy;
        }
        copy = new ActorDataMap();
        copy.putAll(map);
        return copy;
    }

    /**
     * Writes a skin without the persona pieces, and their tints, that 1.26.50 has no type for, and
     * with a PlayFab id, which 1.26.50 carries in the skin rather than in the player list entry.
     */
    @Override
    public void writeSerializedSkin(ByteBuf buffer, SerializedSkin skin) {
        super.writeSerializedSkin(buffer, this.downgrade(skin));
    }

    private SerializedSkin downgrade(SerializedSkin skin) {
        boolean unknownPiece = false;
        for (SerializedPersonaPieceHandle piece : skin.getPersonaPieces()) {
            unknownPiece |= !this.isKnown(piece.getPieceType());
        }
        for (PieceType type : skin.getPieceTintColors().keySet()) {
            unknownPiece |= !this.isKnown(type);
        }
        if (!unknownPiece && skin.getPlayFabID() != null) {
            return skin;
        }
        final SerializedSkin copy = new SerializedSkin();
        copy.setID(skin.getID());
        copy.setPlayFabID(skin.getPlayFabID() == null ? "" : skin.getPlayFabID());
        copy.setResourcePatch(skin.getResourcePatch());
        copy.setImageData(skin.getImageData());
        copy.getAnimatedImageData().addAll(skin.getAnimatedImageData());
        copy.setCapeImageData(skin.getCapeImageData());
        copy.setGeometryData(skin.getGeometryData());
        copy.setGeometryDataMinEngineVersion(skin.getGeometryDataMinEngineVersion());
        copy.setAnimationData(skin.getAnimationData());
        copy.setCapeID(skin.getCapeID());
        copy.setFullID(skin.getFullID());
        copy.setArmSize(skin.getArmSize());
        copy.setSkinColor(skin.getSkinColor());
        for (SerializedPersonaPieceHandle piece : skin.getPersonaPieces()) {
            if (this.isKnown(piece.getPieceType())) {
                copy.getPersonaPieces().add(piece);
            }
        }
        for (Map.Entry<PieceType, TintMapColor> tint : skin.getPieceTintColors().entrySet()) {
            if (this.isKnown(tint.getKey())) {
                copy.getPieceTintColors().put(tint.getKey(), tint.getValue());
            }
        }
        copy.setPremium(skin.isPremium());
        copy.setPersona(skin.isPersona());
        copy.setPersonaCapeOnClassicSkin(skin.isPersonaCapeOnClassicSkin());
        copy.setPrimaryUser(skin.isPrimaryUser());
        copy.setOverridesPlayerAppearance(skin.isOverridesPlayerAppearance());
        copy.setTrustedSkinFlag(skin.getTrustedSkinFlag());
        copy.setProfileHash(skin.getProfileHash());
        return copy;
    }

    private boolean isKnown(PieceType type) {
        return type == null || this.personaPieceTypeMap.getIdUnsafe(type) != -1;
    }
}
