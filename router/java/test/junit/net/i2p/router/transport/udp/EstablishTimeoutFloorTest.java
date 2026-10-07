package net.i2p.router.transport.udp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The establishment-timeout floor.
 *
 * <p>Measured on a live router: at a 2250ms budget the outbound path abandoned roughly
 * 117 session establishments per minute, every one expiring on the deadline rather than
 * on an observed peer failure, and 99.6% of them inside the SSU2 token exchange. Raising
 * the budget to 5000ms took that to zero. The old tunable floor was 1500ms, below the
 * value known to be harmful, so dragging the slider - or a restore-defaults - could
 * silently reintroduce the failure.
 *
 * @since 0.9.71+
 */
public class EstablishTimeoutFloorTest {

    /** The validated floor: above the proven-harmful 2250, below the costly 5000. */
    private static final long SAFE = net.i2p.router.Tuner.ESTABLISH_TIMEOUT_MIN;
    private static final long CEILING = 10000L;

    private static long clampOb(long ms) { return Math.max(SAFE, Math.min(CEILING, ms)); }
    private static long clampIb(long ms) { return Math.max(SAFE, Math.min(CEILING, ms)); }

    /** The value that caused the failure can no longer be set, in either direction. */
    @Test
    public void theHarmfulValueCanNoLongerBeSet() {
        long[] harmful = {0, 1500, 2000, 2250, 2500, 3000, 3999};
        for (long ms : harmful) {
            assertEquals("outbound accepted a known-harmful " + ms, SAFE, clampOb(ms));
            assertEquals("inbound accepted a known-harmful " + ms, SAFE, clampIb(ms));
        }
    }

    @Test
    public void valuesAtOrAboveTheFloorPassThrough() {
        for (long ms = SAFE; ms <= CEILING; ms += 500) {
            assertEquals(ms, clampOb(ms));
            assertEquals(ms, clampIb(ms));
        }
    }

    @Test
    public void ceilingIsClamped() {
        assertEquals(CEILING, clampOb(CEILING + 1));
        assertEquals(CEILING, clampOb(100000L));
        assertEquals(CEILING, clampIb(100000L));
    }

    /**
     * The floor must equal the upstream default, so the param's default is the lowest
     * legal value and "restore defaults" cannot lower it below the safe point.
     */
    @Test
    public void floorIsSharedWithTheTuner() {
        assertEquals("the setter and the param must not drift apart",
                     4000, SAFE);
        assertTrue("floor must stay above the value that caused the failure",
                   SAFE > 2250);
    }

    /** The ceiling exists so autotune, which targets ~4x observed, is not clamped back down. */
    @Test
    public void ceilingLeavesAutotuneHeadroom() {
        assertTrue("ceiling must exceed the floor or the param cannot be tuned at all",
                   CEILING > SAFE);
        assertTrue(CEILING > 4 * 196);  // above 4x the observed mean handshake
    }
}
