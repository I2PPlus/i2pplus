package net.i2p.util;

import static org.junit.Assert.*;

import org.junit.Test;

import net.i2p.I2PAppContext;

/**
 * Pins the fixed-width thread/class/priority column formatting:
 * names shorter than {@code size} are right-padded, names longer
 * are truncated to the last {@code size - 1} chars with the one-character
 * ellipsis prefixed, so every rendered column is exactly {@code size}
 * chars wide and needs no padding compensation on the page.
 * Pins the cached priority labels against the per-record lookup they replace.
 */
public class LogRecordFormatterTest {

    private static final int WIDTH = 12;

    @Test
    public void testPadsShortNameToFullWidth() {
        assertEquals("JobQueue.26 ", LogRecordFormatter.padOrTruncate("JobQueue.26", WIDTH));
        assertEquals("UDPHandle.7 ", LogRecordFormatter.padOrTruncate("UDPHandle.7", WIDTH));
    }

    @Test
    public void testExactWidthNameUnchanged() {
        assertEquals("UDPHandle.12", LogRecordFormatter.padOrTruncate("UDPHandle.12", WIDTH));
        assertEquals("DomClnListen", LogRecordFormatter.padOrTruncate("DomClnListen", WIDTH));
    }

    @Test
    public void testEllipsizesPreservingSuffix() {
        // "Server.<remoteHost>.<remotePort>" accept thread (21 chars) -> last 11 chars
        assertEquals("….0.0.1.7662", LogRecordFormatter.padOrTruncate("Server.127.0.0.1.7662", WIDTH));
        // streaming per-connection thread (24 chars) -> last 11 chars
        assertEquals("…j:er:Dj66.2", LogRecordFormatter.padOrTruncate("SomeApp-Web:Aj:er:Dj66.2", WIDTH));
    }

    @Test
    public void testNullTreatedAsEmpty() {
        assertEquals("            ", LogRecordFormatter.padOrTruncate(null, WIDTH));
        assertEquals("            ", LogRecordFormatter.padOrTruncate("", WIDTH));
    }

    @Test
    public void testOutputWidthAlwaysExact() {
        String[] names = {
            null, "", "x", "JobQueue.26", "UDPHandle.12",
            "Server.127.0.0.1.7662", "SomeApp-Web:Aj:er:Dj66.2",
            "SAM-PoolWkr.9", "AAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "EepGetDecompress", "ShutdownTask.foo.BarBaz", "SnarkSender.12345:1.2.3.4"
        };
        for (String name : names) {
            assertEquals(WIDTH, LogRecordFormatter.padOrTruncate(name, WIDTH).length());
        }
    }

    @Test
    public void testEllipsisAtBoundary() {
        // exactly size+1 chars -> last (size-1) kept with prefix, still exactly size wide
        String thirteen = "1234567890123";
        assertEquals("…34567890123", LogRecordFormatter.padOrTruncate(thirteen, WIDTH));
        // one char short of ellipsizing stays unchanged, padded
        assertEquals("12345678901 ", LogRecordFormatter.padOrTruncate("12345678901", WIDTH));
    }

    @Test
    public void testVeryLongNameStaysBoundsChecked() {
        String huge = "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";
        assertEquals(WIDTH, LogRecordFormatter.padOrTruncate(huge, WIDTH).length());
        assertTrue(LogRecordFormatter.padOrTruncate(huge, WIDTH).startsWith("…"));
    }

    @Test
    public void testLabelIndexCoversTheLevels() {
        assertEquals(0, LogRecordFormatter.labelIndex(Log.DEBUG));
        assertEquals(1, LogRecordFormatter.labelIndex(Log.INFO));
        assertEquals(2, LogRecordFormatter.labelIndex(Log.WARN));
        assertEquals(3, LogRecordFormatter.labelIndex(Log.ERROR));
        assertEquals(4, LogRecordFormatter.labelIndex(Log.CRIT));
    }

    /**
     * The cache holds five labels, so an out-of-range level has to land on the
     * end the way {@link Log#toLevelString} sends it, or the two disagree.
     */
    @Test
    public void testLabelIndexClampsLikeToLevelString() {
        String[] names = {"DEBUG", "INFO", "WARN", "ERROR", "CRIT"};
        for (int level = -20; level <= 80; level++)
            assertEquals(Log.toLevelString(level), names[LogRecordFormatter.labelIndex(level)]);
    }

    /**
     * The cached label must be exactly what the per-record lookup produced,
     * which is the whole point of caching it.
     */
    @Test
    public void testCachedLabelsMatchTheDirectLookup() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        String lang = Translate.getLanguage(ctx);
        int len = "de".equals(lang) ? 8 : 5;
        LogRecordFormatter.Priorities p = LogRecordFormatter.resolvePriorities(lang, ctx);
        for (int i = 0; i < 5; i++) {
            String direct = LogRecordFormatter.padOrTruncate(
                    Translate.getString(Log.toLevelString(Log.DEBUG + i * 10), ctx,
                                        "net.i2p.util.messages"), len);
            assertEquals(direct, p._labels[i]);
        }
    }

    /**
     * German gets the wider column for KRITISCH, and each language keeps its
     * own labels: a probe of one must not evict the router's own, or two
     * contexts would rebuild the set on every record.
     */
    @Test
    public void testEachLanguageKeepsItsOwnLabels() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        String lang = Translate.getLanguage(ctx);
        LogRecordFormatter.resolvePriorities(lang, ctx);
        LogRecordFormatter.resolvePriorities("de", ctx);
        LogRecordFormatter.resolvePriorities("en", ctx);
        assertEquals(8, LogRecordFormatter.cachedPriorities("de")._labels[4].length());
        assertEquals(5, LogRecordFormatter.cachedPriorities("en")._labels[4].length());
        assertNotNull(LogRecordFormatter.cachedPriorities(lang));
    }
}
