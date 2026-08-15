package org.powernukkitx.entity.mob;

import org.powernukkitx.ServerMockFixture;
import org.powernukkitx.entity.Entity;
import org.powernukkitx.level.Level;
import org.powernukkitx.level.Position;
import org.powernukkitx.math.Vector3;
import org.powernukkitx.nbt.tag.CompoundTag;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.Arrays;

/**
 * A slime that is saved with no health left kills itself while it is still being built, from
 * {@link org.powernukkitx.entity.EntityLiving#initEntity()}. Splitting one there used to hand each
 * child the parent's NBT, health included, so the children died on construction and split in turn -
 * the chunk holding that slime could no longer be loaded at all.
 */
public class SlimeSplitOnLoadTest {

    private static final Vector3 SPAWN = new Vector3(8.5, 80, 8.5);

    static Level level;

    @BeforeAll
    static void boot() {
        ServerMockFixture.boot();
        level = ServerMockFixture.level;
    }

    @Test
    void aSlimeSavedWithNoHealthLeftLoadsWithoutSplittingItself() {
        int before = countSlimes();

        Entity slime = Entity.createEntity(Entity.SLIME, chunkAtSpawn(), deadSlimeNbt());

        // Before the fix this never returned: the recursion blew the stack and the registry
        // reported the entity as impossible to create.
        Assertions.assertNotNull(slime);
        Assertions.assertFalse(slime.isAlive(), "a slime loaded with no health should stay dead");
        Assertions.assertEquals(before + 1, countSlimes(), "loading a dead slime spawned children");

        slime.close();
    }

    @Test
    void aLivingSlimeStillSplitsWhenItIsKilled() {
        CompoundTag nbt = Entity.getDefaultNBT(SPAWN);
        nbt.putInt("SlimeSize", EntitySlime.SIZE_BIG);
        Entity slime = Entity.createEntity(Entity.SLIME, chunkAtSpawn(), nbt);
        Assertions.assertNotNull(slime);
        // Splitting is what a slime does once it is out in the world; only the entity that never
        // got there is held back, which is what justCreated tracks.
        slime.justCreated = false;

        int before = countSlimes();
        slime.kill();

        Assertions.assertTrue(countSlimes() > before, "killing a big slime should split it");
    }

    private static CompoundTag deadSlimeNbt() {
        CompoundTag nbt = Entity.getDefaultNBT(SPAWN);
        nbt.putFloat("Health", 0);
        nbt.putInt("SlimeSize", EntitySlime.SIZE_BIG);
        return nbt;
    }

    private static org.powernukkitx.level.format.IChunk chunkAtSpawn() {
        return new Position(SPAWN.x, SPAWN.y, SPAWN.z, level).getChunk();
    }

    private static int countSlimes() {
        return (int) Arrays.stream(level.getEntities()).filter(EntitySlime.class::isInstance).count();
    }
}
