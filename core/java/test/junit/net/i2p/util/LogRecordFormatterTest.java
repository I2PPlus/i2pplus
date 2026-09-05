package net.i2p.util;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins the fixed-width thread/class/priority column formatting:
 * names shorter than {@code size} are right-padded, names longer
 * are truncated to the last {@code size - 3} chars with a "..." prefix,
 * so every rendered column is exactly {@code size} chars wide.
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
        // "Server.<remoteHost>.<remotePort>" accept thread (21 chars) -> last 9 chars
        assertEquals("...0.1.7662", LogRecordFormatter.padOrTruncate("Server.127.0.0.1.7662", WIDTH));
        // streaming per-connection thread (20 chars) -> last 9 chars
        assertEquals("...er:Dj66.2", LogRecordFormatter.padOrTruncate("SomeApp-Web:Aj:er:Dj66.2", WIDTH));
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
        // exactly size+1 chars -> last (size-3)+1 kept with prefix
        String thirteen = "1234567890123";
        assertEquals("...234567890123".substring(0, WIDTH), LogRecordFormatter.padOrTruncate(thirteen, WIDTH));
        // one char short of ellipsizing stays unchanged, padded
        assertEquals("12345678901 ", LogRecordFormatter.padOrTruncate("12345678901", WIDTH));
    }

    @Test
    public void testVeryLongNameStaysBoundsChecked() {
        String huge = "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";
        assertEquals(WIDTH, LogRecordFormatter.padOrTruncate(huge, WIDTH).length());
        assertTrue(LogRecordFormatter.padOrTruncate(huge, WIDTH).startsWith("..."));
    }
}