package org.powernukkitx.network.protocol.legacy;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Translates entity identifiers for 1.26.50 clients. The entities 1.26.60 added are shown as the
 * closest one 1.26.50 has, see {@link #FALLBACKS}; every other identifier, custom ones included, is
 * the same in both versions. Immutable and safe to share between threads.
 */
@ApiStatus.Internal
public final class EntityDowngrade {
    /**
     * Entities 1.26.60 added, with the 1.26.50 entity they are shown as.
     */
    static final Map<String, String> FALLBACKS = Map.of(
        "minecraft:frostbite", "minecraft:stray",
        "minecraft:ice_ball", "minecraft:snowball"
    );

    private final Set<String> added;

    private EntityDowngrade(Set<String> added) {
        this.added = added;
    }

    static @NotNull EntityDowngrade build(@NotNull LegacyGameData nativeData, @NotNull LegacyGameData legacyData) {
        final Set<String> added = new HashSet<>(nativeData.entityIds());
        added.removeAll(legacyData.entityIds());
        return new EntityDowngrade(Set.copyOf(added));
    }

    /**
     * @return the identifier a 1.26.50 client is sent for {@code identifier}
     */
    public @NotNull String downgrade(@NotNull String identifier) {
        return this.added.contains(identifier) ? FALLBACKS.getOrDefault(identifier, identifier) : identifier;
    }

    /**
     * @return whether 1.26.60 added the entity {@code identifier}, so 1.26.50 does not know it
     */
    public boolean isAdded(@NotNull String identifier) {
        return this.added.contains(identifier);
    }

    /**
     * @return the vanilla entities 1.26.50 does not know
     */
    public @NotNull Set<String> added() {
        return this.added;
    }
}
