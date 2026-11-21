package net.i2p.router.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins how a transport's own outbound packet queue reaches the severe-latency
 * line, via {@link TransportImpl#queueClause(int, long)}.
 *
 * <p>The line already carried {@code Queued}, the wait in the router's send
 * pool, and that is where a 62s backlog looked like it lived. SSU adds a
 * second wait underneath - the per-endpoint sender queue - and in the measured
 * minute of 359 severe-latency records the pool figure was all a reader could
 * see, so nothing in the log said whether the SSU queue was 4 packets deep or
 * 1,024, or whether the sender was the thing to scale.
 *
 * @since 0.9.72+
 */
public class SendQueueClauseTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for
     * each one; the clause builder is pure, so nothing is set up.
     */
    public SendQueueClauseTest() {}

    /**
     * An empty queue is the healthy state and says nothing: a line ending in
     * "0 packets * Queue delay: 0ms" spends a reader's attention on a number
     * that carries no information.
     */
    @Test
    public void testEmptyQueueAddsNothing() {
        assertEquals("", TransportImpl.queueClause(0, 0));
        assertEquals("", TransportImpl.queueClause(0, 62_000));
        assertEquals("", TransportImpl.queueClause(-1, 0));
    }

    /**
     * A queued packet is named as a count of packets, and the wait carries its
     * unit from the house formatter - a bare number of seconds is a figure the
     * reader has to convert.
     */
    @Test
    public void testDepthAndDelayAreBothLabelled() {
        String clause = TransportImpl.queueClause(674, 62_000);
        assertTrue("depth names the object: " + clause,
                   clause.contains(" * Sender queue: 674 packets"));
        assertTrue("delay carries a unit: " + clause,
                   clause.contains(" * Queue delay: 62s"));
    }

    /**
     * A queue that is deep but moving, and one that is shallow and stuck, are
     * different findings and both have to be expressible: the depth cap is not
     * the limit that hurts, the delay is.
     */
    @Test
    public void testDeepAndStuckAreToldApart() {
        String deep = TransportImpl.queueClause(1024, 250);
        String stuck = TransportImpl.queueClause(3, 111_000);
        assertTrue("deep but fast: " + deep,
                   deep.contains("Sender queue: 1024 packets") &&
                   deep.contains("Queue delay: 250ms"));
        assertTrue("shallow but slow: " + stuck,
                   stuck.contains("Sender queue: 3 packets") &&
                   stuck.contains("Queue delay: 111s"));
    }

    /**
     * The segments sit beside the ones the base line already writes, after the
     * arrow, and never in brackets: the console linkifier reads a bracketed
     * token as a hash.
     */
    @Test
    public void testSegmentsJoinTheLineAfterTheArrow() {
        String clause = TransportImpl.queueClause(674, 62_000);
        String line = "Severe send latency -> 111s from creation to completion"
                      + "\n* Type: TunnelDataMessage * MsgID: 705976933"
                      + " * Queued: 62s" + clause;
        assertTrue("joined as key-value segments: " + line,
                   line.contains(" * Queued: 62s * Sender queue: 674"));
        assertFalse("no brackets: " + line, line.contains("["));
        assertFalse("no years from an unmeasured value: " + line,
                    line.matches("(?s).*\\d+y.*"));
    }

    /**
     * A queue with packets but no measured wait yet still reports the depth,
     * and the delay renders as zero rather than being dropped: the reader has
     * to know the queue is not empty.
     */
    @Test
    public void testUnmeasuredDelayStillReportsTheDepth() {
        String clause = TransportImpl.queueClause(12, 0);
        assertTrue("depth kept: " + clause,
                   clause.contains(" * Sender queue: 12 packets"));
        assertTrue("delay is zero, not absent: " + clause,
                   clause.contains(" * Queue delay: 0ms"));
    }
}
