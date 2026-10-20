package org.powernukkitx.network.protocol.legacy;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;

/**
 * Translates biome ids for 1.26.50 clients. The biomes 1.26.60 added are shown as the closest one
 * 1.26.50 has, see {@link #FALLBACKS}; all others have the same id in both versions. Immutable and
 * safe to share between threads.
 */
@ApiStatus.Internal
public final class BiomeDowngrade {
    /**
     * Biomes 1.26.60 added, with the 1.26.50 biome they are shown as.
     */
    static final Map<String, String> FALLBACKS = Map.of(
        "minecraft:ice_caves", "minecraft:dripstone_caves"
    );
    private static final String DEFAULT = "minecraft:plains";

    private final Int2IntMap downgrades;
    private final Set<String> legacyNames;
    private final Set<String> nativeNames;

    private BiomeDowngrade(Int2IntMap downgrades, Set<String> legacyNames, Set<String> nativeNames) {
        this.downgrades = downgrades;
        this.legacyNames = legacyNames;
        this.nativeNames = nativeNames;
    }

    static @NotNull BiomeDowngrade build(@NotNull LegacyGameData nativeData, @NotNull LegacyGameData legacyData) {
        final Object2IntMap<String> legacyIds = legacyData.biomeIds();
        final Int2IntOpenHashMap downgrades = new Int2IntOpenHashMap();
        for (Object2IntMap.Entry<String> entry : nativeData.biomeIds().object2IntEntrySet()) {
            final String name = legacyIds.containsKey(entry.getKey()) ? entry.getKey()
                : FALLBACKS.getOrDefault(entry.getKey(), DEFAULT);
            final int legacyId = legacyIds.getOrDefault(name, legacyIds.getInt(DEFAULT));
            if (legacyId != entry.getIntValue()) {
                downgrades.put(entry.getIntValue(), legacyId);
            }
        }
        downgrades.trim();
        return new BiomeDowngrade(downgrades, Set.copyOf(legacyIds.keySet()), Set.copyOf(nativeData.biomeIds().keySet()));
    }

    /**
     * @return the id a 1.26.50 client knows the biome {@code nativeId} as; the id itself for every
     * biome 1.26.50 has and for ids no list has
     */
    public int downgrade(int nativeId) {
        return this.downgrades.getOrDefault(nativeId, nativeId);
    }

    /**
     * @return whether {@link #downgrade(int)} changes {@code nativeId}
     */
    public boolean isChanged(int nativeId) {
        return this.downgrades.containsKey(nativeId);
    }

    /**
     * @return whether a 1.26.50 client knows the biome {@code name}, such as
     * {@code minecraft:plains}; names that are no vanilla biome, such as biome tags, count as known
     */
    public boolean isKnown(@NotNull String name) {
        return this.legacyNames.contains(name) || !this.nativeNames.contains(name);
    }
}
