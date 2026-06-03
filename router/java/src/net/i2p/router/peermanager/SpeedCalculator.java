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
 * - Actual peak throughput adds to bandwidth tier estimate
 */
class SpeedCalculator {

    /** Base scores for profiled bandwidth tiers */
    private static final double BASE_X = 10000;
    private static final double BASE_P = 5000;
    private static final double BASE_O = 2500;
    private static final double BASE_N = 1250;
    private static final double BASE_M = 625;
    private static final double BASE_L = 312;
    private static final double BASE_K = 125;
    private static final double BASE_UNKNOWN = 100;

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
            // Peer has proven throughput — use it as floor, no time-based decay
            // Peak throughput is a persistent capability indicator
            speed = estimatedSpeed + actualThroughput;
        } else {
            // No throughput data: use bandwidth-tier estimate with slow decay
            // 50% decay every 30 minutes (was 5 min — too aggressive; caused
            // good-but-idle peers to drop below speed threshold during cascades)
            long lastUpdate = profile.getLastThroughputUpdate();
            long now = context.clock().now();
            long minutesSinceUpdate = lastUpdate > 0 ? (now - lastUpdate) / (60 * 1000L) : 0;
            double decay = Math.pow(0.5, Math.min(minutesSinceUpdate, 240) / 30.0);
            speed = estimatedSpeed * decay;
        }

        return Math.max(speed, 0.0d);
    }

    private static double getBaseScore(RouterContext context, PeerProfile profile) {
        NetworkDatabaseFacade ndb = context.netDb();
        if (ndb == null) return BASE_O; // Default to medium if netDb unavailable

        RouterInfo ri = (RouterInfo) ndb.lookupLocallyWithoutValidation(profile.getPeer());
        if (ri == null) return BASE_O; // Default to medium if RouterInfo unavailable

        String tier = ri.getBandwidthTier();
        if ("X".equals(tier)) return BASE_X;
        if ("P".equals(tier)) return BASE_P;
        if ("O".equals(tier)) return BASE_O;
        if ("N".equals(tier)) return BASE_N;
        if ("M".equals(tier)) return BASE_M;
        if ("L".equals(tier)) return BASE_L;
        if ("K".equals(tier)) return BASE_K;
        return BASE_UNKNOWN;
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
