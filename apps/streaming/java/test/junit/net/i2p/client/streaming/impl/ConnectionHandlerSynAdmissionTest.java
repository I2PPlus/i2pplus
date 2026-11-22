package net.i2p.client.streaming.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
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
        assertTrue(ConnectionHandler.acceptWorkerNeeded(65, 256, 4, 0));
        assertFalse(ConnectionHandler.acceptWorkerNeeded(64, 256, 4, 0));
        assertFalse(ConnectionHandler.acceptWorkerNeeded(20, 256, 4, 0));
    }

    /**
     * The defect: uncapped was the one setting the scale-up refused to serve. The
     * gate required a positive cap, so a router running with no ceiling on the accept
     * queue - the setting that lets that queue grow deepest - never added a worker.
     */
    @Test
    public void testAnUncappedQueueStillGrowsWorkers() {
        int backstop = ConnectionHandler.SYN_QUEUE_BACKSTOP;
        assertTrue(ConnectionHandler.acceptWorkerNeeded(backstop / 4 + 1, 0, 4, 0));
        assertTrue(ConnectionHandler.acceptWorkerNeeded(backstop / 4 + 1, -1, 4, 0));
        assertFalse(ConnectionHandler.acceptWorkerNeeded(backstop / 4, 0, 4, 0));
    }

    /**
     * The defect: a listener with no accept worker at all resets handshakes long
     * before the depth rule opens, because the depth rule waits for a quarter of
     * the cap.
     *
     * <p>Measured on a live router: one hosted destination ran with no worker,
     * its queue climbed from 47 to 1,039 under a 4,096 cap, and 887 handshakes
     * from 54 peers were reset over 3m 11s before the depth rule fired at 1,039
     * and one worker started - the expiries stopped in that same second. An
     * expiry is the event; the depth is only a forecast of it.
     */
    @Test
    public void testExpiringHandshakesGrowWorkersWithoutTheDepth() {
        // the measured climb: 47 queued, nothing accepted, cap 4096
        assertTrue(ConnectionHandler.acceptWorkerNeeded(47, 4096, 0, 1));
        assertTrue(ConnectionHandler.acceptWorkerNeeded(151, 4096, 0, 887));
        // and with no expiry the same depths are left alone
        assertFalse(ConnectionHandler.acceptWorkerNeeded(47, 4096, 0, 0));
        assertFalse(ConnectionHandler.acceptWorkerNeeded(1024, 4096, 0, 0));
    }

    /**
     * The pool stops growing at the module's ceiling, capped or uncapped, and
     * an expiry cannot buy a worker past it.
     */
    @Test
    public void testThePoolStopsAtTheCeiling() {
        int ceiling = ConnectionHandler.MAX_ACCEPT_WORKERS;
        assertFalse(ConnectionHandler.acceptWorkerNeeded(ConnectionHandler.SYN_QUEUE_BACKSTOP,
                                                         0, ceiling, 0));
        assertFalse(ConnectionHandler.acceptWorkerNeeded(100000, 256, ceiling, 0));
        assertFalse(ConnectionHandler.acceptWorkerNeeded(0, 4096, ceiling, 500));
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
     * The scale-up report says which trigger opened, so a reader can tell
     * pressure that has not hurt anyone yet from a queue that is already resetting
     * clients - and both forms keep the depth and the bound as separate values,
     * never their sum.
     */
    @Test
    public void testTheScaleUpReportNamesWhatTriggeredIt() {
        String depth = ConnectionHandler.synQueuePressureText(1100, 4096, 5, 0);
        assertTrue(depth, depth.startsWith("SYN queue busy -> Added an accept worker"));
        String expiry = ConnectionHandler.synQueuePressureText(47, 4096, 1, 887);
        assertTrue(expiry, expiry.startsWith("SYN handshakes expired -> Added an accept worker"));
        for (String s : new String[] {depth, expiry}) {
            assertTrue(s, s.contains(" * Queued: "));
            assertTrue(s, s.contains("Limit: 4096 SYNs"));
            assertTrue(s, s.contains(" * Workers: "));
            // the arithmetic defect this rendering exists to prevent: 1100 + '/'
            // as a char added the two together
            assertFalse(s, s.contains("5196"));
        }
    }

    /**
     * The gate has to sit where the sweep can see what it just reaped: a
     * predicate no caller consults with the expiry count reads green in unit
     * tests and changes nothing in the log.
     *
     * @throws Exception if the ConnectionHandler source cannot be read back from disk
     */
    @Test
    public void testTheReaperHandsTheExpiryCountToTheGate() throws Exception {
        String rel = "apps/streaming/java/src/net/i2p/client/streaming/" +
                     "impl/ConnectionHandler.java";
        java.io.File src = new java.io.File(rel);
        if (!src.isFile()) {
            src = new java.io.File("src/net/i2p/client/streaming/impl/ConnectionHandler.java");
        }
        Assume.assumeTrue("source not available", src.isFile());
        String body = new String(java.nio.file.Files.readAllBytes(src.toPath()), "UTF-8");
        String gate = "acceptWorkerNeeded(queued, max, workers, expired)";
        assertTrue("the sweep must decide with the count it just reaped", body.contains(gate));
        String report = "synQueuePressureText(queued, max, _activeWorkerCount.get(), expired)";
        assertTrue("the report must say which trigger fired", body.contains(report));
        int tally = body.indexOf("synExpiryTally(expired, timeoutMs");
        assertTrue("the decision must follow the drain, not precede it",
                   tally > 0 && body.indexOf(gate) > tally);
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
