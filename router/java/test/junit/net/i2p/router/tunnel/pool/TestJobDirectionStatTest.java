package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import net.i2p.I2PAppContext;
import net.i2p.stat.RateConstants;
import net.i2p.stat.StatManager;

import org.junit.Test;

/**
 * Contract tests for the per-direction test outcome stats:
 * {@link TestJob#directionStat(boolean, boolean)} and
 * {@link TestJob#registerDirectionStats(StatManager, long[])}.
 *
 * These counters exist because a passing test emits no log line at all, so
 * without them a log review cannot tell "no outbound tests passed" from
 * "passing tests are not logged". The name builder is shared by the registrar
 * and the emitters, so these tests pin the whole contract: the names, their
 * distinctness, and that declaring them actually makes them recordable.
 *
 * @since 0.9.71+
 */
public class TestJobDirectionStatTest {

    // ---------------- directionStat ----------------

    @Test
    public void inboundSuccessName() {
        assertEquals("tunnel.testInboundSuccess", TestJob.directionStat(true, true));
    }

    @Test
    public void inboundFailedName() {
        assertEquals("tunnel.testInboundFailed", TestJob.directionStat(true, false));
    }

    @Test
    public void outboundSuccessName() {
        assertEquals("tunnel.testOutboundSuccess", TestJob.directionStat(false, true));
    }

    @Test
    public void outboundFailedName() {
        assertEquals("tunnel.testOutboundFailed", TestJob.directionStat(false, false));
    }

    /**
     * All four combinations must be distinct, otherwise an inbound result would
     * be indistinguishable from an outbound one — the exact confusion these
     * stats were added to remove.
     */
    @Test
    public void allFourNamesDistinct() {
        boolean[][] flags = {{true, true}, {true, false}, {false, true}, {false, false}};
        java.util.Set<String> names = new java.util.HashSet<String>();
        for (boolean[] flag : flags) {
            assertTrue("duplicate name for " + flag[0] + "/" + flag[1],
                       names.add(TestJob.directionStat(flag[0], flag[1])));
        }
        assertEquals(4, names.size());
    }

    /**
     * Flipping either axis must change the name, so no combination can collide
     * with its neighbour.
     */
    @Test
    public void eachAxisChangesTheName() {
        assertNotEquals(TestJob.directionStat(true, true),  TestJob.directionStat(false, true));
        assertNotEquals(TestJob.directionStat(true, false), TestJob.directionStat(false, false));
        assertNotEquals(TestJob.directionStat(true, true),  TestJob.directionStat(true, false));
        assertNotEquals(TestJob.directionStat(false, true), TestJob.directionStat(false, false));
    }

    /**
     * StatManager.addRateData() drops names it has never seen, so the emitted
     * names must be exactly the declared ones.
     */
    @Test
    public void registrationDeclaresEveryEmittedName() {
        StatManager stats = new StatManager(I2PAppContext.getGlobalContext());
        TestJob.registerDirectionStats(stats, RateConstants.SHORT_TERM_RATES);
        for (boolean inbound : new boolean[] {true, false}) {
            for (boolean success : new boolean[] {true, false}) {
                String name = TestJob.directionStat(inbound, success);
                assertNotNull(name + " was emitted but never registered",
                              stats.getRate(name));
            }
        }
    }

    /**
     * Declaration is expected to happen once at startup, but a second call
     * (e.g. after a context restart) must not fail or duplicate.
     */
    @Test
    public void registrationIsIdempotent() {
        StatManager stats = new StatManager(I2PAppContext.getGlobalContext());
        TestJob.registerDirectionStats(stats, RateConstants.SHORT_TERM_RATES);
        TestJob.registerDirectionStats(stats, RateConstants.SHORT_TERM_RATES);
        assertNotNull(stats.getRate(TestJob.directionStat(false, true)));
        assertNotNull(stats.getRate(TestJob.directionStat(false, false)));
    }

    /**
     * Nothing else in the Tunnels group may squat on these names.
     */
    @Test
    public void namesDoNotCollideWithPreexistingTestStats() {
        String[] preexisting = {
            "tunnel.testSuccessTime",
            "tunnel.testSuccessLength",
            "tunnel.testFailedTime",
            "tunnel.testFailedCompletelyTime",
            "tunnel.testExploratoryFailedTime",
            "tunnel.testExploratoryFailedCompletelyTime",
            "tunnel.testFailedDataTrust",
        };
        for (String name : preexisting) {
            assertNotEquals(name, TestJob.directionStat(true, true));
            assertNotEquals(name, TestJob.directionStat(true, false));
            assertNotEquals(name, TestJob.directionStat(false, true));
            assertNotEquals(name, TestJob.directionStat(false, false));
        }
    }
}
