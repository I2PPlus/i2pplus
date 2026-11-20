package net.i2p.client.streaming.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The ceiling on the SYN accept queue, and what "no ceiling" means.
 *
 * <p>{@code i2p.streaming.maxQueueSize} of 0 is documented, and handled by the
 * accept-queue reaper and the expiry report, as uncapped. The admission test was a
 * plain {@code size() < max}, so at 0 it was false for every packet and the router
 * answered every inbound SYN with a RESET. The cap is re-read on every call so a
 * Tuner write or a config change applies without a restart, which is what makes the
 * trap live: one write of 0 stops the router accepting any inbound stream at all.
 *
 * <p>The same sentinel decides whether the accept pool grows under pressure, and
 * there it was gated on the cap being positive, so the uncapped queue - the one
 * that grows deepest - never got another worker. Both halves are now keyed on the
 * depth the queue really stops at: the configured cap, or the hard backstop when the
 * cap says uncapped.
 *
 * @since 0.9.72+
 */
public class ConnectionHandlerSynAdmissionTest {

    /** The configured cap in a running router, used for the boundary cases. */
    private static final int CONFIGURED = 16384;

    /**
     * A fixture group for the accept-queue ceiling; it holds no state.
     *
     * @since 0.9.72+
     */
    public ConnectionHandlerSynAdmissionTest() {}

    /**
     * Zero is the sentinel for uncapped: a queue of any depth still takes the SYN,
     * rather than resetting every connection attempt.
     */
    @Test
    public void testZeroMeansUncapped() {
        assertTrue(ConnectionHandler.synAdmitted(0, 0));
        assertTrue(ConnectionHandler.synAdmitted(1, 0));
        assertTrue(ConnectionHandler.synAdmitted(CONFIGURED, 0));
        assertTrue(ConnectionHandler.synAdmitted(Integer.MAX_VALUE, 0));
    }

    /**
     * A negative cap behaves like zero. A comparison of {@code size() < max} would
     * refuse everything, and a nonsense value must not be read as a nonsense cap.
     */
    @Test
    public void testANegativeCapBehavesLikeUncapped() {
        assertTrue(ConnectionHandler.synAdmitted(0, -1));
        assertTrue(ConnectionHandler.synAdmitted(CONFIGURED, -1));
        assertTrue(ConnectionHandler.synAdmitted(1, Integer.MIN_VALUE));
    }

    /**
     * A real cap admits up to itself and refuses at it, which is the behaviour the
     * uncapped cases must not disturb.
     */
    @Test
    public void testARealCapRefusesAtTheBoundary() {
        assertTrue(ConnectionHandler.synAdmitted(0, 1));
        assertFalse(ConnectionHandler.synAdmitted(1, 1));
        assertTrue(ConnectionHandler.synAdmitted(CONFIGURED - 1, CONFIGURED));
        assertFalse(ConnectionHandler.synAdmitted(CONFIGURED, CONFIGURED));
        assertFalse(ConnectionHandler.synAdmitted(CONFIGURED + 1, CONFIGURED));
    }

    /**
     * Uncapped still has a depth the queue stops at, and the pressure rules have to
     * be keyed on that: the queue is hard-bounded at the backstop whatever the
     * property says, so "uncapped" means uncapped of the configured kind only.
     */
    @Test
    public void testUncappedStillHasADepthItStopsAt() {
        int backstop = ConnectionHandler.SYN_QUEUE_BACKSTOP;
        assertEquals(backstop, ConnectionHandler.effectiveQueueLimit(0));
        assertEquals(backstop, ConnectionHandler.effectiveQueueLimit(-1));
        assertEquals(256, ConnectionHandler.effectiveQueueLimit(256));
    }

    /**
     * A capped queue is handed another worker past a quarter of the cap and not
     * before it: one worker draining a deep queue leaves every SYN behind it waiting
     * the full accept timeout.
     */
    @Test
    public void testACappedQueueGrowsWorkersPastAQuarterOfTheCap() {
        assertTrue(ConnectionHandler.acceptQueueNeedsWorker(65, 256, 4));
        assertFalse(ConnectionHandler.acceptQueueNeedsWorker(64, 256, 4));
        assertFalse(ConnectionHandler.acceptQueueNeedsWorker(20, 256, 4));
    }

    /**
     * The defect: uncapped was the one setting the scale-up refused to serve. The
     * gate required a positive cap, so a router running with no ceiling on the accept
     * queue - the setting that lets that queue grow deepest - never added a worker.
     */
    @Test
    public void testAnUncappedQueueStillGrowsWorkers() {
        int backstop = ConnectionHandler.SYN_QUEUE_BACKSTOP;
        assertTrue(ConnectionHandler.acceptQueueNeedsWorker(backstop / 4 + 1, 0, 4));
        assertTrue(ConnectionHandler.acceptQueueNeedsWorker(backstop / 4 + 1, -1, 4));
        assertFalse(ConnectionHandler.acceptQueueNeedsWorker(backstop / 4, 0, 4));
    }

    /**
     * The pool stops growing at the module's ceiling, capped or uncapped.
     */
    @Test
    public void testThePoolStopsAtTheCeiling() {
        int ceiling = ConnectionHandler.MAX_ACCEPT_WORKERS;
        assertFalse(ConnectionHandler.acceptQueueNeedsWorker(ConnectionHandler.SYN_QUEUE_BACKSTOP,
                                                             0, ceiling));
        assertFalse(ConnectionHandler.acceptQueueNeedsWorker(100000, 256, ceiling));
    }

    /**
     * The expiry report reads the same sentinel, so the two places cannot disagree
     * about whether the queue is capped: a capped queue is reported as a fraction of
     * the cap, an uncapped one as a depth.
     */
    @Test
    public void testTheExpiryReportUsesTheSameSentinel() {
        Packet syn = synPacket();
        String capped = ConnectionHandler.synExpiryMessage(syn, 5, CONFIGURED, 60000);
        assertTrue(capped, capped.contains("5/" + CONFIGURED));
        String uncapped = ConnectionHandler.synExpiryMessage(syn, 5, 0, 60000);
        assertTrue(uncapped, uncapped.contains("depth 5"));
        assertFalse(uncapped, uncapped.contains("5/0"));
    }

    /**
     * The reset report names the bound the queue actually hit. For the
     * uncapped sentinel it says so: printing <code>Limit: 0 SYNs</code> sends a
     * reader looking for a misconfiguration, when what happened is that an
     * uncapped queue reached the hard backstop.
     */
    @Test
    public void testTheResetReportNamesTheBoundItHit() {
        String capped = ConnectionHandler.synQueueLimitText(128);
        assertTrue(capped, capped.contains("Limit: 128 SYNs"));
        String uncapped = ConnectionHandler.synQueueLimitText(0);
        assertTrue(uncapped, uncapped.contains("Uncapped"));
        String backstop = String.valueOf(ConnectionHandler.SYN_QUEUE_BACKSTOP);
        assertTrue(uncapped, uncapped.contains(backstop));
        assertFalse(uncapped, uncapped.contains("Limit: 0"));
        // a negative cap is the same sentinel, and says the same thing
        assertEquals(ConnectionHandler.synQueueLimitText(-1), uncapped);
    }

    /**
     * @return a SYN packet for the report fixture
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
