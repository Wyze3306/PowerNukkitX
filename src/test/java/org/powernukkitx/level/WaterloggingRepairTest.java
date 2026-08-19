package org.powernukkitx.level;

import org.powernukkitx.ServerMockFixture;
import org.powernukkitx.block.Block;
import org.powernukkitx.block.BlockAir;
import org.powernukkitx.block.BlockID;
import org.powernukkitx.block.BlockLiquid;
import org.powernukkitx.level.format.IChunk;
import org.powernukkitx.level.format.WaterloggingRepair;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A liquid left alone in layer 1 is not drawn by the Bedrock client but is still swum
 * in, so the two layers must never be allowed to drift apart - not even with liquid
 * flow turned off, which is exactly when the old code stopped looking.
 */
public class WaterloggingRepairTest {

    static Level level;
    static boolean liquidFlowBefore;

    @BeforeAll
    static void boot() {
        ServerMockFixture.boot();
        level = ServerMockFixture.level;
        liquidFlowBefore = level.getGameplaySettings().enableLiquidFlow();
        level.getGameplaySettings().enableLiquidFlow(false);
    }

    @AfterAll
    static void restore() {
        level.getGameplaySettings().enableLiquidFlow(liquidFlowBefore);
    }

    @Test
    void orphanLiquidMovesBackToLayer0() {
        int x = 1000, y = 70, z = 1000;
        level.setBlock(x, y, z, 0, Block.get(BlockID.AIR), false, false);
        level.setBlock(x, y, z, 1, Block.get(BlockID.WATER), false, false);

        Assertions.assertTrue(BlockLiquid.normalizeWaterloggedLayer(level, x, y, z));
        Assertions.assertInstanceOf(BlockLiquid.class, level.getBlock(x, y, z, 0));
        Assertions.assertTrue(level.getBlock(x, y, z, 1).isAir());
    }

    @Test
    void liquidUnderABlockThatCannotBeWaterloggedIsDropped() {
        int x = 1002, y = 70, z = 1000;
        level.setBlock(x, y, z, 0, Block.get(BlockID.STONE), false, false);
        level.setBlock(x, y, z, 1, Block.get(BlockID.WATER), false, false);

        Assertions.assertTrue(BlockLiquid.normalizeWaterloggedLayer(level, x, y, z));
        Assertions.assertTrue(level.getBlock(x, y, z, 1).isAir());
    }

    @Test
    void clearingLayer0WithoutBlockUpdatesCannotStrandTheLiquid() {
        int x = 1004, y = 70, z = 1000;
        level.setBlock(x, y, z, 0, Block.get(BlockID.STONE), false, false);
        level.setBlock(x, y, z, 1, Block.get(BlockID.WATER), false, false);

        // update = false is what explosions and direct plugin writes use: nothing else
        // will come along and fix the position afterwards.
        level.setBlock(x, y, z, 0, Block.get(BlockID.AIR), false, false);

        Assertions.assertInstanceOf(BlockLiquid.class, level.getBlock(x, y, z, 0));
        Assertions.assertTrue(level.getBlock(x, y, z, 1).isAir());
    }

    @Test
    void makingRoomForABlockAboutToBePlacedIsNotUndone() {
        int x = 1006, y = 70, z = 1000;
        // What Level#setBlockAtPos does before handing over to Block#place: the water
        // is parked in layer 1 so the new block can take layer 0 and end up waterlogged.
        level.setBlock(x, y, z, 0, Block.get(BlockID.WATER), false, false);
        level.setBlock(x, y, z, 1, Block.get(BlockID.WATER), false, false);
        level.setBlock(x, y, z, 0, Block.get(BlockID.AIR), false, false);

        Assertions.assertInstanceOf(BlockLiquid.class, level.getBlock(x, y, z, 1));

        level.setBlock(x, y, z, 0, Block.get(BlockID.OAK_SLAB), false, false);
        Assertions.assertInstanceOf(BlockLiquid.class, level.getBlock(x, y, z, 1));
    }

    @Test
    void ghostsAlreadySavedInAChunkAreRepaired() {
        int x = 1024, y = 70, z = 1024;
        IChunk chunk = level.getChunk(x >> 4, z >> 4, true);
        // Written straight into the chunk, the way a world edit or an older build left it.
        chunk.setBlockState(x & 15, y, z & 15, BlockAir.STATE, 0);
        chunk.setBlockState(x & 15, y, z & 15, Block.get(BlockID.WATER).getBlockState(), 1);

        Assertions.assertTrue(WaterloggingRepair.repair(chunk) >= 1);
        Assertions.assertInstanceOf(BlockLiquid.class, level.getBlock(x, y, z, 0));
        Assertions.assertTrue(level.getBlock(x, y, z, 1).isAir());
    }

    @Test
    void legitimateWaterloggingIsLeftAlone() {
        int x = 1026, y = 70, z = 1024;
        level.setBlock(x, y, z, 0, Block.get(BlockID.OAK_FENCE), false, false);
        level.setBlock(x, y, z, 1, Block.get(BlockID.WATER), false, false);

        Assertions.assertFalse(BlockLiquid.normalizeWaterloggedLayer(level, x, y, z));
        Assertions.assertInstanceOf(BlockLiquid.class, level.getBlock(x, y, z, 1));
    }
}
