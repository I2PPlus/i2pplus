package i2p.susi.webmail.smtp;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests the SMTP capability line. The server's EHLO answer used to be logged as
 * up to three separate fragments, so a reader could not tell which capabilities
 * were absent, and the size limit was a bare byte count.
 *
 * @since 0.9.72+
 */
public class SMTPClientLogWordingTest {

    @Test
    public void testEveryCapabilityIsAnswered() {
        String caps = SMTPClient.capsText(true, 1048576, false);
        assertTrue(caps.contains("Pipelining (PIPELINING): Yes"));
        assertTrue(caps.contains("8-bit bodies (8BITMIME): No"));
    }

    /**
     * The limit is a size, not a count, so it is written with its unit.
     */
    @Test
    public void testSizeLimitCarriesAUnit() {
        assertTrue(SMTPClient.capsText(false, 1048576, true).contains("Max message size (SIZE): 1 MiB"));
    }
}
