package org.powernukkitx.utils;

import org.powernukkitx.Server;
import lombok.extern.slf4j.Slf4j;

import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;

/**
 * Watches the main thread and reports it when it stops making progress.
 * <p>
 * A stall is the one failure the server cannot report on its own: whatever loop is holding the tick
 * is also holding the only thread that would have logged it. Everything here therefore runs from a
 * separate thread and reads the tick clock from the outside.
 * <p>
 * Two thresholds. {@link #STALL_WARN_MILLIS} is the one that carries the diagnosis: it prints the
 * main thread's stack while the server is still alive, so a hitch that resolves on its own leaves
 * the frame responsible behind. {@code time} - a minute, set by the caller - is the fatal one: past
 * it the server is not coming back, so every thread is dumped and the process is brought down for
 * the supervisor to restart.
 */
@Slf4j
public class Watchdog extends Thread {

    /**
     * How often the tick clock is sampled.
     * <p>
     * This used to be a quarter of the fatal threshold, which is fifteen seconds: a stall shorter
     * than that could start and finish between two samples and leave nothing at all, and one that
     * did get caught was already a quarter of the way to the kill. The interesting stalls are
     * seconds long, so the sampler has to be finer than the shortest thing it reports.
     */
    private static final long POLL_INTERVAL_MILLIS = 1_000L;

    /**
     * How long the main thread may go without finishing a tick before it is worth a stack trace.
     * <p>
     * A healthy tick is 50 ms and a slow one under load is still well inside a second, so five
     * seconds is already a server that has stopped serving. Reporting there is what turns a freeze
     * nobody saw into a stack: below the fatal threshold the process survives, and until now
     * everything in that range - which is most of them - was logged as nothing whatsoever.
     */
    private static final long STALL_WARN_MILLIS = 5_000L;

    /**
     * How long a stall report stays silent before the next one. A stall that lasts a minute is
     * worth following as it grows, but not once per sample.
     */
    private static final long STALL_REPORT_INTERVAL_MILLIS = 10_000L;

    private final Server server;
    private final long time;
    public volatile boolean running;
    private boolean responding = true;

    private boolean stalling;
    private long stallPeakMillis;
    private long lastStallReportMillis;
    private long lastStatisticsResetMillis;

    public Watchdog(Server server, long time) {
        this.server = server;
        this.time = time;
        this.running = true;
        this.setName("Watchdog");
        this.setDaemon(true);
        this.setPriority(Thread.MIN_PRIORITY);
    }

    public void kill() {
        running = false;
        interrupt();
    }

    @Override
    public void run() {
        while (this.running) {
            long current = server.getNextTick();
            if (current != 0) {
                var now = System.currentTimeMillis();
                long diff = now - current;

                // Refresh the advanced network information here rather than on the main thread,
                // since it queries the hardware interfaces and blocks. It is unrelated to the
                // stall check and far too heavy for the sample rate, so it keeps its own cadence.
                if (now - this.lastStatisticsResetMillis >= Math.max(this.time / 4, 1000L)) {
                    this.lastStatisticsResetMillis = now;
                    server.getNetwork().resetStatistics();
                }

                if (!responding && diff > time * 2) {
                    System.exit(1); // Kill the server if it gets stuck on shutdown
                }

                if (diff <= STALL_WARN_MILLIS) {
                    this.noteResumed();
                    responding = true;
                } else if (responding && diff > time && !server.isBusy()) {
                    StringBuilder builder = new StringBuilder(
                            "--------- Server stopped responding --------- (" + Math.round(diff / 1000d) + "s)").append('\n')
                            .append("Please report this to PowerNukkitX:").append('\n')
                            .append(" - https://github.com/PowerNukkitX/PowerNukkitX/issues/new").append('\n')
                            .append("---------------- Main thread ----------------").append('\n');

                    dumpThread(ManagementFactory.getThreadMXBean().getThreadInfo(this.server.getPrimaryThread().threadId(), Integer.MAX_VALUE), builder);

                    builder.append("---------------- All threads ----------------").append('\n');
                    ThreadInfo[] threads = ManagementFactory.getThreadMXBean().dumpAllThreads(true, true);
                    for (int i = 0; i < threads.length; i++) {
                        if (i != 0) builder.append("------------------------------").append('\n');
                        dumpThread(threads[i], builder);
                    }
                    builder.append("---------------------------------------------").append('\n');
                    log.error(builder.toString());
                    responding = false;
                    this.stalling = false;
                    this.server.forceShutdown();
                } else {
                    this.noteStalled(now, diff);
                }
            }
            try {
                sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException interruption) {
                log.error("The Watchdog Thread has been interrupted and is no longer monitoring the server state", interruption);
                running = false;
                return;
            }
        }
        log.warn("Watchdog was stopped");
    }

    /**
     * Reports a main thread that has stopped ticking but may still come back, with the stack of
     * whatever it is doing. The report repeats while the stall lasts so its progress is visible,
     * and the peak is remembered for {@link #noteResumed()}.
     */
    private void noteStalled(long now, long diff) {
        this.stalling = true;
        this.stallPeakMillis = Math.max(this.stallPeakMillis, diff);
        if (now - this.lastStallReportMillis < STALL_REPORT_INTERVAL_MILLIS) {
            return;
        }
        this.lastStallReportMillis = now;

        StringBuilder builder = new StringBuilder("Main thread has not finished a tick for ")
                .append(Math.round(diff / 1000d)).append("s")
                .append(this.server.isBusy() ? " (server marked busy)" : "")
                .append("; it will be shut down at ").append(Math.round(this.time / 1000d)).append("s. Stack:")
                .append('\n');
        dumpThread(ManagementFactory.getThreadMXBean().getThreadInfo(this.server.getPrimaryThread().threadId(), Integer.MAX_VALUE), builder);
        log.warn(builder.toString());
    }

    /**
     * Closes out a stall that resolved on its own. Without this a hitch is only ever visible as a
     * gap between two timestamps, which says that something happened but not for how long.
     */
    private void noteResumed() {
        if (!this.stalling) {
            return;
        }
        log.warn("Main thread resumed after {}s without finishing a tick", Math.round(this.stallPeakMillis / 1000d));
        this.stalling = false;
        this.stallPeakMillis = 0L;
        this.lastStallReportMillis = 0L;
    }

    private static void dumpThread(ThreadInfo thread, StringBuilder builder) {
        if (thread == null) {
            builder.append("Attempted to dump a null thread!").append('\n');
            return;
        }
        builder.append("Current Thread: ").append(thread.getThreadName()).append('\n');
        builder.append("\tPID: ").append(thread.getThreadId()).append(" | Suspended: ").append(thread.isSuspended()).append(" | Native: ").append(thread.isInNative()).append(" | State: ").append(thread.getThreadState()).append('\n');
        // Monitors
        if (thread.getLockedMonitors().length != 0) {
            builder.append("\tThread is waiting on monitor(s):").append('\n');
            for (MonitorInfo monitor : thread.getLockedMonitors()) {
                builder.append("\t\tLocked on:").append(monitor.getLockedStackFrame()).append('\n');
            }
        }

        builder.append("\tStack:").append('\n');
        for (var stack : thread.getStackTrace()) {
            builder.append("\t\t").append(stack).append('\n');
        }
    }
}
