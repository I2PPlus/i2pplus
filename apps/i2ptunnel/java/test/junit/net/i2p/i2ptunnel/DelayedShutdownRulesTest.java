package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins the two rules that decide how long a graceful restart defers, and for
 * which servers.
 *
 * <p>The console's restart button renders its countdown from
 * {@link TunnelControllerGroup#getRemainingShutdownDelay()}, which is the
 * longest configured shutdown delay minus the time elapsed. Both ends of that
 * number come from the rules here: which servers count as delayed at all, and
 * how long the group is willing to wait for them. A server that is wrongly
 * counted defers a restart for a tunnel that stops at once; a wait bound that is
 * too small stops measuring the deferral before the servers have stopped, and
 * the countdown the user is watching lies.
 *
 * <p>The wait bound also has to stay finite. It is applied on a worker thread
 * now, so the restart request returns at once, but the shutdown sequence that
 * follows still waits for the delayed tunnels, and a misconfigured delay of
 * hours must not stall a router that is trying to restart.
 */
public class DelayedShutdownRulesTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for
     * each one; the rules under test are static and carry no state.
     */
    public DelayedShutdownRulesTest() {}

    // ---------- which servers defer the shutdown ----------

    /**
     * A server with a range to pick from stops on a timer, so the shutdown
     * defers for it.
     */
    @Test
    public void testServerWithADelayRangeIsDelayed() {
        assertTrue(TunnelControllerGroup.isDelayedServer(0, 60));
        assertTrue(TunnelControllerGroup.isDelayedServer(30, 31));
        assertTrue(TunnelControllerGroup.isDelayedServer(0, Integer.MAX_VALUE));
    }

    /**
     * A server with no range stops at once, so it is not part of the delayed
     * shutdown and the router does not defer for it.
     */
    @Test
    public void testServerWithoutARangeIsNotDelayed() {
        assertFalse("an empty range stops immediately", TunnelControllerGroup.isDelayedServer(0, 0));
        assertFalse("an inverted range is not a range", TunnelControllerGroup.isDelayedServer(60, 30));
        assertFalse("equal bounds stop immediately", TunnelControllerGroup.isDelayedServer(45, 45));
    }

    /**
     * A server with no delay configured reports negative bounds, and is not
     * part of the delayed shutdown.
     */
    @Test
    public void testServerWithNoDelayConfiguredIsNotDelayed() {
        assertFalse(TunnelControllerGroup.isDelayedServer(-1, -1));
        assertFalse("a minimum of -1 means unset", TunnelControllerGroup.isDelayedServer(-1, 120));
    }

    // ---------- how long the group waits ----------

    /** The floor is a 30 second margin, so a zero delay is not a zero wait. */
    @Test
    public void testWaitHasAMargin() {
        assertEquals(30 * 1000L, TunnelControllerGroup.maxWaitMs(0));
        assertEquals(31 * 1000L, TunnelControllerGroup.maxWaitMs(1));
    }

    /**
     * A delay inside the cap is honoured in full, plus the margin: the servers
     * get the time the user configured for them.
     */
    @Test
    public void testWaitHonoursADelayInsideTheCap() {
        assertEquals(90 * 1000L, TunnelControllerGroup.maxWaitMs(60));
        assertEquals(299 * 1000L, TunnelControllerGroup.maxWaitMs(269));
        assertEquals(300 * 1000L, TunnelControllerGroup.maxWaitMs(270));
    }

    /**
     * The cap is five minutes. It bounds the wait for a shutdown sequence that
     * has to finish, so a delay of hours cannot stall a restart.
     */
    @Test
    public void testWaitIsCapped() {
        assertEquals(300 * 1000L, TunnelControllerGroup.maxWaitMs(271));
        assertEquals(300 * 1000L, TunnelControllerGroup.maxWaitMs(3600));
        assertEquals(300 * 1000L, TunnelControllerGroup.maxWaitMs(Integer.MAX_VALUE));
    }

    /** The wait is never negative, whatever the delay is. */
    @Test
    public void testWaitIsNeverNegative() {
        assertTrue(TunnelControllerGroup.maxWaitMs(0) > 0);
        assertEquals(0L, TunnelControllerGroup.maxWaitMs(-30));
        assertEquals(0L, TunnelControllerGroup.maxWaitMs(Integer.MIN_VALUE));
    }
}
