package org.powernukkitx.entity.projectile;

import org.powernukkitx.Player;
import org.powernukkitx.block.Block;
import org.powernukkitx.block.BlockID;
import org.powernukkitx.entity.Entity;
import org.powernukkitx.event.entity.EntityDamageByEntityEvent;
import org.powernukkitx.event.entity.EntityDamageEvent;
import org.powernukkitx.event.player.PlayerTeleportEvent.TeleportCause;
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

public class EntityEnderPearl extends EntityProjectile {

    /**
     * Overlap tolerated before a landing counts as taken. Brushing an edge is not being in a wall;
     * sinking a fifth of a block into one, as a pearl-width landing against a corner does, is.
     */
    private static final double WALL_CONTACT_MARGIN = 0.1;

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

    /**
     * Drag applied before gravity, on the three axes. The inherited version leaves it off Y, so a
     * pearl keeps accelerating downwards and lands short of where it was aimed.
     */
    @Override
    protected void updateMotion() {
        double friction = 1 - getDrag();
        this.motionX *= friction;
        this.motionY = this.motionY * friction - getGravity();
        this.motionZ *= friction;
    }

    @Override
    public boolean onUpdate(int currentTick) {
        if (this.closed) {
            return false;
        }
        Position oldPosition = getPosition();
        boolean wasInWater = this.isTouchingWater();
        boolean hasUpdate = super.onUpdate(currentTick);

        // Water has no collision box, so a pearl that reaches a lake sinks to the bottom and lands
        // the thrower there. Stopping it on contact puts them on the surface instead, where the
        // pearl was seen to touch down. Only entering the water counts: a pearl thrown while
        // swimming starts inside it and would otherwise stop before leaving the thrower.
        boolean stoppedByWater = false;
        if (!this.isCollided && !wasInWater && this.isTouchingWater()) {
            this.motionX = 0;
            this.motionY = 0;
            this.motionZ = 0;
            this.isCollided = true;
            this.hadCollision = true;
            stoppedByWater = true;
        }

        if (this.isCollided && this.shootingEntity instanceof Player thrower) {
            boolean portal = false;
            for (Block collided : this.getCollisionBlocks()) {
                if (collided.getId().equals(Block.PORTAL)) {
                    portal = true;
                }
            }
            if (!portal) {
                Position impact = getPosition();
                boolean intoBlock = !stoppedByWater
                    && isWedgedUnderBlock(thrower)
                    && !holdsTheMapTogether(thrower, impact);
                teleportOwner(intoBlock ? impact : clearOfWalls(thrower, oldPosition));
            }
        }

        if (this.age > 1200 || this.isCollided) {
            this.close();
            hasUpdate = true;
        }

        return hasUpdate;
    }

    /**
     * The ordinary landing, kept clear of the walls around it.
     * <p>
     * A pearl is a quarter of a block wide and a player is more than twice that, so the point the
     * pearl held a tick earlier is not always one the thrower fits in: a pearl sent up along the
     * seam of a corner leaves it hard against both walls, and a player put down there straddles
     * them. The client resolves that overlap by pushing them inside, which hands out the block
     * entry that is supposed to be reserved to a thrower wedged under a block.
     * <p>
     * Centring them in the column they land in is the whole correction, and it only ever runs when
     * the spot is genuinely taken - a landing with room around it is returned untouched, so a
     * normal throw still puts the thrower exactly where the pearl was.
     *
     * @param thrower the player the pearl belongs to
     * @param landing where the pearl would ordinarily put them down
     * @return that same point, or the middle of its column when it is against a wall
     */
    private Vector3 clearOfWalls(Player thrower, Position landing) {
        if (!overlapsBlocks(thrower, landing)) {
            return landing;
        }

        final Vector3 centred = new Vector3(
            NukkitMath.floorDouble(landing.x) + 0.5,
            landing.y,
            NukkitMath.floorDouble(landing.z) + 0.5);
        return overlapsBlocks(thrower, centred) ? landing : centred;
    }

    /**
     * Whether the thrower, standing at that point, runs into the blocks around it. The margin
     * keeps a box that merely brushes an edge out of it: only a real overlap counts, the kind that
     * leaves a player straddling a wall.
     */
    private boolean overlapsBlocks(Player thrower, Vector3 at) {
        final float scale = thrower.getScale();
        final double halfWidth = thrower.getWidth() * scale / 2 - WALL_CONTACT_MARGIN;
        final double height = thrower.getHeight() * scale - WALL_CONTACT_MARGIN;
        if (halfWidth <= 0 || height <= 0) {
            return false;
        }

        return this.level.hasCollision(thrower, new SimpleAxisAlignedBB(
            at.x - halfWidth, at.y + WALL_CONTACT_MARGIN, at.z - halfWidth,
            at.x + halfWidth, at.y + height, at.z + halfWidth), false);
    }

    /**
     * Whether the thrower is wedged in a corner with a block right over their head.
     * <p>
     * That posture is the one case where landing them on the impact point is allowed instead of
     * where the pearl was a tick earlier. The impact point sits flush against the surface the
     * pearl stopped on, and a player is more than twice as wide as a pearl, so they end up
     * straddling it - which is how one gets inside a block. Everywhere else the pearl lands them
     * short of what it hit and nothing can be crossed.
     * <p>
     * A corner means blocked on both horizontal axes at head height, not a flat wall and not a
     * corridor, so the shot has to be set up rather than stumbled into.
     *
     * @param thrower the player the pearl belongs to
     * @return true if the thrower stands in such a nook
     */
    private boolean isWedgedUnderBlock(Player thrower) {
        final int x = thrower.getFloorX();
        final int y = thrower.getFloorY();
        final int z = thrower.getFloorZ();

        if (isPassable(x, y + 2, z)) {
            return false;
        }

        final int head = y + 1;
        final boolean alongZ = !isPassable(x, head, z - 1) || !isPassable(x, head, z + 1);
        final boolean alongX = !isPassable(x - 1, head, z) || !isPassable(x + 1, head, z);
        return alongZ && alongX;
    }

    /**
     * Whether the box the thrower would occupy at that point runs into a block that is never
     * enterable, whatever the posture that earned them the exception. Bedrock and barriers are what
     * a map is closed with: getting inside one is getting out of the map, so the throw falls back
     * to the ordinary landing short of what the pearl hit.
     *
     * @param thrower the player the pearl belongs to
     * @param landing where they would be put down
     * @return true if that spot is held by bedrock, invisible bedrock, a barrier or a border block
     */
    private boolean holdsTheMapTogether(Player thrower, Vector3 landing) {
        final float scale = thrower.getScale();
        final double halfWidth = thrower.getWidth() * scale / 2;
        final double height = thrower.getHeight() * scale;

        final int minX = NukkitMath.floorDouble(landing.x - halfWidth);
        final int maxX = NukkitMath.floorDouble(landing.x + halfWidth);
        final int minY = NukkitMath.floorDouble(landing.y);
        final int maxY = NukkitMath.floorDouble(landing.y + height);
        final int minZ = NukkitMath.floorDouble(landing.z - halfWidth);
        final int maxZ = NukkitMath.floorDouble(landing.z + halfWidth);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    final Block block = this.level.getBlock(x, y, z, false);
                    if (block == null) {
                        continue;
                    }
                    final String id = block.getId();
                    if (BlockID.BEDROCK.equals(id)
                        || BlockID.INVISIBLE_BEDROCK.equals(id)
                        || BlockID.BARRIER.equals(id)
                        || BlockID.BORDER_BLOCK.equals(id)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isPassable(int x, int y, int z) {
        final Block block = this.level.getBlock(x, y, z, false);
        return block == null || block.canPassThrough();
    }

    @Override
    public void onCollideWithEntity(Entity entity) {
        if (this.shootingEntity instanceof Player thrower) {
            teleportOwner(clearOfWalls(thrower, getPosition()));
        }
        super.onCollideWithEntity(entity);
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
            // No endermite on landing: a mob spawning one throw in twenty, wherever the player
            // happens to arrive, is not something a server wants dropped into its map.
        }
    }

    @Override
    public String getOriginalName() {
        return "Ender Pearl";
    }
}
