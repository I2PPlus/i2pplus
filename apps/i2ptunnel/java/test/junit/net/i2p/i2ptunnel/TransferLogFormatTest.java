package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import net.i2p.i2ptunnel.I2PTunnelHTTPServer.Sender;

import org.junit.Test;

/**
 * Tests the transfer log formatting helpers:
 * {@link Sender#describeTarget(String, String)} and
 * {@link Sender#formatTransferRate(long, double)}.
 *
 * <p>Two gaps motivated these. The request direction logged a bare
 * "from Client -&gt; Server" with no indication of which eepsite or client the
 * transfer belonged to. And rates were printed as "kbps", which is ambiguous
 * against a 1000/1024 base and reads as bits per second; they now read as
 * bytes per second, e.g. {@code 10.5K/s}.
 *
 * @since 0.9.71+
 */
public class TransferLogFormatTest {

    // ---- describeTarget ----

    @Test
    public void labelAndRequestAreBothRendered() {
        assertEquals(" [skank / aanoquc2] GET /installers/i2pinstall.exe",
                     Sender.describeTarget("[skank / aanoquc2]", "GET /installers/i2pinstall.exe"));
    }

    @Test
    public void labelAloneIsRendered() {
        assertEquals(" [skank / aanoquc2]", Sender.describeTarget("[skank / aanoquc2]", null));
    }

    @Test
    public void requestAloneUsesQuestionMarkPlaceholder() {
        assertEquals(" [?] GET /", Sender.describeTarget(null, "GET /"));
    }

    @Test
    public void nothingKnownRendersNothing() {
        assertEquals("", Sender.describeTarget(null, null));
        assertEquals("", Sender.describeTarget("", ""));
        assertEquals("", Sender.describeTarget(null, "Unknown request"));
    }

    @Test
    public void emptyRequestAddsNoTrailingSpace() {
        String out = Sender.describeTarget("[skank / aanoquc2]", "");
        assertEquals(" [skank / aanoquc2]", out);
        assertFalse("no trailing space when the request is unknown", out.endsWith(" "));
    }

    // ---- formatTransferRate ----

    @Test
    public void kilobyteRangeUsesK() {
        assertEquals("10.5K/s", Sender.formatTransferRate(10_500L, 1.0));
    }

    @Test
    public void megabyteRangeUsesM() {
        // the observed 2,639 KB/s peak
        assertEquals("2.6M/s", Sender.formatTransferRate(2_639_000L, 1.0));
    }

    @Test
    public void subKiloValuesUseBytesPerSecond() {
        // 775 bytes in 33.8s, previously logged as 0.1 kbps
        assertEquals("22.9B/s", Sender.formatTransferRate(775L, 33.8));
    }

    @Test
    public void unitBoundaryIsConsistent() {
        assertEquals("1000.0K/s", Sender.formatTransferRate(999_999L, 1.0));
        assertEquals("1.0M/s", Sender.formatTransferRate(1_000_000L, 1.0));
    }

    @Test
    public void measuredInstallerRate() {
        // 45,447,097 bytes in 750.4s, previously logged as 484.5 kbps
        assertEquals("60.6K/s", Sender.formatTransferRate(45_447_097L, 750.4));
    }

    @Test
    public void zeroBytesIsZero() {
        assertEquals("0.0B/s", Sender.formatTransferRate(0, 6.8));
    }

    @Test
    public void nonPositiveDurationIsGuarded() {
        assertEquals("0.0B/s", Sender.formatTransferRate(1000, 0));
        assertEquals("0.0B/s", Sender.formatTransferRate(1000, -5));
    }

    @Test
    public void negativeBytesIsGuarded() {
        assertEquals("0.0B/s", Sender.formatTransferRate(-1, 10));
    }

    @Test
    public void outputNeverCarriesASpace() {
        long[] byteCounts = { 0, 1, 1024, 999_999, 1_000_000, 45_447_097L, 1L << 40 };
        double[] times = { 0.001, 1.0, 33.8, 750.4, 3600.0 };
        for (long bytes : byteCounts) {
            for (double secs : times) {
                String out = Sender.formatTransferRate(bytes, secs);
                assertFalse("space in '" + out + "'", out.contains(" "));
                assertTrue("no unit in '" + out + "'", out.endsWith("/s"));
            }
        }
    }

    @Test
    public void rateIsMonotonicInBytesAtFixedDuration() {
        // Compare in a single unit: the suffix changes at 1KB/s and 1MB/s, so
        // comparing bare numbers across a boundary would read 1000.0K/s as
        // "larger" than the equal 1.0M/s that follows it.
        double previous = 0;
        for (long bytes = 0; bytes < 5_000_000L; bytes += 250_000L) {
            double perSec = bytes;   // secs == 1.0
            assertTrue("rate shrank at " + bytes + " bytes: "
                       + previous + " -> " + perSec, perSec >= previous);
            previous = perSec;
        }
    }

    @Test
    public void unitSuffixTracksTheMagnitude() {
        assertTrue(Sender.formatTransferRate(999, 1.0).endsWith("B/s"));
        assertTrue(Sender.formatTransferRate(1_000, 1.0).endsWith("K/s"));
        assertTrue(Sender.formatTransferRate(999_999, 1.0).endsWith("K/s"));
        assertTrue(Sender.formatTransferRate(1_000_000, 1.0).endsWith("M/s"));
    }
}
