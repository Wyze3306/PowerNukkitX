package org.powernukkitx.utils;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A session's block definitions must not remember the ids a client sends, or a client could grow
 * them without bound by sending new ones.
 */
class RuntimeBlockDefinitionRegistryTest {

    @Test
    void definitionsCarryTheIdAndCompareByIt() {
        RuntimeBlockDefinitionRegistry registry = new RuntimeBlockDefinitionRegistry();
        assertEquals(123456, registry.getDefinition(123456).getRuntimeId());
        assertEquals(registry.getDefinition(-42), registry.getDefinition(-42));
    }

    @Test
    void nothingIsKeptPerId() {
        RuntimeBlockDefinitionRegistry registry = new RuntimeBlockDefinitionRegistry();
        for (int id = 0; id < 100_000; id++) {
            registry.getDefinition(id * 7919);
        }
        Field[] state = Arrays.stream(RuntimeBlockDefinitionRegistry.class.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()))
            .toArray(Field[]::new);
        assertTrue(state.length == 0, "the registry holds state: " + Arrays.toString(state));
    }
}
