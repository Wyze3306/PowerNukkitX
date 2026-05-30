package cn.nukkit.level.generator.stages.voidgen;

import cn.nukkit.block.BlockBedrock;
import cn.nukkit.block.BlockState;
import cn.nukkit.level.biome.BiomeID;
import cn.nukkit.level.format.ChunkSection;
import cn.nukkit.level.format.ChunkState;
import cn.nukkit.level.format.IChunk;
import cn.nukkit.level.generator.ChunkGenerateContext;
import cn.nukkit.level.generator.GenerateStage;

public class VoidGenerateStage extends GenerateStage {
    public static final String NAME = "void_generate";

    static final BlockState BEDROCK = BlockBedrock.PROPERTIES.getDefaultState();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void apply(ChunkGenerateContext context) {
        IChunk chunk = context.getChunk();
        final int minSectionY = chunk.getDimensionData().getMinSectionY();
        final int maxSectionY = chunk.getDimensionData().getMaxSectionY();
        chunk.batchProcess(unsafeChunk -> {
            for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                ChunkSection section = unsafeChunk.getOrCreateSection(sectionY);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = 0; y < 16; y++) {
                            section.setBiomeId(x, y, z, BiomeID.PLAINS);
                        }
                    }
                }
            }
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    unsafeChunk.setHeightMap(x, z, 0);
                }
            }
            if (unsafeChunk.getX() == 0 && unsafeChunk.getZ() == 0) {
                unsafeChunk.setBlockState(0, 100, 0, BEDROCK, 0);
                unsafeChunk.setHeightMap(0, 0, 101);
            }
        });
        chunk.setChunkState(ChunkState.POPULATED);
    }
}
