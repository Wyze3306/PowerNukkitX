package org.powernukkitx.level.format;

import org.powernukkitx.block.BlockAir;
import org.powernukkitx.block.BlockLiquid;
import org.powernukkitx.block.BlockState;

/**
 * Repairs orphan liquids left in the extra block layer of already saved chunks.
 * <p>
 * Layer 1 is only meant to carry the water of a waterlogged block. When layer 0 ends up
 * empty while a liquid stays behind — a block placed in water and later broken, an
 * explosion, a world edit writing chunk data directly — the Bedrock client renders
 * nothing there yet still treats the position as liquid: invisible water everybody can
 * swim in, and it survives a relog because it lives in the world file.
 * <p>
 * Live block changes are kept consistent by
 * {@link BlockLiquid#normalizeWaterloggedLayer(org.powernukkitx.level.Level, int, int, int)};
 * this pass is what heals worlds that already contain such positions, as their chunks
 * are loaded.
 */
public final class WaterloggingRepair {

    private WaterloggingRepair() {
    }

    /**
     * Moves every orphan layer 1 liquid of the chunk down to layer 0, where it renders.
     *
     * @return the number of positions that were repaired
     */
    public static int repair(IChunk chunk) {
        int repaired = 0;
        for (ChunkSection section : chunk.getSections()) {
            if (section == null || section.blockLayer()[1].isEmpty()) {
                continue;//nothing but air in the extra layer: the overwhelming majority
            }
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = 0; y < 16; y++) {
                        BlockState extra = section.getBlockState(x, y, z, 1);
                        if (extra == BlockAir.STATE
                                || section.getBlockState(x, y, z, 0) != BlockAir.STATE
                                || !(extra.toBlock() instanceof BlockLiquid)) {
                            continue;
                        }
                        section.setBlockState(x, y, z, extra, 0);
                        section.setBlockState(x, y, z, BlockAir.STATE, 1);
                        repaired++;
                    }
                }
            }
        }
        return repaired;
    }
}
