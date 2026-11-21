package net.i2p.router.transport;

import static org.junit.Assert.*;

import org.junit.Test;

import net.i2p.data.DataHelper;

/**
 * Pins how {@link TransportImpl} resolves the interval a transport spent
 * on one outbound message, and how that interval reaches the log and the
 * stats.
 *
 * <p>Only NTCP measures a send: it passes its own figure to
 * {@link TransportImpl#afterSend}, and it is the sole caller of
 * {@link net.i2p.router.OutNetMessage#beginSend()} through
 * {@link TransportImpl#getNextMessage()}. SSU calls neither, so its
 * {@code sendBegin} is 0 and the old expression {@code now - sendBegin}
 * returned the epoch. Every one of the 461 "Severe send latency" lines in
 * a live router's 133-minute window printed {@code Send: 56y} for that
 * reason, and every SSU sample of {@code transport.sendMessageSize}
 * carried a 56-year event duration, which is bucketed past every rate
 * window and so never showed in the graph at all.
 *
 * @since 0.9.72+
 */
public class SendDurationDecisionTest {

    /** A wall-clock instant in the measured window, for the epoch cases. */
    private static final long NOW = 1_760_000_000_000L;

    /**
     * One instance per test method, as JUnit builds a fresh test object for
     * each one; both helpers are pure, so nothing is set up.
     */
    public SendDurationDecisionTest() {}

    /**
     * A duration the transport measured itself wins over anything derived
     * from timestamps, because the transport is the only party that knows
     * when the write began and finished.
     */
    @Test
    public void testTransportReportedDurationWins() {
        assertEquals(1500L,
                     TransportImpl.sendDuration(1500L, NOW - 9000L, NOW));
        assertEquals(1500L, TransportImpl.sendDuration(1500L, 0L, NOW));
    }

    /**
     * With no reported duration, a recorded start time is used, and it is
     * measured from that start to now - not from the epoch.
     */
    @Test
    public void testFallsBackToTheRecordedStartTime() {
        assertEquals(2500L, TransportImpl.sendDuration(0L, NOW - 2500L, NOW));
        assertEquals(62_000L,
                     TransportImpl.sendDuration(0L, NOW - 62_000L, NOW));
    }

    /**
     * The SSU case: nothing reported and nothing recorded. The answer is "no
     * measurement", not the gap between now and zero. Returning the gap is
     * what put {@code 56y} in the log and a 56-year duration in the stat.
     */
    @Test
    public void testUnmeasuredIsUnknownNotTheEpoch() {
        long resolved = TransportImpl.sendDuration(0L, 0L, NOW);
        assertEquals(-1L, resolved);
        assertTrue("an unknown duration must not be a wall-clock value",
                   resolved < NOW);
    }

    /**
     * The regression itself, stated in the units a reader sees: the old
     * expression renders as years, and the resolved value renders as nothing.
     */
    @Test
    public void testUnmeasuredNeverRendersAsYears() {
        String old = DataHelper.formatDuration(NOW - 0L);
        assertTrue("the pre-fix value is what we are removing: " + old,
                   old.endsWith("y"));
        long resolved = TransportImpl.sendDuration(0L, 0L, NOW);
        assertEquals("", TransportImpl.durationClause("Send", resolved));
    }

    /**
     * A known duration keeps the house {@code key: value} segment, with the
     * unit supplied by the formatter rather than concatenated onto it.
     */
    @Test
    public void testKnownDurationKeepsTheSegment() {
        assertEquals(" * Send: 62s", sendClause(62_000L));
        assertEquals(" * Time taken: 1500ms",
                     TransportImpl.durationClause("Time taken", 1500L));
        assertEquals(" * Send: 5s", sendClause(5000L));
        assertEquals(" * Send: 3m", sendClause(200_000L));
    }

    /**
     * A measured zero is a real measurement and stays on the line; only the
     * unknown case is dropped. Otherwise a fast send would look like a
     * missing value, and the two are different facts.
     */
    @Test
    public void testZeroDurationIsStillPrinted() {
        assertEquals(0L, TransportImpl.sendDuration(0L, NOW, NOW));
        assertEquals(" * Send: 0ms", TransportImpl.durationClause("Send", 0L));
    }

    /**
     * The severe-latency line has to stay useful with the send segment gone:
     * the queue age is the number that explains an SSU backlog, and it is
     * printed whether or not a send duration exists.
     */
    @Test
    public void testQueueAgeSurvivesWithoutSendDuration() {
        long resolved = TransportImpl.sendDuration(0L, 0L, NOW);
        String line = "Severe send latency -> " +
                      DataHelper.formatDuration(111_000L) +
                      " from creation to completion" +
                      "\n* Type: TunnelDataMessage * MsgID: 705976933" +
                      TransportImpl.durationClause("Send", resolved) +
                      " * Queued: " + DataHelper.formatDuration(62_000L);
        assertFalse("no duration measured in years: " + line,
                    line.matches("(?s).*\\d+y.*"));
        assertTrue("the queue age is the finding: " + line,
                   line.contains("Queued: 62s"));
        assertTrue("the total is the finding: " + line,
                   line.contains("Severe send latency -> 111s"));
    }

    /**
     * Shorthand for the segment under test with the label the severe-latency
     * line uses, so the assertions stay inside the file's line budget.
     *
     * @param duration the interval in ms, or -1 when unknown
     * @return the segment the transport would concatenate
     */
    private static String sendClause(long duration) {
        return TransportImpl.durationClause("Send", duration);
    }
}
