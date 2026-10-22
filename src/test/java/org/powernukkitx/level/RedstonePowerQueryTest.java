package org.powernukkitx.level;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.powernukkitx.ServerMockFixture;
import org.powernukkitx.block.Block;
import org.powernukkitx.block.BlockAir;
import org.powernukkitx.block.BlockLever;
import org.powernukkitx.block.BlockStone;
import org.powernukkitx.block.property.enums.LeverDirection;
import org.powernukkitx.math.BlockFace;
import org.powernukkitx.math.Vector3;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedstonePowerQueryTest {
    private static Level level;

    @BeforeAll
    static void boot() {
        ServerMockFixture.boot();
        level = ServerMockFixture.level;
    }

    @Test
    void aSolidBlockConductsStrongPowerFromEverySide() {
        int x = 300;
        for (BlockFace leverSide : BlockFace.values()) {
            final Vector3 stone = new Vector3(x, 90, 300);
            x += 8;
            level.setBlock(stone, new BlockStone(), true, false);

            final BlockLever lever = new BlockLever();
            lever.setLeverOrientation(Arrays.stream(LeverDirection.values())
                .filter(direction -> direction.getFacing() == leverSide)
                .findFirst()
                .orElseThrow());
            lever.setPowerOn(true);
            level.setBlock(stone.getSide(leverSide), lever, true, false);

            for (BlockFace lampSide : BlockFace.values()) {
                if (lampSide == leverSide) {
                    continue;
                }
                final Vector3 lamp = stone.getSide(lampSide);
                assertTrue(level.isBlockPowered(lamp), "lever on the " + leverSide + " side, lamp on the " + lampSide + " side");
                assertEquals(15, level.isBlockIndirectlyGettingPowered(lamp), "lever on the " + leverSide + " side, lamp on the " + lampSide + " side");
            }

            level.setBlock(stone.getSide(leverSide), new BlockAir(), true, false);
            level.setBlock(stone, new BlockAir(), true, false);
        }
    }
}
