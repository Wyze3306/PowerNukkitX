package org.powernukkitx.level.format.palette;

import org.powernukkitx.block.Block;
import org.powernukkitx.block.BlockAir;
import org.powernukkitx.block.BlockID;
import org.powernukkitx.block.BlockState;
import org.powernukkitx.level.format.ChunkSection;
import org.powernukkitx.level.format.bitarray.BitArrayVersion;
import org.powernukkitx.registry.Registries;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Network form of a palette: a storage whose entries all hold one value is written as that value
 * alone (zero bits per entry), everything else as words plus palette. A void chunk used to weigh
 * about 80 KB on the wire because every all-air layer went out as its full word array.
 */
public class PaletteNetworkTest {

    private static BlockState stone;
    private static BlockState dirt;

    @BeforeAll
    static void init() {
        Registries.POTION.init();
        Registries.BLOCK.init();
        Registries.ITEM.init();
        Registries.ITEM_RUNTIMEID.init();
        stone = Block.get(BlockID.STONE).getBlockState();
        dirt = Block.get(BlockID.DIRT).getBlockState();
    }

    private static ByteBuf write(Palette<BlockState> palette) {
        ByteBuf buf = Unpooled.buffer();
        palette.writeToNetwork(buf, BlockState::blockStateHash);
        return buf;
    }

    private static BlockPalette roundTrip(Palette<BlockState> palette) {
        ByteBuf buf = write(palette);
        try {
            BlockPalette restored = new BlockPalette(BlockAir.STATE);
            restored.readFromNetwork(buf, hash -> Registries.BLOCKSTATE.get(hash));
            Assertions.assertEquals(0, buf.readableBytes(), "bytes left after reading the palette back");
            return restored;
        } finally {
            buf.release();
        }
    }

    private static void assertSameEntries(Palette<BlockState> expected, Palette<BlockState> actual) {
        for (int i = 0; i < ChunkSection.SIZE; i++) {
            Assertions.assertSame(expected.get(i), actual.get(i), "entry " + i);
        }
    }

    @Test
    void allAirLayerIsWrittenAsItsSingleValue() {
        BlockPalette air = new BlockPalette(BlockAir.STATE);
        ByteBuf buf = write(air);
        try {
            Assertions.assertEquals(1, buf.getUnsignedByte(0), "header: zero bits per entry, runtime");
            Assertions.assertTrue(buf.readableBytes() <= 6, "one header byte and one varint, got " + buf.readableBytes());
        } finally {
            buf.release();
        }
        assertSameEntries(air, roundTrip(air));
    }

    @Test
    void layerFilledWithAnotherBlockIsUniformToo() {
        // air stays first in the palette and every entry points at index 1
        BlockPalette solid = new BlockPalette(BlockAir.STATE);
        for (int i = 0; i < ChunkSection.SIZE; i++) solid.set(i, stone);
        Assertions.assertEquals(1, solid.uniformIndex());
        Assertions.assertTrue(solid.isUniform(stone));
        Assertions.assertFalse(solid.isUniform(BlockAir.STATE));
        ByteBuf buf = write(solid);
        try {
            Assertions.assertEquals(1, buf.getUnsignedByte(0));
        } finally {
            buf.release();
        }
        assertSameEntries(solid, roundTrip(solid));
    }

    @Test
    void oneDifferentBlockKeepsTheFullForm() {
        for (int odd : new int[]{0, 1, 2047, ChunkSection.SIZE - 1}) {
            BlockPalette mixed = new BlockPalette(BlockAir.STATE);
            for (int i = 0; i < ChunkSection.SIZE; i++) mixed.set(i, stone);
            mixed.set(odd, dirt);
            Assertions.assertEquals(-1, mixed.uniformIndex(), "odd entry " + odd);
            ByteBuf buf = write(mixed);
            try {
                Assertions.assertNotEquals(1, buf.getUnsignedByte(0), "odd entry " + odd);
            } finally {
                buf.release();
            }
            assertSameEntries(mixed, roundTrip(mixed));
        }
    }

    @Test
    void everyWordLayoutFindsTheUniformIndexAndTheLastEntry() {
        for (BitArrayVersion version : BitArrayVersion.values()) {
            if (version == BitArrayVersion.V0) continue;
            Palette<Integer> palette = new Palette<>(0, version);
            int index = Math.min(version.maxEntryValue, 5);
            for (int value = 1; value <= index; value++) palette.paletteIndexFor(value);
            for (int i = 0; i < ChunkSection.SIZE; i++) palette.set(i, index);
            Assertions.assertEquals(version, palette.bitArray.version(), "no resize expected");
            Assertions.assertEquals(index, palette.uniformIndex(), version.name());
            // the last entry sits alone in the last word of the padded layouts
            palette.set(ChunkSection.SIZE - 1, 0);
            Assertions.assertEquals(-1, palette.uniformIndex(), version.name() + " last entry");
            palette.set(ChunkSection.SIZE - 1, index);
            palette.set(0, 0);
            Assertions.assertEquals(-1, palette.uniformIndex(), version.name() + " first entry");
        }
    }

    @Test
    void bitsOutsideTheEntriesAreIgnored() {
        // padding bits of each word and unused slots of the last one, as a storage read from disk may carry them
        for (BitArrayVersion version : new BitArrayVersion[]{BitArrayVersion.V3, BitArrayVersion.V5, BitArrayVersion.V6}) {
            Palette<Integer> palette = new Palette<>(0, version);
            int[] words = palette.bitArray.words();
            for (int i = 0; i < words.length; i++) words[i] |= 0xC0000000;
            words[words.length - 1] |= -1 << ((ChunkSection.SIZE - (words.length - 1) * version.entriesPerWord) * version.bits);
            Assertions.assertEquals(0, palette.uniformIndex(), version.name());
        }
    }

    @Test
    void biomesUseTheSingleValueFormAndReadBack() {
        Palette<Integer> biomes = new Palette<>(7);
        ByteBuf buf = Unpooled.buffer();
        try {
            biomes.writeToNetwork(buf, Integer::intValue);
            Assertions.assertEquals(2, buf.readableBytes(), "header + varint of 7");
            Palette<Integer> restored = new Palette<>(0);
            restored.readFromNetwork(buf, id -> id);
            for (int i = 0; i < ChunkSection.SIZE; i++) Assertions.assertEquals(7, restored.get(i));
        } finally {
            buf.release();
        }
    }

    @Test
    void sectionIsAllAirOnlyWhenBothLayersAre() {
        ChunkSection section = new ChunkSection((byte) 0);
        Assertions.assertTrue(section.isAllAir());
        section.setBlockState(3, 4, 5, stone, 1);
        Assertions.assertFalse(section.isAllAir(), "waterlogging layer holds a block");
        section.setBlockState(3, 4, 5, BlockAir.STATE, 1);
        Assertions.assertTrue(section.isAllAir(), "block removed again, index back to air");
        section.setBlockState(0, 0, 0, stone, 0);
        Assertions.assertFalse(section.isAllAir());
    }

    @Test
    void largePaletteStaysCorrect() {
        // a section holding many distinct blocks (resized words, index map built) must not read as uniform
        List<BlockState> distinct = new ArrayList<>();
        for (BlockState state : Registries.BLOCKSTATE.getAllState()) {
            if (distinct.size() == 40) break;
            distinct.add(state);
        }
        BlockPalette palette = new BlockPalette(BlockAir.STATE);
        for (int i = 0; i < ChunkSection.SIZE; i++) palette.set(i, distinct.get(i % distinct.size()));
        Assertions.assertEquals(-1, palette.uniformIndex());
        assertSameEntries(palette, roundTrip(palette));
    }
}
