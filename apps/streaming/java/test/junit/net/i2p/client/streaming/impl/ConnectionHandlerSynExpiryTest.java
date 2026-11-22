package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for the SYN accept-queue expiry log formatting in ConnectionHandler.
 *
 * <p>The historical WARN ("Expired on the SYN queue: " + packet) rendered as a
 * bare trailing-colon line with no peer, no queue pressure, and no timeout,
 * so a burst of expiries was impossible to attribute or rate-assess. These
 * tests pin the richer summary so the flood path can be diagnosed from the
 * log alone: source stream IDs, queue depth, timeout in play, and (when the
 * FROM option is present) the short peer hash the per-dest flood gate keys on.
 *
 * <p>The other tests cover the two lines a reaper sweep writes about the queue
 * as a whole. Those numbers are what a reader judges saturation from, so they
 * have to be the depth and the bound, printed separately.
 *
 * @since 0.9.71+
 */
public class ConnectionHandlerSynExpiryTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object
     * for each one; the formatting under test is pure, so nothing is set up.
     */
    public ConnectionHandlerSynExpiryTest() {}

    /**
     * The pre-existing expiry warning ended in a bare colon and nothing else,
     * so a burst of them carried no information at all.
     */
    @Test
    public void testSummaryNoTrailingEmptyColon() {
        Packet syn = synPacket();
        String msg = ConnectionHandler.synExpiryMessage(syn, 5, 256, 60000);
        assertFalse("must not end in an empty trailing colon",
                    msg.endsWith(":"));
        assertFalse("must not end in a colon then whitespace",
                    msg.matches(".*:\\s+$"));
    }

    /**
     * The window that elapsed and the queue the SYN was sitting in are the two
     * numbers that say whether the fabric is slow or the acceptor is loaded.
     */
    @Test
    public void testMessageCarriesTimeoutAndQueuePressure() {
        Packet syn = synPacket();
        String msg = ConnectionHandler.synExpiryMessage(syn, 5, 256, 60000);
        assertTrue("should mention the accept timeout: " + msg,
                   msg.contains("60000ms"));
        assertTrue("should show queue depth / max: " + msg,
                   msg.contains("5/256"));
    }

    /**
     * The stream-ID pair is what ties an expired SYN back to the client that
     * sent it, and the flag says which of the queued packets are connects.
     */
    @Test
    public void testMessageCarriesStreamIdAndSequence() {
        Packet syn = synPacket();
        String msg = ConnectionHandler.synExpiryMessage(syn, 0, 0, 0);
        // streamIDs pair rendered via Packet.toId()
        assertTrue("should contain the StreamID pair: " + msg,
                   msg.contains("streamIDs ["));
        assertTrue("should flag the SYN: " + msg,
                   msg.startsWith("Expired on SYN queue"));
    }

    /**
     * A connect and a re-queued packet expire differently, so the summary
     * names which one this was.
     */
    @Test
    public void testSynFlagLabeled() {
        Packet syn = synPacket();
        StringBuilder out = new StringBuilder();
        ConnectionHandler.synExpirySummary(syn, out);
        assertTrue("SYN packets should be labeled SYN: " + out,
                   out.toString().startsWith("SYN "));
    }

    /**
     * A queued packet that is not a SYN is one the handler re-queued, not a
     * connect, and it has to be labeled so a reader does not count it as a
     * connection that failed.
     */
    @Test
    public void testNonSynFlagLabeledAsPacket() {
        Packet p = new Packet(null);
        p.setSequenceNum(7);
        p.setSendStreamId(1);
        p.setReceiveStreamId(2);
        StringBuilder out = new StringBuilder();
        ConnectionHandler.synExpirySummary(p, out);
        assertTrue("non-SYN packets should be labeled Pkt: " + out,
                   out.toString().startsWith("Pkt "));
    }

    /**
     * The summary is also reached for a packet the handler dropped, so a
     * null SYN has to render as text rather than throw on the timer thread.
     */
    @Test
    public void testNullSynHandled() {
        StringBuilder out = new StringBuilder();
        ConnectionHandler.synExpirySummary(null, out);
        assertEquals("null SYN", out.toString());
    }

    /**
     * A cap of 0 means uncapped, not a queue with no room at all, so the depth
     * has to be printed and the phantom limit of 0 left out.
     */
    @Test
    public void testZeroMaxQueueSizeStillReadable() {
        Packet syn = synPacket();
        String msg = ConnectionHandler.synExpiryMessage(syn, 7, 0, 60000);
        assertTrue("zero max should still print depth: " + msg,
                   msg.contains("depth 7"));
    }

    /**
     * The sweep's aggregate line has to print the depth and the cap as two
     * separate numbers. The inline form it replaces concatenated a char
     * literal, and {@code int + char} is arithmetic, so a queue 674 deep
     * under a 4,096 cap printed 4817 - the figure that made a live router's
     * accept queue look over its bound when its deepest reading was 1,028 of
     * 4,096 and it never sent a single reset.
     */
    @Test
    public void testTallyPrintsDepthAndCapNotTheirSum() {
        String msg = ConnectionHandler.synExpiryTally(1, 60000, 674, 4096);
        assertTrue("depth as its own number: " + msg,
                   msg.contains("Queued: 674 SYNs"));
        assertTrue("cap as its own number: " + msg,
                   msg.contains("Limit: 4096 SYNs"));
        assertFalse("not the sum of the two: " + msg, msg.contains("4817"));
    }

    /**
     * The count drives how the whole run is read, so the word for one stays
     * singular and the word for many plural.
     */
    @Test
    public void testTallyAgreesWithItsOwnCount() {
        String one = ConnectionHandler.synExpiryTally(1, 60000, 0, 4096);
        assertTrue("one connection: " + one, one.contains("1 connection ->"));
        String many = ConnectionHandler.synExpiryTally(12, 60000, 0, 4096);
        assertTrue("many connections: " + many,
                   many.contains("12 connections ->"));
    }

    /**
     * The window the SYNs waited out is the other half of the fact, and an
     * uncapped queue has to say so rather than print a limit of 0.
     */
    @Test
    public void testTallyCarriesTheWindowAndTheSentinel() {
        String capped = ConnectionHandler.synExpiryTally(3, 15000, 900, 4096);
        assertTrue("the window that elapsed: " + capped,
                   capped.contains("15000ms"));
        assertTrue(capped.contains("Queued: 900 SYNs"));
        String uncapped = ConnectionHandler.synExpiryTally(3, 15000, 900, 0);
        assertTrue("uncapped says so: " + uncapped,
                   uncapped.contains("Uncapped"));
        assertTrue(uncapped.contains("Queued: 900 SYNs"));
        assertFalse("no phantom limit of zero: " + uncapped,
                    uncapped.contains("Limit: 0"));
    }

    /**
     * A log line is read at 3am, so the sweep's summary has to fit one line at
     * its widest: many expiries, a queue at its bound, and a wide cap.
     */
    @Test
    public void testTallyFitsOneLogLine() {
        String msg = ConnectionHandler.synExpiryTally(12, 60000, 4096, 4096);
        assertTrue("emitted line is " + msg.length() + " chars: " + msg,
                   msg.length() <= 100);
    }

    /**
     * The scale-up line prints the same pair, and joined them into a single
     * figure the same way.
     */
    @Test
    public void testPressureTextCarriesDepthAndWorkers() {
        String msg = ConnectionHandler.synQueuePressureText(1028, 4096, 3, 0);
        assertTrue("depth: " + msg, msg.contains("Queued: 1028 SYNs"));
        assertTrue("bound: " + msg, msg.contains("Limit: 4096 SYNs"));
        assertTrue("pool after the change: " + msg,
                   msg.contains("Workers: 3"));
        assertFalse("not the sum of depth and bound: " + msg,
                    msg.contains("5171"));
    }

    /**
     * The expiry-triggered form leads with a longer phrase and still fits one
     * log line.
     */
    @Test
    public void testExpiryPressureTextFitsOneLogLine() {
        String msg =
            ConnectionHandler.synQueuePressureText(1039, 4096, 1, 887);
        assertTrue("emitted line is " + msg.length() + " chars: " + msg,
                   msg.length() <= 100);
        assertTrue("reason: " + msg,
                   msg.startsWith("SYN handshakes expired -> "));
    }

    /**
     * A queued SYN, with the stream-ID pair the expiry summary reports.
     *
     * @return the packet to hand to the summary formatters
     */
    private static Packet synPacket() {
        Packet p = new Packet(null);
        p.setFlag(Packet.FLAG_SYNCHRONIZE);
        p.setSequenceNum(3);
        p.setSendStreamId(0x11111111L);
        p.setReceiveStreamId(0x22222222L);
        return p;
    }
}
