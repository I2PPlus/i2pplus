package net.i2p.router.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.FIFOBandwidthLimiter.Request;
import net.i2p.util.Log;

/**
 *  Thread that runs several times a second to "give" bandwidth to
 *  FIFOBandwidthLimiter.
 *  Instantiated by FIFOBandwidthLimiter.
 *
 *  As of 0.8.12, this also contains a counter for outbound participating bandwidth.
 *  This was a good place for it since we needed a thread for it.
 *
 *  Public only for the properties and defaults.
 */
public class FIFOBandwidthRefiller implements Runnable {
    private final Log _log;
    private final RouterContext _context;
    private final FIFOBandwidthLimiter _limiter;
    // This is only changed if the config changes
    private volatile SyntheticREDQueue _partBWE;

    /** how many KBps do we want to allow? */
    private int _inboundKBytesPerSecond;
    /** how many KBps do we want to allow? */
    private int _outboundKBytesPerSecond;
    /** how many KBps do we want to allow during burst? */
    private int _inboundBurstKBytesPerSecond;
    /** how many KBps do we want to allow during burst? */
    private int _outboundBurstKBytesPerSecond;
    /** when did we last replenish the queue? */
    private long _lastRefillTime;
    /** when did we last check the config for updates? */
    private long _lastCheckConfigTime;
    /** how frequently do we check the config for updates? */
    private long _configCheckPeriodMs = 60*1000;
    private volatile boolean _isRunning;

    public static final String PROP_INBOUND_BANDWIDTH = "i2np.bandwidth.inboundKBytesPerSecond";
    public static final String PROP_OUTBOUND_BANDWIDTH = "i2np.bandwidth.outboundKBytesPerSecond";
    public static final String PROP_INBOUND_BURST_BANDWIDTH = "i2np.bandwidth.inboundBurstKBytesPerSecond";
    public static final String PROP_OUTBOUND_BURST_BANDWIDTH = "i2np.bandwidth.outboundBurstKBytesPerSecond";
    public static final String PROP_INBOUND_BANDWIDTH_PEAK = "i2np.bandwidth.inboundBurstKBytes";
    public static final String PROP_OUTBOUND_BANDWIDTH_PEAK = "i2np.bandwidth.outboundBurstKBytes";
    //public static final String PROP_REPLENISH_FREQUENCY = "i2np.bandwidth.replenishFrequencyMs";

    // no longer allow unlimited bandwidth - the user must specify a value, else use defaults below (KBps)
//    public static final int DEFAULT_INBOUND_BANDWIDTH = 300;
    public static final int DEFAULT_INBOUND_BANDWIDTH = 1000;
    /**
     *  Caution, do not make DEFAULT_OUTBOUND_BANDWIDTH * DEFAULT_SHARE_PCT > 32
     *  without thinking about the implications (default connection limits, for example)
     *  of moving the default bandwidth class from L to M, or maybe
     *  adjusting bandwidth class boundaries.
     */
    public static final int DEFAULT_OUTBOUND_BANDWIDTH = 60;
//    public static final int DEFAULT_INBOUND_BURST_BANDWIDTH = 300;
    public static final int DEFAULT_INBOUND_BURST_BANDWIDTH = 1000;
    public static final int DEFAULT_OUTBOUND_BURST_BANDWIDTH = 60;

    public static final int DEFAULT_BURST_SECONDS = 60;

    /** For now, until there is some tuning and safe throttling, we set the floor at this inbound (KBps) */
    public static final int MIN_INBOUND_BANDWIDTH = 5;
    /** For now, until there is some tuning and safe throttling, we set the floor at this outbound (KBps) */
    public static final int MIN_OUTBOUND_BANDWIDTH = 5;
    /** For now, until there is some tuning and safe throttling, we set the floor at this during burst (KBps) */
    public static final int MIN_INBOUND_BANDWIDTH_PEAK = 5;
    /** For now, until there is some tuning and safe throttling, we set the floor at this during burst (KBps) */
    public static final int MIN_OUTBOUND_BANDWIDTH_PEAK = 5;
    /**
     *  Max for reasonable Bloom filter false positive rate.
     *  Do not increase without adding a new Bloom filter size!
     *  See util/DecayingBloomFilter and tunnel/BloomFilterIVValidator.
     */
    public static final int MAX_OUTBOUND_BANDWIDTH = 16384;

    private static final float MAX_SHARE_PERCENTAGE = 0.90f;
    private static final float SHARE_LIMIT_FACTOR = 0.95f;

    /**
     * how often we replenish the queues.
     * the bandwidth limiter will get an update this often (ms)
     */
    private static final long REPLENISH_FREQUENCY = 40;

    FIFOBandwidthRefiller(RouterContext context, FIFOBandwidthLimiter limiter) {
        _limiter = limiter;
        _context = context;
        _log = context.logManager().getLog(FIFOBandwidthRefiller.class);
        _context.statManager().createRateStat("bwLimiter.participatingBandwidthQueue", "Participating tunnel queue (bytes)", "BandwidthLimiter", new long[] { 60*1000l, 5*60*1000l, 60*60*1000l });
        reinitialize();
        _isRunning = true;
    }

    /** @since 0.8.8 */
    synchronized void shutdown() {
        _isRunning = false;
    }

    public void run() {
        // bootstrap 'em with nothing
        _lastRefillTime = _limiter.now();
        List<FIFOBandwidthLimiter.Request> buffer = new ArrayList<Request>(2);
        byte i = 0;
        while (_isRunning) {
            long now = _limiter.now();
            if (now >= _lastCheckConfigTime + _configCheckPeriodMs) {
                checkConfig();
                now = _limiter.now();
                _lastCheckConfigTime = now;
            }
            // just for the stats
            if ((++i) == 0)
                updateParticipating(now);

            boolean updated = updateQueues(buffer, now);
            if (updated) {
                _lastRefillTime = now;
            }

            try { Thread.sleep(REPLENISH_FREQUENCY); } catch (InterruptedException ie) {}
        }
    }

    synchronized void reinitialize() {
        _lastRefillTime = _limiter.now();
        checkConfig();
        _lastCheckConfigTime = _lastRefillTime;
    }

    private boolean updateQueues(List<FIFOBandwidthLimiter.Request> buffer, long now) {
        long numMs = (now - _lastRefillTime);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Updating bandwidth after " + numMs + "ms"
                       + "\n* Status: " + _limiter.getStatus().toString()
                       + " Rate in: " + _inboundKBytesPerSecond + "KB/s;"
                       + " Rate out: " + _outboundKBytesPerSecond + "KB/s");
        // clock skew
        if (numMs >= REPLENISH_FREQUENCY * 50 || numMs <= 0)
            numMs = REPLENISH_FREQUENCY;
        if (numMs >= REPLENISH_FREQUENCY) {
            long inboundToAdd = (1024*_inboundKBytesPerSecond * numMs)/1000;
            long outboundToAdd = (1024*_outboundKBytesPerSecond * numMs)/1000;

            if (inboundToAdd < 0) inboundToAdd = 0;
            if (outboundToAdd < 0) outboundToAdd = 0;

         /**** Always limited for now
            if (_inboundKBytesPerSecond <= 0) {
                _limiter.setInboundUnlimited(true);
                inboundToAdd = 0;
            } else {
                _limiter.setInboundUnlimited(false);
            }
            if (_outboundKBytesPerSecond <= 0) {
                _limiter.setOutboundUnlimited(true);
                outboundToAdd = 0;
            } else {
                _limiter.setOutboundUnlimited(false);
            }
         ****/

            long maxBurstIn = ((_inboundBurstKBytesPerSecond-_inboundKBytesPerSecond)*1024*numMs)/1000;
            long maxBurstOut = ((_outboundBurstKBytesPerSecond-_outboundKBytesPerSecond)*1024*numMs)/1000;
            _limiter.refillBandwidthQueues(buffer, inboundToAdd, outboundToAdd, maxBurstIn, maxBurstOut);

            //if (_log.shouldLog(Log.DEBUG)) {
            //    _log.debug("Adding " + inboundToAdd + " bytes to inboundAvailable");
            //    _log.debug("Adding " + outboundToAdd + " bytes to outboundAvailable");
            //}
            return true;
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Refresh delay too fast (" + numMs + "ms)");
            return false;
        }
    }

    /**
     *  In Bytes per second
     */
    private int getShareBandwidth() {
        int maxKBps = Math.min(_inboundKBytesPerSecond, _outboundKBytesPerSecond);
        // limit to 90% so it doesn't clog up at the transport bandwidth limiter
        float share = Math.min((float) _context.router().getSharePercentage(), MAX_SHARE_PERCENTAGE);
        return (int) (maxKBps * share * 1024f * SHARE_LIMIT_FACTOR);
    }

    private void checkConfig() {
        updateInboundRate();
        updateOutboundRate();
        updateInboundBurstRate();
        updateOutboundBurstRate();
        updateInboundPeak();
        updateOutboundPeak();

        // if share bandwidth config changed, throw out the SyntheticREDQueue and make a new one
        int maxBps = getShareBandwidth();
        if (_partBWE == null || maxBps != _partBWE.getMaxBandwidth()) {
            _partBWE = new SyntheticREDQueue(_context, maxBps);
        }

        // We are always limited for now
        //_limiter.setInboundUnlimited(_inboundKBytesPerSecond <= 0);
        //_limiter.setOutboundUnlimited(_outboundKBytesPerSecond <= 0);
    }

    private void updateInboundRate() {
        int in = _context.getProperty(PROP_INBOUND_BANDWIDTH, DEFAULT_INBOUND_BANDWIDTH);
        if (in != _inboundKBytesPerSecond) {
            // bandwidth was specified *and* changed
                if ( (in <= 0) || (in > MIN_INBOUND_BANDWIDTH) )
                    _inboundKBytesPerSecond = in;
                else
                    _inboundKBytesPerSecond = MIN_INBOUND_BANDWIDTH;
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Updating Inbound rate to " + _inboundKBytesPerSecond + " KB/s");
        }

        if (_inboundKBytesPerSecond <= 0)
            _inboundKBytesPerSecond = DEFAULT_INBOUND_BANDWIDTH;
    }

    private void updateOutboundRate() {
        int out = _context.getProperty(PROP_OUTBOUND_BANDWIDTH, DEFAULT_OUTBOUND_BANDWIDTH);
        if (out != _outboundKBytesPerSecond) {
            // bandwidth was specified *and* changed
                if (out >= MAX_OUTBOUND_BANDWIDTH)
                    _outboundKBytesPerSecond = MAX_OUTBOUND_BANDWIDTH;
                else if ( (out <= 0) || (out >= MIN_OUTBOUND_BANDWIDTH) )
                    _outboundKBytesPerSecond = out;
                else
                    _outboundKBytesPerSecond = MIN_OUTBOUND_BANDWIDTH;
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Updating Outbound rate to " + _outboundKBytesPerSecond + " KB/s");
        }

        if (_outboundKBytesPerSecond <= 0)
            _outboundKBytesPerSecond = DEFAULT_OUTBOUND_BANDWIDTH;
    }

    private void updateInboundBurstRate() {
        int in = _context.getProperty(PROP_INBOUND_BURST_BANDWIDTH, DEFAULT_INBOUND_BURST_BANDWIDTH);
        if (in != _inboundBurstKBytesPerSecond) {
            // bandwidth was specified *and* changed
                if ( (in <= 0) || (in >= _inboundKBytesPerSecond) )
                    _inboundBurstKBytesPerSecond = in;
                else
                    _inboundBurstKBytesPerSecond = _inboundKBytesPerSecond;
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Updating Inbound burst rate to " + _inboundBurstKBytesPerSecond + " KB/s");
        }

        if (_inboundBurstKBytesPerSecond <= 0)
            _inboundBurstKBytesPerSecond = DEFAULT_INBOUND_BURST_BANDWIDTH;
        if (_inboundBurstKBytesPerSecond < _inboundKBytesPerSecond)
            _inboundBurstKBytesPerSecond = _inboundKBytesPerSecond;
        _limiter.setInboundBurstKBps(_inboundBurstKBytesPerSecond);
    }

    private void updateOutboundBurstRate() {
        int out = _context.getProperty(PROP_OUTBOUND_BURST_BANDWIDTH, DEFAULT_OUTBOUND_BURST_BANDWIDTH);
        if (out != _outboundBurstKBytesPerSecond) {
            // bandwidth was specified *and* changed
                if ( (out <= 0) || (out >= _outboundKBytesPerSecond) )
                    _outboundBurstKBytesPerSecond = out;
                else
                    _outboundBurstKBytesPerSecond = _outboundKBytesPerSecond;
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Updating Outbound burst rate to " + _outboundBurstKBytesPerSecond + " KB/s");
        }

        if (_outboundBurstKBytesPerSecond <= 0)
            _outboundBurstKBytesPerSecond = DEFAULT_OUTBOUND_BURST_BANDWIDTH;
        if (_outboundBurstKBytesPerSecond < _outboundKBytesPerSecond)
            _outboundBurstKBytesPerSecond = _outboundKBytesPerSecond;
        _limiter.setOutboundBurstKBps(_outboundBurstKBytesPerSecond);
    }

    private void updateInboundPeak() {
        int in = _context.getProperty(PROP_INBOUND_BANDWIDTH_PEAK,
                                      DEFAULT_BURST_SECONDS * _inboundBurstKBytesPerSecond);
        if (in != _limiter.getInboundBurstBytes()) {
            // peak bw was specified *and* changed
                if (in >= MIN_INBOUND_BANDWIDTH_PEAK) {
                    if (in < _inboundBurstKBytesPerSecond)
                        _limiter.setInboundBurstBytes(_inboundBurstKBytesPerSecond * 1024);
                    else
                        _limiter.setInboundBurstBytes(in * 1024);
                } else {
                    if (MIN_INBOUND_BANDWIDTH_PEAK < _inboundBurstKBytesPerSecond)
                        _limiter.setInboundBurstBytes(_inboundBurstKBytesPerSecond * 1024);
                    else
                        _limiter.setInboundBurstBytes(MIN_INBOUND_BANDWIDTH_PEAK * 1024);
                }
        }
    }
    private void updateOutboundPeak() {
        int in = _context.getProperty(PROP_OUTBOUND_BANDWIDTH_PEAK,
                                      DEFAULT_BURST_SECONDS * _outboundBurstKBytesPerSecond);
        if (in != _limiter.getOutboundBurstBytes()) {
            // peak bw was specified *and* changed
                if (in >= MIN_OUTBOUND_BANDWIDTH_PEAK) {
                    if (in < _outboundBurstKBytesPerSecond)
                        _limiter.setOutboundBurstBytes(_outboundBurstKBytesPerSecond * 1024);
                    else
                        _limiter.setOutboundBurstBytes(in * 1024);
                } else {
                    if (MIN_OUTBOUND_BANDWIDTH_PEAK < _outboundBurstKBytesPerSecond)
                        _limiter.setOutboundBurstBytes(_outboundBurstKBytesPerSecond * 1024);
                    else
                        _limiter.setOutboundBurstBytes(MIN_OUTBOUND_BANDWIDTH_PEAK * 1024);
                }
        }
    }

    int getOutboundKBytesPerSecond() { return _outboundKBytesPerSecond; }
    int getInboundKBytesPerSecond() { return _inboundKBytesPerSecond; }
    int getOutboundBurstKBytesPerSecond() { return _outboundBurstKBytesPerSecond; }
    int getInboundBurstKBytesPerSecond() { return _inboundBurstKBytesPerSecond; }

    /**
     *  We intend to send traffic for a participating tunnel
     *  with the given size and adjustment factor.
     *  Returns true if the message can be sent within the current
     *  share bandwidth limits, or false if it should be dropped.
     *
     *  @param size bytes
     *  @param factor multiplier of size for the drop calculation, 1 for no adjustment
     *  @return true for accepted, false for drop
     *  @since 0.8.12
     */
    boolean incrementParticipatingMessageBytes(int size, float factor) {
        return _partBWE.offer(size, factor);
    }

    /**
     *  Out bandwidth. Actual bandwidth, not smoothed, not bucketed.
     *
     *  @return Bps in recent period (a few seconds)
     *  @since 0.8.12
     */
    int getCurrentParticipatingBandwidth() {
        return (int) (_partBWE.getBandwidthEstimate() * 1000f);
    }

    /**
     *  Run once every replenish period
     *
     *  @since 0.8.12
     */
    private void updateParticipating(long now) {
            _context.statManager().addRateData("tunnel.participating OutBps", getCurrentParticipatingBandwidth());
            _context.statManager().addRateData("bwLimiter.participatingBandwidthQueue", (long) _partBWE.getQueueSizeEstimate());
    }
}
