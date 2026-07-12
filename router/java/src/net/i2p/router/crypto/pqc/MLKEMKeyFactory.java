package net.i2p.router.crypto.pqc;

import java.security.GeneralSecurityException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.I2PAppContext;
import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyFactory;
import net.i2p.crypto.KeyPair;

import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 *  Try to keep key pairs at the ready.
 *  It's important to do this in a separate thread, because if we run out,
 *  the pairs are generated other threads,
 *  and it can fall behind.
 *
 *  Started by ECIESAEADEngine. One type per-thread. Only used for 768, for now.
 *
 *  @since 0.9.68 adapted from X25519KeyFactory
 */
public class MLKEMKeyFactory extends I2PThread implements KeyFactory {

    private static volatile MLKEMKeyFactory _lastInstance;
    private final I2PAppContext _context;
    private final Log _log;
    private volatile int _minSize;
    private volatile int _maxSize;
    private volatile int _calcDelay;
    private final LinkedBlockingQueue<KeyPair> _keys;
    private final EncType _type;
    private volatile boolean _isRunning;
    private long _checkDelay = 10 * 1000L;
    private final AtomicInteger _emptyCount = new AtomicInteger();
    private final AtomicInteger _usedCount = new AtomicInteger();

    private static final String PROP_MLKEM_PRECALC_MIN = "crypto.mlkem.precalc.min";
    private static final String PROP_MLKEM_PRECALC_MAX = "crypto.mlkem.precalc.max";
    private static final String PROP_MLKEM_PRECALC_DELAY = "crypto.mlkem.precalc.delay";
    // MLKEM-768 pair is ~3.5KB so even 1000 keys is <4MB.
    // Scale up by factor (cores/memory) at construction time.
    private static final int DEFAULT_MLKEM_PRECALC_MIN = 64;
    private static final int DEFAULT_MLKEM_PRECALC_MAX = 256;
    private static final int DEFAULT_MLKEM_PRECALC_DELAY = 25;

    /**
     *  Alice side only
     *
     *  @param type must be one of the internal types MLKEM*_INT
     */
    public MLKEMKeyFactory(I2PAppContext ctx, EncType type) {
        super("MLKEMPrecalc");
        _context = ctx;
        _type = type;
        _log = ctx.logManager().getLog(MLKEMKeyFactory.class);
        ctx.statManager().createRequiredRateStat("crypto.MLKEMUsed", "MLKEM keys consumed from precalc pool", "Encryption", new long[] { 60*1000L, 60*60*1000L });
        ctx.statManager().createRequiredRateStat("crypto.MLKEMEmpty", "Queue empty", "Encryption", new long[] { 60*1000L, 10*60*1000L, 60*60*1000L });

        // Scale precomputation with available memory and cores.
        // MLKEM-768 keypair is ~3.5KB so even 1000 keys is <4MB.
        long maxMemory = SystemVersion.getMaxMemory();
        int cores = SystemVersion.getCores();
        // Memory factor: +1 per 128MB
        int memFactor = Math.max(1, (int)(maxMemory / (128L * 1024 * 1024)));
        // Core factor: +1 per core
        int coreFactor = cores;
        // Use the larger — memory cost is negligible, no cap
        int factor = Math.max(memFactor, coreFactor);
        if (SystemVersion.isSlow())
            factor *= 2;
        int defaultMin = DEFAULT_MLKEM_PRECALC_MIN * factor;
        int defaultMax = DEFAULT_MLKEM_PRECALC_MAX * factor;
        _minSize = ctx.getProperty(PROP_MLKEM_PRECALC_MIN, defaultMin);
        _maxSize = ctx.getProperty(PROP_MLKEM_PRECALC_MAX, defaultMax);
        _calcDelay = ctx.getProperty(PROP_MLKEM_PRECALC_DELAY, DEFAULT_MLKEM_PRECALC_DELAY);

        if (_log.shouldDebug())
            _log.debug("MLKEM Precalc (minimum: " + _minSize + " max: " + _maxSize + ", delay: "
                       + _calcDelay + ")");
        _keys = new LinkedBlockingQueue<>(_maxSize);
        if (!SystemVersion.isWindows())
            setPriority(Thread.NORM_PRIORITY - 1);
        _lastInstance = this;
    }

    /**
 * Returns the last created instance.
 *
 * @since 0.9.70+
 */
    public static MLKEMKeyFactory getInstance() { return _lastInstance; }

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
     * based on demand.
     *
     * @since 0.9.70+
     */
    public void refreshPoolSize() {
        int recentEmpties = _emptyCount.getAndSet(0);
        boolean lowDemand = recentEmpties == 0;
        int recentUsage = _usedCount.getAndSet(0);
        int fillCeiling = Math.max(1, Math.min(_maxSize,
            _minSize + Math.max(256, recentUsage + recentUsage / 10)));

        long freeMem = Runtime.getRuntime().freeMemory();
        long totalMem = Runtime.getRuntime().totalMemory();
        double memPressure = 1.0 - ((double) freeMem / Math.max(totalMem, 1));

        // Cap max at demand-based fill ceiling
        _maxSize = Math.min(_maxSize, fillCeiling);

        // Shrink max when idle, especially under memory pressure
        if (lowDemand && memPressure > 0.85) {
            _maxSize = Math.max(_minSize + 64, _maxSize / 2);
        } else if (lowDemand && memPressure > 0.7) {
            _maxSize = Math.max(_minSize + 128, _maxSize * 3 / 4);
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

    @Override
    public void run() {
        try {
            run2();
        } catch (GeneralSecurityException gse) {
            if (_isRunning)
                throw new IllegalStateException(gse);
        } catch (IllegalStateException ise) {
            if (_isRunning)
                throw ise;
        }
    }

    private void run2() throws GeneralSecurityException {
        _isRunning = true;
        while (_isRunning) {
            int startSize = getSize();
            // Adjust delay
            if (startSize <= (_minSize * 2 / 3) && _checkDelay > 1000)
                _checkDelay -= 1000;
            else if (startSize > (_minSize * 3 / 2) && _checkDelay < 60*1000L)
                _checkDelay += 1000;
            if (startSize < _minSize) {
                // fill all the way up, do the check here so we don't
                while (getSize() < _maxSize && _isRunning) {
                    long curStart = System.currentTimeMillis();
                    if (!addKeys(precalc()))
                        break;
                    long curCalc = System.currentTimeMillis() - curStart;
                    // for some relief...
                    // On multi-core systems, spend less time sleeping between keygens
                    if (!interrupted()) {
                        try {
                            int minSleep = Math.max(1, 10 / Math.max(1, SystemVersion.getCores() / 4));
                            Thread.sleep(Math.min(200, Math.max(minSleep, _calcDelay + (curCalc * 3))));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
            if (!_isRunning)
                break;
            try {
                Thread.sleep(_checkDelay);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Pulls a prebuilt keypair from the queue,
     * or if not available, construct a new one.
     */
    @Override
    public KeyPair getKeys() {
        _context.statManager().addRateData("crypto.MLKEMUsed", 1);
        _usedCount.incrementAndGet();
        KeyPair rv = _keys.poll();
        if (rv == null) {
            _emptyCount.incrementAndGet();
            _context.statManager().addRateData("crypto.MLKEMEmpty", 1);
            try {
                rv = precalc();
            } catch (GeneralSecurityException gse) {
                throw new IllegalStateException(gse);
            }
            // stop sleeping, wake up, make some more
            this.interrupt();
        }
        return rv;
    }

    private KeyPair precalc() throws GeneralSecurityException {
        return MLKEM.getKeys(_type);
    }

    /**
     * Return an unused key pair
     * to be put back onto the queue for reuse.
     */
    public void returnUnused(KeyPair kp) {
        _keys.offer(kp);
        //_context.statManager().addRateData("crypto.MLKEMReused", 1);
    }

    /** @return true if successful, false if full */
    private final boolean addKeys(KeyPair kp) {
        return _keys.offer(kp);
    }
}
