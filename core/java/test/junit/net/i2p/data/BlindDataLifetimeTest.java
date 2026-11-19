package net.i2p.data;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins how long a blinded destination is kept.
 *
 * <p>Both places that mint a BlindData stamped its expiry with an inline product, and
 * the unauthenticated one multiplied by 68 minutes in an hour: 90*24*68*60*1000 ms is
 * 102 days, not the 90 the leading 90 says it wants. The entry is kept by the router
 * until that instant, so a blinded destination the operator had rotated keys for
 * stayed in the netDb twelve days past its intended lifetime.
 *
 * @since 0.9.72+
 */
public class BlindDataLifetimeTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public BlindDataLifetimeTest() {}

    /**
     * The defect: the unauthenticated lifetime is the number of days its constant
     * names, converted with 60 minutes in an hour.
     */
    @Test
    public void testTheUnauthenticatedLifetimeIsNinetyDays() {
        assertEquals(90L * 24 * 60 * 60 * 1000, BlindData.UNAUTHENTICATED_LIFETIME);
        assertEquals(BlindData.UNAUTHENTICATED_LIFETIME, BlindData.lifetime(false, false));
    }

    /**
     * The authenticated lifetime is a year, the value that arm already had.
     */
    @Test
    public void testTheAuthenticatedLifetimeIsOneYear() {
        assertEquals(365L * 24 * 60 * 60 * 1000, BlindData.AUTHENTICATED_LIFETIME);
        assertEquals(BlindData.AUTHENTICATED_LIFETIME, BlindData.lifetime(true, false));
        assertEquals(BlindData.AUTHENTICATED_LIFETIME, BlindData.lifetime(false, true));
        assertEquals(BlindData.AUTHENTICATED_LIFETIME, BlindData.lifetime(true, true));
    }

    /**
     * Either a private key or a lookup password is enough to take the longer
     * lifetime, since both mean the client holds something worth keeping.
     */
    @Test
    public void testEitherCredentialTakesTheLongerLifetime() {
        assertTrue(BlindData.lifetime(true, false) > BlindData.lifetime(false, false));
        assertTrue(BlindData.lifetime(false, true) > BlindData.lifetime(false, false));
        assertEquals(BlindData.lifetime(true, false), BlindData.lifetime(false, true));
    }

    /**
     * Both lifetimes are a whole number of days and fit in a timestamp added to the
     * current time without overflowing.
     */
    @Test
    public void testTheLifetimesAreWholeDaysAndCannotOverflow() {
        assertEquals(0, BlindData.UNAUTHENTICATED_LIFETIME % (24 * 60 * 60 * 1000L));
        assertEquals(0, BlindData.AUTHENTICATED_LIFETIME % (24 * 60 * 60 * 1000L));
        long now = System.currentTimeMillis();
        assertTrue(now + BlindData.AUTHENTICATED_LIFETIME > 0);
        assertTrue(now + BlindData.UNAUTHENTICATED_LIFETIME > 0);
    }
}
