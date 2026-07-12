package net.i2p.router.crypto.ratchet;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyFactory;
import net.i2p.crypto.KeyPair;
import net.i2p.router.RouterContext;
import net.i2p.stat.RateConstants;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 *  Threaded factory for pre-calculating Elligator2-encoded X25519 key pairs to prevent Diffie-Hellman computation bottlenecks in critical paths
 *
 *  Try to keep DH pairs at the ready. It's important to do this in a separate thread, because
 *  if we run out, the pairs are generated in the NTCP Pumper thread, and it can fall behind.
 *
 *  @since 0.9.44 from X25519KeyFactory
 */
public class Elg2KeyFactory extends I2PThread implements KeyFactory {

    private static volatile Elg2KeyFactory _lastInstance;
    private final RouterContext _context;
    private final Log _log;
    private final Elligator2 _elg2;
    private volatile int _minSize;
    private volatile int _maxSize;
    private volatile int _calcDelay;
    private final LinkedBlockingQueue<Elg2KeyPair> _keys;
    private volatile boolean _isRunning;
    private long _checkDelay = 10 * 1000L;
    private final AtomicInteger _emptyCount = new AtomicInteger();

    private static final String PROP_DH_PRECALC_MIN = "crypto.edh.precalc.min";
    private static final String PROP_DH_PRECALC_MAX = "crypto.edh.precalc.max";
    private static final String PROP_DH_PRECALC_DELAY = "crypto.edh.precalc.delay";
    private static final int DEFAULT_DH_PRECALC_MIN = SystemVersion.isSlow() ? 20 : 50;
    private static final int DEFAULT_DH_PRECALC_MAX = SystemVersion.isSlow() ? 100 : 200;
    private static final int DEFAULT_DH_PRECALC_DELAY = 25;
    private final boolean RETURN_UNUSED_TO_XDH;

    public Elg2KeyFactory(RouterContext ctx) {
        super("EDH Precalc");
        _context = ctx;
        _log = ctx.logManager().getLog(Elg2KeyFactory.class);
        _elg2 = new Elligator2(ctx);
        ctx.statManager().createRequiredRateStat("crypto.EDHUsed", "Need a DH from the queue", "Encryption", new long[] { RateConstants.ONE_MINUTE });
        ctx.statManager().createRequiredRateStat("crypto.EDHEmpty", "DH queue empty", "Encryption", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        ctx.statManager().createRequiredRateStat("crypto.EDHDrain", "Idle EDH keys drained from pool", "Encryption", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES });

        // Scale precomputation with available memory and cores.
        long maxMemory = SystemVersion.getMaxMemory();
        int cores = SystemVersion.getCores();
        int memFactor = Math.max(1, (int)(maxMemory / (128L * 1024 * 1024)));
        int coreFactor = cores;
        int factor = Math.max(memFactor, coreFactor);
        boolean slow = SystemVersion.isSlow();
        RETURN_UNUSED_TO_XDH = slow;
        if (slow) {factor *= 2;}
        int defaultMin = DEFAULT_DH_PRECALC_MIN * factor;
        int defaultMax = DEFAULT_DH_PRECALC_MAX * factor;
        _minSize = ctx.getProperty(PROP_DH_PRECALC_MIN, defaultMin);
        _maxSize = ctx.getProperty(PROP_DH_PRECALC_MAX, defaultMax);
        _calcDelay = ctx.getProperty(PROP_DH_PRECALC_DELAY, DEFAULT_DH_PRECALC_DELAY);

        if (_log.shouldDebug()) {
            _log.debug("EDH Precalc (minimum: " + _minSize + " max: " + _maxSize + ", delay: " + _calcDelay + ")");
        }
        _keys = new LinkedBlockingQueue<>(_maxSize);
        if (!SystemVersion.isWindows()) {setPriority(Thread.NORM_PRIORITY - 1);}
        _lastInstance = this;
    }

    /**
 * Returns the last created instance.
 *
 * @since 0.9.70+
 */
    public static Elg2KeyFactory getInstance() { return _lastInstance; }

    /**
 * Returns the current minimum precalc queue size.
 *
 * @since 0.9.70+
 */
    public int getMinSize() { return _minSize; }

    /**
 * Sets the minimum precalc queue size.
 *
 * @since 0.9.70+
 */
    public void setMinSize(int min) { _minSize = Math.max(1, min); }

    /**
 * Returns the current maximum precalc queue size.
 *
 * @since 0.9.70+
 */
    public int getMaxSize() { return _maxSize; }

    /**
 * Sets the maximum precalc queue size.
 *
 * @since 0.9.70+
 */
    public void setMaxSize(int max) { _maxSize = Math.max(_minSize, max); }

    /**
 * Returns the current number of precalc keys queued.
 *
 * @since 0.9.70+
 */
    public int getSize() { return _keys.size(); }

    /**
     * Called by the Tuner every 30 seconds. Adjusts max pool size
     * based on demand and drains excess keys.
     *
     * @since 0.9.70+
     */
    public void refreshPoolSize() {
        int recentEmpties = _emptyCount.getAndSet(0);
        boolean lowDemand = recentEmpties == 0;

        long freeMem = Runtime.getRuntime().freeMemory();
        long totalMem = Runtime.getRuntime().totalMemory();
        double memPressure = 1.0 - ((double) freeMem / Math.max(totalMem, 1));

        // Shrink max when idle, especially under memory pressure
        if (lowDemand && memPressure > 0.85) {
            _maxSize = Math.max(_minSize + 64, _maxSize / 2);
        } else if (lowDemand && memPressure > 0.7) {
            _maxSize = Math.max(_minSize + 128, _maxSize * 3 / 4);
        }

        // Drain excess keys when pool shrinks below current queue
        int current = _keys.size();
        int target = _minSize + 256;
        if (current > target) {
            int drained = 0;
            while (_keys.size() > target) {
                Elg2KeyPair kp = _keys.poll();
                if (kp == null)
                    break;
                drained++;
            }
            if (drained > 0) {
                _context.statManager().addRateData("crypto.EDHDrain", drained);
                if (_log.shouldWarn()) {
                    _log.warn("EDH Precalc drained " + drained + " idle keys (" + current + " -> " + _keys.size() + ")");
                }
            }
        }
    }

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
                // fill all the way up, do the check here so we don't throw away one when full in addValues()
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
    public Elg2KeyPair getKeys() {
        _context.statManager().addRateData("crypto.EDHUsed", 1);
        Elg2KeyPair rv = _keys.poll();
        if (rv == null) {
            _emptyCount.incrementAndGet();
            _context.statManager().addRateData("crypto.EDHEmpty", 1);
            rv = precalc();
            this.interrupt(); // stop sleeping, wake up, make some more
        }
        return rv;
    }

    private Elg2KeyPair precalc() {
        long start = System.currentTimeMillis();
        KeyPair rv;
        byte[] enc;
        int i = 0;
        do {
            rv = _context.keyGenerator().generatePKIKeys(EncType.ECIES_X25519);
            enc = _elg2.encode(rv.getPublic());
            i++;
            if (enc == null && RETURN_UNUSED_TO_XDH) {
                _context.commSystem().getXDHFactory().returnUnused(rv);
            }
        } while (enc == null);
        long diff = System.currentTimeMillis() - start;
        if (_log.shouldDebug()) {
            _log.debug("Took " + i + " tries and " + diff + "ms to generate local DH value");
        }
        return new Elg2KeyPair(rv.getPublic(), rv.getPrivate(), enc);
    }

    /**
     * Return an unused DH key builder
     * to be put back onto the queue for reuse.
     */
    public void returnUnused(Elg2KeyPair kp) {
        // intentionally empty - unused keys are discarded, not pooled
    }

    /** @return true if successful, false if full */
    private final boolean addKeys(Elg2KeyPair kp) {return _keys.offer(kp);}

}
