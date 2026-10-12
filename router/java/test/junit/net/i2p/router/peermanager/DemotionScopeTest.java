package net.i2p.router.peermanager;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * A latency judgement may revoke a latency-based promotion and nothing else.
 *
 * <p>{@code demoteIfNotLowLatency} used to evict from the fast tier and the
 * high-capacity tier alike. High-capacity membership is earned from advertised
 * capacity and sustained throughput, so a peer that stops being low-latency has
 * not stopped being high capacity; and with a calibrated per-transport floor, a
 * transport whose baseline rose during congestion could strip
 * bandwidth-qualified peers out of a tier they were admitted to for reasons that
 * had nothing to do with the signal.
 *
 * <p>The rule is expressed as a pure predicate so it can be pinned without a
 * router context.
 *
 * @since 0.9.71+
 */
public class DemotionScopeTest {

    @Test
    public void fastLosingLatencyIsDemoted() {
        assertTrue(ProfileOrganizer.demotesFastTier(true, false));
    }

    @Test
    public void fastKeepingLatencyIsNotDemoted() {
        assertFalse(ProfileOrganizer.demotesFastTier(true, true));
        assertFalse(ProfileOrganizer.demotesFastTier(false, false));
    }

    @Test
    public void aHighCapOnlyPeerLosingLatencyIsUntouched() {
        // the regression: this pair used to evict the peer from high capacity,
        // because the old condition was (inFast || inHighCap) && !lowLatency
        assertFalse("a peer not in the fast tier is never demoted by this signal",
                    ProfileOrganizer.demotesFastTier(false, false));
    }
}