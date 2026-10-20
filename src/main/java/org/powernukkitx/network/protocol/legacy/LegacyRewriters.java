package org.powernukkitx.network.protocol.legacy;

import it.unimi.dsi.fastutil.Pair;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.BlockChangeEntry;
import org.cloudburstmc.protocol.bedrock.data.LevelEventType;
import org.cloudburstmc.protocol.bedrock.data.ParticleType;
import org.cloudburstmc.protocol.bedrock.data.SoundEvent;
import org.cloudburstmc.protocol.bedrock.data.biome.BiomeDefinitionData;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorEvent;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.NameDescriptor;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.RecipeIngredient;
import org.cloudburstmc.protocol.bedrock.data.payload.crafting.ContainerMixDataEntry;
import org.cloudburstmc.protocol.bedrock.data.payload.crafting.MaterialReducerDataEntry;
import org.cloudburstmc.protocol.bedrock.data.payload.crafting.MaterialReducerEntryOutput;
import org.cloudburstmc.protocol.bedrock.data.payload.crafting.PotionMixDataEntry;
import org.cloudburstmc.protocol.bedrock.data.payload.crafting.RecipeUnlockingRequirement;
import org.cloudburstmc.protocol.bedrock.data.payload.crafting.ShapedRecipePayload;
import org.cloudburstmc.protocol.bedrock.data.payload.crafting.ShapelessRecipePayload;
import org.cloudburstmc.protocol.bedrock.data.payload.crafting.SmithingTransformRecipePayload;
import org.cloudburstmc.protocol.bedrock.data.payload.crafting.SmithingTrimRecipePayload;
import org.cloudburstmc.protocol.bedrock.data.payload.creative.CreativeItemEntryPayload;
import org.cloudburstmc.protocol.bedrock.packet.ActorEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddActorPacket;
import org.cloudburstmc.protocol.bedrock.packet.AvailableActorIdentifiersPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BiomeDefinitionListPacket;
import org.cloudburstmc.protocol.bedrock.packet.CompletedUsingItemPacket;
import org.cloudburstmc.protocol.bedrock.packet.CraftingDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.CreativeContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemRegistryPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelEventGenericPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelSoundEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateSubChunkBlocksPacket;
import org.cloudburstmc.protocol.common.util.TypeMap;
import org.powernukkitx.utils.RuntimeBlockDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The packet translations of {@link LegacyCodec_v2193}, one method per packet.
 * <p>
 * An outbound method returns the packet it was given when nothing needs to change and a copy
 * otherwise, never changing the packet itself, see {@link RewritingSerializer}. Every raw item id
 * is translated here, by the one method that writes it, and nowhere else, since translating an
 * item id twice moves it to another item.
 */
final class LegacyRewriters {
    /** Sounds 1.26.60 added, played as the closest one 1.26.50 has. */
    static final Map<SoundEvent, SoundEvent> SOUND_FALLBACKS = Map.of(SoundEvent.ICE_BALL_BREAK, SoundEvent.GLASS);
    /** Particles 1.26.60 added, shown as the closest one 1.26.50 has. */
    static final Map<ParticleType, ParticleType> PARTICLE_FALLBACKS = Map.of(ParticleType.ICE_BALL_BREAK, ParticleType.SNOWBALL_POOF);

    private LegacyRewriters() {
    }

    static <T extends UpdateBlockPacket> T updateBlock(T packet) {
        final BlockDowngrade blocks = LegacyTables.get().blocks();
        final BlockDefinition definition = packet.getDefinition();
        if (definition == null || !blocks.isChanged(definition.getRuntimeId())) {
            return packet;
        }
        final T copy = copy(packet);
        copy.setDefinition(new RuntimeBlockDefinition(blocks.downgrade(definition.getRuntimeId())));
        return copy;
    }

    static UpdateSubChunkBlocksPacket updateSubChunkBlocks(UpdateSubChunkBlocksPacket packet) {
        final BlockDowngrade blocks = LegacyTables.get().blocks();
        final Predicate<BlockChangeEntry> changed = entry -> entry.getDefinition() != null
            && blocks.isChanged(entry.getDefinition().getRuntimeId());
        if (packet.getStandardBlocks().stream().noneMatch(changed) && packet.getExtraBlocks().stream().noneMatch(changed)) {
            return packet;
        }
        final UpdateSubChunkBlocksPacket copy = new UpdateSubChunkBlocksPacket();
        copy.setSubChunkBlockPosition(packet.getSubChunkBlockPosition());
        for (BlockChangeEntry entry : packet.getStandardBlocks()) {
            copy.getStandardBlocks().add(changed.test(entry) ? downgrade(entry, blocks) : entry);
        }
        for (BlockChangeEntry entry : packet.getExtraBlocks()) {
            copy.getExtraBlocks().add(changed.test(entry) ? downgrade(entry, blocks) : entry);
        }
        return copy;
    }

    private static BlockChangeEntry downgrade(BlockChangeEntry entry, BlockDowngrade blocks) {
        return new BlockChangeEntry(entry.getPos(), new RuntimeBlockDefinition(blocks.downgrade(entry.getDefinition().getRuntimeId())),
            entry.getUpdateFlags(), entry.getSyncMessageEntityUniqueID(), entry.getMessage());
    }

    /**
     * Level events carry a block state hash in their data for block particles and sounds, and an
     * item packed as {@code id << 16 | aux} for item particles.
     */
    static LevelEventPacket levelEvent(LevelEventPacket packet, TypeMap<LevelEventType> events) {
        final LevelEventType type = knownType(packet.getType(), events);
        final int data = type == ParticleType.ICON_CRACK
            ? LegacyTables.get().items().downgradePacked(packet.getData())
            : downgradeIfBlock(packet.getData());
        if (type == packet.getType() && data == packet.getData()) {
            return packet;
        }
        final LevelEventPacket copy = packet.clone();
        copy.setType(type);
        copy.setData(data);
        return copy;
    }

    static LevelEventGenericPacket levelEventGeneric(LevelEventGenericPacket packet, TypeMap<LevelEventType> events) {
        final LevelEventType type = knownType(packet.getType(), events);
        if (type == packet.getType()) {
            return packet;
        }
        final LevelEventGenericPacket copy = packet.clone();
        copy.setType(type);
        return copy;
    }

    private static LevelEventType knownType(LevelEventType type, TypeMap<LevelEventType> events) {
        if (type == null || events.getIdUnsafe(type) != -1 || !(type instanceof ParticleType particle)) {
            return type;
        }
        return PARTICLE_FALLBACKS.getOrDefault(particle, particle);
    }

    static LevelSoundEventPacket levelSoundEvent(LevelSoundEventPacket packet, TypeMap<SoundEvent> sounds) {
        SoundEvent sound = packet.getSoundEvent();
        if (sound != null && sounds.getIdUnsafe(sound) == -1) {
            sound = SOUND_FALLBACKS.getOrDefault(sound, SoundEvent.UNDEFINED);
        }
        final int data = downgradeIfBlock(packet.getData());
        final String identifier = packet.getActorIdentifier() == null ? null
            : LegacyTables.get().entities().downgrade(packet.getActorIdentifier());
        if (sound == packet.getSoundEvent() && data == packet.getData() && identifier == packet.getActorIdentifier()) {
            return packet;
        }
        final LevelSoundEventPacket copy = packet.clone();
        copy.setSoundEvent(sound);
        copy.setData(data);
        copy.setActorIdentifier(identifier);
        return copy;
    }

    /**
     * Sound data a 1.26.50 client sends is played to everyone around, so a block state only 1.26.50
     * has must become the server's before it is passed on.
     */
    static void upgradeLevelSoundEvent(LevelSoundEventPacket packet) {
        final BlockDowngrade blocks = LegacyTables.get().blocks();
        if (blocks.isLegacyOnly(packet.getData())) {
            packet.setData(blocks.upgrade(packet.getData()));
        }
    }

    /**
     * The eating event carries the item eaten, packed as {@code id << 16 | aux}.
     */
    static ActorEventPacket actorEvent(ActorEventPacket packet) {
        if (packet.getType() != ActorEvent.FEED || packet.getData() == 0) {
            return packet;
        }
        final int data = LegacyTables.get().items().downgradePacked(packet.getData());
        if (data == packet.getData()) {
            return packet;
        }
        final ActorEventPacket copy = packet.clone();
        copy.setData(data);
        return copy;
    }

    static void upgradeActorEvent(ActorEventPacket packet) {
        if (packet.getType() == ActorEvent.FEED && packet.getData() != 0) {
            packet.setData(LegacyTables.get().items().upgradePacked(packet.getData()));
        }
    }

    static CompletedUsingItemPacket completedUsingItem(CompletedUsingItemPacket packet) {
        final int itemId = LegacyTables.get().items().downgradeId(packet.getItemId());
        if (itemId == packet.getItemId()) {
            return packet;
        }
        final CompletedUsingItemPacket copy = packet.clone();
        copy.setItemId(itemId);
        return copy;
    }

    static AddActorPacket addActor(AddActorPacket packet) {
        final String identifier = packet.getActorType() == null ? null : LegacyTables.get().entities().downgrade(packet.getActorType());
        if (identifier == packet.getActorType()) {
            return packet;
        }
        final AddActorPacket copy = packet.clone();
        copy.setActorType(identifier);
        return copy;
    }

    /**
     * Leaves out the recipes involving anything 1.26.50 does not know, and translates the item ids
     * of brewing and container mixes. Recipe network ids are kept as they are, since crafting
     * requests refer to them. Items inside the recipes that remain are translated by the codec
     * helper, by identifier.
     */
    static CraftingDataPacket craftingData(CraftingDataPacket packet) {
        final ItemDowngrade items = LegacyTables.get().items();
        final CraftingDataPacket copy = new CraftingDataPacket();
        for (ShapedRecipePayload recipe : packet.getShapedRecipes()) {
            if (representable(items, recipe.getIngredients(), recipe.getResults(), recipe.getUnlockingRequirement())) {
                copy.getShapedRecipes().add(recipe);
            }
        }
        for (ShapelessRecipePayload recipe : packet.getShapelessRecipes()) {
            if (representable(items, recipe.getIngredients(), recipe.getResults(), recipe.getUnlockingRequirement())) {
                copy.getShapelessRecipes().add(recipe);
            }
        }
        for (ShapelessRecipePayload recipe : packet.getUserDataShapelessRecipes()) {
            if (representable(items, recipe.getIngredients(), recipe.getResults(), recipe.getUnlockingRequirement())) {
                copy.getUserDataShapelessRecipes().add(recipe);
            }
        }
        for (ShapelessRecipePayload recipe : packet.getShapelessChemistryRecipes()) {
            if (representable(items, recipe.getIngredients(), recipe.getResults(), recipe.getUnlockingRequirement())) {
                copy.getShapelessChemistryRecipes().add(recipe);
            }
        }
        for (ShapedRecipePayload recipe : packet.getShapedChemistryRecipes()) {
            if (representable(items, recipe.getIngredients(), recipe.getResults(), recipe.getUnlockingRequirement())) {
                copy.getShapedChemistryRecipes().add(recipe);
            }
        }
        for (SmithingTransformRecipePayload recipe : packet.getSmithingTransformRecipes()) {
            if (representable(items, List.of(recipe.getTemplateIngredient(), recipe.getBaseIngredient(), recipe.getAdditionIngredient()),
                recipe.getResult() == null ? List.of() : List.of(recipe.getResult()), null)) {
                copy.getSmithingTransformRecipes().add(recipe);
            }
        }
        for (SmithingTrimRecipePayload recipe : packet.getSmithingTrimRecipes()) {
            if (representable(items, List.of(recipe.getTemplateIngredient(), recipe.getBaseIngredient(), recipe.getAdditionIngredient()),
                List.of(), null)) {
                copy.getSmithingTrimRecipes().add(recipe);
            }
        }
        copy.getMultiRecipes().addAll(packet.getMultiRecipes());
        copy.getFurnaceRecipes().addAll(packet.getFurnaceRecipes());
        for (PotionMixDataEntry mix : packet.getPotionMixes()) {
            final PotionMixDataEntry legacy = potionMix(items, mix);
            if (legacy != null) {
                copy.getPotionMixes().add(legacy);
            }
        }
        for (ContainerMixDataEntry mix : packet.getContainerMixes()) {
            final ContainerMixDataEntry legacy = containerMix(items, mix);
            if (legacy != null) {
                copy.getContainerMixes().add(legacy);
            }
        }
        for (MaterialReducerDataEntry reducer : packet.getMaterialReducers()) {
            final MaterialReducerDataEntry legacy = materialReducer(items, reducer);
            if (legacy != null) {
                copy.getMaterialReducers().add(legacy);
            }
        }
        copy.setClearRecipes(packet.isClearRecipes());
        return copy;
    }

    private static boolean representable(ItemDowngrade items, List<RecipeIngredient> ingredients, List<ItemData> results,
                                         RecipeUnlockingRequirement unlocking) {
        for (RecipeIngredient ingredient : ingredients) {
            if (!representable(items, ingredient)) {
                return false;
            }
        }
        if (unlocking != null) {
            for (RecipeIngredient ingredient : unlocking.getUnlockingIngredients()) {
                if (!representable(items, ingredient)) {
                    return false;
                }
            }
        }
        for (ItemData result : results) {
            if (result != null && result.getDefinition() != null
                && !items.isRepresentable(result.getDefinition().getIdentifier(), result.getDamage())) {
                return false;
            }
        }
        return true;
    }

    private static boolean representable(ItemDowngrade items, RecipeIngredient ingredient) {
        if (ingredient == null || !(ingredient.getDescriptor() instanceof NameDescriptor name) || name.getItemId() == null) {
            return true;
        }
        return items.isRepresentable(name.getItemId().getIdentifier(), name.getAuxValue());
    }

    /**
     * @return the mix with 1.26.50 ids, or {@code null} when it involves an item or potion 1.26.50
     * does not have
     */
    private static PotionMixDataEntry potionMix(ItemDowngrade items, PotionMixDataEntry mix) {
        final int from = items.representableId(mix.getFromPotionId(), mix.getFromItemAux());
        final int reagent = items.representableId(mix.getReagentItemId(), mix.getReagentItemAux());
        final int to = items.representableId(mix.getToPotionId(), mix.getToItemAux());
        if (from == Integer.MIN_VALUE || reagent == Integer.MIN_VALUE || to == Integer.MIN_VALUE) {
            return null;
        }
        final PotionMixDataEntry legacy = new PotionMixDataEntry();
        legacy.setFromPotionId(from);
        legacy.setFromItemAux(mix.getFromItemAux());
        legacy.setReagentItemId(reagent);
        legacy.setReagentItemAux(mix.getReagentItemAux());
        legacy.setToPotionId(to);
        legacy.setToItemAux(mix.getToItemAux());
        return legacy;
    }

    private static ContainerMixDataEntry containerMix(ItemDowngrade items, ContainerMixDataEntry mix) {
        final int from = items.representableId(mix.getFromItemId(), 0);
        final int reagent = items.representableId(mix.getReagentItemId(), 0);
        final int output = items.representableId(mix.getOutputItemId(), 0);
        if (from == Integer.MIN_VALUE || reagent == Integer.MIN_VALUE || output == Integer.MIN_VALUE) {
            return null;
        }
        final ContainerMixDataEntry legacy = new ContainerMixDataEntry();
        legacy.setFromItemId(from);
        legacy.setReagentItemId(reagent);
        legacy.setOutputItemId(output);
        return legacy;
    }

    /**
     * Material reducers key their input as {@code id << 16 | aux} and list outputs by id.
     */
    private static MaterialReducerDataEntry materialReducer(ItemDowngrade items, MaterialReducerDataEntry reducer) {
        final int key = reducer.getFromItemKey();
        if (items.representableId(key >> 16, key & 0xFFFF) == Integer.MIN_VALUE) {
            return null;
        }
        final MaterialReducerDataEntry legacy = new MaterialReducerDataEntry();
        legacy.setFromItemKey(items.downgradePacked(key));
        for (MaterialReducerEntryOutput output : reducer.getItemIdsAndCounts()) {
            final int id = items.representableId(output.getItemId(), 0);
            if (id == Integer.MIN_VALUE) {
                return null;
            }
            final MaterialReducerEntryOutput legacyOutput = new MaterialReducerEntryOutput();
            legacyOutput.setItemId(id);
            legacyOutput.setItemCount(output.getItemCount());
            legacy.getItemIdsAndCounts().add(legacyOutput);
        }
        return legacy;
    }

    /**
     * Leaves out the creative items 1.26.50 does not know; their network ids, which crafting
     * requests refer to, and every group stay as they are.
     */
    static CreativeContentPacket creativeContent(CreativeContentPacket packet) {
        final ItemDowngrade items = LegacyTables.get().items();
        final Predicate<CreativeItemEntryPayload> known = entry -> entry.getItemInstance() == null
            || entry.getItemInstance().getDefinition() == null
            || items.isRepresentable(entry.getItemInstance().getDefinition().getIdentifier(), entry.getItemInstance().getDamage());
        if (packet.getEntries().stream().allMatch(known)) {
            return packet;
        }
        final CreativeContentPacket copy = new CreativeContentPacket();
        copy.getGroups().addAll(packet.getGroups());
        for (CreativeItemEntryPayload entry : packet.getEntries()) {
            if (known.test(entry)) {
                copy.getEntries().add(entry);
            }
        }
        return copy;
    }

    /**
     * Sends the items 1.26.50 knows with their 1.26.50 ids. Translating by identifier, so a palette
     * that is already the 1.26.50 one comes out the same.
     */
    static ItemRegistryPacket itemRegistry(ItemRegistryPacket packet) {
        final ItemRegistryPacket copy = new ItemRegistryPacket();
        copy.getItemData().addAll(LegacyTables.get().items().downgradePalette(packet.getItemData()));
        return copy;
    }

    static BiomeDefinitionListPacket biomeDefinitionList(BiomeDefinitionListPacket packet) {
        final BiomeDowngrade biomes = LegacyTables.get().biomes();
        final List<String> names = packet.getBiomeStringList();
        final Predicate<Pair<Short, BiomeDefinitionData>> known = biome -> biome.key() == null
            || biome.key() < 0 || biome.key() >= names.size() || biomes.isKnown(names.get(biome.key()));
        if (packet.getBiomes() == null || packet.getBiomes().stream().allMatch(known)) {
            return packet;
        }
        final BiomeDefinitionListPacket copy = packet.clone();
        copy.setBiomes(new ArrayList<>(packet.getBiomes().stream().filter(known).toList()));
        return copy;
    }

    static AvailableActorIdentifiersPacket availableActorIdentifiers(AvailableActorIdentifiersPacket packet) {
        final EntityDowngrade entities = LegacyTables.get().entities();
        final NbtMap tag = packet.getActorInfoList();
        if (tag == null) {
            return packet;
        }
        final List<NbtMap> identifiers = tag.getList("idlist", NbtType.COMPOUND);
        final List<NbtMap> known = identifiers.stream().filter(entry -> !entities.isAdded(entry.getString("id"))).toList();
        if (known.size() == identifiers.size()) {
            return packet;
        }
        final AvailableActorIdentifiersPacket copy = packet.clone();
        copy.setActorInfoList(tag.toBuilder().putList("idlist", NbtType.COMPOUND, known).build());
        return copy;
    }

    private static int downgradeIfBlock(int data) {
        final BlockDowngrade blocks = LegacyTables.get().blocks();
        return blocks.isChanged(data) ? blocks.downgrade(data) : data;
    }

    @SuppressWarnings("unchecked")
    private static <T extends BedrockPacket> T copy(T packet) {
        return (T) packet.clone();
    }
}
