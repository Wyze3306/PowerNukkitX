package org.powernukkitx.registry;

import org.cloudburstmc.protocol.bedrock.data.VoxelShapes;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Every name in VoxelShapesPacket is written out as a string, so a null one does not fail on
 * whoever registered it: it fails at serialization, for every player, on every login, and the
 * packet is simply never delivered. The data file ends on a run of shapes carrying boxes but no
 * identifier, which is exactly how that null used to get in.
 */
class VoxelShapeRegistryTest {

    @BeforeAll
    static void loadShapes() {
        Registries.VOXEL_SHAPE.init();
    }

    @Test
    void everyRegisteredShapeIsNamed() {
        Assertions.assertFalse(Registries.VOXEL_SHAPE.getAll().isEmpty(), "no shape was loaded at all");
        for (String name : Registries.VOXEL_SHAPE.getAll().keySet()) {
            Assertions.assertNotNull(name, "a shape was registered without a name");
            Assertions.assertFalse(name.isBlank(), "a shape was registered under a blank name");
        }
    }

    @Test
    void packetCarriesNoNamelessEntry() {
        var packet = VoxelShapeRegistry.getPACKET();
        Assertions.assertFalse(packet.getNameMap().isEmpty(), "the packet carries no shape name");
        for (String name : packet.getNameMap().keySet()) {
            Assertions.assertNotNull(name, "the packet carries a null shape name and cannot serialize");
            Assertions.assertFalse(name.isBlank(), "the packet carries a blank shape name");
        }
    }

    @Test
    void everyHandlePointsAtAShapeThatWasSent() {
        var packet = VoxelShapeRegistry.getPACKET();
        // A handle indexes into the shapes list, so one out of range hands the client a different
        // shape than the one it looked up - or nothing at all.
        for (VoxelShapes.RegistryHandle handle : packet.getNameMap().values()) {
            Assertions.assertTrue(handle.getValue() >= 0 && handle.getValue() < packet.getShapes().size(),
                "shape handle " + handle.getValue() + " is outside the " + packet.getShapes().size() + " shapes sent");
        }
    }

    @Test
    void namelessVanillaShapesAreStillSent() {
        var packet = VoxelShapeRegistry.getPACKET();
        // The nameless shapes have no entry in the name map but still occupy a slot in the shapes
        // list. Dropping them instead would shift every index the client resolves by name.
        Assertions.assertTrue(packet.getShapes().size() > packet.getNameMap().size(),
            "the nameless vanilla shapes are no longer sent: " + packet.getShapes().size()
                + " shapes for " + packet.getNameMap().size() + " names");
    }
}
