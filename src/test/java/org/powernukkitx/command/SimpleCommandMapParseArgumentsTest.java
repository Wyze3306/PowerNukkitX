package org.powernukkitx.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SimpleCommandMap#parseArguments(String)}.
 * <p>
 * Quote stripping used to delete characters in place, which shifted the rest of the line down by one
 * on every quote and made parsing cost O(n^2). Because a command line is only bounded by the batch
 * size and compresses roughly 1000:1 on the wire, a single {@code CommandRequestPacket} could hold
 * the tick thread for seconds. The parser now appends kept characters to a separate buffer, so the
 * timed test below is the regression guard for that.
 */
class SimpleCommandMapParseArgumentsTest {

    @Test
    void splitsOnSpacesAndDropsEmptyArguments() {
        assertEquals(new ArrayList<>(java.util.List.of("give", "a", "b")), SimpleCommandMap.parseArguments("give a b"));
        assertEquals(new ArrayList<>(java.util.List.of("a", "b")), SimpleCommandMap.parseArguments("  a  b  "));
        assertEquals(new ArrayList<>(), SimpleCommandMap.parseArguments(""));
    }

    @Test
    void quotesGroupArgumentsAndAreStripped() {
        assertEquals(new ArrayList<>(java.util.List.of("say", "hello world")), SimpleCommandMap.parseArguments("say \"hello world\""));
        assertEquals(new ArrayList<>(java.util.List.of("tp", "na me", "1")), SimpleCommandMap.parseArguments("tp \"na me\" 1"));
        assertEquals(new ArrayList<>(java.util.List.of("ab")), SimpleCommandMap.parseArguments("a\"\"b"));
    }

    @Test
    void squareBracketsAndBracesKeepTheirContentTogether() {
        assertEquals(new ArrayList<>(java.util.List.of("tp", "@e[type=cow x=1]", "1")), SimpleCommandMap.parseArguments("tp @e[type=cow x=1] 1"));
        assertEquals(new ArrayList<>(java.util.List.of("a", "{b c}", "d")), SimpleCommandMap.parseArguments("a {b c} d"));
    }

    @Test
    void unbalancedSquareBracketFallsBackToUngroupedParsing() {
        // The first pass returns null, so the whole line is parsed a second time without grouping.
        assertEquals(new ArrayList<>(java.util.List.of("give", "[a", "b")), SimpleCommandMap.parseArguments("give [a b"));
    }

    @Test
    void leadingBraceIsParsedInsteadOfThrowing() {
        // Reading the preceding character used to run off the front of the line for a leading '{'.
        assertDoesNotThrow(() -> SimpleCommandMap.parseArguments("{"));
        assertEquals(new ArrayList<>(java.util.List.of("{a}")), SimpleCommandMap.parseArguments("{a}"));
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void parsingManyQuotesStaysLinear() {
        // Compares how the cost grows rather than the wall time itself, because coverage
        // instrumentation slows this loop by about a hundred times and would make any absolute
        // budget meaningless. Doubling the input doubles the work when parsing is linear and
        // quadruples it when it is quadratic, and that ratio survives a constant-factor slowdown.
        int size = 100_000;
        parseQuotes(size); // let the JIT settle before anything is measured
        parseQuotes(2 * size);

        long single = timeParseQuotes(size);
        long that = timeParseQuotes(2 * size);
        double growth = (double) that / Math.max(single, 1);

        assertTrue(growth < 3.0,
                "doubling the quote count multiplied the parsing cost by " + String.format("%.1f", growth)
                        + ", expected about 2 for linear parsing (" + single + " ns then " + that + " ns)");
    }

    private static long timeParseQuotes(int quotes) {
        long start = System.nanoTime();
        parseQuotes(quotes);
        return System.nanoTime() - start;
    }

    /** Parses {@code quotes} quote characters behind an unclosed '[' so both parsing passes run. */
    private static void parseQuotes(int quotes) {
        assertNotNull(SimpleCommandMap.parseArguments("help [" + "\"".repeat(quotes)));
    }
}
