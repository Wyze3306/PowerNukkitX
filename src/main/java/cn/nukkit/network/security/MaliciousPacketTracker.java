package cn.nukkit.network.security;

import cn.nukkit.Server;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records malicious-packet violations and short-blocks the offending IP.
 * <p>
 * A violation is any inbound payload the server has decided is hostile
 * (oversized JWT, unknown extraData keys, oversized skin image, etc.).
 * When {@link #flag} is called the IP is added to {@link cn.nukkit.network.Network}'s
 * block list for {@link #BLOCK_DURATION_MS} so the same client cannot
 * immediately retry. Tries to mirror the cheap "kick + 10s ip-block"
 * pattern used by other Bedrock servers to neutralize spam-bots that
 * reconnect on every kick.
 */
@Slf4j
public final class MaliciousPacketTracker {
    /** How long the offending IP is blocked, in milliseconds. */
    public static final int BLOCK_DURATION_MS = 10_000;

    /** Minimum gap between warn logs for the same IP, to avoid log bombing under sustained attack. */
    private static final long LOG_THROTTLE_MS = 60_000;

    /** Last log timestamp per IP. Pruned lazily on each access. */
    private static final ConcurrentHashMap<InetAddress, Long> lastLogMs = new ConcurrentHashMap<>();

    private MaliciousPacketTracker() {}

    /**
     * Flag a session as malicious: log a warning (rate-limited per IP) and block its IP
     * for {@link #BLOCK_DURATION_MS}.
     *
     * @param address socket address of the offending session (may be null on early failures)
     * @param reason  short human-readable reason, included in the log line
     */
    public static void flag(InetSocketAddress address, String reason) {
        if (address == null) {
            log.warn("Malicious packet flagged with no address: {}", reason);
            return;
        }
        InetAddress ip = address.getAddress();
        long now = System.currentTimeMillis();
        Long previous = lastLogMs.get(ip);
        if (previous == null || now - previous >= LOG_THROTTLE_MS) {
            lastLogMs.put(ip, now);
            log.warn("Blocking {} for {}ms: {}", ip.getHostAddress(), BLOCK_DURATION_MS, reason);
            // Opportunistic prune: drop entries older than 5 min to keep the map bounded.
            lastLogMs.entrySet().removeIf(e -> now - e.getValue() > 5 * LOG_THROTTLE_MS);
        }
        Server server = Server.getInstance();
        if (server != null && server.getNetwork() != null) {
            server.getNetwork().blockAddress(ip, BLOCK_DURATION_MS);
        }
    }
}
