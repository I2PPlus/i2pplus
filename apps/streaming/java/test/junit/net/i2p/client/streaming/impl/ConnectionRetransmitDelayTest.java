package net.i2p.client.streaming.impl;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link Connection#nextRetransmitDelay}, the once-only immediate
 * retransmit rule.
 *
 * <p>The defect: an overdue head-of-line packet is legitimate to retransmit at once,
 * but the old code re-armed the timer with {@code schedule(0)} on <em>every</em>
 * subsequent ACK. A stalled connection therefore enqueued one immediate timer run per
 * ACK, and since the coalesce task shares the same two-thread pool, a large
 * population of stalled connections was enough to stop every graph in the router
 * recording at once. Observed live as {@code SimpleTimer active 2/2, 3001 events
 * queued, 0 tasks completed}.
 *
 * @since 0.9.71+
 */
public class ConnectionRetransmitDelayTest {

    private static final long NOW = 1_000_000L;
    private static final long RTO = 1000L;

    @Test
    public void notOverdueUsesTheRemainingRto() {
        // Packet sent 100ms ago with a 1000ms RTO: 900ms still to wait.
        long oldestSend = NOW - 100;
        assertEquals(900, Connection.nextRetransmitDelay(NOW, oldestSend, RTO, false));
    }

    @Test
    public void notOverdueIsUnaffectedByAnEarlierImmediateFire() {
        long oldestSend = NOW - 100;
        assertEquals(Connection.nextRetransmitDelay(NOW, oldestSend, RTO, false),
                     Connection.nextRetransmitDelay(NOW, oldestSend, RTO, true));
    }

    @Test
    public void firstOverdueRequestsImmediate() {
        // Packet already older than one RTO.
        long oldestSend = NOW - 5000;
        assertEquals("the first overdue period is entitled to one immediate attempt",
                     0, Connection.nextRetransmitDelay(NOW, oldestSend, RTO, false));
    }

    @Test
    public void secondOverdueDoesNotRequestImmediateAgain() {
        long oldestSend = NOW - 5000;
        int delay = Connection.nextRetransmitDelay(NOW, oldestSend, RTO, true);
        assertEquals("an overdue head-of-line packet must not fire immediately per ACK",
                     RTO, delay);
        assertTrue("the fallback must still bound recovery time", delay > 0);
    }

    /**
     * The core property: any number of ACKs on an overdue connection yields exactly
     * one immediate fire, not one per ACK.
     */
    @Test
    public void onlyOneImmediateFirePerOverdueEpisode() {
        long oldestSend = NOW - 5000;
        boolean immediateFired = false;
        int immediateCount = 0;
        for (int ack = 0; ack < 1000; ack++) {
            int delay = Connection.nextRetransmitDelay(NOW, oldestSend, RTO, immediateFired);
            if (delay == 0) {
                immediateCount++;
                immediateFired = true;
            }
        }
        assertEquals("1000 ACKs must not produce 1000 immediate timer runs", 1, immediateCount);
    }

    @Test
    public void aNewOverdueEpisodeGetsAFreshImmediateFire() {
        long oldestSend = NOW - 5000;
        // First episode fires immediately, then falls back.
        assertEquals(0, Connection.nextRetransmitDelay(NOW, oldestSend, RTO, false));
        assertEquals(RTO, Connection.nextRetransmitDelay(NOW, oldestSend, RTO, true));
        // Timer catches up (not overdue any more), which clears the latch.
        long caughtUp = NOW - 100;
        assertEquals(900, Connection.nextRetransmitDelay(NOW, caughtUp, RTO, true));
        // A later overdue packet is once again entitled to an immediate attempt.
        assertEquals(0, Connection.nextRetransmitDelay(NOW, NOW - 5000, RTO, false));
    }

    @Test
    public void noOutstandingPacketUsesAFullRtoFromNow() {
        // oldestLastSend == 0 means nothing known is unacked.
        assertEquals((int) RTO, Connection.nextRetransmitDelay(NOW, 0, RTO, false));
    }

    @Test
    public void noOutstandingPacketWithLatchStillUsesAFullRto() {
        assertEquals((int) RTO, Connection.nextRetransmitDelay(NOW, 0, RTO, true));
    }

    @Test
    public void exactlyAtTheRtoBoundaryIsNotYetOverdue() {
        // Sent exactly one RTO ago: deadline == now, so the remaining delay is zero
        // but the once-only latch still governs whether that becomes an immediate fire.
        long oldestSend = NOW - RTO;
        assertEquals(0, Connection.nextRetransmitDelay(NOW, oldestSend, RTO, false));
        assertEquals(RTO, Connection.nextRetransmitDelay(NOW, oldestSend, RTO, true));
    }

    @Test
    public void doubledRtoWidensTheFallback() {
        long oldestSend = NOW - 100000;
        assertEquals(4000, Connection.nextRetransmitDelay(NOW, oldestSend, 4000L, true));
    }

    @Test
    public void hugeRtoDoesNotOverflow() {
        long oldestSend = NOW - 100000;
        int delay = Connection.nextRetransmitDelay(NOW, oldestSend, Long.MAX_VALUE / 4, true);
        assertTrue("delay must stay a sane positive int", delay > 0);
    }

    @Test
    public void futureSendTimeIsTreatedAsNotOverdue() {
        // Clock skew or a bad timestamp must not request an immediate fire.
        long oldestSend = NOW + 5000;
        int delay = Connection.nextRetransmitDelay(NOW, oldestSend, RTO, false);
        assertTrue("a future send time must yield a non-negative delay", delay >= 0);
    }

    @Test
    public void zeroRtoStillYieldsAnImmediateAttemptOnlyOnce() {
        long oldestSend = NOW - 1;
        assertEquals(0, Connection.nextRetransmitDelay(NOW, oldestSend, 0L, false));
        assertEquals("a degenerate zero RTO must not become a busy loop, so the"
                     + " fallback floors at 1ms rather than returning 0 (=immediate)",
                     1, Connection.nextRetransmitDelay(NOW, oldestSend, 0L, true));
    }
}