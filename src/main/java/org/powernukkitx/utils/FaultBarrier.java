package org.powernukkitx.utils;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.powernukkitx.Player;
import org.powernukkitx.event.player.PlayerKickEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reporting side of the fault barriers placed around the loops that iterate over players, entities
 * and chunks.
 * <p>
 * Those loops used to let a throwable escape, which had two consequences a server owner cannot act
 * on: the iteration aborted for <em>every</em> remaining player, so a single broken entity or a
 * single broken player state froze chunk delivery or ticking for the whole server; and because the
 * same failure then repeated on every tick, whatever the outer catch printed drowned in itself or
 * was never reached at all. A barrier keeps the blast radius at the one player, entity or packet
 * the loop was working on, and routes the throwable here so it is always printed.
 * <p>
 * Call sites keep their {@code try}/{@code catch} inline rather than wrapping the body in a lambda:
 * these loops are on the tick path and must not allocate per iteration. Only the catch branch
 * reaches this class.
 * <p>
 * Repeating faults are throttled: the first occurrence is printed with its full stack trace, and
 * further occurrences of the same fault are summarised at most once per
 * {@value #RESTATE_INTERVAL_MILLIS} ms with the number of times it happened since.
 */
@Slf4j
public final class FaultBarrier {

    /**
     * How long the same fault stays silent after being reported, in milliseconds.
     */
    public static final long RESTATE_INTERVAL_MILLIS = 60_000L;

    private static final Map<String, Fault> FAULTS = new ConcurrentHashMap<>();

    private FaultBarrier() {
        throw new UnsupportedOperationException();
    }

    private static final class Fault {
        private final AtomicLong sinceLastReport = new AtomicLong();
        private volatile long lastReportMillis;
        private volatile boolean everReported;
    }

    /**
     * Prints an isolated throwable, with the full stack trace on its first occurrence and a
     * throttled summary afterwards.
     * <p>
     * Safe to call from any thread.
     *
     * @param context what the server was doing, phrased so the line is actionable on its own -
     *                {@code "ticking player"}, {@code "spawning entity to player"}
     * @param subject the object the failure is attributed to (player, entity, packet); its
     *                {@code toString} is only evaluated when the fault is actually printed
     * @param thrown  the throwable that was caught and contained
     */
    public static void report(@NotNull String context, @Nullable Object subject, @NotNull Throwable thrown) {
        final Fault fault = FAULTS.computeIfAbsent(key(context, thrown), unused -> new Fault());
        final long suppressed = fault.sinceLastReport.getAndIncrement();
        final long now = System.currentTimeMillis();
        if (fault.everReported && now - fault.lastReportMillis < RESTATE_INTERVAL_MILLIS) {
            return;
        }
        fault.everReported = true;
        fault.lastReportMillis = now;
        fault.sinceLastReport.set(0);
        if (suppressed == 0) {
            log.error("Fault isolated while {} [{}]", context, describe(subject), thrown);
        } else {
            log.error("Fault isolated while {} [{}] - it also happened {} time(s) since the last report",
                context, describe(subject), suppressed, thrown);
        }
    }

    /**
     * Prints an isolated throwable and disconnects the single player it is attributed to, leaving
     * everybody else connected. The disconnect screen carries the exception type and message so the
     * failure is visible in game and not only in the console.
     * <p>
     * Use this only when the fault came out of that player's own tick or session handling. A fault
     * caused by shared state - a broken entity, a broken chunk - must skip the offending object
     * instead, otherwise every player who comes near it gets kicked in turn.
     *
     * @param player  the player to disconnect
     * @param context what the server was doing, as in {@link #report(String, Object, Throwable)}
     * @param thrown  the throwable that was caught and contained
     */
    public static void isolate(@NotNull Player player, @NotNull String context, @NotNull Throwable thrown) {
        report(context, player, thrown);
        try {
            player.kick(PlayerKickEvent.Reason.UNKNOWN, "Internal error while " + context + ": " + summarise(thrown),
                "Internal error while " + context + "\n" + summarise(thrown), false);
        } catch (Throwable kickFailed) {
            report("disconnecting a player after an internal error", player, kickFailed);
        }
    }

    private static String key(String context, Throwable thrown) {
        final StackTraceElement[] trace = thrown.getStackTrace();
        return context + '|' + thrown.getClass().getName() + '|' + (trace.length == 0 ? "?" : trace[0].toString());
    }

    private static String describe(Object subject) {
        if (subject == null) {
            return "no subject";
        }
        try {
            return subject.getClass().getSimpleName() + ": " + subject;
        } catch (Throwable describeFailed) {
            return subject.getClass().getSimpleName() + ": <toString failed>";
        }
    }

    private static String summarise(Throwable thrown) {
        final String message = thrown.getMessage();
        return message == null ? thrown.getClass().getSimpleName() : thrown.getClass().getSimpleName() + ": " + message;
    }
}
