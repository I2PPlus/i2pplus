package net.i2p.router.transport.ntcp;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Test;

import net.i2p.util.RandomSource;

/**
 *  Unit tests for {@link EventPumper#evaluateInboundFlood(long, int, int, long, boolean, int, int, RandomSource)},
 *  the pure decision half of the inbound flood throttle. Pins the exact arithmetic so
 *  the reasoning that underlies it (accept-rate spike over baseline, warmed-up period,
 *  two-thirds connection ceiling) cannot be broken accidentally, and confirms the hot
 *  path is fully deterministic: once the flood gate opens, the probabilistic term is
 *  always saturated, so {@code nextInt()} is never consulted.
 *
 *  @since 0.9.71+
 */
public class InboundFloodDecisionTest {

    /** 60s into a rate period: currentTime == lastPeriod, rates are easy to reason about. */
    private static final long NOW = 60 * 1000L;

    private static final int MAX_CONNECTIONS = 100;
    private static final int TWO_THIRDS = MAX_CONNECTIONS * 2 / 3;

    /**
     *  A random source that always returns a fixed value from {@code nextInt(int)}
     *  and counts invocations, so tests can assert whether the probabilistic
     *  branch was ever reached.
     */
    private static final class ScriptedRandom extends RandomSource {
        private final int _result;
        private int _calls;
        ScriptedRandom(int result) {
            super(net.i2p.I2PAppContext.getGlobalContext());
            _result = result;
        }
        @Override public int nextInt(int n) { _calls++; return _result; }
        int calls() { return _calls; }
    }

    /**
     *  No current-period activity means the "current - last" subtraction allows
     *  everything, regardless of the stale-event totals.
     */
    @Test
    public void testNoCurrentActivityAllowed() {
        EventPumper.InboundFloodDecision d = EventPumper.evaluateInboundFlood(NOW, 0, 100, 0, true,
                                                                  MAX_CONNECTIONS, 50, new ScriptedRandom(0));
        assertFalse(d.isDrop());
        assertSame(EventPumper.InboundFloodDecision.ALLOW, d);
    }

    /**
     *  The accept rate is meaningless in the first seconds after a period rollover,
     *  so the throttle is always disengaged during warmup.
     */
    @Test
    public void testWarmupPeriodAllowed() {
        EventPumper.InboundFloodDecision d = EventPumper.evaluateInboundFlood(1000, 5000, 100, 0, true,
                                                                  MAX_CONNECTIONS, 100, new ScriptedRandom(0));
        assertFalse(d.isDrop());
    }

    /**
     *  Current rate at or near the baseline never trips the flood gate, even when
     *  the router is at its connection ceiling.
     */
    @Test
    public void testBaselineRateAllowed() {
        // currentRate = 90/60s = 1.5/s vs gate of 5/3 * 1.05 * (100/60s) = 2.9/s
        EventPumper.InboundFloodDecision d = EventPumper.evaluateInboundFlood(NOW, 90, 100, 0, true,
                                                                  MAX_CONNECTIONS, 100, new ScriptedRandom(0));
        assertFalse(d.isDrop());
    }

    /**
     *  A huge accept-rate spike is still allowed while the router is below
     *  two-thirds of its connection ceiling - load is the second gate.
     */
    @Test
    public void testBelowConnectionCeilingAllowed() {
        EventPumper.InboundFloodDecision d = EventPumper.evaluateInboundFlood(NOW, 10000, 100, 0, true,
                                                                  MAX_CONNECTIONS, TWO_THIRDS, new ScriptedRandom(0));
        assertFalse(d.isDrop());
    }

    /**
     *  Once the gate opens (rate spike + high connection count), rejection is
     *  deterministic: the accept-probability term saturates well past the 128
     *  ceiling, so the random draw is never actually consumed. Verdicted carries
     *  the numbers used for logging.
     */
    @Test
    public void testFloodDropDeterministic() {
        ScriptedRandom never = new ScriptedRandom(0);
        EventPumper.InboundFloodDecision d = EventPumper.evaluateInboundFlood(NOW, 10000, 100, 0, true,
                                                                  MAX_CONNECTIONS, MAX_CONNECTIONS, never);
        assertTrue(d.isDrop());
        assertEquals(100, d.getPercent());
        assertEquals(100, d.getLastConnections());
        assertEquals(10000, d.getCurrentConnectionsPerMinute());
        assertEquals("nextInt() must not be consulted once the gate is saturated", 0, never.calls());

        // A different random draw must not change the outcome.
        ScriptedRandom always = new ScriptedRandom(999);
        EventPumper.InboundFloodDecision d2 = EventPumper.evaluateInboundFlood(NOW, 10000, 100, 0, true,
                                                                   MAX_CONNECTIONS, MAX_CONNECTIONS, always);
        assertTrue(d2.isDrop());
        assertEquals(0, always.calls());
    }

    /**
     *  The previous-period baseline is floored at 15 events; a drop must report
     *  the floored value (used in the warn log), never the raw sub-floor count.
     */
    @Test
    public void testLastEventFloorApplied() {
        // rawLast = 10 -> floored to 15; current = 10000 + 10 - 15 = 9995
        EventPumper.InboundFloodDecision d = EventPumper.evaluateInboundFlood(NOW, 10000, 10, 0, true,
                                                                  MAX_CONNECTIONS, MAX_CONNECTIONS, new ScriptedRandom(0));
        assertTrue(d.isDrop());
        assertEquals(15, d.getLastConnections());
        assertEquals(9995, d.getCurrentConnectionsPerMinute());
    }

    /**
     *  The floor is symmetric: a sub-floor last period makes "current" shrink by
     *  the deficit, so small totals can legitimately read as zero activity.
     *  rawCurrent=5, rawLast=10 -> last=15, current=0 -> allowed.
     */
    @Test
    public void testLastEventFloorSubtractionShrinksCurrent() {
        EventPumper.InboundFloodDecision d = EventPumper.evaluateInboundFlood(NOW, 5, 10, 0, true,
                                                                  MAX_CONNECTIONS, MAX_CONNECTIONS, new ScriptedRandom(0));
        assertFalse(d.isDrop());
        assertSame(EventPumper.InboundFloodDecision.ALLOW, d);
    }

    /**
     *  The saturation factor tightens the flood threshold: when the transport
     *  reports full capacity the same overloaded rate must still be dropped.
     */
    @Test
    public void testSaturatedTransportStillDrops() {
        // currentRate = 2/s, gate = 5/3 * 0.95 * (100/60s) = 2.64/s -> rate below gate alone
        // so this one is allowed...
        EventPumper.InboundFloodDecision low = EventPumper.evaluateInboundFlood(NOW, 120, 100, 0, false,
                                                                    MAX_CONNECTIONS, MAX_CONNECTIONS, new ScriptedRandom(0));
        assertFalse(low.isDrop());
        // ...but a real spike is rejected identically to the spare-capacity case.
        EventPumper.InboundFloodDecision high = EventPumper.evaluateInboundFlood(NOW, 10000, 100, 0, false,
                                                                     MAX_CONNECTIONS, MAX_CONNECTIONS, new ScriptedRandom(0));
        assertTrue(high.isDrop());
        assertEquals(100, high.getPercent());
    }
}
