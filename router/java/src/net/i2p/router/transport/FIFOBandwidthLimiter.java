package net.i2p.router.transport;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.i2p.router.RouterContext;
import net.i2p.router.util.PQEntry;
import net.i2p.stat.RateConstants;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * FIFO-based bandwidth limiter for managing inbound and outbound traffic.
 *
 * This class provides bandwidth management using First-In-First-Out
 * queues for both inbound and outbound traffic. It implements
 * token bucket algorithm to control data rates and prevent burst
 * traffic patterns.
 *
 * <strong>Core Features:</strong>
 * <ul>
 *   <li>FIFO request queuing for fair bandwidth allocation</li>
 *   <li>Token bucket rate limiting</li>
 *   <li>Separate inbound and outbound management</li>
 *   <li>Configurable bandwidth limits and refill rates</li>
 *   <li>Thread-safe operations with atomic counters</li>
 *   <li>Request satisfaction and partial fulfillment handling</li>
 * </ul>
 *
 * <strong>Concurrency Strategy:</strong>
 * <ul>
 *   <li>Java 5: Used synchronized ArrayList with head/tail access</li>
 *   <li>Java 6+: Uses LinkedBlockingDeque for lock-free operations</li>
 *   <li>Request polling from queue head for efficiency</li>
 *   <li>Partial request satisfaction with push-back mechanism</li>
 * </ul>
 *
 * <strong>Algorithm:</strong>
 * <ul>
 *   <li>Token refill at fixed intervals</li>
 *   <li>Request processing when tokens available</li>
 *   <li>Burst prevention through token depletion</li>
 *   <li>Priority-based request handling</li>
 * </ul>
 */
public class FIFOBandwidthLimiter {
    private final Log _log;
    private final RouterContext _context;
    private final List<SimpleRequest> _pendingInboundRequests;
    private final List<SimpleRequest> _pendingOutboundRequests;
    /** How many bytes we can consume for inbound transmission immediately. */
    private final AtomicInteger _availableInbound = new AtomicInteger();
    /** How many bytes we can consume for outbound transmission immediately. */
    private final AtomicInteger _availableOutbound = new AtomicInteger();
    /** How many bytes we can queue up for bursting. */
    private final AtomicInteger _unavailableInboundBurst = new AtomicInteger();
    /** How many bytes we can queue up for bursting. */
    private final AtomicInteger _unavailableOutboundBurst = new AtomicInteger();
    /** How large _unavailableInbound can get. */
    private volatile int _maxInboundBurst;
    /** How large _unavailableInbound can get. */
    private volatile int _maxOutboundBurst;
    /** How large _availableInbound can get - aka our inbound rate during a burst. */
    private volatile int _maxInbound;
    /** How large _availableOutbound can get - aka our outbound rate during a burst. */
    private volatile int _maxOutbound;
    /** Shortcut of whether our outbound rate is unlimited - UNUSED, always false for now. */
    private volatile boolean _outboundUnlimited;
    /** Shortcut of whether our inbound rate is unlimited - UNUSED, always false for now. */
    private volatile boolean _inboundUnlimited;
    /** Lifetime counter of bytes received. */
    private final AtomicLong _totalAllocatedInboundBytes = new AtomicLong();
    /** Lifetime counter of bytes sent. */
    private final AtomicLong _totalAllocatedOutboundBytes = new AtomicLong();
    // following is temp until switch to PBQ
    private static final AtomicLong __requestId = new AtomicLong();


    private final FIFOBandwidthRefiller _refiller;
    private final Thread _refillerThread;

    private volatile long _lastTotalSent;
    private volatile long _lastTotalReceived;
    private volatile long _lastStatsUpdated;
    private volatile float _sendBps;
    private volatile float _recvBps;
    private volatile float _sendBps15s;
    private volatile float _recvBps15s;

    /**
     * Current time in milliseconds from the System clock.
     */
    public /* static */ long now() {
        // Don't use the clock().now(), since that may jump
        return System.currentTimeMillis();
    }

    /**
     * FIFOBandwidthLimiter.
     */
    public FIFOBandwidthLimiter(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(FIFOBandwidthLimiter.class);
        _context.statManager().createRateStat("bwLimiter.pendingOutboundRequests", "Outbound non-zero length requests ahead of current", "BandwidthLimiter", RateConstants.BANDWIDTH_RATES);
        _context.statManager().createRateStat("bwLimiter.pendingInboundRequests", "Inbound non-zero length requests ahead of current", "BandwidthLimiter", RateConstants.BANDWIDTH_RATES);
        _context.statManager().createRateStat("bwLimiter.outboundDelayedTime", "Time to honor non-zero length outbound request (ms)", "BandwidthLimiter", RateConstants.BANDWIDTH_RATES);
        _context.statManager().createRateStat("bwLimiter.inboundDelayedTime", "Time to honor non-zero length inbound request (ms)", "BandwidthLimiter", RateConstants.BANDWIDTH_RATES);
        _pendingInboundRequests = new ArrayList<>(16);
        _pendingOutboundRequests = new ArrayList<>(16);
        _lastTotalSent = _totalAllocatedOutboundBytes.get();
        _lastTotalReceived = _totalAllocatedInboundBytes.get();
        _lastStatsUpdated = now();
        _refiller = new FIFOBandwidthRefiller(_context, this);
        _refillerThread = new I2PThread(_refiller, "BWRefiller", true);
        _refillerThread.setPriority(Thread.MAX_PRIORITY);
        _refillerThread.start();
    }

    /**
     * Total bytes allocated for inbound messages since start.
     * @return the total allocated inbound bytes
     */
    public long getTotalAllocatedInboundBytes() { return _totalAllocatedInboundBytes.get(); }
    /**
     * Total bytes allocated for outbound messages since start.
     * @return the total allocated outbound bytes
     */
    public long getTotalAllocatedOutboundBytes() { return _totalAllocatedOutboundBytes.get(); }
    /**
     *  Smoothed one-second send rate.
     *
     *  @return smoothed one second rate
     */
    public float getSendBps() { return _sendBps; }

    /**
     *  Smoothed one-second receive rate.
     *
     *  @return smoothed one second rate
     */
    public float getReceiveBps() { return _recvBps; }

    /**
     *  Smoothed 15-second send rate.
     *
     *  @return smoothed 15 second rate
     */
    public float getSendBps15s() { return _sendBps15s; }

    /**
     *  Smoothed 15-second receive rate.
     *
     *  @return smoothed 15 second rate
     */
    public float getReceiveBps15s() { return _recvBps15s; }

    /**
     *  The configured maximum, not the current rate.
     *  In binary K, i.e. rate / 1024.
     * @return the outbound k bytes per second
     */
    public int getOutboundKBytesPerSecond() { return _refiller.getOutboundKBytesPerSecond(); }

    /**
     *  The configured maximum, not the current rate.
     *  In binary K, i.e. rate / 1024.
     * @return the inbound k bytes per second
     */
    public int getInboundKBytesPerSecond() { return _refiller.getInboundKBytesPerSecond(); }

    /**
     *  The configured maximum, not the current rate.
     *  In binary K, i.e. rate / 1024.
     * @return the outbound burst k bytes per second
     */
    public int getOutboundBurstKBytesPerSecond() { return _refiller.getOutboundBurstKBytesPerSecond(); }

    /**
     *  The configured maximum, not the current rate.
     *  In binary K, i.e. rate / 1024.
     * @return the inbound burst k bytes per second
     */
    public int getInboundBurstKBytesPerSecond() { return _refiller.getInboundBurstKBytesPerSecond(); }

    /**
     * Clear and reinitialize the refiller and queues.
     */
    public synchronized void reinitialize() {
        clear();
        _refiller.reinitialize();
    }

    /** Shut down the bandwidth limiter */
    public synchronized void shutdown() {
        _refiller.shutdown();
        _refillerThread.interrupt();
        clear();
    }

    /** Clear all pending requests and reset counters */
    private void clear() {
        _pendingInboundRequests.clear();
        _pendingOutboundRequests.clear();
        _availableInbound.set(0);
        _availableOutbound.set(0);
        _maxInbound = 0;
        _maxOutbound = 0;
        _maxInboundBurst = 0;
        _maxOutboundBurst = 0;
        _unavailableInboundBurst.set(0);
        _unavailableOutboundBurst.set(0);

    }

    /**
     *  We intend to send traffic for a participating tunnel
     *  with the given size and adjustment factor.
     *  Returns true if the message can be sent within the current
     *  share bandwidth limits, or false if it should be dropped.
     *
     * @param size bytes
     * @param factor multiplier of size for the drop calculation, 1 for no adjustment
     * @return true for accepted, false for drop
     * @since 0.8.12
     */
    public boolean sentParticipatingMessage(int size, float factor) {
        return _refiller.incrementParticipatingMessageBytes(size, factor);
    }

    /**
     *  Check if we should accept an inbound participating message.
     *
     *  @param size bytes
     *  @param factor multiplier of size for the drop calculation, 1 for no adjustment
     *  @return true for accepted, false for drop
     */
    public boolean receivedParticipatingMessage(int size, float factor) {
        return _refiller.incrementParticipatingMessageBytesIn(size, factor);
    }

    /**
     *  Out bandwidth. Actual bandwidth, not smoothed, not bucketed.
     *
     *  @return Bps in recent period (a few seconds)
     *  @since 0.8.12
     */
    public int getCurrentParticipatingBandwidth() {
        return _refiller.getCurrentParticipatingBandwidth();
    }

    /**
     *  In bandwidth. Actual bandwidth, not smoothed, not bucketed.
     *
     *  @return Bps in recent period (a few seconds)
     */
    public int getCurrentParticipatingBandwidthIn() {
        return _refiller.getCurrentParticipatingBandwidthIn();
    }

    /**
     *  In Bytes per second
     * @return the max share bandwidth
     * @since 0.9.68
     */
    public int getMaxShareBandwidth() {
        return _refiller.getMaxShareBandwidth();
    }

    /**
     * Request some bytes. Does not block.
     */
    public Request requestInbound(int bytesIn, String purpose) {
        // try to satisfy without grabbing the global lock
        if (shortcutSatisfyInboundRequest(bytesIn))
            return _noop;
        SimpleRequest req = new SimpleRequest(bytesIn, 0);
        requestInbound(req, bytesIn, purpose);
        return req;
    }

    /**
     * The transports don't use this any more, so make it private
     * and a SimpleRequest instead of a Request
     * So there's no more casting
     */
    private void requestInbound(SimpleRequest req, int bytesIn, String purpose) {
        int pending;
        synchronized (_pendingInboundRequests) {
            pending = _pendingInboundRequests.size();
            _pendingInboundRequests.add(req);
        }
        satisfyInboundRequests(req.satisfiedBuffer);
        req.satisfiedBuffer.clear();
        if (pending > 0)
            _context.statManager().addRateData("bwLimiter.pendingInboundRequests", pending);
    }

    /**
     * Request some bytes. Does not block.
     */
    public Request requestOutbound(int bytesOut, int priority, String purpose) {
        // try to satisfy without grabbing the global lock
        if (shortcutSatisfyOutboundRequest(bytesOut))
            return _noop;
        SimpleRequest req = new SimpleRequest(bytesOut, priority);
        requestOutbound(req, bytesOut, purpose);
        return req;
    }

    private void requestOutbound(SimpleRequest req, int bytesOut, String purpose) {
        int pending;
        synchronized (_pendingOutboundRequests) {
            pending = _pendingOutboundRequests.size();
            _pendingOutboundRequests.add(req);
        }
        satisfyOutboundRequests(req.satisfiedBuffer);
        req.satisfiedBuffer.clear();
        if (pending > 0)
            _context.statManager().addRateData("bwLimiter.pendingOutboundRequests", pending);
    }

    /** Inbound burst rate in KBps. */
    void setInboundBurstKBps(int kbytesPerSecond) {
        _maxInbound = kbytesPerSecond * 1024;
    }
    /** Outbound burst rate in KBps. */
    void setOutboundBurstKBps(int kbytesPerSecond) {
        _maxOutbound = kbytesPerSecond * 1024;
    }
    /**
     * The max inbound burst, in bytes.
     * @return the inbound burst bytes
     */
    public int getInboundBurstBytes() { return _maxInboundBurst; }
    /**
     * The max outbound burst, in bytes.
     * @return the outbound burst bytes
     */
    public int getOutboundBurstBytes() { return _maxOutboundBurst; }
    /** Inbound burst maximum, in bytes. */
    void setInboundBurstBytes(int bytes) { _maxInboundBurst = bytes; }
    /** Outbound burst maximum, in bytes. */
    void setOutboundBurstBytes(int bytes) { _maxOutboundBurst = bytes; }

    /** The current status string. */
    StringBuilder getStatus() {
        StringBuilder rv = new StringBuilder(128);
        rv.append("Available: ").append(_availableInbound).append('/').append(_availableOutbound).append("; ");
        rv.append(" Max: ").append(_maxInbound).append('/').append(_maxOutbound).append("; ");
        rv.append(" Burst: ").append(_unavailableInboundBurst).append('/').append(_unavailableOutboundBurst).append("; ");
        rv.append(" Burst max: ").append(_maxInboundBurst).append('/').append(_maxOutboundBurst).append("; ");
        return rv;
    }

    /**
     *  The inbound bandwidth status.
     * @return the inbound status
     * @since 0.9.53
     */
    private StringBuilder getInboundStatus() {
        StringBuilder rv = new StringBuilder(128);
        rv.append("Available: ").append(_availableInbound).append("; ");
        rv.append(" Max: ").append(_maxInbound).append("; ");
        rv.append(" Burst: ").append(_unavailableInboundBurst).append("; ");
        rv.append(" Burst max: ").append(_maxInboundBurst).append("; ");
        return rv;
    }

    /**
     *  The outbound bandwidth status.
     * @return the outbound status
     * @since 0.9.53
     */
    private StringBuilder getOutboundStatus() {
        StringBuilder rv = new StringBuilder(128);
        rv.append("Available: ").append(_availableOutbound).append("; ");
        rv.append(" Max: ").append(_maxOutbound).append("; ");
        rv.append(" Burst: ").append(_unavailableOutboundBurst).append("; ");
        rv.append(" Burst max: ").append(_maxOutboundBurst).append("; ");
        return rv;
    }

    /**
     * More bytes are available - add them to the queue and satisfy any requests
     * we can
     *
     * @param buf contains satisfied outbound requests, really just to avoid object thrash, not really used
     * @param maxBurstIn allow up to this many bytes in from the burst section for this time period (may be negative)
     * @param maxBurstOut allow up to this many bytes in from the burst section for this time period (may be negative)
     */
    final void refillBandwidthQueues(List<Request> buf, long bytesInbound, long bytesOutbound, long maxBurstIn, long maxBurstOut) {
        // Take some care throughout to minimize accesses to the atomics,
        // both for efficiency and to not let strange things happen if
        // it changes out from under us
        // This never had locks before concurrent, anyway

        // FIXME wrap - change to AtomicLong or detect
        int avi = _availableInbound.addAndGet((int) bytesInbound);
        if (avi > _maxInbound) {
            int uib = _unavailableInboundBurst.addAndGet(avi - _maxInbound);
            _availableInbound.set(_maxInbound);
            if (uib > _maxInboundBurst) {
                _unavailableInboundBurst.set(_maxInboundBurst);
            }
        } else {
            // try to pull in up to 1/10th of the burst rate, since we refill every 100ms
            int want = (int)maxBurstIn;
            if (want > (_maxInbound - avi))
                want = _maxInbound - avi;
            if (want > 0) {
                int uib = _unavailableInboundBurst.get();
                if (want <= uib) {
                    _availableInbound.addAndGet(want);
                    _unavailableInboundBurst.addAndGet(0 - want);
                } else {
                    _availableInbound.addAndGet(uib);
                    _unavailableInboundBurst.set(0);
                }
            }
        }

        int avo = _availableOutbound.addAndGet((int) bytesOutbound);
        if (avo > _maxOutbound) {
            int uob = _unavailableOutboundBurst.getAndAdd(avo - _maxOutbound);
            _availableOutbound.set(_maxOutbound);

            if (uob > _maxOutboundBurst) {
                _unavailableOutboundBurst.set(_maxOutboundBurst);
            }
        } else {
            // try to pull in up to the burst rate, since we refill periodically
            int want = (int)maxBurstOut;
            if (want > (_maxOutbound - avo))
                want = _maxOutbound - avo;
            if (want > 0) {
                int uob = _unavailableOutboundBurst.get();
                if (want <= uob) {
                    _availableOutbound.addAndGet(want);
                    _unavailableOutboundBurst.addAndGet(0 - want);
                } else {
                    _availableOutbound.addAndGet(uob);
                    _unavailableOutboundBurst.set(0);
                }
            }
        }

        satisfyRequests(buf);
        updateStats();
    }

    private void updateStats() {
        long now = now();
        long time = now - _lastStatsUpdated;
        // If at least one second has passed
        if (time >= 1000) {
            long totS = _totalAllocatedOutboundBytes.get();
            long totR = _totalAllocatedInboundBytes.get();
            long sent = totS - _lastTotalSent; // How much we sent meanwhile
            long recv = totR - _lastTotalReceived; // How much we received meanwhile
            _lastTotalSent = totS;
            _lastTotalReceived = totR;
            _lastStatsUpdated = now;

            if (_sendBps <= 0)
                _sendBps = (sent*1000f)/time;
            else
                _sendBps = (0.9f)*_sendBps + (0.1f)*(sent*1000f)/time;
            if (_recvBps <= 0)
                _recvBps = (recv*1000f)/time;
            else
                _recvBps = (0.9f)*_recvBps + (0.1f)*((float)recv*1000)/time;

            // Maintain an approximate average with a 15-second halflife
            // Weights (0.955 and 0.045) are tuned so that transition between two values (e.g. 0..10)
            // would reach their midpoint (e.g. 5) in 15s
                _sendBps15s = (0.955f)*_sendBps15s + (0.045f)*(sent*1000f)/time;

                _recvBps15s = (0.955f)*_recvBps15s + (0.045f)*((float)recv*1000)/time;
        }
    }

    /**
     * Go through the queue, satisfying as many requests as possible (notifying
     * each one satisfied that the request has been granted).
     *
     * @param buffer Out parameter, returned with the satisfied outbound requests only
     */
    private final void satisfyRequests(List<Request> buffer) {
        buffer.clear();
        satisfyInboundRequests(buffer);
        buffer.clear();
        satisfyOutboundRequests(buffer);
    }

    /**
     * Satisfy the pending inbound requests with currently available bandwidth.
     * @param satisfied Out parameter, returned with the satisfied requests added
     */
    private final void satisfyInboundRequests(List<Request> satisfied) {
        synchronized (_pendingInboundRequests) {
            if (_inboundUnlimited) {
                locked_satisfyInboundUnlimited(satisfied);
            } else {
                if (_availableInbound.get() > 0) {
                    locked_satisfyInboundAvailable(satisfied);
                } else {
                    // no bandwidth available
                    if (_log.shouldDebug())
                        _log.debug("Denying " + _pendingInboundRequests.size()
                                  + " pending inbound requests (no bandwidth available)\n* Status: " + getInboundStatus()
                                  + "Longest waited " + locked_getLongestInboundWait() + "ms");
                }
            }
        }

        if (satisfied != null) {
            for (int i = 0; i < satisfied.size(); i++) {
                SimpleRequest creq = (SimpleRequest)satisfied.get(i);
                creq.notifyAllocation();
            }
        }
    }

    /** Called from debug logging only. */
    private long locked_getLongestInboundWait() {
        long start = -1;
        for (int i = 0; i < _pendingInboundRequests.size(); i++) {
            Request req = _pendingInboundRequests.get(i);
            if ( (start < 0) || (start > req.getRequestTime()) )
                start = req.getRequestTime();
        }
        if (start == -1)
            return 0;
        else
            return now() - start;
    }

    /** Called from debug logging only. */
    private long locked_getLongestOutboundWait() {
        long start = -1;
        for (int i = 0; i < _pendingOutboundRequests.size(); i++) {
            Request req = _pendingOutboundRequests.get(i);
            if (req == null) continue;
            if ( (start < 0) || (start > req.getRequestTime()) )
                start = req.getRequestTime();
        }
        if (start == -1)
            return 0;
        else
            return now() - start;
    }

    /**
     * There are no limits, so just give every inbound request whatever they want
     *
     * @param satisfied out param, list of requests that were completely satisfied
     */
    private final void locked_satisfyInboundUnlimited(List<Request> satisfied) {
        while (!_pendingInboundRequests.isEmpty()) {
            SimpleRequest req = _pendingInboundRequests.remove(0);
            int allocated = req.getPendingRequested();
            _totalAllocatedInboundBytes.addAndGet(allocated);
            req.allocateBytes(allocated);
            satisfied.add(req);
            long waited = now() - req.getRequestTime();
            if (_log.shouldDebug())
                 _log.debug("Granting inbound request " + req + " fully (waited "
                            + waited
                            + "ms) pending " + _pendingInboundRequests.size());
            if (waited > 10)
                _context.statManager().addRateData("bwLimiter.inboundDelayedTime", waited);
        }
    }

    /**
     * We have limits, so iterate through the requests, allocating as much
     * bandwidth as we can to those who have used what we have given them and are waiting
     * for more (giving priority to the first ones who requested it)
     *
     * @param satisfied out param, list of requests that were completely satisfied
     */
    private final void locked_satisfyInboundAvailable(List<Request> satisfied) {
        for (int i = 0; i < _pendingInboundRequests.size(); i++) {
            SimpleRequest req = _pendingInboundRequests.get(i);
            long waited = now() - req.getRequestTime();
            if (req.getAborted()) {
                // connection decided they don't want the data anymore
                if (_log.shouldDebug())
                     _log.debug("Aborting inbound request to "
                                + req
                                + " waited "
                                + waited
                                + "ms) pending " + _pendingInboundRequests.size());
                _pendingInboundRequests.remove(i);
                i--;
                continue;
            }
            int avi = _availableInbound.get();
            if (avi <= 0) break;
            // NO, don't do this, since SSU requires a full allocation to proceed.
            // By stopping after a partial allocation, we stall SSU.
            // ok, they are really waiting for us to give them stuff
            int requested = req.getPendingRequested();
            int allocated;
            if (avi >= requested)
                allocated = requested;
            else
                allocated = avi;
            _availableInbound.addAndGet(0 - allocated);
            _totalAllocatedInboundBytes.addAndGet(allocated);
            req.allocateBytes(allocated);
            satisfied.add(req);
            if (req.getPendingRequested() > 0) {
                if (_log.shouldDebug())
                     _log.debug("Allocating " + allocated + " bytes inbound as a partial grant to "
                                + req
                                + " waited "
                                + waited
                                + "ms) pending " + _pendingInboundRequests.size()
                                + ", longest waited " + locked_getLongestInboundWait() + " in");
            } else {
                if (_log.shouldDebug())
                     _log.debug("Allocating " + allocated + " bytes inbound to finish the partial grant to "
                                + req
                                + " waited "
                                + waited
                                + "ms) pending " + _pendingInboundRequests.size()
                                + ", longest waited " + locked_getLongestInboundWait() + " out");
                _pendingInboundRequests.remove(i);
                i--;
                if (waited > 10)
                    _context.statManager().addRateData("bwLimiter.inboundDelayedTime", waited);
            }
        }
    }

    /**
     * Satisfy the pending outbound requests with currently available bandwidth.
     * @param satisfied Out parameter, returned with the satisfied requests added
     */
    private final void satisfyOutboundRequests(List<Request> satisfied) {
        synchronized (_pendingOutboundRequests) {
            if (_outboundUnlimited) {
                locked_satisfyOutboundUnlimited(satisfied);
            } else {
                if (_availableOutbound.get() > 0) {
                    locked_satisfyOutboundAvailable(satisfied);
                } else {
                    // no bandwidth available
                    if (_log.shouldDebug())
                        _log.debug("Denying " + _pendingOutboundRequests.size()
                                  + " pending outbound requests (no bandwidth available)\n* Status: " + getOutboundStatus()
                                  + "Longest waited " + locked_getLongestOutboundWait() + "ms");
                }
            }
        }

        if (satisfied != null) {
            for (int i = 0; i < satisfied.size(); i++) {
                SimpleRequest creq = (SimpleRequest)satisfied.get(i);
                creq.notifyAllocation();
            }
        }
    }

    /**
     * There are no limits, so just give every outbound request whatever they want
     *
     * @param satisfied out param, list of requests that were completely satisfied
     */
    private final void locked_satisfyOutboundUnlimited(List<Request> satisfied) {
        while (!_pendingOutboundRequests.isEmpty()) {
            SimpleRequest req = _pendingOutboundRequests.remove(0);
            int allocated = req.getPendingRequested();
            _totalAllocatedOutboundBytes.addAndGet(allocated);
            req.allocateBytes(allocated);
            satisfied.add(req);
            long waited = now() - req.getRequestTime();
            if (_log.shouldDebug())
                 _log.debug("Granting outbound request " + req + " fully (waited "
                            + waited
                            + "ms) pending " + _pendingOutboundRequests.size()
                            + ", longest waited " + locked_getLongestOutboundWait() + " out");
            if (waited > 10)
                _context.statManager().addRateData("bwLimiter.outboundDelayedTime", waited);
        }
    }

    /**
     * We have limits, so iterate through the requests, allocating as much
     * bandwidth as we can to those who have used what we have given them and are waiting
     * for more (giving priority to the first ones who requested it)
     *
     * @param satisfied out param, list of requests that were completely satisfied
     */
    private final void locked_satisfyOutboundAvailable(List<Request> satisfied) {
        for (int i = 0; i < _pendingOutboundRequests.size(); i++) {
            SimpleRequest req = _pendingOutboundRequests.get(i);
            long waited = now() - req.getRequestTime();
            if (req.getAborted()) {
                // connection decided they don't want the data anymore
                if (_log.shouldDebug())
                     _log.debug("Aborting outbound request to "
                                + req
                                + " waited "
                                + waited
                                + "ms) pending " + _pendingOutboundRequests.size());
                _pendingOutboundRequests.remove(i);
                i--;
                continue;
            }
            int avo = _availableOutbound.get();
            if (avo <= 0) break;
            // NO, don't do this, since SSU requires a full allocation to proceed.
            // By stopping after a partial allocation, we stall SSU.
            // ok, they are really waiting for us to give them stuff
            int requested = req.getPendingRequested();
            int allocated;
            if (avo >= requested)
                allocated = requested;
            else
                allocated = avo;
            _availableOutbound.addAndGet(0 - allocated);
            _totalAllocatedOutboundBytes.addAndGet(allocated);
            req.allocateBytes(allocated);
            satisfied.add(req);
            if (req.getPendingRequested() > 0) {
                if (_log.shouldDebug())
                     _log.debug("Allocating " + allocated + " bytes outbound as a partial grant to "
                                + req
                                + " waited "
                                + waited
                                + "ms) pending " + _pendingOutboundRequests.size()
                                + ", longest waited " + locked_getLongestOutboundWait() + " out");
            } else {
                if (_log.shouldDebug())
                     _log.debug("Allocating " + allocated + " bytes outbound to finish the partial grant to "
                                + req
                                + " waited "
                                + waited
                                + "ms) pending " + _pendingOutboundRequests.size()
                                + ", longest waited " + locked_getLongestOutboundWait() + " out)");
                _pendingOutboundRequests.remove(i);
                i--;
                if (waited > 10)
                    _context.statManager().addRateData("bwLimiter.outboundDelayedTime", waited);
            }
        }
    }

    /**
     *  Lockless total satisfaction,
     *  at some minor risk of exceeding the limits
     *  and driving the available counter below zero
     *
     *  @param requested number of bytes
     *  @return satisfaction
     *  @since 0.7.13
     */
    private boolean shortcutSatisfyInboundRequest(int requested) {
        boolean rv = _inboundUnlimited ||
                     (_pendingInboundRequests.isEmpty() &&
                      _availableInbound.get() >= requested);
        if (rv) {
            _availableInbound.addAndGet(0 - requested);
            _totalAllocatedInboundBytes.addAndGet(requested);
        }
        return rv;
    }

    /**
     *  Lockless total satisfaction,
     *  at some minor risk of exceeding the limits
     *  and driving the available counter below zero
     *
     *  @param requested number of bytes
     *  @return satisfaction
     *  @since 0.7.13
     */
    private boolean shortcutSatisfyOutboundRequest(int requested) {
        boolean rv = _outboundUnlimited ||
                     (_pendingOutboundRequests.isEmpty() &&
                      _availableOutbound.get() >= requested);
        if (rv) {
            _availableOutbound.addAndGet(0 - requested);
            _totalAllocatedOutboundBytes.addAndGet(requested);
        }
        return rv;
    }

    /** @deprecated not worth translating */
    @Deprecated
    public void renderStatusHTML(Writer out) throws IOException {
        // no-op
    }

    private static class SimpleRequest implements Request {
        private int _allocated;
        private final int _total;
        private final long _requestId;
        private final long _requestTime;
        private int _allocationsSinceWait;
        private volatile boolean _aborted;
        private boolean _waited;
        final List<Request> satisfiedBuffer;
        private CompleteListener _lsnr;
        private Object _attachment;
        private final int _priority;

        /**
         *  Allocation request for the given byte count.
         *
         *  @param priority 0 for now
         */
        public SimpleRequest(int bytes, int priority) {
            satisfiedBuffer = new ArrayList<>(1);
            _total = bytes;
            _priority = priority;
            // following two are temp until switch to PBQ
            _requestTime = System.currentTimeMillis();
            _requestId = __requestId.incrementAndGet();
        }

        /** Uses System clock, not context clock. */
        public long getRequestTime() { return _requestTime; }
        /**
         * The total number of bytes requested.
         * @return the total requested
         */
        public int getTotalRequested() { return _total; }
        /**
         * The number of requested bytes not yet allocated.
         * @return the pending requested
         */
        public synchronized int getPendingRequested() { return _total - _allocated; }
        /**
         * Whether this request has been aborted.
         * @return the aborted
         */
        public boolean getAborted() { return _aborted; }
        /**
         * Abort the request; the connection no longer wants the data.
         */
        public synchronized void abort() {
            _aborted = true;
            // so isComplete() will return true
            _allocated = _total;
            notifyAllocation();
        }
        /**
         * The listener notified when the request completes.
         * @return the complete listener
         */
        public synchronized CompleteListener getCompleteListener() { return _lsnr; }

        /**
         *  Only used by NTCP.
         */
        public void setCompleteListener(CompleteListener lsnr) {
            boolean complete = false;
            synchronized (this) {
                _lsnr = lsnr;
                if (isComplete()) {
                    complete = true;
                }
            }
            if (complete && lsnr != null) {
                lsnr.complete(this);
            }
        }

        private synchronized boolean isComplete() { return _allocated >= _total; }

        /**
         *  Only used by SSU.
         *  May return without allocating.
         *  Check getPendingRequested() &gt; 0 in a loop.
         */
        public void waitForNextAllocation() {
            boolean complete = false;
            try {
                synchronized (this) {
                    _waited = true;
                    _allocationsSinceWait = 0;
                    if (isComplete())
                        complete = true;
                    else
                        wait(100);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            if (complete && _lsnr != null)
                _lsnr.complete(this);
        }

        /**
         *  Only returns nonzero if there's no listener and waitForNextAllocation()
         *  has been called (i.e. SSU)
         *  Now unused.
         */
        synchronized int getAllocationsSinceWait() { return _waited ? _allocationsSinceWait : 0; }

        /**
         *  Increments allocationsSinceWait only if there is a listener.
         *  Does not notify; caller must call notifyAllocation()
         */
        synchronized void allocateBytes(int bytes) {
            _allocated += bytes;
            if (_lsnr == null)
                _allocationsSinceWait++;
        }

        void notifyAllocation() {
            boolean complete = false;
            synchronized (this) {
                if (isComplete())
                    complete = true;
                notifyAll();
            }
            if (complete && _lsnr != null) {
                _lsnr.complete(this);

            }
        }

        /**
         * Attach an arbitrary object to this request.
         */
        public void attach(Object obj) { _attachment = obj; }
        /**
         * The attached object, or null.
         */
        public Object attachment() { return _attachment; }

        // PQEntry methods
        /**
         * The request priority.
         * @return the priority
         */
        public int getPriority() { return _priority; }
        // uncomment for switch to PBQ
        /**
         * Sequence number assigned to this request.
         */
        public void setSeqNum(long num) { /** _requestId = num; */ }
        public long getSeqNum() { return _requestId; }

        /**
         * The string representation of the request.
         */
        @Override
        public String toString() {
            return "Req: " + _requestId + " priority: " + _priority +
                   ' ' + _allocated + '/' + _total + " bytes";
        }
    }

    /**
     *  A bandwidth request, either inbound or outbound.
     */
    public interface Request extends PQEntry {
        /** When the request was made. */
        public long getRequestTime();
        /** How many bytes were requested. */
        public int getTotalRequested();
        /** How many bytes were requested and haven't yet been allocated. */
        public int getPendingRequested();
        /**
         *  Block until we are allocated some more bytes.
         *  May return without allocating.
         *  Check getPendingRequested() &gt; 0 in a loop.
         */
        public void waitForNextAllocation();
        /** We no longer want the data requested (the connection closed). */
        public void abort();
        /** Whether this request was aborted. */
        public boolean getAborted();
        /**
         * The listener notified when the request is complete.
         */
        public void setCompleteListener(CompleteListener lsnr);
        /** Only supported if the request is not satisfied. */
        public void attach(Object obj);
        /**
         * The attached object, or null.
         */
        public Object attachment();
        /**
         * The listener notified when the request completes.
         * @return the complete listener
         */
        public CompleteListener getCompleteListener();
    }

    /**
     * Listener for bandwidth request completion events.
     */
    public interface CompleteListener {
        /**
         * Notify the listener that the request completed.
         */
        public void complete(Request req);
    }

    private static final NoopRequest _noop = new NoopRequest();

    private static class NoopRequest implements Request {
        /**
         * Abort the request; no-op.
         */
        public void abort() {
            // No-op - intentionally empty
        }
        /**
         * Whether this request was aborted; always false.
         * @return the aborted
         */
        public boolean getAborted() { return false; }
        /**
         * The pending requested bytes; always 0.
         * @return the pending requested
         */
        public int getPendingRequested() { return 0; }
        /**
         * The string representation; "noop".
         */
        @Override
        public String toString() { return "noop"; }
        /**
         * The request time; always 0.
         * @return the request time
         */
        public long getRequestTime() { return 0; }
        /**
         * The total requested bytes; always 0.
         * @return the total requested
         */
        public int getTotalRequested() { return 0; }
        /**
         * Wait for the next allocation; no-op.
         */
        public void waitForNextAllocation() {
            // No-op - intentionally empty
        }
        /**
         * The complete listener; always null.
         * @return the complete listener
         */
        public CompleteListener getCompleteListener() { return null; }
        /**
         * Immediately notify the listener that the request completed.
         */
        public void setCompleteListener(CompleteListener lsnr) {
            lsnr.complete(NoopRequest.this);
        }
        /**
         * Throw, since a satisfied request cannot be attached to.
         */
        public void attach(Object obj) {
            throw new UnsupportedOperationException("Don't attach to a satisfied request");
        }
        /**
         * The attached object; always null.
         */
        public Object attachment() { return null; }
        // PQEntry methods
        /**
         * The request priority; always 0.
         * @return the priority
         */
        public int getPriority() { return 0; }
        /**
         * Sequence number assigned to this request; no-op.
         */
        public void setSeqNum(long num) {
            // No-op - intentionally empty
        }
        /**
         * The sequence number; always 0.
         * @return the seq num
         */
        public long getSeqNum() { return 0; }
    }
}
