package net.i2p.router.transport.udp;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.BandwidthEstimator;
import net.i2p.util.Log;

/**
 * A Westwood+ bandwidth estimator with:
 * &lt;ul&gt;
 *   &lt;li&gt;A first-stage anti-aliasing low-pass filter based on RTT&lt;/li&gt;
 *   &lt;li&gt;A time-varying EWMA filter based on inter-arrival time&lt;/li&gt;
 * &lt;/ul&gt;
 *
 * &lt;p&gt;This estimator is adapted from the Linux kernel's &lt;code&gt;tcp_westwood.c&lt;/code&gt;
 * implementation and the Westwood+ research paper.&lt;/p&gt;
 *
 * &lt;p&gt;Ref: &lt;em&gt;TCP Westwood: End-to-End Congestion Control for Wired/Wireless Networks&lt;/em&gt; - Casetti et al.&lt;/p&gt;
 * &lt;p&gt;Ref: &lt;em&gt;End-to-End Bandwidth Estimation for Congestion Control in Packet Networks&lt;/em&gt; - Grieco and Mascolo.&lt;/p&gt;
 *
 * &lt;p&gt;Adapted for I2P streaming transport in 0.9.49.&lt;/p&gt;
 */
class SimpleBandwidthEstimator implements BandwidthEstimator {

    private final I2PAppContext _context;
    private final Log _log;
    private final PeerState _state;

    private long _tAck;
    private float _bKFiltered, _bK_ns_est;
    private int _acked;

    private static final int DECAY_FACTOR = 8;
    private static final int WESTWOOD_RTT_MIN = 500;

    /**
     * Creates a new bandwidth estimator for the given peer.
     *
     * @param ctx I2P application context
     * @param state peer state for RTT and other transport metrics
     */
    SimpleBandwidthEstimator(I2PAppContext ctx, PeerState state) {
        _log = ctx.logManager().getLog(SimpleBandwidthEstimator.class);
        _context = ctx;
        _state = state;
        _tAck = ctx.clock().now();
        _acked = -1;
    }

    /**
     * Records a new bandwidth sample based on the number of bytes acknowledged.
     *
     * @param acked number of bytes newly acknowledged
     */
    public void addSample(int acked) {
        long now = _context.clock().now();
        int rtt = _state.getRTT();
        addSample(acked, now, rtt);
    }

    /**
     * Internal synchronized version of addSample that also takes the current time and RTT.
     *
     * @param acked number of bytes newly acknowledged
     * @param now current time in milliseconds
     * @param rtt current round-trip time estimate
     */
    private synchronized void addSample(int acked, long now, int rtt) {
        if (_acked < 0) {
            long deltaT = Math.max(now - _tAck, WESTWOOD_RTT_MIN);
            float bkdt = ((float) acked) / deltaT;
            _bKFiltered = bkdt;
            _bK_ns_est = bkdt;
            _acked = 0;
            _tAck = now;
            if (_log.shouldDebug()) {
                _log.debug(String.format("Initial sample: %d bytes over %d ms → %.4f B/ms (%s/s)",
                                         acked, deltaT, _bKFiltered, formatRate(_bKFiltered * 1000)));
            }
        } else {
            _acked += acked;
            if (now - _tAck >= Math.max(rtt, WESTWOOD_RTT_MIN))
                computeBWE(now, rtt);
        }
    }

    /**
     * Returns the current bandwidth estimate in bytes per millisecond.
     *
     * @return bandwidth estimate in bytes per millisecond
     */
    public float getBandwidthEstimate() {
        return getBandwidthEstimate(_context.clock().now());
    }

    /**
     * Returns the current bandwidth estimate at the specified time.
     *
     * @param now current time in milliseconds
     * @return bandwidth estimate in bytes per millisecond
     * @since 0.9.58
     */
    public float getBandwidthEstimate(long now) {
        int rtt = _state.getRTT();
        synchronized(this) {
            if (now - _tAck >= Math.max(rtt, WESTWOOD_RTT_MIN))
                return computeBWE(now, rtt);
            return _bKFiltered;
        }
    }

    /**
     * Synchronized version of the bandwidth computation method.
     *
     * @param now current time in milliseconds
     * @param rtt current round-trip time
     * @return updated bandwidth estimate
     */
    private synchronized float computeBWE(final long now, final int rtt) {
        if (_acked < 0)
            return 0.0f; // nothing ever sampled
        updateBK(now, _acked, rtt);
        _acked = 0;
        return _bKFiltered;
    }

    /**
     * Applies exponential decay to the bandwidth estimate when no new data is received.
     */
    private void decay() {
        _bK_ns_est *= (DECAY_FACTOR - 1) / (float) DECAY_FACTOR;
        _bKFiltered = westwood_do_filter(_bKFiltered, _bK_ns_est);
    }

    /**
     * Updates the bandwidth estimate using the latest sample.
     * If no recent acknowledgments were received, virtual null samples are inserted.
     *
     * @param time time of the measurement
     * @param packets number of bytes acknowledged
     * @param rtt current round-trip time
     */
    private void updateBK(long time, int packets, int rtt) {
        long deltaT = time - _tAck;
        if (rtt < WESTWOOD_RTT_MIN)
            rtt = WESTWOOD_RTT_MIN;
        if (deltaT > 2 * rtt) {
            int numrtts = Math.min((int) ((deltaT / rtt) - 1), 2 * DECAY_FACTOR);
            for (int i = 0; i < numrtts; i++) {
                decay();
            }
            deltaT -= numrtts * rtt;
            if (_log.shouldDebug()) {
                String rate = formatRate(_bK_ns_est * 1000);
                _log.debug(String.format("No ACKs → Decayed %d× → %s/s", numrtts, rate));
            }
        }
        float bkdt;
        if (packets > 0) {
            bkdt = ((float) packets) / deltaT;
            _bK_ns_est = westwood_do_filter(_bK_ns_est, bkdt);
            _bKFiltered = westwood_do_filter(_bKFiltered, _bK_ns_est);
        } else {
            bkdt = 0;
            decay();
        }
        _tAck = time;
        if (_log.shouldDebug()) {
            String rate = formatRate(_bK_ns_est * 1000);
            _log.debug(String.format("%d B over %d ms → %.4f B/ms (%s/s)", packets, deltaT, bkdt, rate));
        }
    }

    private String formatRate(double value) {
        return DataHelper.formatSize2Decimal((long) value, false).trim();
    }

    /**
     * Applies an exponential weighted moving average (EWMA) filter to the bandwidth estimate.
     *
     * @param a previous estimate
     * @param b new measurement
     * @return filtered estimate
     */
    private static float westwood_do_filter(float a, float b) {
        return (((DECAY_FACTOR - 1) * a) + b) / DECAY_FACTOR;
    }

    /**
     * Returns a string representation of this estimator's current state.
     *
     * @return string representation
     */
    @Override
    public synchronized String toString() {
        return String.format("Bandwidth: %.4f B/ms (%s/s) @ %d ms", _bKFiltered, formatRate(_bKFiltered * 1000), _tAck);
    }
}