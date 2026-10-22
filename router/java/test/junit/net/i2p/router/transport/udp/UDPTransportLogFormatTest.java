package net.i2p.router.transport.udp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The entries written when a second session replaces an existing peer. The old
 * form ran "Old = ..." and "old=..." together with both peers on one line,
 * against the house rules on separators, key style and line length.
 */
public class UDPTransportLogFormatTest {

    /** Both peers are named, each on its own continuation line. */
    @Test
    public void replacementEntryPutsEachPeerOnItsOwnLine() {
        assertEquals("Router already connected (PBID) -> Replacing the previous peer" +
                     "\n* Old: [abc123] outbound v0.9.62 1234567890" +
                     "\n* New: [def456] outbound v0.9.62 1234567890",
                     UDPTransport.alreadyConnectedLine("PBID", "[abc123] outbound v0.9.62 1234567890",
                                                       "[def456] outbound v0.9.62 1234567890"));
    }

    /** The host-id collision reads the same way, only the tag differs. */
    @Test
    public void hostIdCollisionNamesTheMapThatCollided() {
        String entry = UDPTransport.alreadyConnectedLine("PBRH", "[abc123] inbound v0.9.62 7",
                                                         "[def456] inbound v0.9.62 7");
        assertTrue("the tag must say which map held the peer: " + entry, entry.contains("(PBRH)"));
        for (String line : entry.split("\n")) {
            assertTrue("line over 100 chars (" + line.length() + "): " + line, line.length() <= 100);
        }
    }
}
