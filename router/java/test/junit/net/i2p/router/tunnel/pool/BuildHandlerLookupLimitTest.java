package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Unit tests for the cold-start next-hop lookup limit decision extracted
 * from BuildHandler ({@link BuildHandler#lookupLimit}).
 *
 * @since 0.9.71+
 */
public class BuildHandlerLookupLimitTest {

    private static final long TEN_MIN = 10 * 60 * 1000L;
    private static final int MIN = 10;
    private static final int MAX = 32;
    private static final int PCT = 40;

    // ---- startup boost window ------------------------------------------------

    @Test
    public void startupBoostUsesFullCeiling() {
        // transit count irrelevant during the boost: the netDb miss rate is
        // high and the proportional formula would clamp to the floor
        assertEquals(MAX, BuildHandler.lookupLimit(0, MIN, MAX, PCT, 0));
        assertEquals(MAX, BuildHandler.lookupLimit(5, MIN, MAX, PCT, TEN_MIN - 1));
    }

    @Test
    public void boostExpiresAfterWindow() {
        long justAfter = TEN_MIN;
        assertEquals(Math.max(MIN, Math.min(MAX, 20 * PCT / 100)),
                     BuildHandler.lookupLimit(20, MIN, MAX, PCT, justAfter));
    }

    // ---- steady-state formula ------------------------------------------------

    @Test
    public void proportionalWithinCeiling() {
        assertEquals(26, BuildHandler.lookupLimit(65, MIN, MAX, PCT, TEN_MIN * 2));
    }

    @Test
    public void ceilingCapsLargePools() {
        assertEquals(MAX, BuildHandler.lookupLimit(500, MIN, MAX, PCT, TEN_MIN * 2));
    }

    @Test
    public void floorKeepsDrainedPoolLookingUp() {
        // a drained pool must not zero out next-hop resolution
        assertEquals(MIN, BuildHandler.lookupLimit(0, MIN, MAX, PCT, TEN_MIN * 2));
    }

    @Test
    public void slowBoxParametersRespected() {
        // IS_SLOW profile: min 4, max 10, pct 15
        assertEquals(10, BuildHandler.lookupLimit(500, 4, 10, 15, TEN_MIN * 2));
        assertEquals(4, BuildHandler.lookupLimit(0, 4, 10, 15, TEN_MIN * 2));
        assertEquals(10, BuildHandler.lookupLimit(3, 4, 10, 15, TEN_MIN / 2));
    }
}
