package net.i2p.router.tunnel.pool;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Pins what the transit request throttle is allowed to do to the peer it has
 * just throttled. shouldThrottle() runs on the previous hop of a transit build
 * request, before the record is decrypted, so the peer in hand is not
 * necessarily the router that sent the request: an honest relay carries requests
 * for every creator in front of it, and at the default limits it reaches the
 * reject branch after about ten forwarded requests. Dropping this request is
 * therefore the only response that is always on; the ban and the session drop
 * are operator switches, and both are off by default.
 *
 * No router context is needed: the helpers are static.
 *
 * @since 0.9.72+
 */
public class TransitThrottlePunishmentTest {

    @Test
    public void throttlingIsOnAndPunishmentIsOff() {
        assertTrue("the throttle itself stays on", RequestThrottler.DEFAULT_SHOULD_THROTTLE);
        assertFalse("the reject path must not drop the previous hop's session by default",
                    RequestThrottler.DEFAULT_SHOULD_DISCONNECT);
        assertFalse("and no build-request path may ban it by default",
                    RequestThrottler.DEFAULT_BAN_EXCESSIVE_REQUESTS);
    }

    @Test
    public void burstBansOnlyWhenTheSwitchIsOn() {
        // the ladder itself is pinned in RequestThrottlerLuBanTest; this is the switch
        assertEquals(0, RequestThrottler.burstBanDurationFor(3, false));
        assertEquals(0, RequestThrottler.burstBanDurationFor(100, false));
        assertEquals(5 * 60 * 1000L, RequestThrottler.burstBanDurationFor(3, true));
        assertEquals(10 * 60 * 1000L, RequestThrottler.burstBanDurationFor(4, true));
        assertEquals(30 * 60 * 1000L, RequestThrottler.burstBanDurationFor(100, true));
    }

    @Test
    public void firstOffensesOnlyThrottleTheRequest() {
        // the switch being on does not pull the first two offenses into a ban
        assertEquals(0, RequestThrottler.burstBanDurationFor(1, true));
        assertEquals(0, RequestThrottler.burstBanDurationFor(2, true));
    }
}
