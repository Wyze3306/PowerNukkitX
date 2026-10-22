package org.powernukkitx.utils;

import lombok.Value;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.common.DefinitionRegistry;

/**
 * The block definitions of a session, resolved from the block state hash as it is.
 * <p>
 * Keeps nothing per id: a registry that remembered every hash a client sent would grow with every
 * new value a client chose to send, and definitions compare by their runtime id anyway.
 *
 * @author Kaooot
 * @version 1.0
 */
public class RuntimeBlockDefinitionRegistry implements DefinitionRegistry<BlockDefinition> {

    public RuntimeBlockDefinitionRegistry() {

    }

    @Override
    public BlockDefinition getDefinition(int runtimeId) {
        return new RuntimeBlockDefinition(runtimeId);
    }

    @Override
    public int getRuntimeIdByName(String name) {
        return -1;
    }

    @Override
    public boolean isRegistered(BlockDefinition definition) {
        return true;
    }

    @Value
    public static class RuntimeBlockDefinition implements BlockDefinition {
        int runtimeId;
    }
}
