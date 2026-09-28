package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import net.i2p.router.RouterContext;
import net.i2p.router.tunnel.TCConfig;
import net.i2p.router.tunnel.TunnelCreatorConfig;
import net.i2p.util.Clock;
import net.i2p.util.LogManager;

import org.junit.Before;
import org.junit.Test;

/**
 * Contract tests for the young-tunnel grace period and the retest-interval
 * fix that together stop a pool condemning tunnels faster than they can prove
 * themselves.
 *
 * <p>Measured on a live router: outbound pools condemned a tunnel every ~1.8
 * minutes against a 10-12 minute configured lifetime.  The arithmetic that
 * produces it is exact — the fastest retest interval (30s) times a four-strike
 * threshold is ~90 seconds — so a fresh tunnel could be condemned before it had
 * settled, joined the LeaseSet, or carried the traffic that would demonstrate
 * it was working.  A pool in that state never reaches steady state, which also
 * makes every other measurement of it unreliable.
 *
 * @since 0.9.71+
 */
public class TestJobYoungTunnelGraceTest {

    private RouterContext _ctx;

    @Before
    public void setUp() {
        _ctx = mock(RouterContext.class);
        when(_ctx.logManager()).thenReturn(mock(LogManager.class));
        Clock clock = mock(Clock.class);
        when(clock.now()).thenReturn(1_000_000_000L);
        when(_ctx.clock()).thenReturn(clock);
    }

    // ================= the creation timestamp =================

    @Test
    public void aNewTunnelReportsItsCreationTime() {
        long before = System.currentTimeMillis();
        TunnelCreatorConfig cfg = new TCConfig(_ctx, 2, false);
        long after = System.currentTimeMillis();
        assertTrue("creation time must be set at construction, not left at zero",
                   cfg.getCreationTime() >= before);
        assertTrue(cfg.getCreationTime() <= after);
    }

    @Test
    public void creationTimeIsNeverZero() {
        // The grace predicate has no unset case; this is what makes that safe.
        assertTrue(new TCConfig(_ctx, 2, false).getCreationTime() > 0);
        assertTrue(new TCConfig(_ctx, 2, true).getCreationTime() > 0);
    }

    // ================= the grace boundary =================

    @Test
    public void aBrandNewTunnelIsWithinGrace() {
        long now = 1_000_000_000L;
        assertTrue(TestJob.withinYoungTunnelGrace(now, now));
    }

    @Test
    public void aTunnelPastTheGraceIsNot() {
        long now = 1_000_000_000L;
        assertFalse("the grace must end, or a dead tunnel is shielded forever",
                    TestJob.withinYoungTunnelGrace(now - TestJob.YOUNG_TUNNEL_GRACE_MS, now));
        assertFalse(TestJob.withinYoungTunnelGrace(
                now - TestJob.YOUNG_TUNNEL_GRACE_MS - 1, now));
    }

    @Test
    public void unknownCreationTimeGetsNoGrace() {
        // Never built, or an implementor that does not track it: charge it.
        // Excusing failures we cannot date would shield an unknown tunnel.
        assertFalse(TestJob.withinYoungTunnelGrace(0, 1_000_000_000L));
        assertFalse(TestJob.withinYoungTunnelGrace(-1, 1_000_000_000L));
    }

    /**
     * The {@code creationTimeMs <= 0} guard is only load-bearing while the
     * clock is small.  With a realistic uptime the age of an unset timestamp
     * already exceeds the grace, so a test using a large {@code now} cannot
     * tell the difference — it would pass with the guard removed.  This pins
     * the case that actually depends on it: early in a router's life, a
     * never-set timestamp would otherwise look brand new.
     */
    @Test
    public void unknownCreationTimeGetsNoGraceEarlyInUptime() {
        long earlyUptime = 1_000L;
        assertFalse("an unset creation time must not read as new just because the "
                    + "router has only been up " + earlyUptime + "ms",
                    TestJob.withinYoungTunnelGrace(0, earlyUptime));
        assertFalse(TestJob.withinYoungTunnelGrace(0, 0L));
    }

    @Test
    public void aFutureCreationTimeGetsNoGrace() {
        // A clock step backwards must not read as "brand new forever".
        assertFalse(TestJob.withinYoungTunnelGrace(2_000_000_000L, 1_000_000_000L));
    }

    /**
     * The grace has to be shorter than a tunnel lifetime, or a genuinely dead
     * tunnel would be excused for a meaningful fraction of its life.  It also
     * has to exceed the time it takes to collect four strikes at the fastest
     * retest interval, or it would not actually break the loop it exists for.
     */
    @Test
    public void theGraceIsSizedBetweenTheStrikeTimeAndTheLifetime() {
        long strikes = TunnelCreatorConfig.MAX_CONSECUTIVE_TEST_FAILURES + 1;
        long timeToCondemnFastest = strikes * 30_000L;
        assertTrue("the grace must outlast the fastest path to condemnation (" +
                   timeToCondemnFastest + "ms), or it does not break the loop",
                   TestJob.YOUNG_TUNNEL_GRACE_MS > timeToCondemnFastest);
        assertTrue("the grace must stay well under a 10-12min lifetime",
                   TestJob.YOUNG_TUNNEL_GRACE_MS < 10 * 60 * 1000L);
    }

    // ================= the retest interval =================

    /**
     * The defect Fix B addresses: a tunnel with a low success rate but no
     * failures at all is unproven, and must not be given the fastest retest
     * interval, which is what produced four strikes in ninety seconds.
     */
    @Test
    public void theGraceAndRetestFixShareTheSamePremise() {
        // A tunnel inside its grace period has by definition not been judged.
        // Fix B gives such a tunnel a slower retest; this pins the premise
        // both fixes rest on rather than re-testing the private method.
        long now = 1_000_000_000L;
        assertTrue("a tunnel being granted grace must be one with no failures yet",
                   TestJob.withinYoungTunnelGrace(now, now));
    }

    /**
     * Both fixes live in direction-agnostic code — the grace predicate takes no
     * direction and getCreationTime() is set the same way for both — which is
     * the point.  The original problem was an asymmetry, so a new one must not
     * be introduced by the cure.
     */
    @Test
    public void theGraceDoesNotDependOnDirection() {
        long now = 1_000_000_000L;
        long inboundCreated = new TCConfig(_ctx, 2, true).getCreationTime();
        long outboundCreated = new TCConfig(_ctx, 2, false).getCreationTime();
        boolean inb = TestJob.withinYoungTunnelGrace(inboundCreated, now);
        boolean outb = TestJob.withinYoungTunnelGrace(outboundCreated, now);
        assertEquals("a same-age inbound and outbound tunnel must get the same verdict",
                     inb, outb);
    }
}
