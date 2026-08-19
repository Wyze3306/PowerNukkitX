package org.powernukkitx.network.security;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Counts the packets one client gets denied and prints a summary once enough pile up.
 * <p>
 * The paths that drop a packet - the rate limiters, the malformed-packet barrier - deliberately stay
 * quiet per packet: they are all reachable by a client at will, so a line each would let anyone flood
 * the console and bury whatever else was being reported. Staying quiet also meant a client stuck in a
 * loop, or hammering a handler on purpose, produced no trace at all beyond a debug line nobody runs
 * with in production. Counting here keeps both properties: the per-packet path stays silent, and
 * sustained pressure surfaces as one line carrying how much of it there was.
 * <p>
 * A counter only speaks once {@link #threshold} packets have been denied, which is what separates a
 * client that occasionally trips a limit from one that is saturating it, and then at most once per
 * {@value #REPORT_INTERVAL_MILLIS} ms. One instance belongs to one client and one category, so it is
 * discarded with the session and never accumulates server-wide state.
 */
@Slf4j
public final class DeniedPacketLog {

    /**
     * How long a counter stays silent after printing a summary, in milliseconds.
     */
    public static final long REPORT_INTERVAL_MILLIS = 30_000L;

    private final String what;
    private final long threshold;
    private final Supplier<String> subject;

    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong total = new AtomicLong();
    private final AtomicLong lastReportMillis = new AtomicLong();
    private volatile String lastCause;

    /**
     * @param what      what was denied, as a plural noun phrase used verbatim in the summary -
     *                  {@code "chat packets"}, {@code "world interaction packets"}
     * @param threshold how many packets must be denied before anything is printed
     * @param subject   who the packets came from; only evaluated when a summary is actually printed,
     *                  so this stays free on the deny path
     */
    public DeniedPacketLog(@NotNull String what, long threshold, @NotNull Supplier<String> subject) {
        this.what = what;
        this.threshold = Math.max(1, threshold);
        this.subject = subject;
    }

    /**
     * Counts one denied packet. Safe to call from any thread.
     */
    public void record() {
        this.record(null);
    }

    /**
     * Counts one denied packet, remembering why so the summary can name the most recent cause.
     * <p>
     * Safe to call from any thread.
     *
     * @param cause short reason this packet was denied, or {@code null} when the category says it all
     */
    public void record(@Nullable String cause) {
        if (cause != null) {
            this.lastCause = cause;
        }
        final long count = this.total.incrementAndGet();
        if (this.pending.incrementAndGet() < this.threshold) {
            return;
        }
        final long now = System.currentTimeMillis();
        final long last = this.lastReportMillis.get();
        if (now - last < REPORT_INTERVAL_MILLIS) {
            return;
        }
        // Whoever wins the swap prints; a concurrent caller keeps counting into the next window
        // instead of printing the same summary twice.
        if (!this.lastReportMillis.compareAndSet(last, now)) {
            return;
        }
        final long denied = this.pending.getAndSet(0);
        final String cursor = this.lastCause;
        if (cursor == null) {
            log.warn("{}: denied {} {} ({} so far this session)", this.describeSubject(), denied, this.what, count);
        } else {
            log.warn("{}: denied {} {} ({} so far this session), most recently {}",
                    this.describeSubject(), denied, this.what, count, cursor);
        }
    }

    /**
     * @return how many packets this counter has denied for the whole session
     */
    public long total() {
        return this.total.get();
    }

    private String describeSubject() {
        try {
            final String described = this.subject.get();
            return described == null ? "unknown client" : described;
        } catch (Throwable describeFailed) {
            return "unknown client";
        }
    }
}
