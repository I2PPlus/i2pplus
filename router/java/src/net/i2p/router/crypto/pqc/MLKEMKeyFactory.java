package net.i2p.router.crypto.pqc;

import java.security.GeneralSecurityException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.I2PAppContext;
import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyFactory;
import net.i2p.crypto.KeyPair;

import net.i2p.stat.RateConstants;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Try to keep key pairs at the ready.
 * It's important to do this in a separate thread, because if we run out,
 * the pairs are generated other threads,
 * and it can fall behind.
 * <p>
 * Started by ECIESAEADEngine. One type per-thread. Only used for 768, for now.
 *
 * @since 0.9.68 adapted from X25519KeyFactory
 */
public class MLKEMKeyFactory extends I2PThread implements KeyFactory {

    private static volatile MLKEMKeyFactory lastInstance;
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
    /**
     * Approximate heap size of one pooled ML-KEM-768 keypair, in bytes. The
     * pool scales with cores and memory, so the scaled defaults are capped by
     * the byte budget below - an uncapped count scale can reach 100+ MB on
     * high-core boxes.
     * @since 0.9.72+
     */
    static final int BYTES_PER_PAIR = 4 * 1024;
    /**
     * Ceiling on the whole pool, in bytes.
     * @since 0.9.72+
     */
    static final long MAX_POOL_BYTES = 16L * 1024 * 1024;
    private static final int DEFAULT_MLKEM_PRECALC_MIN = 64;
    private static final int DEFAULT_MLKEM_PRECALC_MAX = 256;
    private static final int DEFAULT_MLKEM_PRECALC_DELAY = 25;
    private static final int HARD_MAX = 32768;
    /**
     * The byte budget expressed in keypairs. This, not {@link #HARD_MAX}, is
     * what bounds the pool - HARD_MAX alone would allow 32768 pairs, about
     * 128 MB, which is what the demand-driven resize used to be able to reach.
     * @since 0.9.72+
     */
    static final int BYTE_CAP = (int) Math.min(HARD_MAX, MAX_POOL_BYTES / BYTES_PER_PAIR);

    /**
     * The pool ceiling in keypairs that {@link #setMinSize} will accept.
     *
     * <p>Public because the auto-tuner declares a range for this pool and must not
     * propose a floor the setter silently rewrites; a target above this cap lands
     * back on the current value and reads as a param that will not tune.
     *
     * @return the byte-budget cap expressed in keypairs
     * @since 0.9.72+
     */
    public static int getByteCap() { return BYTE_CAP; }

    /**
     * Alice side only.
     *
     * @param ctx the application context whose log, stat and system info are used
     * @param type must be one of the internal types MLKEM*_INT
     */
    public MLKEMKeyFactory(I2PAppContext ctx, EncType type) {
        super("MLKEMPrecalc");
        _context = ctx;
        _type = type;
        _log = ctx.logManager().getLog(MLKEMKeyFactory.class);
        ctx.statManager().createRequiredRateStat("crypto.MLKEMUsed", "MLKEM keys consumed from precalc pool", "Encryption", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        ctx.statManager().createRequiredRateStat("crypto.MLKEMEmpty", "MLKEM queue empty", "Encryption", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });

        // Scale precomputation with available memory and cores, then cap
        // the scaled defaults by the byte budget (BYTES_PER_PAIR above).
        long maxMemory = SystemVersion.getMaxMemory();
        int cores = SystemVersion.getCores();
        // Memory factor: +1 per 128MB
        int memFactor = Math.max(1, (int)(maxMemory / (128L * 1024 * 1024)));
        // Core factor: +1 per core
        int coreFactor = cores;
        // Use the larger
        int factor = Math.max(memFactor, coreFactor);
        if (SystemVersion.isSlow())
            factor *= 2;
        int byteCap = BYTE_CAP;
        int defaultMin = Math.min(DEFAULT_MLKEM_PRECALC_MIN * factor, byteCap);
        int defaultMax = Math.min(DEFAULT_MLKEM_PRECALC_MAX * factor, byteCap);
        // Cap the configured min as well: an unbounded min would let the pool
        // floor exceed the byte budget no matter what the max is capped at.
        _minSize = Math.min(ctx.getProperty(PROP_MLKEM_PRECALC_MIN, defaultMin), byteCap);
        int cfgMax = ctx.getProperty(PROP_MLKEM_PRECALC_MAX, defaultMax);
        // The same bound the Tuner's demand path uses. Without it a configured
        // max above the budget survives construction, so the byte budget would
        // only start bounding the pool at the first refreshPoolSize() call.
        _maxSize = boundedPoolSize(_minSize, (_minSize + cfgMax) / 2, byteCap);
        _calcDelay = ctx.getProperty(PROP_MLKEM_PRECALC_DELAY, DEFAULT_MLKEM_PRECALC_DELAY);

        if (_log.shouldDebug())
            _log.debug("MLKEM Precalc (minimum: " + _minSize + " max: " + _maxSize + ", delay: "
                       + _calcDelay + ")");
        _keys = new LinkedBlockingQueue<>(HARD_MAX);
        if (!SystemVersion.isWindows())
            setPriority(Thread.NORM_PRIORITY - 1);
        lastInstance = this;
    }

    /**
     * The most recently constructed factory.
     *
     * @return the most recently constructed factory, or null if none has been created yet
     * @since 0.9.70+
     */
    public static MLKEMKeyFactory getInstance() { return lastInstance; }

    /**
     * Lower bound on the precalculated key queue size.
     *
     * @return the min size
     * @since 0.9.70+
     */
    public int getMinSize() { return _minSize; }

    /**
     * Minimum precalc queue size.
     *
     * @param min the floor on the queue, clamped into [1, {@link #BYTE_CAP}] so a
     *            Tuner value cannot lift the floor past the byte budget
     * @since 0.9.70+
     */
    public void setMinSize(int min) { _minSize = Math.min(BYTE_CAP, Math.max(1, min)); }

    /**
     * Upper bound on the precalculated key queue size.
     *
     * @return the max size
     * @since 0.9.70+
     */
    public int getMaxSize() { return _maxSize; }

    /**
     * Maximum precalc queue size.
     *
     * @param max the ceiling on the queue, raised to the minimum if it is lower
     * @since 0.9.70+
     */
    public void setMaxSize(int max) { _maxSize = boundedPoolSize(_minSize, max, BYTE_CAP); }

    /**
     * Returns the current number of precalc keys queued.
     *
     * @return the number of prebuilt keypairs currently waiting in the pool
     * @since 0.9.70+
     */
    public int getSize() { return _keys.size(); }

    /**
     * Bound a pool size: at least four above the floor, never above the cap.
     * <p>
     * The floor is capped too, so a configured minimum larger than the cap
     * cannot smuggle the pool past the byte budget by way of the floor.
     * <p>
     * Pure and static so the byte budget's reach can be pinned without running
     * the precalc thread.
     *
     * @param minSize the pool floor the pool must stay above
     * @param demandMax the size the caller wants, from demand or configuration
     * @param cap the ceiling, in keypairs
     * @return the size to use, always &le; cap
     * @since 0.9.72+
     */
    static int boundedPoolSize(int minSize, int demandMax, int cap) {
        int floor = poolFloor(minSize, cap);
        return Math.max(floor, Math.min(cap, demandMax));
    }

    /**
     * The smallest size the pool may be driven to: its floor, never above the
     * cap. A floor set at the cap pins the pool there instead of letting every
     * "at least min + 4" margin push it past the byte budget.
     *
     * @param minSize the pool floor
     * @param cap the ceiling, in keypairs
     * @return the effective floor, always &le; cap
     * @since 0.9.72+
     */
    static int poolFloor(int minSize, int cap) {
        return Math.min(cap, minSize + 4);
    }

    /**
     * Called by the Tuner every 15 seconds. Adjusts max pool size
     * based on demand.
     * <p>
     * Demand is bounded by the byte budget, not by {@link #HARD_MAX}: the
     * earlier bound let demand drive the pool to 32768 pairs, roughly 128 MB,
     * and the memory-pressure halving below only engages above 70% pressure, so
     * a router with a large heap could sit there indefinitely.
     *
     * @since 0.9.70+
     */
    public void refreshPoolSize() {
        int recentEmpties = _emptyCount.getAndSet(0);
        int recentUsage = _usedCount.getAndSet(0);
        long freeMem = Runtime.getRuntime().freeMemory();
        long totalMem = Runtime.getRuntime().totalMemory();
        double memPressure = 1.0 - ((double) freeMem / Math.max(totalMem, 1));

        int demandMax = _minSize + Math.max(256, recentUsage * 4) + recentEmpties;
        _maxSize = boundedPoolSize(_minSize, demandMax, BYTE_CAP);
        _maxSize = pressureAdjustedMax(_maxSize, _minSize, BYTE_CAP, memPressure);
    }

    /**
     * Shrink the pool ceiling under memory pressure, without letting the shrink
     * raise it past the byte budget on its way back down.
     * <p>
     * The floor of each shrink goes through {@link #poolFloor}, so a floor set
     * at the budget cannot add its four-pair margin on top of the cap.
     * <p>
     * Pure and static so the pressure branches can be pinned without driving the
     * JVM's free-memory ratio.
     *
     * @param maxSize the ceiling demand produced
     * @param minSize the pool floor
     * @param cap the ceiling, in keypairs
     * @param memPressure the fraction of the heap in use, 0 to 1
     * @return the ceiling to use, never above cap
     * @since 0.9.72+ split out of {@link #refreshPoolSize()}
     */
    static int pressureAdjustedMax(int maxSize, int minSize, int cap, double memPressure) {
        if (memPressure > 0.85)
            return Math.max(poolFloor(minSize, cap), maxSize / 2);
        else if (memPressure > 0.7)
            return Math.max(poolFloor(minSize, cap), maxSize * 3 / 4);
        return maxSize;
    }

    /**
     * Note that this stops the singleton precalc thread.
     * You don't want to do this if there are multiple routers in the JVM.
     * Fix this if you care. See {@link net.i2p.router.Router#shutdown}.
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
                        int curSize = getSize();
                        int minSleep = Math.max(1, 10 / Math.max(1, SystemVersion.getCores() / 4));
                        long sleepMs;
                        if (curSize < _minSize) {
                            // Below min: fill fast — minimal yield, no calcDelay overhead
                            sleepMs = Math.min(100, Math.max(minSleep, curCalc));
                        } else {
                            // Above min but filling to max: moderate pace
                            sleepMs = Math.min(200, Math.max(minSleep, _calcDelay + curCalc));
                        }
                        try { Thread.sleep(sleepMs); }
                        catch (InterruptedException ie) {
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
     * @return an ML-KEM keypair, taken from the prebuilt pool when one was
     *         available and generated on demand otherwise, never null
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
     *
     * @param kp the pair that was taken from the queue but never used
     */
    public void returnUnused(KeyPair kp) {
        _keys.offer(kp);
        //_context.statManager().addRateData("crypto.MLKEMReused", 1);
    }

    /**
     * Adds a precomputed key pair to the pool.
     *
     * @param kp the key pair to add
     * @return true if successful, false if full
     */
    private final boolean addKeys(KeyPair kp) {
        return _keys.offer(kp);
    }
}
