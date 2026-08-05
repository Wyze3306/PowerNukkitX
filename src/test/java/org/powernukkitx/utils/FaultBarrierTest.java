package org.powernukkitx.utils;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The throttle is the part of {@link FaultBarrier} that is easy to get backwards: a fault repeating
 * every tick must print once, not twenty times a second, and a distinct fault must never be hidden
 * behind an unrelated one that is already being throttled.
 */
class FaultBarrierTest {

    private CapturingAppender appender;
    private org.apache.logging.log4j.core.Logger logger;

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        CapturingAppender() {
            super("FaultBarrierTestAppender", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    @BeforeEach
    void attachAppender() {
        appender = new CapturingAppender();
        appender.start();
        logger = (org.apache.logging.log4j.core.Logger) LogManagerHolder.context()
                .getLogger(FaultBarrier.class.getName());
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.removeAppender(appender);
        appender.stop();
    }

    @Test
    void printsTheFirstOccurrenceWithItsStackTrace() {
        final IllegalStateException thrown = new IllegalStateException("boom");
        FaultBarrier.report(uniqueContext(), "subject", thrown);

        assertEquals(1, appender.events.size());
        final LogEvent event = appender.events.getFirst();
        assertEquals(Level.ERROR, event.getLevel());
        assertNotNull(event.getThrown(), "the throwable must reach the log, not only its message");
        assertTrue(event.getMessage().getFormattedMessage().contains("subject"));
    }

    @Test
    void throttlesTheSameFaultRepeatingEveryTick() {
        final String context = uniqueContext();
        for (int tick = 0; tick < 100; tick++) {
            FaultBarrier.report(context, "subject", new IllegalStateException("boom"));
        }

        assertEquals(1, appender.events.size(), "a fault repeating every tick must be printed once");
    }

    @Test
    void doesNotHideAnUnrelatedFaultBehindAThrottledOne() {
        final String throttled = uniqueContext();
        for (int tick = 0; tick < 10; tick++) {
            FaultBarrier.report(throttled, "subject", new IllegalStateException("boom"));
        }
        FaultBarrier.report(uniqueContext(), "other subject", new IllegalStateException("boom"));

        assertEquals(2, appender.events.size());
    }

    @Test
    void survivesASubjectWhoseToStringThrows() {
        final Object hostile = new Object() {
            @Override
            public String toString() {
                throw new UnsupportedOperationException();
            }
        };

        FaultBarrier.report(uniqueContext(), hostile, new IllegalStateException("boom"));

        assertEquals(1, appender.events.size());
    }

    /**
     * Faults are throttled per context for the lifetime of the JVM, so every test needs its own.
     */
    private static String uniqueContext() {
        return "a unit test " + System.nanoTime();
    }

    private static final class LogManagerHolder {
        static LoggerContext context() {
            return (LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
        }
    }
}
