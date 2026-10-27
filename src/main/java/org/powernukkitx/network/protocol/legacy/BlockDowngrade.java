package org.powernukkitx.network.protocol.legacy;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.powernukkitx.block.BlockState;
import org.powernukkitx.level.format.palette.RuntimeDataSerializer;
import org.powernukkitx.level.updater.block.BlockStateUpdater_1_26_60;
import org.powernukkitx.utils.HashUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Translates block state hashes between the server's palette and the 1.26.50 one.
 * <p>
 * A hash is computed from the block name and its properties, so the two palettes share every state
 * that did not change, and those map to themselves. That covers custom blocks too, which neither
 * palette lists. The states 1.26.60 added, or changed by giving a block new properties, are sent
 * as the 1.26.50 state left after dropping what that version does not know, with the new blocks
 * swapped for the closest old one, see {@link #RENAMED}.
 * <p>
 * Downgrading is idempotent: a hash that comes out of {@link #downgrade(int)} maps to itself.
 * Immutable and safe to share between threads.
 */
@ApiStatus.Internal
public final class BlockDowngrade {
    /**
     * Blocks 1.26.60 added, with the 1.26.50 block they are shown as.
     */
    static final Map<String, String> RENAMED = Map.of(
        "minecraft:icicle", "minecraft:pointed_dripstone",
        "minecraft:ice_crystal", "minecraft:amethyst_cluster",
        "minecraft:disc_press", "minecraft:stonecutter_block"
    );
    private static final String AIR = "minecraft:air";

    private final Int2IntMap downgrades;
    private final Int2IntMap upgrades;
    private final int airHash;
    private final int misses;
    private final RuntimeDataSerializer<BlockState> serializer = state -> this.downgrade(state.blockStateHash());

    private BlockDowngrade(Int2IntMap downgrades, Int2IntMap upgrades, int airHash, int misses) {
        this.downgrades = downgrades;
        this.upgrades = upgrades;
        this.airHash = airHash;
        this.misses = misses;
    }

    static @NotNull BlockDowngrade build(@NotNull LegacyGameData nativeData, @NotNull LegacyGameData legacyData) {
        final IntSet nativeHashes = hashes(nativeData);
        final IntSet legacyHashes = hashes(legacyData);
        final Map<String, Set<String>> legacySchema = schema(legacyData);
        final Map<String, Integer> legacyFirstState = firstStates(legacyData);
        final Map<String, Integer> nativeFirstState = firstStates(nativeData);

        final Int2IntOpenHashMap downgrades = new Int2IntOpenHashMap();
        int misses = 0;
        for (NbtMap block : nativeData.blocks()) {
            final int hash = block.getInt("network_id");
            if (legacyHashes.contains(hash)) {
                continue;
            }
            final String name = RENAMED.getOrDefault(block.getString("name"), block.getString("name"));
            final Set<String> properties = legacySchema.get(name);
            int target = properties == null ? Integer.MIN_VALUE : hash(name, project(block.getCompound("states"), properties));
            if (!legacyHashes.contains(target)) {
                misses++;
                target = legacyFirstState.getOrDefault(name, legacyFirstState.get(AIR));
            }
            downgrades.put(hash, target);
        }

        final Int2IntOpenHashMap upgrades = new Int2IntOpenHashMap();
        for (NbtMap block : legacyData.blocks()) {
            final int hash = block.getInt("network_id");
            if (nativeHashes.contains(hash)) {
                continue;
            }
            final String name = block.getString("name");
            final NbtMapBuilder states = block.getCompound("states").toBuilder();
            BlockStateUpdater_1_26_60.ADDED_PROPERTIES.getOrDefault(name, Map.of()).forEach((property, value) -> {
                if (!states.containsKey(property)) {
                    states.put(property, value);
                }
            });
            int target = hash(name, states.build());
            if (!nativeHashes.contains(target)) {
                target = nativeFirstState.getOrDefault(name, nativeFirstState.get(AIR));
            }
            upgrades.put(hash, target);
        }
        downgrades.trim();
        upgrades.trim();
        return new BlockDowngrade(downgrades, upgrades, nativeFirstState.get(AIR), misses);
    }

    /**
     * @return the hash a 1.26.50 client knows the state {@code nativeHash} as; the hash itself for
     * every state 1.26.50 has, and for hashes no palette lists, such as custom blocks
     */
    public int downgrade(int nativeHash) {
        return this.downgrades.getOrDefault(nativeHash, nativeHash);
    }

    /**
     * @return whether 1.26.50 knows the state {@code nativeHash} under another hash, so that
     * {@link #downgrade(int)} changes it
     */
    public boolean isChanged(int nativeHash) {
        return this.downgrades.containsKey(nativeHash);
    }

    /**
     * Resolves a hash a 1.26.50 client sent. A state only 1.26.50 has becomes the server state it
     * turned into; a state only the server has, which such a client cannot know, becomes air rather
     * than a block the client never saw. Anything else is the same in both versions.
     *
     * @return the server's hash for {@code legacyHash}
     */
    public int upgrade(int legacyHash) {
        if (this.upgrades.containsKey(legacyHash)) {
            return this.upgrades.get(legacyHash);
        }
        return this.downgrades.containsKey(legacyHash) ? this.airHash : legacyHash;
    }

    /**
     * @return whether {@code legacyHash} names a state only 1.26.50 has
     */
    public boolean isLegacyOnly(int legacyHash) {
        return this.upgrades.containsKey(legacyHash);
    }

    /**
     * @return writes a block state as its 1.26.50 hash, for chunk palettes
     */
    public @NotNull RuntimeDataSerializer<BlockState> serializer() {
        return this.serializer;
    }

    /**
     * @return how many server states 1.26.50 has no state for, even after dropping properties,
     * which are sent as the first state of that block instead
     */
    public int misses() {
        return this.misses;
    }

    /**
     * @return the server states that have another hash in 1.26.50
     */
    public @NotNull IntSet changedHashes() {
        return IntSets.unmodifiable(new IntOpenHashSet(this.downgrades.keySet()));
    }

    /**
     * @return the 1.26.50 states that have another hash on the server
     */
    public @NotNull IntSet legacyOnlyHashes() {
        return IntSets.unmodifiable(new IntOpenHashSet(this.upgrades.keySet()));
    }

    private static NbtMap project(NbtMap states, Set<String> properties) {
        final NbtMapBuilder projected = NbtMap.builder();
        for (Map.Entry<String, Object> entry : states.entrySet()) {
            if (properties.contains(entry.getKey())) {
                projected.put(entry.getKey(), entry.getValue());
            }
        }
        return projected.build();
    }

    private static int hash(String name, NbtMap states) {
        return HashUtils.fnv1a_32_nbt_palette(NbtMap.builder().putString("name", name).putCompound("states", states).build());
    }

    private static IntSet hashes(LegacyGameData data) {
        final IntSet hashes = new IntOpenHashSet(data.blocks().size());
        for (NbtMap block : data.blocks()) {
            hashes.add(block.getInt("network_id"));
        }
        return hashes;
    }

    private static Map<String, Set<String>> schema(LegacyGameData data) {
        final Map<String, Set<String>> schema = new HashMap<>();
        for (NbtMap block : data.blocks()) {
            schema.computeIfAbsent(block.getString("name"), name -> new HashSet<>()).addAll(block.getCompound("states").keySet());
        }
        return schema;
    }

    private static Map<String, Integer> firstStates(LegacyGameData data) {
        final Map<String, Integer> first = new HashMap<>();
        for (NbtMap block : data.blocks()) {
            first.putIfAbsent(block.getString("name"), block.getInt("network_id"));
        }
        return first;
    }
}
