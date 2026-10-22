package org.powernukkitx;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.async.AsyncQueueFullPolicyFactory;
import org.apache.logging.log4j.core.async.DiscardingAsyncQueueFullPolicy;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped logging configuration: the log file rolls by size as well as by day, nothing deletes
 * the old ones, and a full async queue drops events instead of blocking the thread that logs.
 */
class LoggingConfigurationTest {

    @Test
    void theLogFileRollsBySizeAndKeepsItsHistory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document config = factory.newDocumentBuilder().parse(new File("src/main/resources/log4j2.xml"));

        Element appender = (Element) config.getElementsByTagName("RollingRandomAccessFile").item(0);
        Element size = (Element) appender.getElementsByTagName("SizeBasedTriggeringPolicy").item(0);
        assertEquals("64 MB", size.getAttribute("size"));
        Element strategy = (Element) appender.getElementsByTagName("DefaultRolloverStrategy").item(0);
        assertEquals("32", strategy.getAttribute("max"));
        assertEquals(0, strategy.getElementsByTagName("Delete").getLength(), "old logs are never deleted");
    }

    @Test
    void aFullQueueDiscardsInsteadOfBlocking() {
        assertInstanceOf(DiscardingAsyncQueueFullPolicy.class, AsyncQueueFullPolicyFactory.create());
    }

    @Test
    void warningsClientsTriggerAreRateLimited(@TempDir Path dir) throws Exception {
        // The shipped configuration, writing its log file into the temporary directory
        String xml = Files.readString(Path.of("src/main/resources/log4j2.xml"), StandardCharsets.UTF_8)
            .replace("logs/", dir.toString().replace('\\', '/') + "/");
        Path config = dir.resolve("log4j2.xml");
        Files.writeString(config, xml, StandardCharsets.UTF_8);

        // A context of its own, so the test JVM's logging is left alone
        LoggerContext context = new LoggerContext("logging-configuration-test", null, config.toUri());
        context.start();
        try {
            AtomicInteger warnings = new AtomicInteger();
            AtomicInteger debug = new AtomicInteger();
            AbstractAppender counter = new AbstractAppender("counter", null, null, true, Property.EMPTY_ARRAY) {
                @Override
                public void append(LogEvent event) {
                    if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
                        warnings.incrementAndGet();
                    } else {
                        debug.incrementAndGet();
                    }
                }
            };
            counter.start();
            LoggerConfig root = context.getConfiguration().getRootLogger();
            root.addAppender(counter, null, null);
            root.setLevel(Level.DEBUG);
            context.updateLoggers();

            Logger flooded = context.getLogger("org.powernukkitx.inventory.request.SwapActionProcessor");
            for (int i = 0; i < 300; i++) {
                flooded.warn("mismatch stack network id!");
                flooded.debug("detail {}", i);
            }
            context.getLogger("org.powernukkitx.Server").warn("not rate limited");
            context.stop(5, TimeUnit.SECONDS);

            assertTrue(warnings.get() <= 60, "warnings let through: " + warnings.get());
            assertTrue(warnings.get() >= 2, "the first warnings and other loggers still get through");
            assertEquals(300, debug.get(), "debug output is not rate limited");
        } finally {
            context.stop();
        }
    }
}
