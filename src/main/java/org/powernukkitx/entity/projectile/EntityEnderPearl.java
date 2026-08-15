package org.powernukkitx.entity.projectile;

import org.powernukkitx.Player;
import org.powernukkitx.block.Block;
import org.powernukkitx.entity.Entity;
import org.powernukkitx.entity.mob.EntityEndermite;
import org.powernukkitx.event.entity.EntityDamageByEntityEvent;
import org.powernukkitx.event.entity.EntityDamageEvent;
import org.powernukkitx.event.player.PlayerTeleportEvent.TeleportCause;
import org.powernukkitx.level.GameRule;
import org.powernukkitx.level.Position;
import org.powernukkitx.level.format.IChunk;
import org.powernukkitx.math.NukkitMath;
import org.powernukkitx.math.SimpleAxisAlignedBB;
import org.powernukkitx.math.Vector3;
import org.powernukkitx.nbt.tag.CompoundTag;
import org.powernukkitx.nbt.tag.DoubleTag;
import org.powernukkitx.nbt.tag.FloatTag;
import org.powernukkitx.nbt.tag.ListTag;
import org.cloudburstmc.protocol.bedrock.data.LevelEvent;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ThreadLocalRandom;

public class EntityEnderPearl extends EntityProjectile {

    /**
     * How far back along its last step the pearl is followed when the player does not fit where it
     * stopped, as a fraction of that step. The pearl flew through there, so it is clear of the
     * block it hit, and it is the side the player is coming from.
     */
    private static final double[] BACKTRACK_FRACTIONS = {0.34, 0.67, 1};
    /**
     * Block columns probed around the impact once its own flight path is exhausted, nearest first
     * so the player is still put down as close as possible to where the pearl was seen to land.
     */
    private static final int[][] NEARBY_COLUMNS = {
        {0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };
    private static final int[] NEARBY_LAYERS = {0, 1, -1};
    /**
     * Overlap tolerated when probing a landing spot. Touching a block is not a collision to begin
     * with, so this only absorbs the rounding of a position that comes out of clipping a move
     * against a surface: two millimetres inside a block is nothing the collision code cannot undo.
     */
    private static final double CONTACT_TOLERANCE = 0.002;

    @Override
    @NotNull
    public String getIdentifier() {
        return ENDER_PEARL;
    }

    public EntityEnderPearl(IChunk chunk, CompoundTag nbt) {
        this(chunk, nbt, null);
    }

    public EntityEnderPearl(IChunk chunk, CompoundTag nbt, Entity shootingEntity) {
        super(chunk, nbt, shootingEntity);
    }


    @Override
    public float getWidth() {
        return 0.25f;
    }

    @Override
    public float getLength() {
        return 0.25f;
    }

    @Override
    public float getHeight() {
        return 0.25f;
    }

    @Override
    protected float getDefaultGravity() {
        return 0.03f;
    }

    @Override
    protected float getDrag() {
        return 0.01f;
    }

    @Override
    public boolean onUpdate(int currentTick) {
        if (this.closed) {
            return false;
        }
        Position oldPosition = getPosition();
        boolean hasUpdate = super.onUpdate(currentTick);

        if (this.isCollided && this.shootingEntity instanceof Player) {
            boolean portal = false;
            for (Block collided : this.getCollisionBlocks()) {
                if (collided.getId().equals(Block.PORTAL)) {
                    portal = true;
                }
            }
            if (!portal) {
                teleportOwner(findLandingPosition(getPosition(), oldPosition));
            }
        }

        if (this.age > 1200 || this.isCollided) {
            this.close();
            hasUpdate = true;
        }

        return hasUpdate;
    }

    @Override
    public void onCollideWithEntity(Entity entity) {
        if (this.shootingEntity instanceof Player) {
            final Position impact = getPosition();
            teleportOwner(findLandingPosition(impact, impact));
        }
        super.onCollideWithEntity(entity);
    }

    /**
     * Picks where the owner is put down by the pearl that just landed.
     * <p>
     * A pearl is a quarter of a block wide and comes to rest flush against whatever it hits, so
     * the point it stops at is regularly one the player does not fit in: against a wall it sits a
     * fifth of a block inside it, and on the rim of a block the player's box hangs over the
     * neighbouring column. Landing them there suffocates them or hands them to the collision code,
     * which shoves them back out - the sideways hop bystanders see just after the teleport. So the
     * impact point is only used when the player's own box fits there, and the search otherwise
     * widens to the closest spot that works.
     *
     * @param impact       where the pearl came to rest
     * @param beforeImpact where it was a tick earlier, the last point of its flight that is known
     *                     to be clear of what it hit
     * @return the position to teleport the owner to
     */
    protected Position findLandingPosition(Position impact, Position beforeImpact) {
        if (fitsAt(impact.x, impact.y, impact.z)) {
            return impact;
        }

        // Centring the column is what unhooks a pearl that stopped on the rim of a block or in a
        // corner: the block it hit is one it flew over, so the player fits above the middle of it.
        final double columnX = NukkitMath.floorDouble(impact.x) + 0.5;
        final double columnZ = NukkitMath.floorDouble(impact.z) + 0.5;
        if (fitsAt(columnX, impact.y, columnZ)) {
            return new Position(columnX, impact.y, columnZ, this.level);
        }

        for (double fraction : BACKTRACK_FRACTIONS) {
            final double x = impact.x + (beforeImpact.x - impact.x) * fraction;
            final double y = impact.y + (beforeImpact.y - impact.y) * fraction;
            final double z = impact.z + (beforeImpact.z - impact.z) * fraction;
            if (fitsAt(x, y, z)) {
                return new Position(x, y, z, this.level);
            }
        }

        final int impactY = NukkitMath.floorDouble(impact.y);
        for (int layer : NEARBY_LAYERS) {
            for (int[] column : NEARBY_COLUMNS) {
                final double x = columnX + column[0];
                final double y = impactY + layer;
                final double z = columnZ + column[1];
                if (fitsAt(x, y, z)) {
                    return new Position(x, y, z, this.level);
                }
            }
        }

        // Walled in on every side within reach: the point the pearl was at a tick earlier is the
        // best guess left, and it is where the owner used to be sent unconditionally.
        return beforeImpact;
    }

    /**
     * Whether the owner, put down feet first at the given position, is clear of the blocks around
     * it. Measured on the box they stand in, not the one they are in right now: a crouched player
     * fits under an overhang they cannot stand up in, and they do stand up.
     */
    private boolean fitsAt(double x, double y, double z) {
        final Entity owner = this.shootingEntity;
        final float scale = owner.getScale();
        final double halfWidth = owner.getWidth() * scale / 2 - CONTACT_TOLERANCE;
        final double height = owner.getHeight() * scale - CONTACT_TOLERANCE;

        return !this.level.hasCollision(this.shootingEntity, new SimpleAxisAlignedBB(
            x - halfWidth, y + CONTACT_TOLERANCE, z - halfWidth,
            x + halfWidth, y + height, z + halfWidth
        ), false);
    }

    private void teleportOwner(Vector3 destination) {
        if (!this.level.equals(this.shootingEntity.getLevel())) {
            return;
        }

        this.level.addLevelEvent(this.shootingEntity.add(0.5, 0.5, 0.5), LevelEvent.SOUND_TELEPORT_ENDERPEARL);
        if(this.shootingEntity.teleport(destination, TeleportCause.ENDER_PEARL)) {
            if ((((Player) this.shootingEntity).getGamemode() & 0x01) == 0) {
                this.shootingEntity.attack(new EntityDamageByEntityEvent(this, shootingEntity, EntityDamageEvent.DamageCause.PROJECTILE, 5f, 0f));
            }
            this.level.addLevelEvent(this, LevelEvent.PARTICLE_TELEPORT);
            this.level.addLevelEvent(this.shootingEntity.add(0.5, 0.5, 0.5), LevelEvent.SOUND_TELEPORT_ENDERPEARL);
            if (this.level.getGameRules().getBoolean(GameRule.DO_MOB_SPAWNING)) {
                if (ThreadLocalRandom.current().nextInt(1, 20) == 1) {
                    EntityEndermite endermite = (EntityEndermite) Entity.createEntity(Entity.ENDERMITE,
                        this.getChunk(), Entity.getDefaultNBT(destination)
                    );
                    endermite.spawnToAll();
                }
            }
        }
    }

    @Override
    public String getOriginalName() {
        return "Ender Pearl";
    }
}
