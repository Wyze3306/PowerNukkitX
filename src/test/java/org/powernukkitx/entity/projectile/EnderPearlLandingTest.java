package org.powernukkitx.entity.projectile;

import org.powernukkitx.PlayerFixture;
import org.powernukkitx.TestPlayer;
import org.powernukkitx.block.Block;
import org.powernukkitx.entity.Entity;
import org.powernukkitx.level.Level;
import org.powernukkitx.level.Position;
import org.powernukkitx.math.AxisAlignedBB;
import org.powernukkitx.math.SimpleAxisAlignedBB;
import org.powernukkitx.math.Vector3;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Where an ender pearl puts its owner down. A pearl is a quarter of a block wide and comes to rest
 * flush against what it hits, so the point it stops at is regularly one a player does not fit in;
 * the landing has to be resolved to a spot that holds them without straying from where it fell.
 */
public class EnderPearlLandingTest {

    private static final int FLOOR_Y = 79;
    private static final double FEET_Y = FLOOR_Y + 1;
    private static final int WALL_X = 25;
    private static final int LOW_CEILING_Z = 19;

    static TestPlayer player;
    static Level level;
    static EntityEnderPearl pearl;

    @BeforeAll
    static void boot() {
        player = PlayerFixture.get();
        level = player.getLevel();
        player.setPosition(new Vector3(21.5, FEET_Y, 21.5));
        // The fixture player is never spawned into a world, so its box is only sized once it has
        // been given a position - and a zero sized one fits anywhere, which would make every
        // assertion below pass without proving a thing.
        AxisAlignedBB box = player.getBoundingBox();
        Assertions.assertEquals(0.6, box.getMaxX() - box.getMinX(), 1e-6);

        buildTerrain();

        pearl = (EntityEnderPearl) Entity.createEntity(
            Entity.ENDER_PEARL, new Position(21.5, FEET_Y + 2, 21.5, level), player);
        Assertions.assertNotNull(pearl);
    }

    private static void buildTerrain() {
        for (int x = 19; x <= 31; x++) {
            for (int z = 19; z <= 23; z++) {
                for (int y = FLOOR_Y; y <= FLOOR_Y + 5; y++) {
                    level.setBlock(new Vector3(x, y, z), Block.get(y == FLOOR_Y ? Block.STONE : Block.AIR));
                }
            }
        }
        // A wall across the strip: a pearl stopping against it, or landing on the floor along it,
        // leaves the player's own box hanging inside it.
        for (int z = 19; z <= 23; z++) {
            for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 3; y++) {
                level.setBlock(new Vector3(WALL_X, y, z), Block.get(Block.STONE));
            }
        }
        // A ceiling over one lane only, so a spot part way up the wall there has 1.6 of headroom:
        // enough for a crouched player, not for a standing one.
        for (int x = 19; x <= 26; x++) {
            level.setBlock(new Vector3(x, FLOOR_Y + 3, LOW_CEILING_Z), Block.get(Block.STONE));
        }
        // Solid further around than the search reaches: nothing in here can hold a player.
        for (int x = 27; x <= 31; x++) {
            for (int z = 19; z <= 23; z++) {
                for (int y = FLOOR_Y; y <= FLOOR_Y + 4; y++) {
                    level.setBlock(new Vector3(x, y, z), Block.get(Block.STONE));
                }
            }
        }
    }

    @Test
    void aPearlWithRoomWhereItStoppedLandsThePlayerExactlyThere() {
        Position impact = new Position(21.7, FEET_Y, 21.3, level);

        Position landing = pearl.findLandingPosition(impact, new Position(21.7, FEET_Y + 1, 21.3, level));

        assertLandsAt(impact, landing);
        assertHoldsThePlayer(landing);
    }

    @Test
    void aPearlStoppingAgainstAWallDoesNotLandThePlayerInsideIt() {
        Position impact = new Position(WALL_X - 0.1, FEET_Y + 1.4, 21.5, level);

        Position landing = pearl.findLandingPosition(impact, new Position(WALL_X - 1.3, FEET_Y + 1.4, 21.5, level));

        assertHoldsThePlayer(landing);
        Assertions.assertTrue(landing.x < impact.x, "landing at " + landing + " was not pulled back from the wall");
    }

    /**
     * The corner case: the pearl lands on top of a block, but hard against the wall next to it, so
     * the player standing on that spot would have their box inside the wall.
     */
    @Test
    void aPearlLandingOnTheFloorInACornerPutsThePlayerBesideTheWall() {
        Position impact = new Position(WALL_X - 0.1, FEET_Y, 21.5, level);

        Position landing = pearl.findLandingPosition(impact, new Position(WALL_X - 0.9, FEET_Y + 0.8, 21.5, level));

        assertHoldsThePlayer(landing);
        Assertions.assertEquals(FEET_Y, landing.y, 1e-6, "the player should still be put down on the floor");
    }

    /**
     * Throwing while crouched is the ordinary way to pearl at a wall from a ledge. The landing has
     * to hold the player standing anyway - they let go of the key the moment they arrive.
     */
    @Test
    void aPearlThrownCrouchedDoesNotUseAGapOnlyACrouchedPlayerFitsIn() {
        Position impact = new Position(WALL_X - 0.1, FEET_Y + 0.4, LOW_CEILING_Z + 0.5, level);
        player.setSneaking(true);
        try {
            Position landing = pearl.findLandingPosition(
                impact, new Position(WALL_X - 1.5, FEET_Y + 0.4, LOW_CEILING_Z + 0.5, level));

            assertHoldsThePlayer(landing);
        } finally {
            player.setSneaking(false);
        }
    }

    @Test
    void aPearlWithNowhereToPutThePlayerFallsBackToWhereItCameFrom() {
        Position impact = new Position(29.5, FLOOR_Y + 2, 21.5, level);
        Position beforeImpact = new Position(29.5, FLOOR_Y + 2, 20.5, level);

        assertLandsAt(beforeImpact, pearl.findLandingPosition(impact, beforeImpact));
    }

    private static void assertLandsAt(Position expected, Position landing) {
        Assertions.assertEquals(expected.x, landing.x, 1e-6);
        Assertions.assertEquals(expected.y, landing.y, 1e-6);
        Assertions.assertEquals(expected.z, landing.z, 1e-6);
    }

    /**
     * Measured standing, whatever pose the thrower was in: a landing that only holds a crouched
     * player is one they stand up out of and into the block.
     */
    private static void assertHoldsThePlayer(Position landing) {
        double halfWidth = player.getWidth() / 2;
        double height = player.getHeight();

        Assertions.assertFalse(level.hasCollision(player, new SimpleAxisAlignedBB(
            landing.x - halfWidth, landing.y, landing.z - halfWidth,
            landing.x + halfWidth, landing.y + height, landing.z + halfWidth
        ), false), "landing at " + landing + " leaves the player inside a block");
    }
}
