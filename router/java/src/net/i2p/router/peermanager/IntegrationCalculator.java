package net.i2p.router.peermanager;

import net.i2p.stat.RateConstants;

/**
 * Determine how well integrated a peer is - how likely they will be useful
 * to us if we are trying to get further connected.
 *
 */
class IntegrationCalculator {

    public static double calc(PeerProfile profile) {
        long val = 0;
        if (profile.getIsExpandedDB()) {
            // give more weight to recent counts
            val =  profile.getDbIntroduction().getRate(RateConstants.ONE_DAY).getLastEventCount();
            val += profile.getDbIntroduction().getRate(RateConstants.ONE_DAY).getCurrentEventCount() * 2;
            val += 24 * profile.getDbIntroduction().getRate(RateConstants.ONE_HOUR).getLastEventCount();
            val += 24 * profile.getDbIntroduction().getRate(RateConstants.ONE_HOUR).getCurrentEventCount() * 2;
            val /= 16;
        }
        val += profile.getIntegrationBonus();
        return val;
    }
}
