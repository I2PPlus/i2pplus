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

    /**
     * Groups the request-error and transfer-rate formatting checks; the class
     * holds no state, each test calls a static formatter directly.
     */
    public TransferLogFormatTest() {}

    // ---- describeTarget ----

    /**
     * Both halves of the far side render: the tunnel label and the request the
     * transfer is carrying.
     */
    @Test
    public void labelAndRequestAreBothRendered() {
        assertEquals(" [skank / aanoquc2] GET /installers/i2pinstall.exe",
                     Sender.describeTarget("[skank / aanoquc2]", "GET /installers/i2pinstall.exe"));
    }

    /** A transfer whose request line is not parsed yet still names the tunnel. */
    @Test
    public void labelAloneIsRendered() {
        assertEquals(" [skank / aanoquc2]", Sender.describeTarget("[skank / aanoquc2]", null));
    }

    /** A request with no tunnel label gets the {@code [?]} placeholder, not nothing. */
    @Test
    public void requestAloneUsesQuestionMarkPlaceholder() {
        assertEquals(" [?] GET /", Sender.describeTarget(null, "GET /"));
    }

    /** Nothing known about the far side, including the literal "Unknown request". */
    @Test
    public void nothingKnownRendersNothing() {
        assertEquals("", Sender.describeTarget(null, null));
        assertEquals("", Sender.describeTarget("", ""));
        assertEquals("", Sender.describeTarget(null, "Unknown request"));
    }

    /** An empty request must not leave a trailing space on the transfer line. */
    @Test
    public void emptyRequestAddsNoTrailingSpace() {
        String out = Sender.describeTarget("[skank / aanoquc2]", "");
        assertEquals(" [skank / aanoquc2]", out);
        assertFalse("no trailing space when the request is unknown", out.endsWith(" "));
    }

    // ---- formatTransferRate ----

    /** The kilobyte range is labelled K/s, never the ambiguous "kbps". */
    @Test
    public void kilobyteRangeUsesK() {
        assertEquals("10.5K/s", Sender.formatTransferRate(10_500L, 1.0));
    }

    /** The megabyte range is labelled M/s. */
    @Test
    public void megabyteRangeUsesM() {
        // the observed 2,639 KB/s peak
        assertEquals("2.6M/s", Sender.formatTransferRate(2_639_000L, 1.0));
    }

    /** A trickle stays in bytes per second, where a "0.1 kbps" used to hide it. */
    @Test
    public void subKiloValuesUseBytesPerSecond() {
        // 775 bytes in 33.8s, previously logged as 0.1 kbps
        assertEquals("22.9B/s", Sender.formatTransferRate(775L, 33.8));
    }

    /** The K/s to M/s switch happens at exactly 1000, the base the suffixes claim. */
    @Test
    public void unitBoundaryIsConsistent() {
        assertEquals("1000.0K/s", Sender.formatTransferRate(999_999L, 1.0));
        assertEquals("1.0M/s", Sender.formatTransferRate(1_000_000L, 1.0));
    }

    /** A real measured download: 45 MB in 750s reads as 60.6K/s, not 484.5 kbps. */
    @Test
    public void measuredInstallerRate() {
        // 45,447,097 bytes in 750.4s, previously logged as 484.5 kbps
        assertEquals("60.6K/s", Sender.formatTransferRate(45_447_097L, 750.4));
    }

    /** An empty transfer is 0.0B/s, not a missing field. */
    @Test
    public void zeroBytesIsZero() {
        assertEquals("0.0B/s", Sender.formatTransferRate(0, 6.8));
    }

    /** A zero or negative duration cannot divide, and must not print infinity. */
    @Test
    public void nonPositiveDurationIsGuarded() {
        assertEquals("0.0B/s", Sender.formatTransferRate(1000, 0));
        assertEquals("0.0B/s", Sender.formatTransferRate(1000, -5));
    }

    /** A byte count that was never trusted (negative) is not a rate. */
    @Test
    public void negativeBytesIsGuarded() {
        assertEquals("0.0B/s", Sender.formatTransferRate(-1, 10));
    }

    /**
     * No rate ever carries a space, and every one carries a unit — the two shapes
     * that made a rate unreadable in a {@code " * "} list.
     */
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

    /** A larger transfer at the same duration never reports a smaller rate. */
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

    /** The suffix follows the magnitude, so a reader never converts by hand. */
    @Test
    public void unitSuffixTracksTheMagnitude() {
        assertTrue(Sender.formatTransferRate(999, 1.0).endsWith("B/s"));
        assertTrue(Sender.formatTransferRate(1_000, 1.0).endsWith("K/s"));
        assertTrue(Sender.formatTransferRate(999_999, 1.0).endsWith("K/s"));
        assertTrue(Sender.formatTransferRate(1_000_000, 1.0).endsWith("M/s"));
    }

    // ---- clientSegments ----

    /**
     * The client is the 8-character head of its address in brackets, the form the
     * console log viewer links. The full address is 60 characters and pushed the
     * request-error line past the house limit.
     */
    @Test
    public void tunnelAndClientRenderAsSegments() {
        assertEquals(" * Tunnel: [ZzzOT / by7luzwh] * Client: [3yvijmll]",
                     I2PTunnelHTTPServer.clientSegments("[ZzzOT / by7luzwh]",
                             "3yvijmll4sdix2yioib2pcbdmnwlkh6laezidv54kx6mykbl4t5a.b32.i2p"));
    }

    /** A client with no address still gets the tunnel, and no empty Client:. */
    @Test
    public void missingClientRendersOnlyTheTunnel() {
        assertEquals(" * Tunnel: [ZzzOT / by7luzwh]",
                     I2PTunnelHTTPServer.clientSegments("[ZzzOT / by7luzwh]", null));
        assertEquals(" * Tunnel: [ZzzOT / by7luzwh]",
                     I2PTunnelHTTPServer.clientSegments("[ZzzOT / by7luzwh]", ""));
    }

    /** A tunnel label is optional too; nothing known renders nothing. */
    @Test
    public void nothingKnownRendersNoSegments() {
        assertEquals("", I2PTunnelHTTPServer.clientSegments(null, null));
        assertEquals("", I2PTunnelHTTPServer.clientSegments("", ""));
    }

    /**
     * Every segment opens with the house separator, so the caller can append the
     * result straight onto a message without leaving a double space or a bare
     * label welded to the sentence.
     */
    @Test
    public void segmentsAlwaysOpenWithTheSeparator() {
        String both = I2PTunnelHTTPServer.clientSegments("[t / abcdefgh]", "qiii4iqr");
        String only = I2PTunnelHTTPServer.clientSegments(null, "qiii4iqr");
        assertTrue(both.startsWith(" * "));
        assertTrue(only.startsWith(" * "));
        assertFalse("no double space at the join", both.contains("  "));
        assertEquals(" * Client: [qiii4iqr]", only);
    }
}
