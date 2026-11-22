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
 * <p>Sampling that sender queue after the send completed turned out to be the
 * wrong instrument: across 79 severe-latency lines not one carried the clause,
 * because a slow send is normally held by the peer it is going to - window
 * closed, packets awaiting ACKs, or a peer still being established - while
 * every sender queue is empty. So the peer's own queue is reported too, via
 * {@link TransportImpl#peerQueueClause(int, long)}, and the two queues share a
 * continuation line so the line stays inside the log column.
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

    /**
     * A peer with nothing queued for it says nothing, the same way the sender
     * queue does.
     */
    @Test
    public void testEmptyPeerQueueAddsNothing() {
        assertEquals("", TransportImpl.peerQueueClause(0, 0));
        assertEquals("", TransportImpl.peerQueueClause(0, 62_000));
        assertEquals("", TransportImpl.peerQueueClause(-1, 62_000));
    }

    /**
     * The per-peer queue is the wait the sender queue could not report: on a
     * live router, 79 severe-latency lines carried no sender clause at all,
     * while the messages behind them had waited between 60s and 5m, because
     * those messages sat in the peer's queue, not on the wire path.
     */
    @Test
    public void testPeerQueueIsCountedAndAged() {
        String clause = TransportImpl.peerQueueClause(4, 62_000);
        assertTrue("count names the object: " + clause,
                   clause.contains(" * Peer queue: 4 messages"));
        assertTrue("the wait carries a unit: " + clause,
                   clause.contains(" * Peer wait: 62s"));
    }

    /**
     * A peer queue whose age is not sampled reports the count without the age.
     * The transport caches the age of messages that have started sending, so a
     * queue of messages still waiting to start has no figure to print - and
     * <code>Peer wait: 0ms</code> would read as "not waiting", which is a
     * different claim from "not measured".
     */
    @Test
    public void testUnsampledPeerAgeOmitsTheFigure() {
        String clause = TransportImpl.peerQueueClause(9, 0);
        assertTrue("count kept: " + clause,
                   clause.contains(" * Peer queue: 9 messages"));
        assertFalse("no invented zero: " + clause,
                    clause.contains("Peer wait"));
    }

    /**
     * Nothing queued adds no report at all - an empty continuation line is
     * noise on a line that already says the queue was empty.
     */
    @Test
    public void testEmptyReportAddsNoLine() {
        assertEquals("", TransportImpl.queueReport(""));
    }

    /**
     * Both queues together stay inside the log column: the report is its own
     * continuation line, not four more segments on the line that already
     * carries the type, the message id and the send-pool wait.
     */
    @Test
    public void testBothQueuesGetTheirOwnLine() {
        String clauses = TransportImpl.queueClause(1024, 62_000) +
                         TransportImpl.peerQueueClause(12, 302_000);
        String report = TransportImpl.queueReport(clauses);
        assertTrue("opens its own line: " + report, report.startsWith("\n* "));
        String line = "Severe send latency -> 5m 12s from creation" +
                      "\n* Type: TunnelDataMessage * MsgID: 705976933" +
                      " * Queued: 5m 12s" + report;
        for (String s : line.split("\n")) {
            assertTrue("within the log column: " + s, s.length() <= 100);
        }
        assertTrue("both queues present: " + report,
                   report.contains("Sender queue: 1024 packets") &&
                   report.contains("Peer queue: 12 messages"));
        assertFalse("no brackets: " + report, report.contains("["));
    }
}
