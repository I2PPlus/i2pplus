package net.i2p.router.transport.crypto;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.I2PAppContext;
import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyFactory;
import net.i2p.crypto.KeyPair;
import net.i2p.stat.Rate;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 *  Try to keep DH pairs at the ready.
 *  It's important to do this in a separate thread, because if we run out,
 *  the pairs are generated in the NTCP Pumper thread,
 *  and it can fall behind.
 *
 *  <p>Pool sizes scale dynamically based on memory headroom, CPU pressure,
 *  and connection demand. The Tuner calls {@link #refreshPoolSize()} every
 *  30 seconds to adapt to changing conditions.</p>
 *
 *  @since 0.9.36 from DHSessionKeyFactory.PrecalcRunner
 */
public class X25519KeyFactory extends I2PThread implements KeyFactory {

    private static volatile X25519KeyFactory _lastInstance;
    private final I2PAppContext _context;
    private final Log _log;
    private volatile int _minSize;
    private volatile int _maxSize;
    private volatile int _calcDelay;
    private volatile int _targetMin;
    private volatile int _targetMax;
    private final LinkedBlockingQueue<KeyPair> _keys;
    private volatile boolean _isRunning;
    private long _checkDelay = 10 * 1000L;
    /** Empties observed since last refresh — drives pool growth */
    private final AtomicInteger _emptyCount = new AtomicInteger();
    private static final String PROP_DH_PRECALC_MIN = "crypto.xdh.precalc.min";
    private static final String PROP_DH_PRECALC_MAX = "crypto.xdh.precalc.max";
    private static final String PROP_DH_PRECALC_DELAY = "crypto.xdh.precalc.delay";
    private static final int DEFAULT_DH_PRECALC_MIN = 512;
    private static final int DEFAULT_DH_PRECALC_MAX = 4096;
    private static final int DEFAULT_DH_PRECALC_DELAY = 25;
    /** Absolute floor — never go below this even under memory pressure */
    private static final int HARD_MIN = 128;
    /** Absolute ceiling — never exceed this regardless of headroom */
    private static final int HARD_MAX = 65536;
    /** Each keypair is ~64 bytes; this is the memory budget per key in bytes */
    private static final int KEY_SIZE_BYTES = 64;

    public X25519KeyFactory(I2PAppContext ctx) {
        super("XDHPrecalc");
        _context = ctx;
        _log = ctx.logManager().getLog(X25519KeyFactory.class);
        ctx.statManager().createRequiredRateStat("crypto.XDHUsed", "Need a DH from the queue", "Encryption", new long[] { RateConstants.ONE_MINUTE });
        ctx.statManager().createRequiredRateStat("crypto.XDHEmpty", "DH queue empty", "Encryption", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        ctx.statManager().createRequiredRateStat("crypto.XDHDrain", "Idle keys drained from pool", "Encryption", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES });

        // Initial dynamic sizing
        computeTargetSizes();
        _minSize = _targetMin;
        _maxSize = _targetMax;
        _calcDelay = ctx.getProperty(PROP_DH_PRECALC_DELAY, DEFAULT_DH_PRECALC_DELAY);

        if (_log.shouldDebug()) {
            _log.debug("XDH Precalc (minimum: " + _minSize + " max: " + _maxSize + ", delay: " + _calcDelay + ")");
        }
        _keys = new LinkedBlockingQueue<>(HARD_MAX);
        if (!SystemVersion.isWindows()) {setPriority(Thread.NORM_PRIORITY - 1);}
        _lastInstance = this;
    }

    /**
     * Dynamically compute target pool sizes based on real-time system signals.
     *
     * <p>Signals used:
     * <ul>
     *   <li>Free memory headroom — more free memory = larger pool budget</li>
     *   <li>Memory pressure — high usage = shrink aggressively</li>
     *   <li>CPU load (job lag) — busy CPU = limit generation rate</li>
     *   <li>Empty queue events — zero empties = low demand shrinks pool</li>
     *   <li>Active peers — more connections = more key demand</li>
     * </ul>
     *
     * <p>Memory budget: up to 2% of free heap when active, 0.5% when idle.</p>
     *
     * @since 0.9.70+
     */
    void computeTargetSizes() {
        Runtime rt = Runtime.getRuntime();
        long maxMem = rt.maxMemory();
        long totalMem = rt.totalMemory();
        long freeMem = rt.freeMemory();
        double memPressure = (maxMem > 0) ? (double)(totalMem - freeMem) / maxMem : 0.5;
        int cores = SystemVersion.getCores();

        // CPU pressure from job queue lag (60s average)
        double jobLag = 0;
        RateStat lagStat = _context.statManager().getRate("jobQueue.jobLag");
        if (lagStat != null) {
            Rate rate = lagStat.getRate(60 * 1000L);
            if (rate != null && rate.getLastEventCount() > 0) {
                jobLag = rate.getAverageValue();
            }
        }
        boolean cpuBusy = jobLag > 50;

        // Demand signal: empties per minute (atomic read-and-reset)
        int recentEmpties = _emptyCount.getAndSet(0);
        boolean highDemand = recentEmpties > 10;
        boolean lowDemand = recentEmpties == 0;

        // === MINIMUM SIZE ===
        // Base: 256 × cores, scaled by demand
        int min = Math.max(HARD_MIN, 256 * cores);
        if (highDemand) { min *= 2; }
        if (lowDemand) { min = Math.max(HARD_MIN, min / 2); }
        if (cpuBusy) { min = Math.max(HARD_MIN, min / 2); }
        if (memPressure > 0.85) { min = Math.max(HARD_MIN, min / 2); }
        if (SystemVersion.isSlow()) { min = Math.max(HARD_MIN, min / 2); }

        // === MAXIMUM SIZE ===
        // Memory budget: up to 2% of free heap when active, scaled by demand
        long memBudgetBytes = (long)(freeMem * (lowDemand ? 0.005 : 0.02));
        int maxFromMemory = (int) Math.min(HARD_MAX, memBudgetBytes / KEY_SIZE_BYTES);

        // CPU scaling: more cores = can generate faster = sustain larger pool
        int cpuScale = Math.max(1, cores / 2);
        int max = maxFromMemory * cpuScale;

        // Demand scaling
        if (highDemand) { max = Math.min(HARD_MAX, max * 2); }
        if (cpuBusy) { max = Math.min(HARD_MAX, max * 3 / 4); }
        if (memPressure > 0.85) { max = Math.min(HARD_MAX, max / 2); }
        if (memPressure > 0.90) { max = Math.min(HARD_MAX, max / 2); }
        if (SystemVersion.isSlow()) { max = Math.min(HARD_MAX, max / 2); }

        // Ensure min ≤ max
        min = Math.min(min, max);
        min = Math.max(HARD_MIN, min);
        max = Math.max(min + 256, Math.min(HARD_MAX, max));

        // Smooth transitions: don't swing more than 2× per refresh
        int prevMin = _targetMin > 0 ? _targetMin : min;
        int prevMax = _targetMax > 0 ? _targetMax : max;
        _targetMin = clampSmooth(prevMin, min, 2);
        _targetMax = clampSmooth(prevMax, max, 2);
        _targetMin = Math.max(HARD_MIN, Math.min(_targetMax - 256, _targetMin));
        _targetMax = Math.max(_targetMin + 256, Math.min(HARD_MAX, _targetMax));
    }

    /**
     * Clamp a value to within a factor of the previous value.
     * Prevents wild swings between refresh cycles.
     */
    private static int clampSmooth(int prev, int target, int factor) {
        int lo = Math.max(HARD_MIN, prev / factor);
        int hi = Math.min(HARD_MAX, prev * factor);
        return Math.max(lo, Math.min(hi, target));
    }

    /**
     * Called by the Tuner every 30 seconds. Applies computed target sizes
     * and drains excess keys when the pool shrinks below the current queue.
     * The precalc thread uses _minSize/_maxSize to decide generation targets.
     *
     * @since 0.9.70+
     */
    public void refreshPoolSize() {
        computeTargetSizes();
        if (_targetMin != _minSize || _targetMax != _maxSize) {
            int oldMin = _minSize;
            int oldMax = _maxSize;
            _minSize = _targetMin;
            _maxSize = _targetMax;
            if (_log.shouldDebug()) {
                _log.debug("XDH Precalc resized min: " + oldMin + " → " + _minSize
                           + " max: " + oldMax + " → " + _maxSize);
            }
        }
        // Drain excess keys when cap is reduced below current queue
        int current = _keys.size();
        int target = _minSize + 256;
        if (current > target) {
            int drained = 0;
            while (_keys.size() > target) {
                KeyPair kp = _keys.poll();
                if (kp == null)
                    break;
                drained++;
            }
            if (drained > 0) {
                _context.statManager().addRateData("crypto.XDHDrain", drained);
                if (_log.shouldWarn()) {
                    _log.warn("XDH Precalc drained " + drained + " idle keys (" + current + " -> " + _keys.size() + ")");
                }
            }
        }
    }

    /**
     * Record an empty-queue event for demand tracking.
     * Called by {@link #getKeys()} when the pool is depleted.
     *
     * @since 0.9.70+
     */
    public void recordEmpty() { _emptyCount.incrementAndGet(); }

    /**
     * Returns the last created instance.
     * @since 0.9.70+
     */
    public static X25519KeyFactory getInstance() { return _lastInstance; }

    /**
     * Returns the current minimum precalc queue size.
     * @since 0.9.70+
     */
    public int getMinSize() { return _minSize; }

    /**
     * Sets the minimum precalc queue size.
     * @since 0.9.70+
     */
    public void setMinSize(int min) { _minSize = Math.max(HARD_MIN, min); }

    /**
     * Returns the current maximum precalc queue size.
     * @since 0.9.70+
     */
    public int getMaxSize() { return _maxSize; }

    /**
     * Sets the maximum precalc queue size.
     * @since 0.9.70+
     */
    public void setMaxSize(int max) { _maxSize = Math.max(_minSize, max); }

    /**
     * Returns the current number of precalc keys queued.
     * @since 0.9.70+
     */
    public int getSize() { return _keys.size(); }

    /**
     *  Note that this stops the singleton precalc thread.
     *  You don't want to do this if there are multiple routers in the JVM.
     *  Fix this if you care. See Router.shutdown().
     */
    public void shutdown() {
        _isRunning = false;
        this.interrupt();
        _keys.clear();
    }

    public void run() {
        try {run2();}
        catch (IllegalStateException ise) {
            if (_isRunning) {throw ise;}
            // else ignore, thread can be slow to shutdown on Android,
            // PRNG gets stopped first and throws ISE
        }
    }

    private void run2() {
        _isRunning = true;
        while (_isRunning) {
            int startSize = getSize();
            // Adjust delay
            if (startSize <= (_minSize * 2 / 3) && _checkDelay > 1000) {_checkDelay -= 1000;}
            else if (startSize > (_minSize * 3 / 2) && _checkDelay < 60*1000L) {_checkDelay += 1000;}
            if (startSize < _minSize) {
                // fill all the way up, do the check here so we don't
                while (getSize() < _maxSize && _isRunning) {
                    long curStart = System.currentTimeMillis();
                    if (!addKeys(precalc())) {break;}
                    long curCalc = System.currentTimeMillis() - curStart;
                    // for some relief... on multi-core systems sleep less between keygens
                    if (!interrupted()) {
                        int minSleep = Math.max(1, 10 / Math.max(1, SystemVersion.getCores() / 4));
                        try {Thread.sleep(Math.min(200, Math.max(minSleep, _calcDelay + (curCalc * 3))));}
                        catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
            if (!_isRunning) {break;}
            try {Thread.sleep(_checkDelay);}
            catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Pulls a prebuilt keypair from the queue,
     * or if not available, construct a new one.
     */
    public KeyPair getKeys() {
        _context.statManager().addRateData("crypto.XDHUsed", 1);
        KeyPair rv = _keys.poll();
        if (rv == null) {
            _context.statManager().addRateData("crypto.XDHEmpty", 1);
            recordEmpty();
            rv = precalc();
            // stop sleeping, wake up, make some more
            this.interrupt();
        }
        return rv;
    }

    private KeyPair precalc() {
        long start = System.currentTimeMillis();
        KeyPair rv = _context.keyGenerator().generatePKIKeys(EncType.ECIES_X25519);
        long diff = System.currentTimeMillis() - start;
        if (_log.shouldDebug()) {
            _log.debug("Took " + diff + "ms to generate local DH value");
        }
        return rv;
    }

    /**
     * Return an unused DH key builder
     * to be put back onto the queue for reuse.
     */
    public void returnUnused(KeyPair kp) {
        _keys.offer(kp);
    }

    /** @return true if successful, false if at or above max size */
    private final boolean addKeys(KeyPair kp) {
        if (_keys.size() >= _maxSize) { return false; }
        return _keys.offer(kp);
    }

}
