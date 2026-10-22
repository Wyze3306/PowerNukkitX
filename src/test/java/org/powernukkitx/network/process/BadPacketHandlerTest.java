package org.powernukkitx.network.process;

import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.codec.PacketSerializeException;
import org.cloudburstmc.protocol.bedrock.codec.v2192.Bedrock_v2192;
import org.cloudburstmc.protocol.bedrock.data.ParticleType;
import org.cloudburstmc.protocol.bedrock.packet.LevelEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.powernukkitx.network.NetworkConstants;

class BadPacketHandlerTest {

    @Test
    void tellsPacketsTheServerCouldNotWriteFromPacketsTheClientSent() {
        // A particle 1.26.50 has no id for, written with its codec
        final LevelEventPacket packet = new LevelEventPacket();
        packet.setType(ParticleType.ICE_BALL_BREAK);
        packet.setPosition(Vector3f.ZERO);
        final PacketSerializeException encode = Assertions.assertThrows(PacketSerializeException.class,
            () -> Bedrock_v2192.CODEC.tryEncode(Bedrock_v2192.CODEC.createHelper(), Unpooled.buffer(), packet));
        Assertions.assertTrue(BadPacketHandler.isEncodeFailure(encode));

        final int textId = NetworkConstants.CODEC.getPacketDefinition(TextPacket.class).getId();
        final PacketSerializeException decode = Assertions.assertThrows(PacketSerializeException.class,
            () -> NetworkConstants.CODEC.tryDecode(NetworkConstants.CODEC.createHelper(), Unpooled.wrappedBuffer(new byte[]{1}), textId));
        Assertions.assertFalse(BadPacketHandler.isEncodeFailure(decode));
    }
}
