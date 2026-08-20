package org.powernukkitx.entity.projectile;

import org.powernukkitx.Player;
import org.powernukkitx.block.Block;
import org.powernukkitx.entity.Entity;
import org.powernukkitx.event.entity.EntityDamageByEntityEvent;
import org.powernukkitx.event.entity.EntityDamageEvent;
import org.powernukkitx.event.player.PlayerTeleportEvent.TeleportCause;
import org.powernukkitx.level.Position;
import org.powernukkitx.level.format.IChunk;
import org.powernukkitx.math.Vector3;
import org.powernukkitx.nbt.tag.CompoundTag;
import org.powernukkitx.nbt.tag.DoubleTag;
import org.powernukkitx.nbt.tag.FloatTag;
import org.powernukkitx.nbt.tag.ListTag;
import org.cloudburstmc.protocol.bedrock.data.LevelEvent;
import org.jetbrains.annotations.NotNull;

public class EntityEnderPearl extends EntityProjectile {

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
        if (!this.isCollided && !wasInWater && this.isTouchingWater()) {
            this.motionX = 0;
            this.motionY = 0;
            this.motionZ = 0;
            this.isCollided = true;
            this.hadCollision = true;
        }

        if (this.isCollided && this.shootingEntity instanceof Player) {
            boolean portal = false;
            for (Block collided : this.getCollisionBlocks()) {
                if (collided.getId().equals(Block.PORTAL)) {
                    portal = true;
                }
            }
            if (!portal) {
                teleportOwner(oldPosition);
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
            teleportOwner(getPosition());
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
