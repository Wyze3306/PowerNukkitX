package org.powernukkitx.utils;

import org.powernukkitx.Server;
import lombok.extern.slf4j.Slf4j;

import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;

@Slf4j
public class Watchdog extends Thread {
    /**
     * How long {@link System#exit} may take before the process is halted outright. Exit runs the
     * shutdown hooks, and the server's own hook can block on the same lock as the main thread.
     */
    private static final long EXIT_GRACE_MILLIS = 15_000L;

    private final Server server;
    private final long time;
    public volatile boolean running;

    /** When the server was declared dead, 0 while it is alive. Never reset: past it the process goes. */
    private long fatalAtMillis;
    private long exitRequestedAtMillis;

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
        // forceShutdown clears running when it is done, but a server declared dead is only done
        // once the process is gone: its main thread is still stuck and keeps the JVM alive.
        while (this.running || this.fatalAtMillis != 0) {
            //Refresh the advanced network information in watchdog, as this is a time-consuming operation and will block the main thread
            server.getNetwork().resetStatistics();

            long current = server.getNextTick();
            if (this.fatalAtMillis != 0) {
                this.bringDown(System.currentTimeMillis());
            } else if (current != 0) {
                var now = System.currentTimeMillis();
                long diff = now - current;
                if (diff > time && now - server.getBusyingTime() < 60) {
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
                    this.fatalAtMillis = now;
                    // Not on this thread: forceShutdown kicks and saves every player, and one of
                    // them can wait on the very lock that froze the main thread. Run here, it hung
                    // the watchdog with it and the process stayed up, frozen, for eight hours.
                    Thread shutdown = new Thread(this.server::forceShutdown, "Watchdog shutdown");
                    shutdown.setDaemon(true);
                    shutdown.start();
                }
            }
            try {
                sleep(Math.max(time / 4, 1000));
            } catch (InterruptedException interruption) {
                log.error("The Watchdog Thread has been interrupted and is no longer monitoring the server state", interruption);
                running = false;
                return;
            }
        }
        log.warn("Watchdog was stopped");
    }

    /**
     * Ends a process that was declared dead. The shutdown gets {@code time} to save what it can,
     * then {@link System#exit} is asked for from a thread of its own, and if the shutdown hooks
     * still have not let it finish after {@link #EXIT_GRACE_MILLIS} the process is halted. Either
     * way the supervisor sees a non-zero exit and restarts the server.
     */
    private void bringDown(long now) {
        if (now - this.fatalAtMillis < this.time) {
            return;
        }
        if (this.exitRequestedAtMillis == 0) {
            this.exitRequestedAtMillis = now;
            log.error("Server still running {}s after it stopped responding, exiting", Math.round((now - this.fatalAtMillis) / 1000d));
            Thread exit = new Thread(() -> System.exit(1), "Watchdog exit");
            exit.setDaemon(true);
            exit.start();
        } else if (now - this.exitRequestedAtMillis >= EXIT_GRACE_MILLIS) {
            System.err.println("[Watchdog] Exit blocked by a shutdown hook for " + Math.round((now - this.exitRequestedAtMillis) / 1000d) + "s, halting");
            Runtime.getRuntime().halt(1);
        }
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
