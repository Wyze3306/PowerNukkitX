package org.powernukkitx.network.security;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DeniedPacketLog}.
 * <p>
 * The point of the class is that a client cannot turn a denied packet into a console line, so these
 * tests assert on what actually reaches the logger rather than on the counters.
 */
class DeniedPacketLogTest {

    private CapturingAppender appender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        this.appender = new CapturingAppender();
        this.appender.start();
        this.logger = (Logger) LogManager.getLogger(DeniedPacketLog.class);
        this.logger.addAppender(this.appender);
    }

    @AfterEach
    void detachAppender() {
        this.logger.removeAppender(this.appender);
        this.appender.stop();
    }

    @Test
    void staysSilentBelowTheThreshold() {
        DeniedPacketLog log = new DeniedPacketLog("chat packets", 100, () -> "Tester");
        for (int i = 0; i < 99; i++) {
            log.record();
        }
        assertEquals(List.of(), this.appender.messages(), "a client under the threshold must not reach the console");
        assertEquals(99, log.total());
    }

    @Test
    void reportsOnceWhenTheThresholdIsCrossed() {
        DeniedPacketLog log = new DeniedPacketLog("chat packets", 100, () -> "Tester");
        for (int i = 0; i < 100; i++) {
            log.record();
        }
        assertEquals(1, this.appender.messages().size());
        String line = this.appender.messages().getFirst();
        assertTrue(line.contains("Tester"), line);
        assertTrue(line.contains("100"), line);
        assertTrue(line.contains("chat packets"), line);
    }

    @Test
    void floodingProducesASingleLine() {
        DeniedPacketLog log = new DeniedPacketLog("chat packets", 100, () -> "Tester");
        for (int i = 0; i < 1_000_000; i++) {
            log.record();
        }
        // Everything after the first report falls inside the same window, so the client gains nothing
        // by sending more. This is the anti-flood guarantee the drop paths rely on.
        assertEquals(1, this.appender.messages().size(), "a flood must not scale into more log lines");
        assertEquals(1_000_000, log.total(), "every denied packet is still counted");
    }

    @Test
    void summaryNamesTheMostRecentCause() {
        DeniedPacketLog log = new DeniedPacketLog("inbound packets as malformed", 2, () -> "Tester");
        log.record("IllegalArgumentException on TextPacket");
        log.record("IndexOutOfBoundsException on ItemStackRequestPacket");
        assertEquals(1, this.appender.messages().size());
        assertTrue(this.appender.messages().getFirst().contains("IndexOutOfBoundsException on ItemStackRequestPacket"),
                this.appender.messages().getFirst());
    }

    @Test
    void subjectFailingToDescribeItselfStillReports() {
        DeniedPacketLog log = new DeniedPacketLog("chat packets", 1, () -> {
            throw new IllegalStateException("no name yet");
        });
        assertDoesNotThrow(() -> log.record());
        assertEquals(1, this.appender.messages().size());
    }

    private static final class CapturingAppender extends AbstractAppender {

        private final List<String> messages = new CopyOnWriteArrayList<>();

        private CapturingAppender() {
            super("DeniedPacketLogTestAppender", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
                this.messages.add(event.getMessage().getFormattedMessage());
            }
        }

        private List<String> messages() {
            return List.copyOf(this.messages);
        }
    }
}
