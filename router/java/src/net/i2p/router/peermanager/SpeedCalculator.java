package net.i2p.router.peermanager;

import net.i2p.data.router.RouterInfo;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;

/**
 * Quantify how fast the peer is based on advertised bandwidth tier,
 * measured latency, congestion capability, and actual tunnel throughput.
 *
 * Scoring:
 * - Base score from bandwidth tier (X=10000, P=5000, O=2500)
 * - RTT penalty reduces score for high-latency peers
 * - Congestion caps (D/E) reduce effective speed
 * - Actual peak throughput overrides estimate when available
 * - Speed bonus adds temporary boost for recently proven fast peers
 */
class SpeedCalculator {

    /** Base scores for profiled bandwidth tiers */
    private static final double BASE_X = 10000;
    private static final double BASE_P = 5000;
    private static final double BASE_O = 2500;

    /** RTT threshold where speed is fully penalized (16s) */
    private static final double MAX_RTT = 16000;

    /** Congestion capability multipliers */
    private static final double CONGESTION_D = 0.75;
    private static final double CONGESTION_E = 0.50;

    public static double calc(PeerProfile profile) {
        RouterContext context = profile.getContext();
        double baseScore = getBaseScore(context, profile);
        double rttFactor = getRTTFactor(profile);
        double congestionFactor = getCongestionFactor(context, profile);

        double estimatedSpeed = baseScore * rttFactor * congestionFactor;
        double actualThroughput = profile.getPeakTunnel1mThroughputKBps() * 1024d;

        double speed;
        if (actualThroughput > 0) {
            // Use actual throughput when available (immune to decay)
            speed = Math.max(estimatedSpeed, actualThroughput);
        } else {
            // No actual throughput: decay estimated speed over time
            // 50% decay every 5 minutes, effectively 0 after 30 minutes
            long lastUpdate = profile.getLastThroughputUpdate();
            long now = context.clock().now();
            long minutesSinceUpdate = (now - lastUpdate) / (60 * 1000L);
            if (lastUpdate <= 0 || minutesSinceUpdate >= 30) {
                // No throughput data or expired: estimated speed is 0
                speed = 0;
            } else {
                double decay = Math.pow(0.5, minutesSinceUpdate / 5.0);
                speed = estimatedSpeed * decay;
            }
        }

        // Add decaying speed bonus for recently proven fast peers
        speed += profile.getSpeedBonus();

        return speed >= 0 ? speed : 0.0d;
    }

    private static double getBaseScore(RouterContext context, PeerProfile profile) {
        NetworkDatabaseFacade ndb = context.netDb();
        if (ndb == null) return BASE_O; // Default to medium if netDb unavailable

        RouterInfo ri = (RouterInfo) ndb.lookupLocallyWithoutValidation(profile.getPeer());
        if (ri == null) return BASE_O; // Default to medium if RouterInfo unavailable

        String tier = ri.getBandwidthTier();
        if ("X".equals(tier)) return BASE_X;
        if ("P".equals(tier)) return BASE_P;
        return BASE_O; // O or unknown defaults to medium
    }

    @SuppressWarnings("deprecation")
    private static double getRTTFactor(PeerProfile profile) {
        float avgRTT = profile.getTunnelTestTimeAverage();
        if (avgRTT <= 0) return 1.0; // No RTT data, assume average
        double factor = 1.0 - (avgRTT / MAX_RTT);
        return Math.max(0.1, factor);
    }

    private static double getCongestionFactor(RouterContext context, PeerProfile profile) {
        NetworkDatabaseFacade ndb = context.netDb();
        if (ndb == null) return 1.0;

        RouterInfo ri = (RouterInfo) ndb.lookupLocallyWithoutValidation(profile.getPeer());
        if (ri == null) return 1.0;

        String caps = ri.getCapabilities();
        if (caps.indexOf(Router.CAPABILITY_CONGESTION_SEVERE) >= 0) return CONGESTION_E;
        if (caps.indexOf(Router.CAPABILITY_CONGESTION_MODERATE) >= 0) return CONGESTION_D;
        return 1.0;
    }
}
