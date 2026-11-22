package net.i2p.router.message;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Test;

/**
 * Tests whether a LeaseSet missing from the local store is re-probed over the
 * network, in {@link OutboundClientMessageOneShotJob#leaseSetReprobeWanted(boolean, boolean)}.
 *
 * <p>The defect: the re-probe branch ran for every destination, including ones
 * this router hosts. A locally hosted LeaseSet is mirrored into the main NetDb
 * at publish time and the facade fails a client sub-NetDb miss for it without
 * any search, so the negative-cache clear was a no-op and the line that claimed
 * "Re-probing with negative cache cleared" described a search that never
 * started. Measured over 107 minutes: 18 such lines, every one for a
 * destination this router hosts, each followed in the same millisecond by
 * {@code LeaseSet lookup failed ... after 0ms} and a failed client send.
 *
 * <p>Pure, so no router is required.
 *
 * @since 0.9.72+
 */
public class OutboundClientMessageLeaseSetReprobeTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one; the class carries no per-test state.
     *
     * @since 0.9.72+
     */
    public OutboundClientMessageLeaseSetReprobeTest() {}

    /** A remote destination whose LeaseSet lapsed moments ago is worth a search. */
    @Test
    public void testARemoteRecentlyValidLeaseSetIsReprobed() {
        assertTrue(OutboundClientMessageOneShotJob.leaseSetReprobeWanted(true, false));
    }

    /**
     * The defect: a destination this router hosts was sent down the same branch,
     * clearing a cache the lookup never consults for it and announcing a probe
     * that never ran.
     */
    @Test
    public void testALocallyHostedLeaseSetIsNeverReprobed() {
        assertFalse(OutboundClientMessageOneShotJob.leaseSetReprobeWanted(true, true));
        assertFalse(OutboundClientMessageOneShotJob.leaseSetReprobeWanted(false, true));
    }

    /** A destination that has been missing a while gets no free search either. */
    @Test
    public void testStaleLeaseSetGetsNoFreeSearch() {
        assertFalse(OutboundClientMessageOneShotJob.leaseSetReprobeWanted(false, false));
    }

    /**
     * The branch has to be taken in the pass that starts the lookup: a helper no
     * caller consults reads green in unit tests and changes nothing in the log.
     *
     * @throws Exception if the job source cannot be read back from disk
     */
    @Test
    public void testTheLookupPassAsksTheHelper() throws Exception {
        String rel = "router/java/src/net/i2p/router/message/" +
                     "OutboundClientMessageOneShotJob.java";
        java.io.File src = new java.io.File(rel);
        if (!src.isFile()) {
            src = new java.io.File("src/net/i2p/router/message/" +
                                   "OutboundClientMessageOneShotJob.java");
        }
        Assume.assumeTrue("source not available", src.isFile());
        String body = new String(java.nio.file.Files.readAllBytes(src.toPath()), "UTF-8");
        String call = "if (leaseSetReprobeWanted(recentlyValid, isLocalDest)) {";
        int gate = body.indexOf(call);
        int claim = body.indexOf("Re-probing with negative cache cleared");
        int branchEnd = body.indexOf("\n            }", gate);
        assertTrue("the re-probe branch must consult the rule", gate >= 0);
        assertTrue("the re-probe line must sit inside the guarded branch",
                   claim > gate && claim < branchEnd);
    }
}
