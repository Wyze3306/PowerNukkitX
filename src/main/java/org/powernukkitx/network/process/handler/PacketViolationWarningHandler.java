package org.powernukkitx.network.process.handler;

import io.netty.util.AttributeKey;
import org.powernukkitx.Player;
import org.powernukkitx.Server;
import org.powernukkitx.network.process.PacketHandler;
import org.powernukkitx.network.process.PlayerSessionHolder;
import lombok.extern.slf4j.Slf4j;
import org.cloudburstmc.protocol.bedrock.packet.PacketViolationWarningPacket;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Logs the client's reports of packets it could not read, which are useful when a packet layout is
 * wrong. Everything in the report comes from the client, possibly before it logged in, so the text
 * is truncated, control characters are dropped and a session gets a handful of lines at most.
 *
 * @author Kaooot
 */
@Slf4j
public class PacketViolationWarningHandler implements PacketHandler<PacketViolationWarningPacket> {
    private static final AttributeKey<AtomicInteger> REPORTS = AttributeKey.valueOf("pnx_violation_reports");
    private static final int MAX_REPORTS_PER_SESSION = 5;
    private static final int MAX_CONTEXT_LENGTH = 256;

    @Override
    public void handle(PacketViolationWarningPacket packet, PlayerSessionHolder holder, Server server) {
        AtomicInteger reports = holder.getSession().getPeer().getChannel().attr(REPORTS).setIfAbsent(new AtomicInteger());
        if (reports == null) {
            reports = holder.getSession().getPeer().getChannel().attr(REPORTS).get();
        }
        int count = reports.incrementAndGet();
        if (count > MAX_REPORTS_PER_SESSION) {
            return;
        }
        final Player player = holder.getPlayer();
        log.warn("Violation warning from {}: type={} severity={} packetId={} context={}{}",
                player != null ? player.getName() : holder.getSession().getSocketAddress(),
                packet.getViolationType(), packet.getViolationSeverity(), packet.getViolatingPacketId(), sanitize(packet.getViolationContext()),
                count == MAX_REPORTS_PER_SESSION ? " (further reports from this session are not logged)" : "");
    }

    private static String sanitize(String context) {
        if (context == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(context.length(), MAX_CONTEXT_LENGTH) + 3);
        for (int i = 0; i < context.length() && out.length() < MAX_CONTEXT_LENGTH; i++) {
            char c = context.charAt(i);
            out.append(Character.isISOControl(c) ? ' ' : c);
        }
        if (context.length() > MAX_CONTEXT_LENGTH) {
            out.append("...");
        }
        return out.toString();
    }
}
