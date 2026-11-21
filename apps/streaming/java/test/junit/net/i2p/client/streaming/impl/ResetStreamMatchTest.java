package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import org.junit.Test;

/**
 * Pins which RST packet a connection may act on, and what the ignored one
 * reports.
 *
 * <p>A peer resets the stream it knows about, so the packet carries our
 * receive stream ID in its send stream ID field. Packets are routed to a
 * connection by destination pair, so a reset for a stream we no longer have
 * lands on whatever live connection we have to the same peer, and acting on
 * it there would kill a working stream. In a live router's 133-minute window
 * all 83 of these drops were one tunnel peer resetting a connect attempt it
 * had just NACKed, while the line printed said only "Ignored" - the IDs that
 * made it a different stream were nowhere, so a stale reset could not be told
 * apart from a forged one.
 *
 * @since 0.9.72+
 */
public class ResetStreamMatchTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for
     * each one; both helpers are pure, so nothing is set up.
     */
    public ResetStreamMatchTest() {}

    /**
     * A reset carrying our own receive stream ID is for this connection and
     * has to be acted on - that is the whole point of the check.
     */
    @Test
    public void testOwnStreamIdIsActedOn() {
        assertTrue(ConnectionPacketHandler.resetForThisConnection(0x1234L,
                                                                 0x1234L));
    }

    /**
     * A reset for any other stream ID is not ours. The values are a live
     * connection's receive ID and the ID of a connect attempt the peer NACKed
     * and reset a moment later.
     */
    @Test
    public void testAnotherStreamsIdIsIgnored() {
        long ours = 0x1234L;
        long theirs = 0x5678L;
        assertFalse(ConnectionPacketHandler.resetForThisConnection(ours,
                                                                  theirs));
    }

    /**
     * A connection still being built has no receive stream ID yet, and neither
     * does a reset sent before the handshake completed, so the two zeros match
     * and the reset is acted on, as it always was.
     */
    @Test
    public void testBothUnsetStillMatches() {
        assertTrue(ConnectionPacketHandler.resetForThisConnection(0L, 0L));
        assertFalse("unset against a set ID is not ours",
                    ConnectionPacketHandler.resetForThisConnection(0L,
                                                                   0x5678L));
        assertFalse("a set ID against an unset connection is not ours",
                    ConnectionPacketHandler.resetForThisConnection(0x1234L,
                                                                   0L));
    }

    /**
     * The ignored reset names both stream IDs in the same base64 form the
     * packet dump lines use, so the dropped reset can be matched to the SYN or
     * data line it belongs to. The tokens are decoded back to the numbers they
     * came from, which pins the form without restating the encoder.
     */
    @Test
    public void testClauseCarriesBothIdsInDumpForm() {
        long packetSend = 0x6c0c5f7aL;
        long ourReceive = 0x1234L;
        String clause = ConnectionPacketHandler.resetStreamClause(packetSend,
                                                                 ourReceive);
        String packetToken = tokenOf(clause, "Packet send stream: ");
        String ourToken = tokenOf(clause, "Our receive stream: ");
        assertEquals("packet ID round-trips", packetSend, idOf(packetToken));
        assertEquals("our ID round-trips", ourReceive, idOf(ourToken));
    }

    /**
     * A stream ID of zero renders as the six A's a reader sees in the packet
     * dumps, not as the digit 0, so the two line styles line up by eye.
     */
    @Test
    public void testUnsetIdRendersAsTheDumpForm() {
        String clause = ConnectionPacketHandler.resetStreamClause(0L, 0L);
        assertEquals("AAAAAA", tokenOf(clause, "Packet send stream: "));
        assertEquals("AAAAAA", tokenOf(clause, "Our receive stream: "));
    }

    /**
     * The IDs go on one continuation line as house {@code key: value} segments
     * joined by {@code " * "}, and never in brackets: the console linkifier
     * parses a bracketed token as a hash, so a bracketed stream ID is a broken
     * link.
     */
    @Test
    public void testClauseIsSegmentsNotBrackets() {
        String clause = ConnectionPacketHandler.resetStreamClause(0x6c0c5f7aL,
                                                                  0x1234L);
        assertTrue("continuation line: " + clause, clause.startsWith("\n* "));
        assertEquals("one continuation line: " + clause, 2,
                     clause.split("\n").length);
        assertTrue("house separator: " + clause,
                   clause.contains(" * Our receive stream: "));
        assertFalse("no brackets: " + clause, clause.contains("["));
    }

    /**
     * Pull one labelled token out of the clause.
     *
     * @param clause the clause to read
     * @param label the segment's label, up to and including its colon
     * @return the token that follows the label
     */
    private static String tokenOf(String clause, String label) {
        int at = clause.indexOf(label);
        assertTrue("segment missing: " + label, at >= 0);
        String rest = clause.substring(at + label.length());
        int end = rest.indexOf(' ');
        return end < 0 ? rest : rest.substring(0, end);
    }

    /**
     * Decode a rendered stream ID back to the number it was built from.
     *
     * @param token the base64 token as rendered, without its padding
     * @return the stream ID
     */
    private static long idOf(String token) {
        byte[] buf = Base64.decode(token + "==");
        return DataHelper.fromLong(buf, 0, 4);
    }
}
