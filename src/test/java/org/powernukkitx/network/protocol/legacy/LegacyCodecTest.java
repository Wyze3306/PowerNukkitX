package org.powernukkitx.network.protocol.legacy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v2192.Bedrock_v2192;
import org.cloudburstmc.protocol.bedrock.codec.v2225.Bedrock_v2225;
import org.cloudburstmc.protocol.bedrock.data.EncodingSettings;
import org.cloudburstmc.protocol.bedrock.data.ParticleType;
import org.cloudburstmc.protocol.bedrock.data.SoundEvent;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorDataTypes;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorEvent;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.NameDescriptor;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.RecipeIngredient;
import org.cloudburstmc.protocol.bedrock.data.payload.chunk.HeightMapDataType;
import org.cloudburstmc.protocol.bedrock.data.payload.chunk.SubChunkHeightmapData;
import org.cloudburstmc.protocol.bedrock.data.payload.chunk.SubChunkPacketData;
import org.cloudburstmc.protocol.bedrock.data.payload.chunk.SubChunkRequestResult;
import org.cloudburstmc.protocol.bedrock.data.payload.common.DimensionType;
import org.cloudburstmc.protocol.bedrock.data.payload.crafting.PotionMixDataEntry;
import org.cloudburstmc.protocol.bedrock.data.payload.crafting.RecipeNetId;
import org.cloudburstmc.protocol.bedrock.data.payload.crafting.RecipeUnlockingRequirement;
import org.cloudburstmc.protocol.bedrock.data.payload.crafting.ShapelessRecipePayload;
import org.cloudburstmc.protocol.bedrock.data.payload.creative.CreativeItemEntryPayload;
import org.cloudburstmc.protocol.bedrock.data.payload.creative.CreativeItemNetId;
import org.cloudburstmc.protocol.bedrock.data.payload.skin.ArmSizeType;
import org.cloudburstmc.protocol.bedrock.data.payload.skin.PieceType;
import org.cloudburstmc.protocol.bedrock.data.payload.skin.SerializedPersonaPieceHandle;
import org.cloudburstmc.protocol.bedrock.data.payload.skin.SerializedSkin;
import org.cloudburstmc.protocol.bedrock.data.payload.skin.SkinImage;
import org.cloudburstmc.protocol.bedrock.data.payload.skin.TintMapColor;
import org.cloudburstmc.protocol.bedrock.data.payload.skin.TrustedSkinFlag;
import org.cloudburstmc.protocol.bedrock.data.payload.crafting.RecipeUnlockingContext;
import org.cloudburstmc.protocol.bedrock.packet.ActorEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddActorPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.CraftingDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.CreativeContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemRegistryPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelSoundEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetActorDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.SubChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;
import org.cloudburstmc.protocol.common.DefinitionRegistry;
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.powernukkitx.network.NetworkConstants;
import org.powernukkitx.network.protocol.GameProtocol;
import org.powernukkitx.utils.RuntimeBlockDefinition;
import org.powernukkitx.utils.RuntimeBlockDefinitionRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Encodes with the 1.26.50 codec and decodes with the plain protocol library codec of that
 * version, the way a 1.26.50 client reads what the server sends.
 */
class LegacyCodecTest {
    private static LegacyGameData nativeData;
    private static LegacyGameData legacyData;
    private static DefinitionRegistry<ItemDefinition> legacyItems;

    @BeforeAll
    static void load() {
        nativeData = LegacyGameData.load(LegacyGameData.NATIVE_ROOT);
        legacyData = LegacyGameData.load(LegacyGameData.LEGACY_ROOT);
        legacyItems = SimpleDefinitionRegistry.<ItemDefinition>builder()
            .addAll(LegacyTables.get().items().downgradePalette(nativeDefinitions()))
            .build();
    }

    @Test
    void protocolsUseTheirCodecs() {
        Assertions.assertSame(Bedrock_v2225.CODEC, NetworkConstants.CODEC);
        Assertions.assertSame(NetworkConstants.CODEC, GameProtocol.NATIVE.codec(), "native sessions keep the library codec");
        Assertions.assertSame(LegacyCodec_v2193.CODEC, GameProtocol.V1_26_50.codec());
        Assertions.assertEquals(2193, LegacyCodec_v2193.CODEC.getProtocolVersion());
        Assertions.assertEquals(GameProtocol.V1_26_50, GameProtocol.byProtocolVersion(2193));
        Assertions.assertEquals(GameProtocol.V1_26_60, GameProtocol.byProtocolVersion(2225));
        Assertions.assertNull(GameProtocol.byProtocolVersion(2192));
        Assertions.assertNull(GameProtocol.byProtocolVersion(2207));
        Assertions.assertEquals(GameProtocol.V1_26_50, GameProtocol.byCodec(LegacyCodec_v2193.CODEC));
        Assertions.assertNull(GameProtocol.byCodec(Bedrock_v2192.CODEC));
    }

    @Test
    void wrapsTheSerializersThisLibraryVersionUses() {
        // The translations wrap these classes; a library update that swaps them needs a look
        Assertions.assertEquals("LevelChunkSerializer_v2168",
            Bedrock_v2192.CODEC.getPacketDefinition(LevelChunkPacket.class).getSerializer().getClass().getSimpleName());
        Assertions.assertEquals("SubChunkSerializer_v2192",
            Bedrock_v2192.CODEC.getPacketDefinition(SubChunkPacket.class).getSerializer().getClass().getSimpleName());
        Assertions.assertEquals("BedrockCodecHelper_v2192", Bedrock_v2192.CODEC.createHelper().getClass().getSimpleName());
    }

    @Test
    void inventoryItemsArriveWithTheirOldIds() {
        final InventoryContentPacket packet = new InventoryContentPacket();
        packet.setContainerId(0);
        packet.getSlots().add(item("minecraft:apple", 0));
        packet.getSlots().add(item("minecraft:breeze_rod", 0));
        packet.getSlots().add(item("minecraft:ice_ball", 0));
        packet.getSlots().add(item("minecraft:splash_potion", 48));
        packet.getSlots().add(item("my:custom", 0, 10000));

        final InventoryContentPacket decoded = roundTrip(packet);
        Assertions.assertEquals("minecraft:apple", decoded.getSlots().get(0).getDefinition().getIdentifier());
        Assertions.assertEquals(882, decoded.getSlots().get(0).getDefinition().getRuntimeId());
        Assertions.assertEquals("minecraft:breeze_rod", decoded.getSlots().get(1).getDefinition().getIdentifier());
        Assertions.assertEquals("minecraft:snowball", decoded.getSlots().get(2).getDefinition().getIdentifier());
        Assertions.assertEquals("minecraft:splash_potion", decoded.getSlots().get(3).getDefinition().getIdentifier());
        Assertions.assertEquals(0, decoded.getSlots().get(3).getDamage());
        Assertions.assertEquals("my:custom", decoded.getSlots().get(4).getDefinition().getIdentifier());
        Assertions.assertEquals(nativeData.itemIds().getInt("minecraft:apple"), packet.getSlots().get(0).getDefinition().getRuntimeId(),
            "the packet sent is left as it was");
    }

    @Test
    void itemPaletteListsOnlyKnownItems() {
        final ItemRegistryPacket packet = new ItemRegistryPacket();
        packet.getItemData().addAll(nativeDefinitions());
        final ItemRegistryPacket decoded = roundTrip(packet);
        Assertions.assertEquals(legacyData.itemIds().size() + 1, decoded.getItemData().size(), "every 1.26.50 item and the custom one");
        for (ItemDefinition definition : decoded.getItemData()) {
            final int expected = definition.getIdentifier().equals("my:custom") ? 10000 : legacyData.itemIds().getInt(definition.getIdentifier());
            Assertions.assertEquals(expected, definition.getRuntimeId(), definition.getIdentifier());
        }
        Assertions.assertEquals(nativeData.itemIds().size() + 1, packet.getItemData().size(), "the packet sent is left as it was");
    }

    @Test
    void recipesWithUnknownItemsAreLeftOut() {
        final CraftingDataPacket packet = new CraftingDataPacket();
        packet.getShapelessRecipes().add(shapeless("apples", 1, "minecraft:apple", "minecraft:breeze_rod"));
        packet.getShapelessRecipes().add(shapeless("ice", 2, "minecraft:ice_ball", "minecraft:snowball"));
        packet.getShapelessRecipes().add(shapeless("ice_in", 3, "minecraft:snowball", "minecraft:ice_ball"));
        packet.getPotionMixes().add(mix(0, "minecraft:apple", 47));
        packet.getPotionMixes().add(mix(0, "minecraft:breeze_rod", 5));
        packet.setClearRecipes(true);

        final CraftingDataPacket decoded = roundTrip(packet);
        Assertions.assertEquals(1, decoded.getShapelessRecipes().size());
        final ShapelessRecipePayload recipe = decoded.getShapelessRecipes().getFirst();
        Assertions.assertEquals(1, recipe.getNetId().getRawId(), "network ids stay as they are");
        Assertions.assertEquals(882, recipe.getResults().getFirst().getDefinition().getRuntimeId());
        Assertions.assertEquals(1, decoded.getPotionMixes().size());
        Assertions.assertEquals(legacyData.itemIds().getInt("minecraft:breeze_rod"), decoded.getPotionMixes().getFirst().getReagentItemId());
        Assertions.assertEquals(legacyData.itemIds().getInt("minecraft:potion"), decoded.getPotionMixes().getFirst().getFromPotionId());
        Assertions.assertEquals(3, packet.getShapelessRecipes().size());
    }

    @Test
    void creativeItemsKeepTheirNetworkIds() {
        final CreativeContentPacket packet = new CreativeContentPacket();
        packet.getEntries().add(creative(1, "minecraft:apple", 0));
        packet.getEntries().add(creative(2, "minecraft:ice_ball", 0));
        packet.getEntries().add(creative(3, "minecraft:potion", 47));
        packet.getEntries().add(creative(4, "minecraft:potion", 46));

        final CreativeContentPacket decoded = roundTrip(packet);
        Assertions.assertEquals(2, decoded.getEntries().size());
        Assertions.assertEquals(1, decoded.getEntries().get(0).getCreativeNetId().getID());
        Assertions.assertEquals(882, decoded.getEntries().get(0).getItemInstance().getDefinition().getRuntimeId());
        Assertions.assertEquals(4, decoded.getEntries().get(1).getCreativeNetId().getID());
    }

    @Test
    void newSoundsAndParticlesHaveFallbacks() {
        final LevelSoundEventPacket sound = new LevelSoundEventPacket();
        sound.setSoundEvent(SoundEvent.ICE_BALL_BREAK);
        sound.setPosition(Vector3f.ZERO);
        sound.setActorIdentifier("minecraft:ice_ball");
        final LevelSoundEventPacket decodedSound = roundTrip(sound);
        Assertions.assertEquals(SoundEvent.GLASS, decodedSound.getSoundEvent());
        Assertions.assertEquals("minecraft:snowball", decodedSound.getActorIdentifier());
        Assertions.assertEquals(SoundEvent.ICE_BALL_BREAK, sound.getSoundEvent());

        final LevelEventPacket particle = new LevelEventPacket();
        particle.setType(ParticleType.ICE_BALL_BREAK);
        particle.setPosition(Vector3f.ZERO);
        Assertions.assertThrows(Exception.class, () -> Bedrock_v2192.CODEC.tryEncode(Bedrock_v2192.CODEC.createHelper(), Unpooled.buffer(), particle),
            "1.26.50 has no id for this particle");
        Assertions.assertEquals(ParticleType.SNOWBALL_POOF, roundTrip(particle).getType());
    }

    @Test
    void blockDataInEventsIsTranslatedButOtherDataIsNot() {
        final int wire = hash(nativeData, "minecraft:redstone_wire", Map.of("redstone_signal", 4, "redstone_north", "up"));
        final LevelSoundEventPacket place = new LevelSoundEventPacket();
        place.setSoundEvent(SoundEvent.PLACE);
        place.setPosition(Vector3f.ZERO);
        place.setActorIdentifier("");
        place.setData(wire);
        Assertions.assertEquals(LegacyTables.get().blocks().downgrade(wire), roundTrip(place).getData());

        final LevelSoundEventPacket note = new LevelSoundEventPacket();
        note.setSoundEvent(SoundEvent.NOTE);
        note.setPosition(Vector3f.ZERO);
        note.setActorIdentifier("");
        note.setData(12);
        Assertions.assertEquals(12, roundTrip(note).getData());

        final LevelEventPacket itemBreak = new LevelEventPacket();
        itemBreak.setType(ParticleType.ICON_CRACK);
        itemBreak.setPosition(Vector3f.ZERO);
        itemBreak.setData(nativeData.itemIds().getInt("minecraft:apple") << 16);
        Assertions.assertEquals(882 << 16, roundTrip(itemBreak).getData());
    }

    @Test
    void blockUpdatesAreTranslatedWithoutTouchingTheSharedPacket() {
        final int wire = hash(nativeData, "minecraft:redstone_wire", Map.of("redstone_signal", 9, "redstone_east", "side"));
        final UpdateBlockPacket packet = new UpdateBlockPacket();
        packet.setBlockPosition(Vector3i.from(1, 2, 3));
        packet.setDefinition(new RuntimeBlockDefinition(wire));
        packet.setLayer(0);
        final var definition = packet.getDefinition();

        final UpdateBlockPacket decoded = roundTrip(packet);
        Assertions.assertEquals(hash(legacyData, "minecraft:redstone_wire", Map.of("redstone_signal", 9)), decoded.getDefinition().getRuntimeId());
        Assertions.assertSame(definition, packet.getDefinition());
    }

    @Test
    void fallingBlocksShowTheirOldState() {
        final int wire = hash(nativeData, "minecraft:redstone_wire", Map.of("redstone_signal", 5, "redstone_east", "side", "redstone_west", "up"));
        final SetActorDataPacket packet = new SetActorDataPacket();
        packet.setTargetRuntimeID(7);
        packet.getActorData().put(ActorDataTypes.VARIANT, wire);
        packet.getActorData().put(ActorDataTypes.HEARTBEAT_SOUND_EVENT, SoundEvent.ICE_BALL_BREAK);

        final SetActorDataPacket decoded = roundTrip(packet);
        Assertions.assertEquals(hash(legacyData, "minecraft:redstone_wire", Map.of("redstone_signal", 5)),
            decoded.getActorData().get(ActorDataTypes.VARIANT));
        Assertions.assertEquals(SoundEvent.GLASS, decoded.getActorData().get(ActorDataTypes.HEARTBEAT_SOUND_EVENT));
        Assertions.assertEquals(wire, packet.getActorData().get(ActorDataTypes.VARIANT));
    }

    @Test
    void newEntitiesAreSentAsOldOnes() {
        final AddActorPacket packet = new AddActorPacket();
        packet.setTargetActorID(1);
        packet.setTargetRuntimeID(1);
        packet.setActorType("minecraft:frostbite");
        packet.setPosition(Vector3f.ZERO);
        packet.setVelocity(Vector3f.ZERO);
        packet.setRotation(org.cloudburstmc.math.vector.Vector2f.ZERO);
        Assertions.assertEquals("minecraft:stray", roundTrip(packet).getActorType());
        Assertions.assertEquals("minecraft:frostbite", packet.getActorType());
    }

    @Test
    void skinsLosePiecesTheOldVersionCannotName() {
        final SerializedSkin skin = new SerializedSkin();
        skin.setID("skin");
        skin.setResourcePatch("{}");
        skin.setImageData(new SkinImage(64, 64, new byte[64 * 64 * 4]));
        skin.setCapeImageData(new SkinImage(0, 0, new byte[0]));
        skin.setGeometryData("");
        skin.setGeometryDataMinEngineVersion("");
        skin.setAnimationData("");
        skin.setCapeID("");
        skin.setFullID("full");
        skin.setArmSize(ArmSizeType.WIDE);
        skin.getPersonaPieces().add(piece(PieceType.CO_CO));
        skin.getPersonaPieces().add(piece(PieceType.HAIR));
        skin.getPieceTintColors().put(PieceType.CO_CO, tint());
        skin.getPieceTintColors().put(PieceType.HAIR, tint());
        skin.setTrustedSkinFlag(TrustedSkinFlag.TRUE);
        skin.setProfileHash("");

        final ByteBuf buffer = Unpooled.buffer();
        LegacyCodec_v2193.CODEC.createHelper().writeSerializedSkin(buffer, skin);
        final SerializedSkin decoded = Bedrock_v2192.CODEC.createHelper().readSerializedSkin(buffer);
        Assertions.assertEquals(1, decoded.getPersonaPieces().size());
        Assertions.assertEquals(PieceType.HAIR, decoded.getPersonaPieces().getFirst().getPieceType());
        Assertions.assertEquals(1, decoded.getPieceTintColors().size());
        Assertions.assertEquals("", decoded.getPlayFabID());
        Assertions.assertEquals(2, skin.getPersonaPieces().size());
    }

    @Test
    void eatingEventsTranslateBothWays() {
        final int apple = nativeData.itemIds().getInt("minecraft:apple");
        final ActorEventPacket packet = new ActorEventPacket();
        packet.setTargetRuntimeID(1);
        packet.setType(ActorEvent.FEED);
        packet.setData(apple << 16);
        Assertions.assertEquals(882 << 16, roundTrip(packet).getData());

        // What a 1.26.50 client sends for the apple it eats
        final ActorEventPacket client = new ActorEventPacket();
        client.setTargetRuntimeID(1);
        client.setType(ActorEvent.FEED);
        client.setData(882 << 16);
        final ByteBuf buffer = Unpooled.buffer();
        Bedrock_v2192.CODEC.tryEncode(Bedrock_v2192.CODEC.createHelper(), buffer, client);
        final ActorEventPacket received = (ActorEventPacket) LegacyCodec_v2193.CODEC.tryDecode(legacyHelper(LegacyCodec_v2193.CODEC),
            buffer, LegacyCodec_v2193.CODEC.getPacketDefinition(ActorEventPacket.class).getId());
        Assertions.assertEquals(apple << 16, received.getData());
    }

    @Test
    void clientItemIdsNeverResolveToAnotherItem() {
        // A 1.26.50 client sending the server's id of an apple must not get a breeze rod or
        // anything else; it resolves to nothing
        Assertions.assertNull(legacyItems.getDefinition(nativeData.itemIds().getInt("minecraft:apple")));
        Assertions.assertEquals("minecraft:apple", legacyItems.getDefinition(882).getIdentifier());
    }

    @Test
    void chunkSectionsAreTranslatedInPlace() {
        final int air = hash(nativeData, "minecraft:air", Map.of());
        final int stone = hash(nativeData, "minecraft:stone", Map.of());
        final int wire = hash(nativeData, "minecraft:redstone_wire", Map.of("redstone_signal", 15, "redstone_west", "up"));
        final int legacyWire = LegacyTables.get().blocks().downgrade(wire);

        final ByteBuf plain = chunk(air, stone, 1);
        Assertions.assertNull(LegacyChunkTranslator.translateLevelChunk(plain, 2, 2, LegacyTables.get()), "nothing to change");

        final ByteBuf withWire = chunk(air, wire, 196);
        final ByteBuf expected = chunk(air, legacyWire, 188);
        final int readerIndex = withWire.readerIndex();
        final ByteBuf translated = LegacyChunkTranslator.translateLevelChunk(withWire, 2, 2, LegacyTables.get());
        Assertions.assertNotNull(translated);
        Assertions.assertArrayEquals(ByteBufUtil.getBytes(expected), ByteBufUtil.getBytes(translated));
        Assertions.assertEquals(readerIndex, withWire.readerIndex(), "the shared buffer keeps its indices");

        final LevelChunkPacket packet = new LevelChunkPacket();
        packet.setChunkX(1);
        packet.setChunkZ(2);
        packet.setDimension(DimensionType.from(0));
        packet.setSubChunksCount(2);
        packet.setSerializedChunkData(withWire.retainedDuplicate());
        final LevelChunkPacket decoded = roundTrip(packet);
        Assertions.assertArrayEquals(ByteBufUtil.getBytes(expected), ByteBufUtil.getBytes(decoded.getSerializedChunkData()));
        packet.release();
    }

    @Test
    void subChunksAreTranslated() {
        final int air = hash(nativeData, "minecraft:air", Map.of());
        final int fire = hash(nativeData, "minecraft:fire", Map.of("age", 3, "minecraft:connection_up", (byte) 1));
        final SubChunkPacket packet = new SubChunkPacket();
        packet.setDimensionType(DimensionType.from(0));
        packet.setCenterPos(Vector3i.ZERO);
        final SubChunkPacketData data = new SubChunkPacketData();
        data.setSubChunkPosOffset(Vector3i.ZERO);
        data.setSubChunkRequestResult(SubChunkRequestResult.SUCCESS);
        final ByteBuf serialized = Unpooled.buffer();
        section(serialized, air, fire);
        serialized.writeByte(0);
        data.setSerializedSubChunk(serialized);
        final SubChunkHeightmapData heightMap = new SubChunkHeightmapData();
        heightMap.setHeightMapType(HeightMapDataType.NO_DATA);
        heightMap.setRenderHeightMapType(HeightMapDataType.NO_DATA);
        data.setHeightMapData(heightMap);
        packet.getSubChunkData().add(data);

        final SubChunkPacket decoded = roundTrip(packet);
        final ByteBuf expected = Unpooled.buffer();
        section(expected, air, LegacyTables.get().blocks().downgrade(fire));
        expected.writeByte(0);
        Assertions.assertArrayEquals(ByteBufUtil.getBytes(expected), ByteBufUtil.getBytes(decoded.getSubChunkData().getFirst().getSerializedSubChunk()));
        packet.release();
    }

    /**
     * Two sections, one with air and {@code block}, and two biome palettes, the second copying the
     * first, followed by no border blocks and no block entities.
     */
    private static ByteBuf chunk(int air, int block, int biome) {
        final ByteBuf buffer = Unpooled.buffer();
        section(buffer, air, block);
        section(buffer, air, block);
        buffer.writeByte(1);
        VarInts.writeInt(buffer, biome);
        buffer.writeByte(0xFF);
        buffer.writeByte(0);
        buffer.writeByte(0);
        return buffer;
    }

    private static void section(ByteBuf buffer, int air, int block) {
        buffer.writeByte(9);
        buffer.writeByte(2);
        buffer.writeByte(0);
        buffer.writeByte(1 << 1 | 1);
        for (int i = 0; i < 128; i++) {
            buffer.writeIntLE(i % 3 == 0 ? 0x5555 : 0);
        }
        VarInts.writeInt(buffer, 2);
        VarInts.writeInt(buffer, air);
        VarInts.writeInt(buffer, block);
        buffer.writeByte(1);
        VarInts.writeInt(buffer, air);
    }

    @SuppressWarnings("unchecked")
    private static <T extends BedrockPacket> T roundTrip(T packet) {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            LegacyCodec_v2193.CODEC.tryEncode(legacyHelper(LegacyCodec_v2193.CODEC), buffer, packet);
            final int id = Bedrock_v2192.CODEC.getPacketDefinition(packet.getClass()).getId();
            return (T) Bedrock_v2192.CODEC.tryDecode(legacyHelper(Bedrock_v2192.CODEC), buffer, id);
        } finally {
            buffer.release();
        }
    }

    private static BedrockCodecHelper legacyHelper(BedrockCodec codec) {
        final BedrockCodecHelper helper = codec.createHelper();
        helper.setItemDefinitions(legacyItems);
        helper.setBlockDefinitions(new RuntimeBlockDefinitionRegistry());
        helper.setEncodingSettings(EncodingSettings.UNLIMITED);
        return helper;
    }

    private static List<ItemDefinition> nativeDefinitions() {
        final List<ItemDefinition> definitions = new ArrayList<>();
        nativeData.itemIds().forEach((identifier, id) -> definitions.add(new SimpleItemDefinition(identifier, id, false)));
        definitions.add(new SimpleItemDefinition("my:custom", 10000, true));
        return definitions;
    }

    private static ItemData item(String identifier, int damage) {
        return item(identifier, damage, nativeData.itemIds().getInt(identifier));
    }

    private static ItemData item(String identifier, int damage, int runtimeId) {
        return ItemData.builder()
            .definition(new SimpleItemDefinition(identifier, runtimeId, false))
            .damage(damage)
            .count(1)
            .usingNetId(true)
            .netId(5)
            .build();
    }

    private static ShapelessRecipePayload shapeless(String id, int netId, String result, String ingredient) {
        final ShapelessRecipePayload recipe = new ShapelessRecipePayload();
        recipe.setRecipeId(id);
        recipe.getIngredients().add(new RecipeIngredient(new NameDescriptor(
            new SimpleItemDefinition(ingredient, nativeData.itemIds().getInt(ingredient), false), 0), 1));
        recipe.getResults().add(item(result, 0));
        recipe.setUuid(new UUID(0, netId));
        recipe.setTag("crafting_table");
        recipe.setUnlockingRequirement(new RecipeUnlockingRequirement(RecipeUnlockingContext.ALWAYS_UNLOCKED));
        recipe.setNetId(new RecipeNetId(netId));
        return recipe;
    }

    private static PotionMixDataEntry mix(int fromAux, String reagent, int toAux) {
        final int potion = nativeData.itemIds().getInt("minecraft:potion");
        final PotionMixDataEntry mix = new PotionMixDataEntry();
        mix.setFromPotionId(potion);
        mix.setFromItemAux(fromAux);
        mix.setReagentItemId(nativeData.itemIds().getInt(reagent));
        mix.setToPotionId(potion);
        mix.setToItemAux(toAux);
        return mix;
    }

    private static CreativeItemEntryPayload creative(int netId, String identifier, int damage) {
        final CreativeItemEntryPayload entry = new CreativeItemEntryPayload();
        entry.setCreativeNetId(new CreativeItemNetId(netId));
        entry.setItemInstance(item(identifier, damage));
        entry.setGroupIndex(0);
        return entry;
    }

    private static SerializedPersonaPieceHandle piece(PieceType type) {
        final SerializedPersonaPieceHandle piece = new SerializedPersonaPieceHandle();
        piece.setPieceId(type.name());
        piece.setPieceType(type);
        piece.setPackId(new UUID(0, 0));
        piece.setProductId("");
        return piece;
    }

    private static TintMapColor tint() {
        final TintMapColor tint = new TintMapColor();
        tint.getColors().addAll(List.of(1, 2, 3, 4));
        return tint;
    }

    private static int hash(LegacyGameData data, String name, Map<String, Object> properties) {
        for (NbtMap block : data.blocks()) {
            if (!block.getString("name").equals(name)) {
                continue;
            }
            boolean matches = true;
            for (Map.Entry<String, Object> property : properties.entrySet()) {
                matches &= property.getValue().equals(block.getCompound("states").get(property.getKey()));
            }
            if (matches) {
                return block.getInt("network_id");
            }
        }
        throw new AssertionError("No state " + name + " " + properties);
    }
}
