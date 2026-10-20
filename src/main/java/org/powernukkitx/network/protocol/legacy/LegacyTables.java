package org.powernukkitx.network.protocol.legacy;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * The tables that translate the server's content to what a 1.26.50 (protocol 2193) client knows:
 * block states, items, entities and biomes.
 * <p>
 * Built once from the server's game data and the bundled 1.26.50 data, see {@link LegacyGameData},
 * the first time a 1.26.50 session needs them. They only depend on those two dumps: custom blocks,
 * items and entities are the same in both versions and pass through unchanged, so registering
 * content later never makes them stale. Immutable, so any thread may use them, including level
 * threads ticking in parallel.
 */
@Slf4j
@ApiStatus.Internal
public final class LegacyTables {
    private static volatile LegacyTables instance;

    private final BlockDowngrade blocks;
    private final ItemDowngrade items;
    private final EntityDowngrade entities;
    private final BiomeDowngrade biomes;

    private LegacyTables(BlockDowngrade blocks, ItemDowngrade items, EntityDowngrade entities, BiomeDowngrade biomes) {
        this.blocks = blocks;
        this.items = items;
        this.entities = entities;
        this.biomes = biomes;
    }

    /**
     * @return the tables for the bundled 1.26.50 data, built on first use
     * @throws java.io.UncheckedIOException when the game data cannot be read
     */
    public static @NotNull LegacyTables get() {
        LegacyTables tables = instance;
        if (tables == null) {
            synchronized (LegacyTables.class) {
                tables = instance;
                if (tables == null) {
                    final long start = System.nanoTime();
                    tables = build(LegacyGameData.load(LegacyGameData.NATIVE_ROOT), LegacyGameData.load(LegacyGameData.LEGACY_ROOT));
                    log.debug("Built the 1.26.50 translation tables in {} ms", (System.nanoTime() - start) / 1_000_000);
                    if (tables.blocks.misses() > 0) {
                        log.warn("{} block states have no 1.26.50 counterpart and are sent as another state of their block",
                            tables.blocks.misses());
                    }
                    instance = tables;
                }
            }
        }
        return tables;
    }

    /**
     * Builds the tables that translate the content of {@code nativeData} to {@code legacyData}.
     */
    public static @NotNull LegacyTables build(@NotNull LegacyGameData nativeData, @NotNull LegacyGameData legacyData) {
        final BlockDowngrade blocks = BlockDowngrade.build(nativeData, legacyData);
        return new LegacyTables(blocks, ItemDowngrade.build(nativeData, legacyData, blocks),
            EntityDowngrade.build(nativeData, legacyData), BiomeDowngrade.build(nativeData, legacyData));
    }

    public @NotNull BlockDowngrade blocks() {
        return this.blocks;
    }

    public @NotNull ItemDowngrade items() {
        return this.items;
    }

    public @NotNull EntityDowngrade entities() {
        return this.entities;
    }

    public @NotNull BiomeDowngrade biomes() {
        return this.biomes;
    }
}
