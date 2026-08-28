package org.powernukkitx.network.security;

import org.powernukkitx.config.category.network.RateLimitSettings;
import com.google.common.util.concurrent.RateLimiter;

import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

/**
 * Per-player inbound packet rate limiters.
 * <p>
 * One instance lives on {@link org.powernukkitx.PlayerHandle} for the lifetime of the player's session.
 * All limiters use {@link RateLimiter#tryAcquire()} (non-blocking)
 * <p>
 * A denied packet is dropped immediately rather than stalling the processing thread.
 * <p>
 * Denials are counted per category and summarised by {@link DeniedPacketLog}, so a client saturating
 * a limiter shows up in the console without the drop path itself printing anything. Callers only have
 * to honour the {@code boolean}; they do not report anything themselves.
 */
public final class PacketRateLimiter {
    /**
     * How many packets one category may have denied before it is worth a line. A client that trips a
     * limiter now and then is normal, so this sits well above the incidental case.
     */
    private static final int REPORT_THRESHOLD = 100;

    private final RateLimiter command;
    private final RateLimiter chat;
    private final RateLimiter formResponse;
    private final RateLimiter movement;
    private final RateLimiter worldInteraction;

    private final DeniedPacketLog commandDenied;
    private final DeniedPacketLog chatDenied;
    private final DeniedPacketLog formResponseDenied;
    private final DeniedPacketLog movementDenied;
    private final DeniedPacketLog worldInteractionDenied;

    /**
     * @param subject who the packets come from, used when a saturated limiter is summarised; only
     *                evaluated at that point, so a named player costs nothing per denied packet
     */
    public PacketRateLimiter(RateLimitSettings settings, @NotNull Supplier<String> subject) {
        this.command = create(settings.maxCommandsPerSecondPerPlayer());
        this.chat = create(settings.maxChatPerSecondPerPlayer());
        this.formResponse = create(settings.maxFormResponsesPerSecondPerPlayer());
        this.movement = create(settings.maxMovementPacketsPerSecondPerPlayer());
        this.worldInteraction = create(settings.maxWorldInteractionPacketsPerSecondPerPlayer());

        this.commandDenied = new DeniedPacketLog("commands over the rate limit", REPORT_THRESHOLD, subject);
        this.chatDenied = new DeniedPacketLog("chat packets over the rate limit", REPORT_THRESHOLD, subject);
        this.formResponseDenied = new DeniedPacketLog("form responses over the rate limit", REPORT_THRESHOLD, subject);
        this.movementDenied = new DeniedPacketLog("movement packets over the rate limit", REPORT_THRESHOLD, subject);
        this.worldInteractionDenied = new DeniedPacketLog("world interaction packets over the rate limit", REPORT_THRESHOLD, subject);
    }

    private static RateLimiter create(int permitsPerSecond) {
        return RateLimiter.create(Math.max(1, permitsPerSecond));
    }

    private static boolean acquire(RateLimiter limiter, DeniedPacketLog denied) {
        if (limiter.tryAcquire()) {
            return true;
        }
        denied.record();
        return false;
    }

    /** @return true if the command packet should be processed, false if it should be dropped. */
    public boolean tryCommand() {
        return acquire(this.command, this.commandDenied);
    }

    /** @return true if the chat packet should be processed, false if it should be dropped. */
    public boolean tryChat() {
        return acquire(this.chat, this.chatDenied);
    }

    /** @return true if the form response should be processed, false if it should be dropped. */
    public boolean tryFormResponse() {
        return acquire(this.formResponse, this.formResponseDenied);
    }

    /** @return true if the movement packet should be processed, false if it should be dropped. */
    public boolean tryMovement() {
        return acquire(this.movement, this.movementDenied);
    }

    public boolean tryWorldInteraction() {
        return acquire(this.worldInteraction, this.worldInteractionDenied);
    }
}
