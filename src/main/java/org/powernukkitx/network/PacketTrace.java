package org.powernukkitx.network;

import lombok.extern.slf4j.Slf4j;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.powernukkitx.Player;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records the last packets sent to a player so that a client which hangs up leaves something behind.
 * <p>
 * A Bedrock client that cannot make sense of a packet does not tell the server why: it closes the
 * RakNet connection, which arrives as {@code disconnect.closed} and is indistinguishable from
 * someone leaving from the pause menu. There is no server-side throwable to log, so the only way to
 * find the packet responsible is to know what was on the wire just before the client left. Arm this
 * on the player who gets dropped - which for a broadcast is usually a bystander rather than the
 * player performing the action - reproduce, and read the dump.
 * <p>
 * Disarmed by default and gated behind a single volatile read, so a server that is not debugging
 * pays one predictable branch per sent packet. While armed it calls {@code toString} on every packet
 * sent to a traced player, which is expensive: this is a debugging tool, not something to leave on.
 */
@Slf4j
public final class PacketTrace {

    /**
     * How many entries are kept per traced player. A burst of identical packets counts as one.
     */
    public static final int CAPACITY = 512;

    /**
     * How long a client may stay silent before the recorder considers it gone and stops writing.
     * <p>
     * A live client sends its input every tick, so two seconds without a word is already abnormal,
     * while the session only times out after ten. Recording those ten seconds is what emptied the
     * window of everything that mattered: the traffic the server kept pushing to a dead client
     * pushed out the packet that killed it. Freezing keeps the window on the last moments the
     * client was still there.
     */
    private static final long SILENCE_TICKS = 40;

    /**
     * Packets a level emits continuously, each with a position of its own. Folding these on type
     * alone loses nothing - one portal keeps a client's window full of particle events and pushes
     * out the packet the trace was armed to find.
     */
    private static final Set<String> CHATTY = Set.of(
        "LevelEventPacket",
        "LevelEventGenericPacket",
        "MoveActorDeltaPacket",
        "MoveActorAbsolutePacket",
        "SetActorMotionPacket",
        "UpdateBlockPacket");

    private static final int SUMMARY_LIMIT = 300;
    private static final Map<String, PacketTrace> TRACES = new ConcurrentHashMap<>();

    private static volatile boolean armed;
    private static volatile boolean everyone;

    private final String[] summaries = new String[CAPACITY];
    private final long[] ticks = new long[CAPACITY];
    private final String[] types = new String[CAPACITY];
    private final int[] repeats = new int[CAPACITY];
    private int next;
    private int recorded;
    private volatile long lastInboundTick = -1;
    private volatile long lastInboundAt;

    private PacketTrace() {
    }

    /**
     * Arms the recorder for one player, by name, or for everyone.
     *
     * @param target a player name, or {@code all} for every player currently online and every player
     *               who joins afterwards
     */
    public static void arm(@NotNull String target) {
        if (target.equalsIgnoreCase("all")) {
            everyone = true;
        } else {
            TRACES.putIfAbsent(target.toLowerCase(Locale.ENGLISH), new PacketTrace());
        }
        armed = true;
    }

    /**
     * Disarms every recorder and drops what was recorded.
     */
    public static void disarm() {
        armed = false;
        everyone = false;
        TRACES.clear();
    }

    public static boolean isArmed() {
        return armed;
    }

    /**
     * Names currently traced by name, not counting an {@code all} that is in effect.
     */
    public static int tracedCount() {
        return TRACES.size();
    }

    /**
     * Records one outbound packet. Returns immediately when nothing is being traced.
     * <p>
     * Safe to call from any thread.
     */
    public static void record(@NotNull Player player, @NotNull BedrockPacket packet, long tick) {
        if (!armed) {
            return;
        }
        final PacketTrace trace = traceFor(player);
        if (trace == null) {
            return;
        }
        final long silentSince = trace.lastInboundTick;
        if (silentSince >= 0 && tick - silentSince > SILENCE_TICKS) {
            // The client stopped answering: it is either already dead or about to be timed out, and
            // nothing sent from here on can teach us anything. Keep what is in the window.
            return;
        }
        String summary;
        try {
            summary = packet.toString();
        } catch (Throwable describeFailed) {
            summary = packet.getClass().getSimpleName() + " <toString failed: " + describeFailed + '>';
        }
        if (summary.length() > SUMMARY_LIMIT) {
            summary = summary.substring(0, SUMMARY_LIMIT) + "…";
        }
        final String type = packet.getClass().getSimpleName();
        synchronized (trace) {
            // A single effect or movement routine can emit hundreds of identical packets in one
            // tick. Counting a run as one entry keeps the window wide enough to still show what
            // else was on the wire, which is the whole point of the trace.
            //
            // The summary has to match too, not just the type: a scoreboard redraw sends a dozen
            // SetScorePackets in one tick that differ only in their payload, and folding them on
            // type alone hides every one of them behind the first - which is exactly the field
            // you need when hunting the packet a client choked on. The exception is the chatty
            // effect packets, which are all distinct and would otherwise be the only thing left
            // in the window.
            final int last = (trace.next - 1 + CAPACITY) % CAPACITY;
            if (trace.recorded > 0 && type.equals(trace.types[last]) && trace.ticks[last] == tick
                && (summary.equals(trace.summaries[last]) || CHATTY.contains(type))) {
                trace.repeats[last]++;
                return;
            }
            trace.summaries[trace.next] = summary;
            trace.types[trace.next] = type;
            trace.ticks[trace.next] = tick;
            trace.repeats[trace.next] = 1;
            trace.next = (trace.next + 1) % CAPACITY;
            if (trace.recorded < CAPACITY) {
                trace.recorded++;
            }
        }
    }

    /**
     * Notes that the client is still talking.
     * <p>
     * A client that crashes stops answering, but the server keeps sending to it until the session
     * times out - tens of seconds of traffic that the client never saw, which is all the window ends
     * up holding on a {@code disconnect.timeout}. The last inbound tick is where the client actually
     * died, so the dump can draw the line and say which entries are still worth reading.
     * <p>
     * Safe to call from any thread.
     */
    public static void recordInbound(@NotNull Player player, long tick) {
        if (!armed) {
            return;
        }
        final PacketTrace trace = traceFor(player);
        if (trace == null) {
            return;
        }
        trace.lastInboundTick = tick;
        trace.lastInboundAt = System.currentTimeMillis();
    }

    /**
     * Prints what was sent to a player before their connection went away, oldest first, and forgets
     * it. No-op when that player was not being traced.
     *
     * @param reason the disconnect reason the network layer reported
     */
    public static void dump(@NotNull Player player, @Nullable String reason) {
        if (!armed) {
            return;
        }
        final PacketTrace trace = everyone
                ? TRACES.get(player.getName().toLowerCase(Locale.ENGLISH))
                : TRACES.remove(player.getName().toLowerCase(Locale.ENGLISH));
        if (trace == null) {
            return;
        }
        final StringBuilder out = new StringBuilder(1024);
        out.append("Last packets sent to ").append(player.getName())
                .append(" before the connection ended (").append(reason).append("), oldest first:");
        synchronized (trace) {
            final long silentAt = trace.lastInboundTick;
            if (silentAt >= 0) {
                out.append("\n  client last spoke at tick ").append(silentAt)
                        .append(", ").append(System.currentTimeMillis() - trace.lastInboundAt)
                        .append("ms before this dump");
            }
            final int start = trace.recorded < CAPACITY ? 0 : trace.next;
            boolean lineDrawn = false;
            for (int i = 0; i < trace.recorded; i++) {
                final int slot = (start + i) % CAPACITY;
                if (!lineDrawn && silentAt >= 0 && trace.ticks[slot] > silentAt) {
                    lineDrawn = true;
                    out.append("\n  --- client stopped answering here; what follows never reached it ---");
                }
                out.append("\n  tick ").append(trace.ticks[slot]).append(" | ");
                if (trace.repeats[slot] > 1) {
                    out.append('x').append(trace.repeats[slot]).append(' ');
                }
                out.append(trace.summaries[slot]);
            }
            if (trace.recorded == 0) {
                out.append("\n  (nothing recorded)");
            } else if (silentAt >= 0 && trace.ticks[start] > silentAt) {
                // Every entry postdates the client's last word, so the packet it choked on has already
                // been pushed out of the window by the traffic the server kept sending into the void.
                out.append("\n  NOTE: the whole window is after the client went silent - the packet to")
                        .append(" look for scrolled out. Raise PacketTrace.CAPACITY, or reproduce")
                        .append(" somewhere quieter, and trace that one player rather than everyone.");
            } else if (silentAt >= 0) {
                out.append("\n  (recording stopped ").append(SILENCE_TICKS)
                        .append(" ticks after the client went quiet; the last entries above are the")
                        .append(" last things it could still have read)");
            }
            trace.recorded = 0;
            trace.next = 0;
            trace.lastInboundTick = -1;
        }
        log.warn(out.toString());
    }

    private static PacketTrace traceFor(Player player) {
        final String name = player.getName();
        if (name == null) {
            return null;
        }
        final String key = name.toLowerCase(Locale.ENGLISH);
        if (everyone) {
            return TRACES.computeIfAbsent(key, unused -> new PacketTrace());
        }
        return TRACES.get(key);
    }
}
