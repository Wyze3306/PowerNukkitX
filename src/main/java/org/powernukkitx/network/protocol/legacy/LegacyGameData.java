package org.powernukkitx.network.protocol.legacy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The ids one Minecraft version knows, read from a gamedata dump: block states with their hashes,
 * item runtime ids, entity identifiers and biome ids. Immutable once loaded.
 * <p>
 * The dumps of every version have the same layout, so this reads the bundled 1.26.50 data under
 * {@link #LEGACY_ROOT} as well as the server's own under {@link #NATIVE_ROOT}, which the
 * translation tables are built from, see {@link LegacyTables}.
 *
 * @param blocks     every block state, each a compound with {@code name}, {@code states} and its
 *                   hash as {@code network_id}, in palette order
 * @param itemIds    item identifier to runtime id
 * @param entityIds  entity identifiers, such as {@code minecraft:pig}
 * @param biomeIds   biome identifier, such as {@code minecraft:plains}, to biome id
 */
@ApiStatus.Internal
public record LegacyGameData(List<NbtMap> blocks, Object2IntMap<String> itemIds, Set<String> entityIds,
                             Object2IntMap<String> biomeIds) {
    /**
     * The 1.26.50 (protocol 2193) dump bundled with the server.
     */
    public static final String LEGACY_ROOT = "multiversion/v2193/";
    /**
     * The dump the server's own content is loaded from.
     */
    public static final String NATIVE_ROOT = "gamedata/kaooot/";

    public LegacyGameData {
        blocks = List.copyOf(blocks);
        itemIds = Object2IntMaps.unmodifiable(new Object2IntOpenHashMap<>(itemIds));
        entityIds = Set.copyOf(entityIds);
        biomeIds = Object2IntMaps.unmodifiable(new Object2IntOpenHashMap<>(biomeIds));
    }

    /**
     * Reads the dump under {@code root}.
     *
     * @param root resource directory holding {@code block_palette.nbt}, {@code item_palette.json},
     *             {@code entity_identifiers.nbt} and {@code biomes.json}, ending in {@code /}
     * @throws UncheckedIOException when a file is missing or cannot be read
     */
    public static @NotNull LegacyGameData load(@NotNull String root) {
        try {
            return new LegacyGameData(readBlocks(root + "block_palette.nbt"), readItems(root + "item_palette.json"),
                readEntities(root + "entity_identifiers.nbt"), readBiomes(root + "biomes.json"));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the game data under " + root, e);
        }
    }

    private static List<NbtMap> readBlocks(String path) throws IOException {
        try (InputStream stream = open(path); NBTInputStream nbt = NbtUtils.createGZIPReader(stream)) {
            return ((NbtMap) nbt.readTag()).getList("blocks", NbtType.COMPOUND);
        }
    }

    private static Object2IntMap<String> readItems(String path) throws IOException {
        final Object2IntMap<String> ids = new Object2IntOpenHashMap<>();
        for (JsonElement element : readJson(path).getAsJsonArray("items")) {
            final JsonObject item = element.getAsJsonObject();
            ids.put(item.get("name").getAsString(), item.get("id").getAsInt());
        }
        return ids;
    }

    private static Set<String> readEntities(String path) throws IOException {
        try (InputStream stream = open(path); NBTInputStream nbt = NbtUtils.createGZIPReader(stream)) {
            final Set<String> ids = new LinkedHashSet<>();
            for (NbtMap entity : ((NbtMap) nbt.readTag()).getList("idlist", NbtType.COMPOUND)) {
                ids.add(entity.getString("id"));
            }
            return ids;
        }
    }

    private static Object2IntMap<String> readBiomes(String path) throws IOException {
        final Object2IntMap<String> ids = new Object2IntOpenHashMap<>();
        for (var entry : readJson(path).entrySet()) {
            if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isNumber()) {
                ids.put(entry.getKey(), entry.getValue().getAsInt());
            }
        }
        return ids;
    }

    private static JsonObject readJson(String path) throws IOException {
        try (InputStream stream = open(path); var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static InputStream open(String path) throws IOException {
        final InputStream stream = LegacyGameData.class.getClassLoader().getResourceAsStream(path);
        if (stream == null) {
            throw new IOException("Missing resource " + path);
        }
        return stream;
    }
}
