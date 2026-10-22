package org.powernukkitx.network.process;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.PacketSerializeException;
import org.powernukkitx.Player;
import org.powernukkitx.Server;
import org.powernukkitx.event.player.PlayerHackDetectedEvent;

/**
 * Reacts to packets that failed to decode. Packets the server fails to encode are reported the same
 * way; those are logged and dropped, and never held against the client.
 *
 * @author Kaooot
 */
@Slf4j
@RequiredArgsConstructor
public class BadPacketHandler extends ChannelInboundHandlerAdapter {
    /** How {@link org.cloudburstmc.protocol.bedrock.codec.BedrockCodec#tryEncode} words its failures. */
    private static final String ENCODE_FAILURE = "Error whilst serializing";

    private final BedrockServerSession session;
    private int encodeFailures;

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable throwable) {
        while (throwable != null) {
            if (throwable instanceof PacketSerializeException e) {
                if (isEncodeFailure(e)) {
                    // A packet the server failed to write is the server's fault, never the client's:
                    // it is dropped and logged, without kicking the player or closing the session
                    if (this.encodeFailures++ == 0) {
                        log.error("Could not send a packet to {}", this.session.getSocketAddress(), e);
                    } else {
                        log.debug("Could not send another packet to {}", this.session.getSocketAddress(), e);
                    }
                    return;
                }
                for (final Player player : Server.getInstance().getOnlinePlayers().values()) {
                    if (player.getSession().getSocketAddress().equals(this.session.getSocketAddress())) {
                        final PlayerHackDetectedEvent event = new PlayerHackDetectedEvent(
                            player,
                            PlayerHackDetectedEvent.HackType.BAD_PACKET
                        );

                        Server.getInstance().getPluginManager().callEvent(event);

                        if (event.isKick()) {
                            player.close("Bad Packet");
                        }

                        Server.getInstance().getLogger().warning(player.getName() + " sent a bad packet", e);
                        return;
                    }
                }
                this.session.close("Bad Packet");
            }
            throwable = throwable.getCause();
        }
    }

    /**
     * The packet codec reports both directions through the same exception; only the message tells
     * a packet it failed to encode from one it failed to decode.
     */
    static boolean isEncodeFailure(PacketSerializeException exception) {
        return exception.getMessage() != null && exception.getMessage().startsWith(ENCODE_FAILURE);
    }
}
