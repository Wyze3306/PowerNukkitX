package org.powernukkitx.utils;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the {@code <replace>} rule that hides player addresses from the console, straight out of
 * the shipped {@code log4j2.xml} - a typo there fails here rather than on a production console.
 * <p>
 * The interesting half of the rule is what it must <em>not</em> touch: version numbers, durations
 * and MAC addresses are made of the same characters as addresses, and mangling them would make the
 * console harder to read than the leak it prevents.
 */
class ConsoleIpMaskingTest {

    /**
     * Read from the source tree rather than the classpath, because {@code src/test/resources}
     * ships its own {@code log4j2.xml} which shadows the real one during tests.
     */
    private static final Path CONFIG = Path.of("src", "main", "resources", "log4j2.xml");

    private static Element appender(String name) throws Exception {
        assertTrue(Files.isReadable(CONFIG), "cannot read " + CONFIG.toAbsolutePath());
        try (InputStream in = Files.newInputStream(CONFIG)) {
            NodeList appenders = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(in)
                .getElementsByTagName("Appenders")
                .item(0)
                .getChildNodes();
            for (int i = 0; i < appenders.getLength(); i++) {
                if (appenders.item(i) instanceof Element element && name.equals(element.getAttribute("name"))) {
                    return element;
                }
            }
            throw new AssertionError("log4j2.xml has no appender named " + name);
        }
    }

    private static Element replaceRuleOf(String appenderName) throws Exception {
        NodeList rules = appender(appenderName).getElementsByTagName("replace");
        return rules.getLength() == 0 ? null : (Element) rules.item(0);
    }

    /**
     * Applies the console rule to a line exactly the way log4j does - {@code RegexReplacement} is a
     * plain {@link java.util.regex.Matcher#replaceAll(String)} over the rendered line.
     */
    private static String mask(String line) {
        return CONSOLE_RULE.matcher(line).replaceAll(CONSOLE_REPLACEMENT);
    }

    private static final Pattern CONSOLE_RULE;
    private static final String CONSOLE_REPLACEMENT;

    static {
        try {
            Element rule = replaceRuleOf("Console");
            assertNotNull(rule, "the Console appender no longer masks addresses");
            CONSOLE_RULE = Pattern.compile(rule.getAttribute("regex"));
            CONSOLE_REPLACEMENT = rule.getAttribute("replacement");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Test
    void masksThePlayerAddressOfAJoinLine() {
        assertEquals("Wyze[/51.83.*:19132] logged in with entity id 4 at (12.5, 64.0, -33.25)",
            mask("Wyze[/51.83.42.7:19132] logged in with entity id 4 at (12.5, 64.0, -33.25)"));
    }

    @Test
    void masksEveryAddressOfALine() {
        assertEquals("L:/0.0.0.0:19132 - R:/203.0.*:5678",
            mask("L:/0.0.0.0:19132 - R:/203.0.113.9:5678"));
    }

    @Test
    void masksAddressesInsideStackTraces() {
        assertEquals("\tSuppressed: java.io.IOException: closed /198.51.*:5678",
            mask("\tSuppressed: java.io.IOException: closed /198.51.100.4:5678"));
    }

    @Test
    void keepsPrivateAndLoopbackAddressesReadable() {
        assertEquals("bound to 0.0.0.0:19132", mask("bound to 0.0.0.0:19132"));
        assertEquals("proxy at 127.0.0.1:19133", mask("proxy at 127.0.0.1:19133"));
        assertEquals("lan peer 192.168.1.42", mask("lan peer 192.168.1.42"));
        assertEquals("lan peer 10.0.0.8", mask("lan peer 10.0.0.8"));
        assertEquals("lan peer 172.20.3.4", mask("lan peer 172.20.3.4"));
        assertEquals("link local 169.254.7.7", mask("link local 169.254.7.7"));
        assertEquals("multicast 239.255.255.250", mask("multicast 239.255.255.250"));
    }

    @Test
    void keepsPrefixedVersionNumbersAlone() {
        assertEquals("running v1.4.0.0", mask("running v1.4.0.0"));
        assertEquals("Minecraft: BE 1.26.40 (protocol 2168)", mask("Minecraft: BE 1.26.40 (protocol 2168)"));
        assertEquals("netty 4.1.115.Final", mask("netty 4.1.115.Final"));
        assertEquals("This server is running PowerNukkitX version git-903795 (API 3.0.1)",
            mask("This server is running PowerNukkitX version git-903795 (API 3.0.1)"));
    }

    /**
     * Known limitation: a bare four-part version is the same string as an address, so it loses. The
     * usual spellings survive - {@code v1.4.0.0} is shielded by its prefix and the versions the
     * server itself prints have three parts - and a masked version number is a cosmetic problem
     * where a printed address would be a leak.
     */
    @Test
    void masksBareFourPartVersions() {
        assertEquals("plugin 1.0.*", mask("plugin 1.0.0.1"));
    }

    @Test
    void leavesLongerDottedSequencesAlone() {
        assertEquals("build 1.2.3.4.5", mask("build 1.2.3.4.5"));
        assertEquals("out of range 300.1.2.3", mask("out of range 300.1.2.3"));
    }

    @Test
    void masksIpv6() {
        assertEquals("session [2a01:cb08:*]:19132", mask("session [2a01:cb08:8c5c:1a00:1:2:3:4]:19132"));
        assertEquals("from 2001:db8:*", mask("from 2001:db8::1"));
    }

    @Test
    void keepsLocalIpv6Readable() {
        assertEquals("link local fe80::1%eth0", mask("link local fe80::1%eth0"));
        assertEquals("unique local fd00::5", mask("unique local fd00::5"));
        assertEquals("bound to ::1", mask("bound to ::1"));
        assertEquals("bound to ::", mask("bound to ::"));
    }

    @Test
    void leavesColonSeparatedTextAlone() {
        assertEquals("took 12:34:56", mask("took 12:34:56"));
        assertEquals("mac aa:bb:cc:dd:ee:ff", mask("mac aa:bb:cc:dd:ee:ff"));
        assertEquals("see Level::getChunk", mask("see Level::getChunk"));
        assertEquals("at 01:23:45.678", mask("at 01:23:45.678"));
    }

    /**
     * The rule is deliberately scoped to what is on screen; {@code logs/server.log} still needs full
     * addresses to be of any use when tracking ban evasion.
     */
    @Test
    void theFileAppenderKeepsFullAddresses() throws Exception {
        assertNull(replaceRuleOf("File"), "the File appender masks addresses, which was not intended");
    }
}
