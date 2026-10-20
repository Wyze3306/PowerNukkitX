package org.powernukkitx.network.protocol.legacy;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.BedrockPacketDefinition;
import org.cloudburstmc.protocol.bedrock.codec.BedrockPacketSerializer;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Wraps another codec's serializer for a packet, translating the packet on its way out and on its
 * way in.
 * <p>
 * A packet may be shared: a broadcast hands the same instance to every viewer's session, and those
 * sessions may speak different versions. The outbound rewrite must therefore never change the
 * packet it is given; it returns that very packet when nothing needs translating, otherwise a copy.
 * The inbound rewrite works on a packet that was just decoded for this session alone, so it
 * translates in place.
 *
 * @param <T> the packet type
 */
@ApiStatus.Internal
public final class RewritingSerializer<T extends BedrockPacket> implements BedrockPacketSerializer<T> {
    private final BedrockPacketSerializer<T> delegate;
    private final UnaryOperator<T> outbound;
    private final @Nullable Consumer<T> inbound;

    private RewritingSerializer(BedrockPacketSerializer<T> delegate, UnaryOperator<T> outbound, @Nullable Consumer<T> inbound) {
        this.delegate = delegate;
        this.outbound = outbound;
        this.inbound = inbound;
    }

    /**
     * Wraps the serializer {@code codec} uses for {@code type}.
     *
     * @param outbound returns the packet to encode in place of the one sent, which it must not modify
     * @param inbound  translates a decoded packet in place, or {@code null} when nothing comes in
     * @throws IllegalArgumentException when {@code codec} does not know {@code type}
     */
    public static <T extends BedrockPacket> @NotNull RewritingSerializer<T> wrap(@NotNull BedrockCodec codec, @NotNull Class<T> type,
                                                                                 @NotNull UnaryOperator<T> outbound,
                                                                                 @Nullable Consumer<T> inbound) {
        final BedrockPacketDefinition<T> definition = codec.getPacketDefinition(type);
        if (definition == null) {
            throw new IllegalArgumentException(type.getSimpleName() + " is not part of protocol " + codec.getProtocolVersion());
        }
        return new RewritingSerializer<>(definition.getSerializer(), Objects.requireNonNull(outbound), inbound);
    }

    /**
     * @return the serializer this one translates for
     */
    public @NotNull BedrockPacketSerializer<T> delegate() {
        return this.delegate;
    }

    @Override
    public void serialize(ByteBuf buffer, BedrockCodecHelper helper, T packet) {
        this.delegate.serialize(buffer, helper, this.outbound.apply(packet));
    }

    @Override
    public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, T packet) {
        this.delegate.deserialize(buffer, helper, packet);
        if (this.inbound != null) {
            this.inbound.accept(packet);
        }
    }
}
