package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Whether the resend-delay pair can be left inverted (§4.4).
 * The Tuner applies minResendDelay, maxResendDelay and maxRTO one at a time, in
 * whatever order its cycles happen to reach them, and each setter used to clamp
 * only against its own range. A pair could therefore land with the floor above
 * the ceiling — both values legal, the combination fatal: computeRTO() clamped
 * every timer to the inverted pair's upper bound, below one RTT, so every packet
 * retransmitted before its ACK came back. The setters now keep the pair ordered
 * whichever order they are called in, and clampRto() orders the bounds itself.
 */
public class ConnectionOptionsResendDelayOrderTest {

    private int _savedMin;
    private int _savedMax;
    private int _savedRto;
    private int _savedDefault;

    @Before
    public void setUp() {
        _savedMin = ConnectionOptions.getMinResendDelayStatic();
        _savedMax = ConnectionOptions.getMaxResendDelayStatic();
        _savedRto = ConnectionOptions.getMaxRTOStatic();
        _savedDefault = ConnectionOptions.getDefaultResendDelayStatic();
    }

    @After
    public void tearDown() {
        ConnectionOptions.setMinResendDelay(_savedMin);
        ConnectionOptions.setMaxResendDelay(_savedMax);
        ConnectionOptions.setMaxRTO(_savedRto);
        ConnectionOptions.setDefaultResendDelay(_savedDefault);
    }

    /**
     * Raising the floor above the ceiling lifts the ceiling with it.
     */
    @Test
    public void testMinAboveMaxRaisesMax() {
        ConnectionOptions.setMaxResendDelay(1000);
        ConnectionOptions.setMinResendDelay(5000);
        assertEquals(5000, ConnectionOptions.getMinResendDelayStatic());
        assertEquals("the pair stayed inverted", 5000, ConnectionOptions.getMaxResendDelayStatic());
    }

    /**
     * Lowering the ceiling below the floor drops the floor with it.
     */
    @Test
    public void testMaxBelowMinLowersMin() {
        ConnectionOptions.setMinResendDelay(5000);
        ConnectionOptions.setMaxResendDelay(1000);
        assertEquals(1000, ConnectionOptions.getMaxResendDelayStatic());
        assertEquals("the pair stayed inverted", 1000, ConnectionOptions.getMinResendDelayStatic());
    }

    /**
     * The pair is ordered, labelled with the application order being asserted.
     *
     * @param when the order under test, used as the failure message
     */
    private static void assertOrdered(String when) {
        assertTrue(when, ConnectionOptions.getMinResendDelayStatic() <=
                         ConnectionOptions.getMaxResendDelayStatic());
    }

    /**
     * Both application orders of the pathological pair end up ordered — the
     * Tuner's cycle order is not ours to control.
     */
    @Test
    public void testEitherOrderEndsOrdered() {
        ConnectionOptions.setMaxResendDelay(1000);
        ConnectionOptions.setMinResendDelay(5000);
        assertOrdered("max applied before min");
        ConnectionOptions.setMinResendDelay(5000);
        ConnectionOptions.setMaxResendDelay(1000);
        assertOrdered("min applied before max");
    }

    /**
     * The RTO backoff ceiling never exceeds the ceiling computeRTO() applies.
     */
    @Test
    public void testMaxRtoCappedByMaxResendDelay() {
        ConnectionOptions.setMaxResendDelay(2000);
        ConnectionOptions.setMaxRTO(60000);
        assertEquals("maxRTO outgrew the RTO ceiling", 2000, ConnectionOptions.getMaxRTOStatic());
    }

    /**
     * The initial resend delay is capped by the ceiling too.
     */
    @Test
    public void testDefaultResendDelayCappedByMax() {
        ConnectionOptions.setMaxResendDelay(1000);
        ConnectionOptions.setDefaultResendDelay(3000);
        assertEquals("the initial timer exceeded its own ceiling",
                     1000, ConnectionOptions.getDefaultResendDelayStatic());
    }

    /**
     * A well-ordered pair clamps both ways and passes a value through.
     */
    @Test
    public void testClampRtoOrderedPair() {
        assertEquals(1500, ConnectionOptions.clampRto(1500, 300, 20000));
        assertEquals(300, ConnectionOptions.clampRto(50, 300, 20000));
        assertEquals(20000, ConnectionOptions.clampRto(99999, 300, 20000));
    }

    /**
     * An inverted pair still clamps to the ordered span, not to whichever bound
     * the expression happened to apply last.
     */
    @Test
    public void testClampRtoInvertedPair() {
        assertEquals("an inverted pair lost an in-range timer",
                     1500, ConnectionOptions.clampRto(1500, 5000, 1000));
        assertEquals(5000, ConnectionOptions.clampRto(9000, 5000, 1000));
        assertEquals(1000, ConnectionOptions.clampRto(50, 5000, 1000));
    }
}
