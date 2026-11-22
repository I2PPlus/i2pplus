package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.stat.RateConstants;
import net.i2p.stat.StatManager;

/**
 * Tests for the removal-bar guards in {@link TestJob}:
 * {@link TestJob#removalThreshold(int, int)} and
 * {@link TestJob#shouldDeferFailingRetest(int, int)} — thin-pool guards that
 * raise the consecutive-failure removal bar and defer ASAP retests while the
 * pool has ≤ 2 remaining tunnels — and
 * {@link TestJob#dataCarryingDropAt(int, boolean)} and
 * {@link TestJob#holdDataCarryingDrop}, which hold a drop while the tunnel's
 * own leg is proven by production traffic it carried just now and nothing
 * independent says the tunnel is broken.
 *
 * @since 0.9.71+
 */
public class TestJobRemovalThresholdTest {

    /**
     * JUnit fixture object; every test here drives a static helper of
     * {@link TestJob}, so the instance carries no state.
     */
    public TestJobRemovalThresholdTest() {
    }

    // ---------- baseRemovalThreshold ----------

    /** A healthy pool removes a failing tunnel after 3 failures. */
    @Test
    public void testBaseThresholdHealthy() {
        assertEquals(3, TestJob.baseRemovalThreshold(false));
    }

    /** A pool with low build success waits for 5, sparing scarce builds. */
    @Test
    public void testBaseThresholdDegraded() {
        assertEquals(5, TestJob.baseRemovalThreshold(true));
    }

    // ---------- removalThreshold ----------

    /** A pool down to two tunnels gets two extra failures of grace each. */
    @Test
    public void testThinPoolRaisesBarByTwo() {
        assertEquals(5, TestJob.removalThreshold(3, 2));
        assertEquals(7, TestJob.removalThreshold(5, 2));
        assertEquals(5, TestJob.removalThreshold(3, 1));
        assertEquals(5, TestJob.removalThreshold(3, 0));
        assertEquals(5, TestJob.removalThreshold(3, -1));
    }

    /** A pool with tunnels to spare keeps the ordinary bar. */
    @Test
    public void testAdequatePoolKeepsBaseBar() {
        assertEquals(3, TestJob.removalThreshold(3, 3));
        assertEquals(3, TestJob.removalThreshold(3, 4));
        assertEquals(3, TestJob.removalThreshold(3, 10));
        assertEquals(5, TestJob.removalThreshold(5, 3));
        assertEquals(5, TestJob.removalThreshold(5, 8));
    }

    // ---------- shouldDeferFailingRetest ----------

    /** A thin pool must not waste retest slots on a failing tunnel. */
    @Test
    public void testDeferWhenThinAndFailing() {
        assertTrue(TestJob.shouldDeferFailingRetest(2, 1));
        assertTrue(TestJob.shouldDeferFailingRetest(1, 3));
        assertTrue(TestJob.shouldDeferFailingRetest(0, 1));
    }

    /** A healthy pool retests at the usual urgency. */
    @Test
    public void testNoDeferWhenHealthyPool() {
        assertFalse(TestJob.shouldDeferFailingRetest(3, 1));
        assertFalse(TestJob.shouldDeferFailingRetest(10, 5));
    }

    /** A tunnel with no failures has nothing to slow down. */
    @Test
    public void testNoDeferWhenNoFailures() {
        assertFalse(TestJob.shouldDeferFailingRetest(2, 0));
        assertFalse(TestJob.shouldDeferFailingRetest(0, 0));
        assertFalse(TestJob.shouldDeferFailingRetest(1, 0));
    }

    // ---------- dataCarryingDropAt ----------

    /**
     * A verified-traffic tunnel whose drop is held gets
     * {@link TestJob#UNCORROBORATED_DROP_GRACE} more rounds than the pool's
     * bar; one that is not held is dropped at the pool's own bar.
     */
    @Test
    public void heldDropsGetTwoMoreRoundsBeforeTheBar() {
        assertEquals(3, TestJob.dataCarryingDropAt(3, false));
        assertEquals(5, TestJob.dataCarryingDropAt(3, true));
        assertEquals(5, TestJob.dataCarryingDropAt(5, false));
        assertEquals(7, TestJob.dataCarryingDropAt(5, true));
    }

    /**
     * The grace only ever raises the bar, and it is bounded: the failure
     * count still climbs past it, so a dead tunnel is dropped later rather
     * than never.
     */
    @Test
    public void graceOnlyRaisesTheBarAndStillEnds() {
        for (int bar : new int[] {0, 3, 5, 7}) {
            assertTrue(TestJob.dataCarryingDropAt(bar, true) >= bar);
            assertTrue(TestJob.dataCarryingDropAt(bar, false)
                       <= TestJob.dataCarryingDropAt(bar, true));
        }
        int held = TestJob.dataCarryingDropAt(3, true);
        assertFalse("the last grace round is still held", 5 > held);
        assertTrue("one round past the grace drops it", 6 > held);
    }

    // ---------- holdDataCarryingDrop ----------

    /**
     * The grace is for the one case where the tunnel's own leg is known good
     * right now: production traffic inside the trust window, and nothing
     * independent saying the tunnel is bad.
     */
    @Test
    public void freshProofWithNoIndependentSignalHoldsTheDrop() {
        assertTrue(TestJob.holdDataCarryingDrop(false, false, true));
    }

    /**
     * Either independent signal ends the grace: a confirming round through a
     * different partner rules out one bad reply path; a far endpoint known
     * unreachable is a data-path fault the tunnel owns outright.
     */
    @Test
    public void anyIndependentSignalEndsTheGrace() {
        assertFalse(TestJob.holdDataCarryingDrop(true, false, true));
        assertFalse(TestJob.holdDataCarryingDrop(false, true, true));
        assertFalse(TestJob.holdDataCarryingDrop(true, true, true));
    }

    /**
     * Stale proof earns nothing. Every one of the 18 tunnels dropped from
     * this branch in the hour before a stalled download had last carried
     * traffic 9 to 12 minutes ago, against a 30-second trust window - they
     * fell because the proof had gone stale, and a 12-minute-old delivery is
     * no working tunnel to protect.
     */
    @Test
    public void staleProofEarnsNoGrace() {
        assertFalse(TestJob.holdDataCarryingDrop(false, false, false));
        assertFalse(TestJob.holdDataCarryingDrop(true, false, false));
        assertFalse(TestJob.holdDataCarryingDrop(false, true, false));
    }

    // ---------- the hold is visible ----------

    /**
     * The hold must be counted, in both directions, or a pool that is
     * holding its data-carrying tunnels reads in the stats exactly like one
     * that is not dropping anything at all.
     */
    @Test
    public void heldDropsAreCountedInBothDirections() {
        StatManager stats =
            new StatManager(I2PAppContext.getGlobalContext());
        TestJob.registerDirectionStats(stats,
                                       RateConstants.SHORT_TERM_RATES);
        for (boolean inbound : new boolean[] {true, false}) {
            String name = TestJob.directionStat(inbound, "DataDropHeld");
            assertNotNull(name + " is emitted but never registered",
                          stats.getRate(name));
        }
    }

    /**
     * The held line names both bars - the pool bar that was passed and the
     * higher one it is being held to - the difference between them is the
     * whole message. It also keeps the house log shape.
     */
    @Test
    public void heldLineNamesBothBars() {
        String tunnel = "Outbound Client tunnel [pool / dest]";
        String line = TestJob.dataTrustHeldLine(tunnel, 5, 3, 5);
        assertTrue(line, line.contains("no independent evidence"));
        assertTrue(line, line.contains("Failures: 5"));
        assertTrue(line, line.contains("Pool bar: 3"));
        assertTrue(line, line.contains("Drop at: 5"));
        assertTrue(line, line.contains(" -> "));
        for (String part : line.split("\\n")) {
            assertTrue("line over 100 chars: " + part.length() + ' ' + part,
                       part.length() <= 100);
            assertFalse("trailing whitespace: [" + part + ']',
                        part.endsWith(" "));
        }
    }

    /**
     * The drop site must actually consult the corroboration-aware bar, and
     * the far-end verdict must reach the decision. A helper nobody calls
     * reads green in unit tests and changes nothing in the router - that
     * split is the regression this file has been bitten by once before.
     *
     * @throws Exception if the TestJob source cannot be read back from disk
     */
    @Test
    public void theDropSiteUsesTheCorroborationAwareBar() throws Exception {
        String path = "router/java/src/net/i2p/router/tunnel/pool/";
        java.io.File src = new java.io.File(path + "TestJob.java");
        if (!src.isFile()) {
            src = new java.io.File(
                "src/net/i2p/router/tunnel/pool/TestJob.java");
        }
        org.junit.Assume.assumeTrue("source not available", src.isFile());
        byte[] raw = java.nio.file.Files.readAllBytes(src.toPath());
        String body = new String(raw, "UTF-8");
        assertTrue("the drop must use the corroboration-aware bar",
                   body.contains("currentFailures > dropAt"));
        assertTrue("the bar must come from dataCarryingDropAt",
                   body.contains("dataCarryingDropAt(maxFailures, held)"));
        String hold = "holdDataCarryingDrop(confirmedByOtherPartner, ";
        assertTrue("the hold must weigh the proof and both signals",
                   body.contains(hold + "farEndUnreachable,"));
        assertTrue("the far-end verdict must reach the decision",
                   body.contains(
                       "\"FarEndUnreachable\".equals(farEndEvent)"));
        assertTrue("a confirming round through another partner must count",
                   body.contains("confirmedByOtherPartner = true"));
    }
}
