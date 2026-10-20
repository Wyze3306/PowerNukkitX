package org.powernukkitx.network.protocol.legacy;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.powernukkitx.utils.RuntimeBlockDefinition;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Translates items between the server's item palette and the 1.26.50 one.
 * <p>
 * 1.26.60 inserted items in the middle of the palette, which moved 82 runtime ids, and the moved
 * ranges overlap: 1.26.60 id 882 is a breeze rod while 1.26.50 id 882 is an apple. A raw id must
 * therefore be translated exactly once, by the one place that writes it, see
 * {@link #downgradeId(int)}. Items carried as {@link ItemData} or {@link ItemDefinition} are
 * translated by identifier instead, which may safely happen more than once.
 * <p>
 * The items 1.26.50 does not have are shown as the closest one it has, see {@link #FALLBACKS}, and
 * potion and arrow variants it does not have as the plain variant. Custom items have the same id
 * in both versions. Immutable and safe to share between threads.
 */
@ApiStatus.Internal
public final class ItemDowngrade {
    /**
     * Items 1.26.60 added, with the 1.26.50 item they are shown as.
     */
    static final Map<String, String> FALLBACKS = Map.of(
        "minecraft:frostbite_spawn_egg", "minecraft:stray_spawn_egg",
        "minecraft:ice_ball", "minecraft:snowball",
        "minecraft:ice_crystal", "minecraft:amethyst_cluster",
        "minecraft:icicle", "minecraft:pointed_dripstone",
        "minecraft:disc_press", "minecraft:stonecutter_block"
    );
    /**
     * The highest aux value 1.26.50 knows for items whose variants 1.26.60 extended; higher ones
     * are sent as variant 0. 1.26.60 added the freezing potion (47, 48) and its arrows (48, 49).
     */
    private static final Map<String, Integer> MAX_AUX = Map.of(
        "minecraft:potion", 46,
        "minecraft:splash_potion", 46,
        "minecraft:lingering_potion", 46,
        "minecraft:arrow", 47
    );

    private final Object2IntMap<String> legacyIds;
    private final Set<String> nativeIdentifiers;
    private final Int2IntMap nativeToLegacy;
    private final Int2ObjectMap<String> nativeNames;
    private final Int2IntMap legacyToNative;
    private final Map<String, ItemDefinition> definitions;
    private final BlockDowngrade blocks;

    private ItemDowngrade(Object2IntMap<String> legacyIds, Set<String> nativeIdentifiers, Int2IntMap nativeToLegacy,
                          Int2ObjectMap<String> nativeNames, Int2IntMap legacyToNative,
                          Map<String, ItemDefinition> definitions, BlockDowngrade blocks) {
        this.legacyIds = legacyIds;
        this.nativeIdentifiers = nativeIdentifiers;
        this.nativeToLegacy = nativeToLegacy;
        this.nativeNames = nativeNames;
        this.legacyToNative = legacyToNative;
        this.definitions = definitions;
        this.blocks = blocks;
    }

    static @NotNull ItemDowngrade build(@NotNull LegacyGameData nativeData, @NotNull LegacyGameData legacyData,
                                        @NotNull BlockDowngrade blocks) {
        final Object2IntMap<String> legacyIds = new Object2IntOpenHashMap<>(legacyData.itemIds());
        final Map<String, ItemDefinition> definitions = new HashMap<>();
        final Int2IntOpenHashMap legacyToNative = new Int2IntOpenHashMap();
        for (Object2IntMap.Entry<String> entry : legacyIds.object2IntEntrySet()) {
            definitions.put(entry.getKey(), new SimpleItemDefinition(entry.getKey(), entry.getIntValue(), false));
            if (nativeData.itemIds().containsKey(entry.getKey())) {
                legacyToNative.put(entry.getIntValue(), nativeData.itemIds().getInt(entry.getKey()));
            }
        }

        final Int2IntOpenHashMap nativeToLegacy = new Int2IntOpenHashMap();
        final Int2ObjectOpenHashMap<String> nativeNames = new Int2ObjectOpenHashMap<>();
        for (Object2IntMap.Entry<String> entry : nativeData.itemIds().object2IntEntrySet()) {
            nativeNames.put(entry.getIntValue(), entry.getKey());
            final String sentAs = legacyIds.containsKey(entry.getKey()) ? entry.getKey() : FALLBACKS.get(entry.getKey());
            nativeToLegacy.put(entry.getIntValue(), sentAs != null && legacyIds.containsKey(sentAs) ? legacyIds.getInt(sentAs) : 0);
        }
        nativeToLegacy.trim();
        nativeNames.trim();
        legacyToNative.trim();
        return new ItemDowngrade(legacyIds, Set.copyOf(nativeData.itemIds().keySet()), nativeToLegacy, nativeNames,
            legacyToNative, Map.copyOf(definitions), blocks);
    }

    /**
     * @return the identifier 1.26.50 shows {@code identifier} as: itself when 1.26.50 has it or it
     * is a custom item, a {@linkplain #FALLBACKS fallback} for an item 1.26.60 added, otherwise
     * {@code null}
     */
    public @Nullable String sentAs(@NotNull String identifier) {
        if (this.legacyIds.containsKey(identifier)) {
            return identifier;
        }
        final String fallback = FALLBACKS.get(identifier);
        if (fallback != null && this.legacyIds.containsKey(fallback)) {
            return fallback;
        }
        return this.nativeIdentifiers.contains(identifier) ? null : identifier;
    }

    /**
     * @return whether a 1.26.50 client knows the item {@code identifier} with aux value {@code aux}
     * as it is, without a fallback; custom items always are
     */
    public boolean isRepresentable(@NotNull String identifier, int aux) {
        if (this.legacyIds.containsKey(identifier)) {
            return aux <= MAX_AUX.getOrDefault(identifier, Integer.MAX_VALUE);
        }
        return !this.nativeIdentifiers.contains(identifier);
    }

    /**
     * @return the runtime id a 1.26.50 client knows the server's item {@code nativeId} as; {@code 0}
     * (air) for an item it has nothing for, and the id itself for ids no palette lists, such as
     * custom items
     */
    public int downgradeId(int nativeId) {
        return this.nativeToLegacy.getOrDefault(nativeId, nativeId);
    }

    /**
     * Resolves an item runtime id a 1.26.50 client sent. An id of the 1.26.50 palette becomes the
     * server's id for the same item. An id that only the server's palette has cannot come from such
     * a client and becomes {@code 0} (air), so it never names another item than the client meant.
     *
     * @return the server's id for {@code legacyId}
     */
    public int upgradeId(int legacyId) {
        if (this.legacyToNative.containsKey(legacyId)) {
            return this.legacyToNative.get(legacyId);
        }
        return this.nativeToLegacy.containsKey(legacyId) ? 0 : legacyId;
    }

    /**
     * @return the 1.26.50 runtime id of the server's item {@code nativeId} with aux value
     * {@code aux}, or {@link Integer#MIN_VALUE} when 1.26.50 does not know that item or variant as
     * it is; custom items keep their id
     */
    public int representableId(int nativeId, int aux) {
        final String identifier = this.nativeNames.get(nativeId);
        if (identifier == null) {
            return nativeId;
        }
        return this.isRepresentable(identifier, aux) ? this.legacyIds.getInt(identifier) : Integer.MIN_VALUE;
    }

    /**
     * Translates an item packed as {@code runtimeId << 16 | aux}, the way item particles and eating
     * events carry it.
     */
    public int downgradePacked(int packed) {
        final int id = packed >> 16;
        int aux = packed & 0xFFFF;
        final String identifier = this.nativeNames.get(id);
        if (identifier != null) {
            final String sentAs = this.sentAs(identifier);
            aux = sentAs == null ? 0 : this.downgradeAux(sentAs, aux);
        }
        return this.downgradeId(id) << 16 | aux;
    }

    /**
     * Resolves an item packed as {@code runtimeId << 16 | aux} that a 1.26.50 client sent, see
     * {@link #upgradeId(int)}.
     */
    public int upgradePacked(int packed) {
        return this.upgradeId(packed >> 16) << 16 | packed & 0xFFFF;
    }

    /**
     * @return {@code aux} when 1.26.50 knows that variant of the item it shows {@code sentAs} as,
     * otherwise {@code 0}
     */
    public int downgradeAux(@NotNull String sentAs, int aux) {
        return aux > MAX_AUX.getOrDefault(sentAs, Integer.MAX_VALUE) ? 0 : aux;
    }

    /**
     * Translates an item definition by identifier. Returns {@code definition} itself when 1.26.50
     * knows it under the same id, and {@link ItemDefinition#AIR} for an item it has nothing for.
     */
    public @NotNull ItemDefinition downgrade(@NotNull ItemDefinition definition) {
        final String sentAs = this.sentAs(definition.getIdentifier());
        if (sentAs == null) {
            return ItemDefinition.AIR;
        }
        final ItemDefinition legacy = this.definitions.get(sentAs);
        if (legacy == null || legacy.getRuntimeId() == definition.getRuntimeId() && sentAs.equals(definition.getIdentifier())) {
            return definition;
        }
        return legacy;
    }

    /**
     * Translates an item by identifier: its definition, an aux value 1.26.50 does not know and the
     * block it places. Returns {@code item} itself when nothing changes, so it is cheap for the
     * common case and may be applied more than once.
     */
    public @NotNull ItemData downgrade(@NotNull ItemData item) {
        final ItemDefinition definition = item.getDefinition();
        if (definition == null || definition.getRuntimeId() == 0) {
            return item;
        }
        final ItemDefinition legacy = this.downgrade(definition);
        if (legacy == ItemDefinition.AIR) {
            return ItemData.AIR;
        }
        final int damage = this.downgradeAux(legacy.getIdentifier(), item.getDamage());
        final BlockDefinition block = item.getBlockDefinition();
        final BlockDefinition legacyBlock = block != null && this.blocks.isChanged(block.getRuntimeId())
            ? new RuntimeBlockDefinition(this.blocks.downgrade(block.getRuntimeId()))
            : block;
        if (legacy == definition && damage == item.getDamage() && legacyBlock == block) {
            return item;
        }
        return item.toBuilder().definition(legacy).damage(damage).blockDefinition(legacyBlock).build();
    }

    /**
     * Builds the item palette a 1.26.50 client is sent from the server's: the items it knows, with
     * their 1.26.50 ids, plus every custom item. Items 1.26.50 does not have are left out.
     *
     * @param definitions the server's item palette
     * @return a new list
     */
    public @NotNull List<ItemDefinition> downgradePalette(@NotNull Collection<? extends ItemDefinition> definitions) {
        final List<ItemDefinition> legacy = new ObjectArrayList<>(definitions.size());
        for (ItemDefinition definition : definitions) {
            final String identifier = definition.getIdentifier();
            if (this.legacyIds.containsKey(identifier)) {
                legacy.add(new SimpleItemDefinition(identifier, this.legacyIds.getInt(identifier),
                    definition.getVersion() == null ? ItemVersion.LEGACY : definition.getVersion(),
                    definition.isComponentBased(), legacyComponents(definition.getComponentData())));
            } else if (!this.nativeIdentifiers.contains(identifier)) {
                legacy.add(definition);
            }
        }
        return legacy;
    }

    /**
     * 1.26.60 lists many vanilla items in its item components with no components at all, which the
     * server sends as an empty {@code components} compound. 1.26.50 did not list them, so they are
     * sent the way the server sends any item without components there: as an empty compound.
     */
    private static @Nullable NbtMap legacyComponents(@Nullable NbtMap components) {
        if (components != null && components.size() == 1 && components.containsKey("components")
            && components.getCompound("components").isEmpty()) {
            return NbtMap.EMPTY;
        }
        return components;
    }
}
