package org.powernukkitx.registry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecipeItemIdTest {

    @Test
    void dropsTheAuxSuffixOfTheRecipeDump() {
        assertEquals("minecraft:cobbled_deepslate", RecipeRegistry.withoutAuxSuffix("minecraft:cobbled_deepslate:0", 0));
        assertEquals("minecraft:wool", RecipeRegistry.withoutAuxSuffix("minecraft:wool:3", 3));
        assertEquals("minecraft:planks", RecipeRegistry.withoutAuxSuffix("minecraft:planks:32767", Short.MAX_VALUE));
    }

    @Test
    void keepsIdentifiersWithoutAMatchingSuffix() {
        assertEquals("minecraft:stone", RecipeRegistry.withoutAuxSuffix("minecraft:stone", 0));
        assertEquals("minecraft:wool:3", RecipeRegistry.withoutAuxSuffix("minecraft:wool:3", 4));
        assertEquals("custom:item:name", RecipeRegistry.withoutAuxSuffix("custom:item:name", 0));
        assertEquals("stone:0", RecipeRegistry.withoutAuxSuffix("stone:0", 0));
    }
}
