package org.powernukkitx.network.protocol.legacy;

import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.IntUnaryOperator;

/**
 * Rewrites serialized chunk data for 1.26.50 clients: the block state hashes in every sub-chunk
 * palette, and the biome ids in the biome palettes.
 * <p>
 * Working on the bytes rather than on the chunk keeps one path for every way chunk data is
 * produced, cached or anti-xray, whole chunks or single sub-chunks, the server's or a plugin's,
 * and the native bytes stay exactly what they were. Only palette entries are rewritten, everything
 * between them is copied, and nothing is allocated when no entry changes, which is the common case:
 * only chorus plants, fire, redstone wire and the blocks 1.26.60 added have other hashes.
 * <p>
 * Stateless and safe to call from any thread. The data passed in is only read, through a duplicate,
 * so a buffer shared with other sessions keeps its indices.
 */
@ApiStatus.Internal
public final class LegacyChunkTranslator {
    private static final int BLOCKS_PER_SECTION = 4096;
    /** Biome palette header meaning "same as the previous section". */
    private static final int COPY_LAST_HEADER = (0x7F << 1) | 1;
    private static final int TAG_COMPOUND = 10;

    private LegacyChunkTranslator() {
    }

    /**
     * Translates the payload of a level chunk packet: {@code sectionCount} sub-chunks, then
     * {@code biomeCount} biome palettes, then data that is copied as it is.
     * <p>
     * The number of biome palettes is not part of the payload. When the biomes do not parse as
     * {@code biomeCount} palettes followed by the border blocks, they are left as they are rather
     * than guessed at; the sub-chunks are still translated.
     *
     * @return a new buffer the caller owns, or {@code null} when nothing needs to change
     * @throws IllegalArgumentException when the sub-chunks are not in a format this understands
     */
    public static @Nullable ByteBuf translateLevelChunk(@NotNull ByteBuf data, int sectionCount, int biomeCount,
                                                        @NotNull LegacyTables tables) {
        final ByteBuf in = data.duplicate();
        final Edits edits = new Edits();
        try {
            for (int i = 0; i < sectionCount; i++) {
                section(in, edits, tables.blocks()::downgrade);
            }
        } catch (IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Truncated sub-chunk data", e);
        }
        final int blockEdits = edits.size();
        try {
            for (int i = 0; i < biomeCount; i++) {
                storage(in, edits, tables.biomes()::downgrade, true);
            }
            if (!isChunkTail(in)) {
                edits.truncate(blockEdits);
            }
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            edits.truncate(blockEdits);
        }
        return edits.apply(data);
    }

    /**
     * Translates a sub-chunk sent on its own: one serialized section, followed by data that is
     * copied as it is.
     *
     * @return a new buffer the caller owns, or {@code null} when nothing needs to change
     * @throws IllegalArgumentException when the section is not in a format this understands
     */
    public static @Nullable ByteBuf translateSubChunk(@NotNull ByteBuf data, @NotNull LegacyTables tables) {
        final ByteBuf in = data.duplicate();
        final Edits edits = new Edits();
        try {
            section(in, edits, tables.blocks()::downgrade);
        } catch (IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Truncated sub-chunk data", e);
        }
        return edits.apply(data);
    }

    private static void section(ByteBuf in, Edits edits, IntUnaryOperator blocks) {
        final int version = in.readUnsignedByte();
        final int layers = switch (version) {
            case 1 -> 1;
            case 8 -> in.readUnsignedByte();
            case 9 -> {
                final int count = in.readUnsignedByte();
                in.skipBytes(1); // section y
                yield count;
            }
            default -> throw new IllegalArgumentException("Unsupported sub-chunk version " + version);
        };
        for (int i = 0; i < layers; i++) {
            storage(in, edits, blocks, false);
        }
    }

    private static void storage(ByteBuf in, Edits edits, IntUnaryOperator mapper, boolean biomes) {
        final int header = in.readUnsignedByte();
        if (biomes && header == COPY_LAST_HEADER) {
            return;
        }
        if ((header & 1) == 0) {
            throw new IllegalArgumentException("Persistent palette in network data");
        }
        final int bits = header >> 1;
        if (bits == 0) {
            value(in, edits, mapper);
            return;
        }
        in.skipBytes(words(bits) * Integer.BYTES);
        final int size = VarInts.readInt(in);
        if (size <= 0 || size > BLOCKS_PER_SECTION) {
            throw new IllegalArgumentException("Palette size " + size);
        }
        for (int i = 0; i < size; i++) {
            value(in, edits, mapper);
        }
    }

    private static void value(ByteBuf in, Edits edits, IntUnaryOperator mapper) {
        final int start = in.readerIndex();
        final int value = VarInts.readInt(in);
        final int mapped = mapper.applyAsInt(value);
        if (mapped != value) {
            edits.add(start, in.readerIndex(), mapped);
        }
    }

    /**
     * @return how many 32-bit words a palette of {@code bits} bits per block takes; every word holds
     * as many whole entries as fit, so 3, 5 and 6 bits leave padding
     */
    private static int words(int bits) {
        final int perWord = switch (bits) {
            case 1, 2, 3, 4, 5, 6, 8, 16 -> Integer.SIZE / bits;
            default -> throw new IllegalArgumentException("Unsupported palette of " + bits + " bits");
        };
        return (BLOCKS_PER_SECTION + perWord - 1) / perWord;
    }

    /**
     * Whether what follows the biomes is the border block list and, optionally, block entity NBT,
     * which is how a level chunk payload ends.
     */
    private static boolean isChunkTail(ByteBuf in) {
        if (!in.isReadable()) {
            return false;
        }
        final int borderBlocks = in.readUnsignedByte();
        if (in.readableBytes() < borderBlocks) {
            return false;
        }
        in.skipBytes(borderBlocks);
        if (!in.isReadable()) {
            return true;
        }
        final int next = in.getUnsignedByte(in.readerIndex());
        return next == 0 || next == TAG_COMPOUND;
    }

    /**
     * Replacements of varints, by absolute position in the data, in ascending order.
     */
    private static final class Edits {
        private final IntArrayList positions = new IntArrayList();

        void add(int start, int end, int value) {
            this.positions.add(start);
            this.positions.add(end);
            this.positions.add(value);
        }

        int size() {
            return this.positions.size();
        }

        void truncate(int size) {
            this.positions.size(size);
        }

        @Nullable ByteBuf apply(ByteBuf data) {
            if (this.positions.isEmpty()) {
                return null;
            }
            final int edits = this.positions.size() / 3;
            final ByteBuf out = data.alloc().buffer(data.readableBytes() + edits * 5);
            int copied = data.readerIndex();
            for (int i = 0; i < this.positions.size(); i += 3) {
                final int start = this.positions.getInt(i);
                out.writeBytes(data, copied, start - copied);
                VarInts.writeInt(out, this.positions.getInt(i + 2));
                copied = this.positions.getInt(i + 1);
            }
            out.writeBytes(data, copied, data.writerIndex() - copied);
            return out;
        }
    }
}
