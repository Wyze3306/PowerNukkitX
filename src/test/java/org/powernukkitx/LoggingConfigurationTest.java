package org.powernukkitx;

import org.apache.logging.log4j.core.async.AsyncQueueFullPolicyFactory;
import org.apache.logging.log4j.core.async.DiscardingAsyncQueueFullPolicy;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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
}
