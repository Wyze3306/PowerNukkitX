package org.powernukkitx.network.protocol.legacy;

import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.common.DefinitionRegistry;
import org.jetbrains.annotations.ApiStatus;
import org.powernukkitx.utils.RuntimeBlockDefinition;

/**
 * The block definitions of a 1.26.50 session: resolves every block state hash the client sends to
 * the server's, see {@link BlockDowngrade#upgrade(int)}. A state only the server knows resolves to
 * air, so a client can never name a block it was never shown.
 * <p>
 * Keeps nothing per id, so whatever a client sends allocates no more than the definition returned.
 * Every hash counts as registered, as with the server's own registry, so writing a block state is
 * never refused.
 */
@ApiStatus.Internal
public final class LegacyBlockDefinitionRegistry implements DefinitionRegistry<BlockDefinition> {
    private final BlockDowngrade blocks;

    public LegacyBlockDefinitionRegistry() {
        this(LegacyTables.get().blocks());
    }

    LegacyBlockDefinitionRegistry(BlockDowngrade blocks) {
        this.blocks = blocks;
    }

    @Override
    public BlockDefinition getDefinition(int runtimeId) {
        return new RuntimeBlockDefinition(this.blocks.upgrade(runtimeId));
    }

    @Override
    public int getRuntimeIdByName(String name) {
        return -1;
    }

    @Override
    public boolean isRegistered(BlockDefinition definition) {
        return true;
    }
}
