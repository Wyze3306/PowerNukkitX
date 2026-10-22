package org.powernukkitx.network.process;

import org.powernukkitx.Player;
import org.powernukkitx.Server;
import org.powernukkitx.event.server.PacketHandleEvent;
import org.powernukkitx.event.server.PacketReceiveEvent;
import org.powernukkitx.network.security.PacketTypeRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.jetbrains.annotations.Nullable;

/**
 * @author Kaooot
 */
@Slf4j
public class NetworkPacketHandler implements BedrockPacketHandler {

    private final Server server;
    private final PlayerSessionHolder session;
    /** Per packet type limits, see {@link PacketTypeRateLimiter}; null when rate limiting is off. */
    private final @Nullable PacketTypeRateLimiter packetTypeLimiter;

    public NetworkPacketHandler(Server server, PlayerSessionHolder session) {
        this.server = server;
        this.session = session;
        this.packetTypeLimiter = server.getSettings().networkSettings().rateLimitSettings().rateLimitEnabled()
                ? new PacketTypeRateLimiter()
                : null;
    }

    @Override
    public PacketSignal handlePacket(BedrockPacket packet) {
        if (!this.session.checkRateLimits()) {
            return PacketSignal.HANDLED;
        }
        if (this.packetTypeLimiter != null && !this.packetTypeLimiter.tryAcquire(packet)) {
            if (this.packetTypeLimiter.reportDue()) {
                log.debug("{}: dropping {} beyond {} per second", this.session.getSession().getSocketAddress(),
                        packet.getClass().getSimpleName(), PacketTypeRateLimiter.perSecond(packet.getClass()));
            }
            return PacketSignal.HANDLED;
        }

        final Player player = this.session.getPlayer();
        final PacketHandler packetHandler = PacketHandlerRegistry.getPacketHandler(packet.getClass());

        if (packetHandler != null && !packetHandler.runsOnNetworkThread() && player != null && player.spawned) {
            this.session.getPlayerHandle().handlePacket(packet);
            return PacketSignal.HANDLED;
        }

        if (firePacketReceive(player, packet)) {
            return PacketSignal.UNHANDLED;
        }
        if (packetHandler != null) {
            if (player != null && firePacketHandle(player, packet)) {
                return PacketSignal.UNHANDLED;
            }
            packetHandler.handle(packet, this.session, this.server);
            return PacketSignal.HANDLED;
        }
        return BedrockPacketHandler.super.handlePacket(packet);
    }

    /**
     * Dispatches a queued inbound packet on the main thread: fires the receive/handle events then
     * runs the handler. Installed on the player via {@link Player#setInboundProcessor}.
     */
    void processInbound(BedrockPacket packet) {
        final Player player = this.session.getPlayer();
        if (player == null || firePacketReceive(player, packet)) {
            return;
        }
        final PacketHandler packetHandler = PacketHandlerRegistry.getPacketHandler(packet.getClass());
        if (packetHandler == null || firePacketHandle(player, packet)) {
            return;
        }
        packetHandler.handle(packet, this.session, this.server);
    }

    private boolean firePacketReceive(@Nullable Player player, BedrockPacket packet) {
        if (PacketReceiveEvent.getHandlers().isEmpty()) {
            return false;
        }
        final PacketReceiveEvent event = new PacketReceiveEvent(player, packet);
        this.server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    private boolean firePacketHandle(Player player, BedrockPacket packet) {
        if (PacketHandleEvent.getHandlers().isEmpty()) {
            return false;
        }
        final PacketHandleEvent event = new PacketHandleEvent(player, packet);
        this.server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    // client closes the connection
    @Override
    public void onDisconnect(String reason) {
        final Player player = this.session.getPlayer();
        if (player != null) {
            if (player.spawned) {
                player.requestClose(reason);
            } else {
                player.close(reason);
            }
        }
        BedrockPacketHandler.super.onDisconnect(reason);
    }
}
