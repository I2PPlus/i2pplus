package net.i2p.util;

import java.util.Random;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;

/**
 * A "synthetic" queue that does not store data but estimates the average queue size
 * assuming a fixed output bandwidth, and applies the Random Early Detection (RED)
 * algorithm to decide probabilistically whether to accept or drop packets.
 *
 * The purpose is to model queue behavior and preemptively drop packets to prevent congestion
 * before an actual queue is affected downstream.
 *
 * The average queue size is measured in bytes and is updated continuously, assuming the
 * queue drains at a constant rate defined at construction.
 *
 * Packet acceptance is based on the RED algorithm, which uses the current average queue size
 * and the offered packet size to calculate a drop probability. The drop probability is adjusted
 * by a factor parameter and limited by configurable minimum and maximum queue size thresholds.
 *
 * This class also incorporates a Westwood+ bandwidth estimator that updates bandwidth estimates
 * based on acknowledged packet sizes and timing, using an exponential weighted moving average (EWMA).
 * Both bandwidth and queue size estimates are only updated when packets are accepted.
 *
 * The implementation is adapted from the Linux kernel tcp_westwood.c logic and follows
 * established research including:
 * - Random Early Detection Gateways for Congestion Avoidance (Floyd &amp; Jacobson)
 * - TCP Westwood: End-to-End Congestion Control for Wired/Wireless Networks (Casetti et al.)
 * - End-to-End Bandwidth Estimation for Congestion Control (Grieco &amp; Mascolo)
 *
 * Note: minThB (minimum threshold) must be less than maxThB (maximum threshold) for correct RED operation.
 *
 * @since 0.9.50 adapted from streaming; moved from transport in 0.9.62
 */
public class SyntheticREDQueue implements BandwidthEstimator {

    private static final float MAX_DROP_PROBABILITY = 0.0002f;
    private static final int DECAY_FACTOR = 8;
    private static final int WESTWOOD_RTT_MIN = 50; // ms

    private static final int DEFAULT_LOW_THRESHOLD_DIV = 4;
    private static final int DEFAULT_HIGH_THRESHOLD_DIV = 2;

    private final I2PAppContext _context;
    private final Log _log;
    private final Random _random;

    private long _lastAckTime;
    private float _bKFiltered, _bKNonSmoothed;
    private int _bytesAcked;

    private int _dropCount = -1;
    private float _avgQueueSize, _queueSizeEstimate;
    private int _newDataSize;
    private long _lastQueueUpdateTime;

    private final int _minThresholdBytes, _maxThresholdBytes;
    private final int _bandwidthBps;
    private final float _bandwidthBytesPerMs;

    /**
     * Construct with default queue size thresholds based on bandwidth.
     *
     * @param ctx the I2P application context
     * @param bwBps nominal output bandwidth in bytes per second
     */
     public SyntheticREDQueue(I2PAppContext ctx, int bwBps) {
         this(ctx, bwBps,
         Math.max(1, (int)(bwBps / DEFAULT_LOW_THRESHOLD_DIV)),
         Math.max(1, (int)(bwBps / DEFAULT_HIGH_THRESHOLD_DIV)));
    }

    /**
     * Construct specifying bandwidth and queue size thresholds.
     *
     * Queue size thresholds:
     * - minThB: queue size in bytes to start probabilistically dropping packets
     * - maxThB: queue size in bytes at which all packets are dropped (100% drop probability)
     *
     * NOTE: minThB must be less than maxThB for proper operation.
     *
     * @param ctx the I2P application context
     * @param bwBps nominal output bandwidth in bytes per second
     * @param minThB minimum threshold queue size to start dropping (Bytes)
     * @param maxThB maximum threshold queue size to drop all packets (Bytes)
     */
    public SyntheticREDQueue(I2PAppContext ctx, int bwBps, int minThB, int maxThB) {
        if (minThB >= maxThB) {
            throw new IllegalArgumentException("minThB (" + minThB + ") must be less than maxThB (" + maxThB + ")");
        }
        _context = ctx;
        _log = ctx.logManager().getLog(SyntheticREDQueue.class);
        _random = _context.random();

        _lastAckTime = ctx.clock().now();
        _bytesAcked = -1;

        _minThresholdBytes = minThB;
        _maxThresholdBytes = maxThB;

        _bandwidthBps = bwBps;
        _bandwidthBytesPerMs = bwBps / 1000f;

        _lastQueueUpdateTime = _lastAckTime;

        if (_log.shouldDebug()) {
            _log.debug("Configured bandwidth: " + bwBps + "B/s; MinThreshold: " + minThB + "B; MaxThreshold: " + maxThB + "B");
        }
    }

    /**
     * @return the configured maximum bandwidth in bytes per second.
     */
    public int getMaxBandwidth() {
        return _bandwidthBps;
    }

    /**
     * Unconditionally accepts the given packet size.
     * Updates queue size and bandwidth estimates accordingly.
     *
     * @param size size of packet in bytes
     */
    public void addSample(int size) {
        offer(size, 0f);
    }

    /**
     * Offer a packet to the queue.
     *
     * The decision to accept or drop the packet is made based on RED algorithm using
     * the current average queue size and the offered packet size with a drop probability.
     * If packet is accepted, the queue size and bandwidth estimators are updated.
     *
     * @param size size in bytes of the packet
     * @param factor drop probability adjustment factor (1.0 for standard, 0 disables dropping)
     * @return true if accepted, false if dropped
     */
    public boolean offer(int size, float factor) {
        long now = _context.clock().now();
        return addSampleInternal(size, factor, now);
    }

    /**
     * Core synchronized update method that processes incoming byte packets,
     * decides whether to accept or drop them based on queue size and probabilistic dropping,
     * and updates bandwidth and queue statistics accordingly.
     *
     * @param bytes Number of bytes in the sample or packet received.
     * @param factor A factor affecting drop probability, typically related to congestion level (0 if no drop logic needed).
     * @param now Current timestamp in milliseconds.
     * @return true if the bytes are accepted and stats updated, false if the sample is dropped.
     */
    private synchronized boolean addSampleInternal(int bytes, float factor, long now) {
        // Initialization block for the very first sample received
        // Sets initial bandwidth estimates using bytes and elapsed time since lastAckTime (or a minimum RTT)
        if (_bytesAcked < 0) {
            long deltaT = Math.max(now - _lastAckTime, WESTWOOD_RTT_MIN);
            float bkdt = (float) bytes / deltaT; // Initial bandwidth estimate in bytes per millisecond
            _bKFiltered = bkdt;                  // Smoothed bandwidth estimate (filtered)
            _bKNonSmoothed = bkdt;               // Raw bandwidth estimate (non-smoothed)
            _bytesAcked = 0;                     // Total bytes acknowledged so far initialized to zero
            _lastAckTime = now;                  // Timestamp of last acknowledged sample
            _lastQueueUpdateTime = now;          // Timestamp of last queue size update
            _newDataSize = bytes;                // Pending new data size to accumulate
            if (_log.shouldDebug()) {
                _log.debug("First sample bytes: " + bytes + " deltaT: " + deltaT + " " + this);
            }
            return true;
        }

        // Update the estimated queue size if enough time has elapsed since last update
        long deltaTQueue = now - _lastQueueUpdateTime;
        if (deltaTQueue > WESTWOOD_RTT_MIN) {
            updateQueueSize(now, deltaTQueue);
        }

        // If drop probability factor is positive, apply probabilistic dropping logic based on queue thresholds
        if (factor > 0f) {
            // Immediately drop all bytes if queue size exceeds the configured max threshold
            if (_avgQueueSize > _maxThresholdBytes) {
                if (_log.shouldWarn()) {
                    _log.warn("Dropping bytes (queue size exceeded max): " + bytes + " " + this);
                }
                _dropCount = 0;  // Reset consecutive drop counter
                return false;    // Drop this sample (bytes rejected)
            }

            // If average queue size is above min threshold but below max, probabilistically drop bytes
            if (_avgQueueSize > _minThresholdBytes) {
                _dropCount++;  // Increment count of consecutive drop attempts

                // Compute base drop probability proportional to bytes size, factor, max drop probability, and queue size above min threshold
                float pb = (bytes / 1024f) * factor * MAX_DROP_PROBABILITY * (_avgQueueSize - _minThresholdBytes) / (_maxThresholdBytes - _minThresholdBytes);
                pb = Math.min(pb, MAX_DROP_PROBABILITY); // Clamp to max drop probability

                // Calculate adjusted drop probability pa that accounts for consecutive drops to avoid over-dropping
                float denominator = 1.0f - (_dropCount * pb);
                if (denominator <= 0) {
                    denominator = Float.MIN_VALUE; // Prevent division by zero or negative values
                }
                float pa = pb / denominator;

                // Clamp pa to maximum of 0.99 to avoid nonsensical probability >= 1
                pa = Math.min(pa, 0.99f);

                float rand = _random.nextFloat(); // Random float
                if (rand < pa) {
                    if (_log.shouldWarn()) {
                        _log.warn(String.format("Dropping bytes (probabilistic): %d; Factor: %.2f; Probability: %.4f; deltaTQueue: %d %s",
                                                bytes, factor, pa, deltaTQueue, this));
                    }
                    _dropCount = 0;
                    return false;
                }
                _dropCount = -1;
            }
        }

        // Accepted, update stats
        _newDataSize += bytes;
        _bytesAcked += bytes;

        long deltaTAck = now - _lastAckTime;
        if (deltaTAck >= WESTWOOD_RTT_MIN) {
            computeBandwidthEstimate(now, (int) deltaTAck);
        }

        if (_log.shouldDebug()) {
            _log.debug("Accepted bytes: " + bytes + "; Factor: " + factor + " " + this);
        }
        return true;
    }

    /**
     * Returns the current bandwidth estimate in bytes/ms.
     * Updates estimate if enough time elapsed since last update.
     *
     * @return bandwidth estimate in bytes/ms
     */
    public float getBandwidthEstimate() {
        long now = _context.clock().now();
        synchronized (this) {
            long deltaT = now - _lastAckTime;
            if (deltaT >= WESTWOOD_RTT_MIN) {
                return computeBandwidthEstimate(now, (int) deltaT);
            }
            return _bKFiltered;
        }
    }

    /**
     * Returns the current estimated average queue size in bytes.
     * Updates estimate if enough time elapsed since last update.
     *
     * @return queue size estimate in bytes
     */
    public float getQueueSizeEstimate() {
        long now = _context.clock().now();
        synchronized (this) {
            long deltaT = now - _lastQueueUpdateTime;
            if (deltaT >= WESTWOOD_RTT_MIN) {
                updateQueueSize(now, deltaT);
            }
            return _avgQueueSize;
        }
    }

    /**
     * Computes and updates the bandwidth estimate applying the Westwood EWMA filter.
     * Resets acknowledged byte count after update.
     *
     * @param now current time in milliseconds
     * @param rtt round-trip time in milliseconds (used as filter parameter)
     * @return the updated bandwidth estimate in bytes/ms
     */
    private synchronized float computeBandwidthEstimate(long now, int rtt) {
        if (_bytesAcked < 0) {
            return 0.0f;
        }
        updateBandwidthEstimate(now, _bytesAcked, rtt);
        _bytesAcked = 0;
        return _bKFiltered;
    }

    /**
     * Applies an exponential weighted moving average (EWMA) decay to the bandwidth estimate,
     * simulating bandwidth reduction when no packets have been received.
     */
    private void decayBandwidth() {
        _bKNonSmoothed *= (DECAY_FACTOR - 1) / (float) DECAY_FACTOR;
        _bKFiltered = westwoodFilter(_bKFiltered, _bKNonSmoothed);
    }

    /**
     * Applies EWMA decay to the queue size estimate to simulate queue draining over the given RTT.
     * Ensures queue size estimate does not drop below zero.
     *
     * @param rtt round-trip time in milliseconds to use for decay calculation
     */
    private void decayQueue(int rtt) {
        _queueSizeEstimate -= rtt * _bandwidthBytesPerMs;
        if (_queueSizeEstimate < 1) {
            _queueSizeEstimate = 0;
        }
        _avgQueueSize = westwoodFilter(_avgQueueSize, _queueSizeEstimate);
    }

    /**
     * Updates the bandwidth estimate based on newly acknowledged bytes and elapsed time,
     * applying Westwood EWMA filtering and injecting decay for periods with no packets.
     *
     * @param time current time in milliseconds
     * @param packets number of bytes acknowledged since last update
     * @param rtt current round-trip time in milliseconds (minimum enforced)
     */
    private void updateBandwidthEstimate(long time, int packets, int rtt) {
        long deltaT = time - _lastAckTime;
        if (rtt < WESTWOOD_RTT_MIN) {
            rtt = WESTWOOD_RTT_MIN;
        }
        if (deltaT > 2 * rtt) {
            int numRTTs = Math.min((int) ((deltaT / rtt) - 1), 2 * DECAY_FACTOR);
            for (int i = 0; i < numRTTs; i++) {
                decayBandwidth();
            }
            deltaT -= numRTTs * rtt;
        }

        if (packets > 0) {
            float bkdt = ((float) packets) / deltaT;
            _bKNonSmoothed = westwoodFilter(_bKNonSmoothed, bkdt);
            _bKFiltered = westwoodFilter(_bKFiltered, _bKNonSmoothed);
        } else {
            decayBandwidth();
        }
        _lastAckTime = time;
    }

    /**
     * Updates the average queue size estimator with new data sizes and elapsed time,
     * applying EWMA filtering and decay to reflect queue drainage.
     *
     * @param time current time in milliseconds
     * @param deltaT time elapsed since the last update in milliseconds
     */
    private void updateQueueSize(long time, long deltaT) {
        long originalDeltaT = deltaT;

        if (deltaT > 2 * WESTWOOD_RTT_MIN) {
            int numRTTs = Math.min((int) ((deltaT / WESTWOOD_RTT_MIN) - 1), 2 * DECAY_FACTOR);
            for (int i = 0; i < numRTTs; i++) {
                if (_avgQueueSize <= 0) break;
                decayQueue(WESTWOOD_RTT_MIN);
            }
            deltaT -= numRTTs * WESTWOOD_RTT_MIN;
        }

        int originalNewDataSize = _newDataSize;
        float newQueueSize = _newDataSize;

        if (_newDataSize > 0) {
            // Decrease queue size by drained bytes since last update
            newQueueSize -= deltaT * _bandwidthBytesPerMs;
            if (newQueueSize < 1) {
                newQueueSize = 0;
            }
            _queueSizeEstimate = westwoodFilter(_queueSizeEstimate, newQueueSize);
            _avgQueueSize = westwoodFilter(_avgQueueSize, _queueSizeEstimate);
            _newDataSize = 0;
        } else {
            // No new data, decay queue estimate to simulate drainage
            decayQueue((int) deltaT);
        }
        _lastQueueUpdateTime = time;

        if (_log.shouldDebug()) {
            _log.debug("Queue update - deltaT: " + originalDeltaT + " newData: " + originalNewDataSize +
                       " newQueueSize: " + newQueueSize + " queueSizeEstimate: " + _queueSizeEstimate + " " + this);
        }
    }

    /**
     * Applies a Westwood exponential weighted moving average (EWMA) filter,
     * combining previous and new sample values with a weighted average.
     *
     * @param oldVal previous filtered value
     * @param newVal new sample value
     * @return updated filtered value
     */
    private static float westwoodFilter(float oldVal, float newVal) {
        return (((DECAY_FACTOR - 1) * oldVal) + newVal) / DECAY_FACTOR;
    }


    /**
     * Returns a human-readable string displaying the current bandwidth and queue size estimates,
     * formatted for debugging or informational output.
     *
     * @return formatted status string including bandwidth and average queue size
     */
    @Override
    public synchronized String toString() {
        return "\n* " +
               (_bKFiltered > 0 ? "Bandwidth: " + DataHelper.formatSize2Decimal((long) (_bKFiltered * 1000), false) + " Bytes/s " : "") +
               (_avgQueueSize > 0 ? "Average Queue Size / " : "") +
               (_avgQueueSize > 0 ? DataHelper.formatSize2((long) _avgQueueSize, false) + " B / " : "") +
               "Limit: " + DataHelper.formatSize2Decimal((long) _bandwidthBps, false) + " Bytes/s";
    }
}