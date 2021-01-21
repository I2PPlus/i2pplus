package net.i2p.router.peermanager;

/**
 * Determine how well integrated the peer is - how likely they will be useful
 * to us if we are trying to get further connected.
 *
 */
class IntegrationCalculator {

    public static double calc(PeerProfile profile) {
        long val = 0;
        if (profile.getIsExpandedDB()) {
            // give more weight to recent counts
            val =  profile.getDbIntroduction().getRate(24*60*60*1000l).getLastEventCount();
            val += profile.getDbIntroduction().getRate(24*60*60*1000l).getCurrentEventCount() * 2;
            val += 24 * profile.getDbIntroduction().getRate(60*60*1000l).getLastEventCount();
            val += 24 * profile.getDbIntroduction().getRate(60*60*1000l).getCurrentEventCount() * 2;
            val += 6 * 24 * profile.getDbIntroduction().getRate(10*60*1000l).getLastEventCount();
            val += 6 * 24 * profile.getDbIntroduction().getRate(10*60*1000l).getCurrentEventCount() * 2;
            val /= 24;
            if (val > 0 && val < 1)
                val = 1;
        }
        val += profile.getIntegrationBonus();
        return val;
    }
}
