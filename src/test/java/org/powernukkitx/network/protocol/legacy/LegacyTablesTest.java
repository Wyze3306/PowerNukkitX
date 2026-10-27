package org.powernukkitx.network.protocol.legacy;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.powernukkitx.level.updater.block.BlockStateUpdater_1_26_60;
import org.powernukkitx.utils.HashUtils;
import org.powernukkitx.utils.RuntimeBlockDefinition;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

class LegacyTablesTest {
    private static LegacyGameData nativeData;
    private static LegacyGameData legacyData;
    private static LegacyTables tables;

    @BeforeAll
    static void load() {
        nativeData = LegacyGameData.load(LegacyGameData.NATIVE_ROOT);
        legacyData = LegacyGameData.load(LegacyGameData.LEGACY_ROOT);
        tables = LegacyTables.build(nativeData, legacyData);
    }

    @Test
    void bundledFilesAreTheRecordedOnes() throws Exception {
        final Map<String, String> expected = Map.of(
            "block_palette.nbt", "d83f382577fdecfca2e9e98ae9a591f3735e32891c476a79cb2ddca8f866db9f",
            "item_palette.json", "a400cd230acaeec957afa44ab227559af5b4b2e4ed312b91fe73dcc01170060a",
            "entity_identifiers.nbt", "3214a260feaa3db611c9baf513722fdbf26726d0582fca0be6771051a455f0c8",
            "biomes.json", "6e91fc91c295d032010a67d3d47023c700f79040e6989cf6d7f460238d34c650"
        );
        for (Map.Entry<String, String> file : expected.entrySet()) {
            try (InputStream stream = LegacyTablesTest.class.getClassLoader()
                .getResourceAsStream(LegacyGameData.LEGACY_ROOT + file.getKey())) {
                Assertions.assertNotNull(stream, file.getKey());
                final byte[] digest = MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes());
                Assertions.assertEquals(file.getValue(), HexFormat.of().formatHex(digest), file.getKey());
            }
        }
    }

    @Test
    void paletteHashesAreTheHashesOfTheirStates() {
        for (LegacyGameData data : new LegacyGameData[]{nativeData, legacyData}) {
            for (NbtMap block : data.blocks()) {
                if (block.getString("name").equals("minecraft:unknown")) {
                    continue;
                }
                Assertions.assertEquals(block.getInt("network_id"), HashUtils.fnv1a_32_nbt_palette(block), block.toString());
            }
        }
    }

    @Test
    void onlyTheReshapedAndAddedBlocksChange() {
        final IntSet legacyHashes = hashes(legacyData);
        Assertions.assertEquals(23930, nativeData.blocks().size());
        Assertions.assertEquals(22091, legacyData.blocks().size());

        final BlockDowngrade blocks = tables.blocks();
        Assertions.assertEquals(0, blocks.misses(), "every server state has a 1.26.50 counterpart");
        Assertions.assertEquals(1872, blocks.changedHashes().size());
        Assertions.assertEquals(33, blocks.legacyOnlyHashes().size());

        final Set<String> changedNames = new HashSet<>(BlockStateUpdater_1_26_60.ADDED_PROPERTIES.keySet());
        changedNames.addAll(BlockDowngrade.RENAMED.keySet());
        int same = 0;
        for (NbtMap block : nativeData.blocks()) {
            final int hash = block.getInt("network_id");
            final int downgraded = blocks.downgrade(hash);
            Assertions.assertTrue(legacyHashes.contains(downgraded), block.toString());
            Assertions.assertEquals(downgraded, blocks.downgrade(downgraded), "downgrading is idempotent");
            Assertions.assertEquals(changedNames.contains(block.getString("name")), blocks.isChanged(hash), block.toString());
            if (!blocks.isChanged(hash)) {
                Assertions.assertEquals(hash, downgraded);
                same++;
            }
        }
        Assertions.assertEquals(22058, same);
    }

    @Test
    void newBlocksBecomeTheirClosestOldBlockWithTheSameProperties() {
        final NbtMap wire = state(nativeData, "minecraft:redstone_wire", Map.of("redstone_signal", 7, "redstone_east", "side"));
        final NbtMap oldWire = state(legacyData, "minecraft:redstone_wire", Map.of("redstone_signal", 7));
        Assertions.assertEquals(oldWire.getInt("network_id"), tables.blocks().downgrade(wire.getInt("network_id")));
    }

    @Test
    void clientHashesResolveToTheStateTheClientMeant() {
        final IntSet nativeHashes = hashes(nativeData);
        final BlockDowngrade blocks = tables.blocks();
        for (NbtMap block : legacyData.blocks()) {
            final int upgraded = blocks.upgrade(block.getInt("network_id"));
            Assertions.assertTrue(nativeHashes.contains(upgraded), block.toString());
        }
        final NbtMap oldWire = state(legacyData, "minecraft:redstone_wire", Map.of("redstone_signal", 3));
        final NbtMap wire = state(nativeData, "minecraft:redstone_wire", Map.of("redstone_signal", 3, "redstone_east", "none",
            "redstone_north", "none", "redstone_south", "none", "redstone_west", "none"));
        Assertions.assertEquals(wire.getInt("network_id"), blocks.upgrade(oldWire.getInt("network_id")));

        final int air = state(nativeData, "minecraft:air", Map.of()).getInt("network_id");
        final NbtMap sideWire = state(nativeData, "minecraft:redstone_wire", Map.of("redstone_signal", 3, "redstone_east", "side"));
        Assertions.assertEquals(air, blocks.upgrade(sideWire.getInt("network_id")), "a state the client cannot know is air");
        Assertions.assertEquals(123456789, blocks.upgrade(123456789), "unknown hashes resolve as on the native protocol");
    }

    @Test
    void changedHashesNeverCollideWithSmallValues() {
        // Sound and level event data only translate when they hold a changed hash, which relies on
        // no changed hash being a value such as a pitch, a colour index or an entity type
        for (IntSet set : new IntSet[]{tables.blocks().changedHashes(), tables.blocks().legacyOnlyHashes()}) {
            set.forEach(hash -> Assertions.assertTrue(Math.abs((long) hash) > 65536, "hash " + hash));
        }
    }

    @Test
    void itemsMoveByIdentifier() {
        final ItemDowngrade items = tables.items();
        int moved = 0;
        for (var entry : legacyData.itemIds().object2IntEntrySet()) {
            final int nativeId = nativeData.itemIds().getInt(entry.getKey());
            Assertions.assertTrue(nativeData.itemIds().containsKey(entry.getKey()), "no item was removed: " + entry.getKey());
            Assertions.assertEquals(entry.getIntValue(), items.downgradeId(nativeId), entry.getKey());
            Assertions.assertEquals(nativeId, items.upgradeId(entry.getIntValue()), entry.getKey());
            if (nativeId != entry.getIntValue()) {
                moved++;
            }
        }
        Assertions.assertEquals(82, moved);
        Assertions.assertEquals(2078, nativeData.itemIds().size());
        Assertions.assertEquals(2076, legacyData.itemIds().size());

        final int apple = nativeData.itemIds().getInt("minecraft:apple");
        final int breezeRod = nativeData.itemIds().getInt("minecraft:breeze_rod");
        Assertions.assertEquals(882, breezeRod);
        Assertions.assertEquals(882, items.downgradeId(apple), "1.26.50 knows apples as 882");
        Assertions.assertEquals(apple, items.upgradeId(882), "and 882 from such a client is an apple, never a breeze rod");
        Assertions.assertEquals(0, items.upgradeId(apple), "an id only the server has cannot come from a 1.26.50 client");
        Assertions.assertEquals(10003, items.upgradeId(10003), "custom items keep their id");
        Assertions.assertEquals(10003, items.downgradeId(10003));
    }

    @Test
    void addedItemsHaveFallbacks() {
        final ItemDowngrade items = tables.items();
        final Set<String> added = new HashSet<>(nativeData.itemIds().keySet());
        added.removeAll(legacyData.itemIds().keySet());
        // The experimental items PNX dropped since keep a fallback, in case they come back
        Assertions.assertTrue(ItemDowngrade.FALLBACKS.keySet().containsAll(added), added.toString());
        for (String identifier : added) {
            final String sentAs = items.sentAs(identifier);
            Assertions.assertEquals(ItemDowngrade.FALLBACKS.get(identifier), sentAs);
            Assertions.assertEquals(legacyData.itemIds().getInt(sentAs), items.downgradeId(nativeData.itemIds().getInt(identifier)));
            Assertions.assertFalse(items.isRepresentable(identifier, 0));
        }
        Assertions.assertEquals("my:custom", items.sentAs("my:custom"));
        Assertions.assertTrue(items.isRepresentable("my:custom", 0));
    }

    @Test
    void itemDataIsTranslatedByIdentifierAndIdempotent() {
        final ItemDowngrade items = tables.items();
        for (var entry : nativeData.itemIds().object2IntEntrySet()) {
            final ItemData item = ItemData.builder()
                .definition(new SimpleItemDefinition(entry.getKey(), entry.getIntValue(), false))
                .count(1)
                .build();
            final ItemData once = items.downgrade(item);
            final ItemData twice = items.downgrade(once);
            Assertions.assertEquals(once.getDefinition().getRuntimeId(), twice.getDefinition().getRuntimeId(), entry.getKey());
            Assertions.assertEquals(items.downgradeId(entry.getIntValue()), once.getDefinition().getRuntimeId(), entry.getKey());
        }

        final ItemData sameId = ItemData.builder()
            .definition(new SimpleItemDefinition("minecraft:stone", nativeData.itemIds().getInt("minecraft:stone"), false))
            .count(1).build();
        Assertions.assertSame(sameId, items.downgrade(sameId), "an unchanged item is not copied");
    }

    @Test
    void unknownVariantsBecomeThePlainVariant() {
        final ItemDowngrade items = tables.items();
        Assertions.assertEquals(0, items.downgrade(potion("minecraft:potion", 47)).getDamage());
        Assertions.assertEquals(46, items.downgrade(potion("minecraft:potion", 46)).getDamage());
        Assertions.assertEquals(0, items.downgrade(potion("minecraft:arrow", 49)).getDamage());
        Assertions.assertEquals(47, items.downgrade(potion("minecraft:arrow", 47)).getDamage());
        Assertions.assertFalse(items.isRepresentable("minecraft:lingering_potion", 48));
        Assertions.assertTrue(items.isRepresentable("minecraft:lingering_potion", -1), "a recipe wildcard is no variant");

        final int potion = nativeData.itemIds().getInt("minecraft:potion");
        Assertions.assertEquals(legacyData.itemIds().getInt("minecraft:potion") << 16, items.downgradePacked(potion << 16 | 48));
        final int apple = nativeData.itemIds().getInt("minecraft:apple");
        Assertions.assertEquals(882 << 16 | 3, items.downgradePacked(apple << 16 | 3));
        Assertions.assertEquals(apple << 16 | 3, items.upgradePacked(882 << 16 | 3));
    }

    @Test
    void blockItemsCarryTheirOldState() {
        final NbtMap chorus = state(nativeData, "minecraft:chorus_plant", Map.of("minecraft:connection_up", (byte) 1));
        final ItemData item = ItemData.builder()
            .definition(new SimpleItemDefinition("minecraft:chorus_plant", nativeData.itemIds().getInt("minecraft:chorus_plant"), false))
            .blockDefinition(new RuntimeBlockDefinition(chorus.getInt("network_id")))
            .count(1).build();
        final ItemData legacy = tables.items().downgrade(item);
        Assertions.assertEquals("minecraft:chorus_plant", legacy.getDefinition().getIdentifier());
        Assertions.assertEquals(legacyData.itemIds().getInt("minecraft:chorus_plant"), legacy.getDefinition().getRuntimeId());
        Assertions.assertEquals(state(legacyData, "minecraft:chorus_plant", Map.of()).getInt("network_id"), legacy.getBlockDefinition().getRuntimeId());
    }

    @Test
    void paletteKeepsKnownAndCustomItems() {
        final var definitions = new java.util.ArrayList<ItemDefinition>();
        nativeData.itemIds().forEach((identifier, id) -> definitions.add(new SimpleItemDefinition(identifier, id, false)));
        definitions.add(new SimpleItemDefinition("my:custom", 10000, true));
        final var legacy = tables.items().downgradePalette(definitions);
        Assertions.assertEquals(2077, legacy.size());
        final IntSet ids = new IntOpenHashSet();
        for (ItemDefinition definition : legacy) {
            Assertions.assertTrue(ids.add(definition.getRuntimeId()), "duplicate id " + definition.getRuntimeId());
        }
    }

    @Test
    void addedEntitiesAndBiomesHaveFallbacks() {
        final EntityDowngrade entities = tables.entities();
        Assertions.assertEquals(EntityDowngrade.FALLBACKS.keySet(), entities.added());
        for (String fallback : EntityDowngrade.FALLBACKS.values()) {
            Assertions.assertTrue(legacyData.entityIds().contains(fallback), fallback);
        }
        Assertions.assertEquals("minecraft:stray", entities.downgrade("minecraft:frostbite"));
        Assertions.assertEquals("minecraft:pig", entities.downgrade("minecraft:pig"));

        final BiomeDowngrade biomes = tables.biomes();
        Assertions.assertEquals(188, biomes.downgrade(196));
        Assertions.assertFalse(biomes.isKnown("minecraft:ice_caves"));
        Assertions.assertTrue(biomes.isKnown("minecraft:plains"));
        for (var entry : legacyData.biomeIds().object2IntEntrySet()) {
            Assertions.assertEquals(entry.getIntValue(), biomes.downgrade(nativeData.biomeIds().getInt(entry.getKey())), entry.getKey());
        }
    }

    private static ItemData potion(String identifier, int aux) {
        return ItemData.builder()
            .definition(new SimpleItemDefinition(identifier, nativeData.itemIds().getInt(identifier), false))
            .damage(aux).count(1).build();
    }

    private static NbtMap state(LegacyGameData data, String name, Map<String, Object> properties) {
        for (NbtMap block : data.blocks()) {
            if (!block.getString("name").equals(name)) {
                continue;
            }
            final NbtMap states = block.getCompound("states");
            boolean matches = true;
            for (Map.Entry<String, Object> property : properties.entrySet()) {
                matches &= property.getValue().equals(states.get(property.getKey()));
            }
            if (matches) {
                return block;
            }
        }
        throw new AssertionError("No state " + name + " " + properties);
    }

    private static IntSet hashes(LegacyGameData data) {
        final IntSet hashes = new IntOpenHashSet();
        for (NbtMap block : data.blocks()) {
            hashes.add(block.getInt("network_id"));
        }
        return hashes;
    }
}
