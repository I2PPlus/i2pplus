package net.i2p.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't  make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 */

import net.i2p.I2PAppContext;
import net.i2p.util.I2PThread;
import net.i2p.util.NativeBigInteger;
import net.i2p.util.SystemVersion;

import java.math.BigInteger;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Precalculate the Y and K for ElGamal encryption operations.
 *
 * This class precalcs a set of values on its own thread, using those transparently
 * when a new instance is created.  By default, the minimum threshold for creating
 * new values for the pool is 50, and the max pool size is 200 (30 and 100 on a
 * slow system); both scale up with heap size, to 400 and 1600.  Whenever the pool
 * has less than the minimum, it refills it above the minimum by however many values
 * it saw consumed since the previous check, so an idle router does no speculative
 * 2048-bit modular exponentiations.  There is a delay after each precalculation so
 * that the CPU isn't hosed during startup.  These three parameters are controlled by
 * java environmental variables and can be adjusted via:
 * -Dcrypto.yk.precalc.min=40 -Dcrypto.yk.precalc.max=100 -Dcrypto.yk.precalc.delay=60000
 *
 * (delay is milliseconds)
 *
 * To disable precalculation, set min to 0
 *
 * Tradeoff: the pool now normally sits near the minimum rather than near the
 * maximum, so a sustained burst can drain it and cost a foreground modular
 * exponentiation per message until the precalc thread catches up.  Raise
 * -Dcrypto.yk.precalc.min if that cost is noticeable.
 *
 * @author jrandom
 */
final class YKGenerator {
    private final int MIN_NUM_BUILDERS;
    private final int MAX_NUM_BUILDERS;
    private final int CALC_DELAY;
    private final LinkedBlockingQueue<BigInteger[]> _values;
    /**
     * Pool occupancy, maintained alongside {@link #_values}: sampling
     * LinkedBlockingQueue.size() takes both the put and the take lock, so it
     * contends with poll() on the encryption path.
     */
    private final AtomicInteger _size = new AtomicInteger();
    private Thread _precalcThread;
    private final I2PAppContext ctx;
    private volatile boolean _isRunning;

    /** Property: minimum pool threshold */
    public static final String PROP_YK_PRECALC_MIN = "crypto.yk.precalc.min";
    /** Property: maximum pool size */
    public static final String PROP_YK_PRECALC_MAX = "crypto.yk.precalc.max";
    /** Property: delay between precalc operations (ms) */
    public static final String PROP_YK_PRECALC_DELAY = "crypto.yk.precalc.delay";
    /** Default minimum pool threshold */
    public static final int DEFAULT_YK_PRECALC_MIN = SystemVersion.isSlow() ? 30 : 50;
    /** Default maximum pool size */
    public static final int DEFAULT_YK_PRECALC_MAX = SystemVersion.isSlow() ? 100 : 200;
    /** Default delay between precalc operations (ms) */
    public static final int DEFAULT_YK_PRECALC_DELAY = SystemVersion.isSlow() ? 200 : 150;

    /**
     * Caller must also call start() to start the background precalc thread.
     * Unit tests will still work without calling start().
     *
     * @param context application context, source of the precalc pool size and delay properties
     */
    public YKGenerator(I2PAppContext context) {
        ctx = context;

        // Add to the defaults for every 128MB of RAM, up to 1GB
        long maxMemory = SystemVersion.getMaxMemory();
        int factor = (int) Math.max(1L, Math.min(8L, 1 + (maxMemory / (128 * 1024 * 1024L))));
        int defaultMin = DEFAULT_YK_PRECALC_MIN * factor;
        int defaultMax = DEFAULT_YK_PRECALC_MAX * factor;
        MIN_NUM_BUILDERS = ctx.getProperty(PROP_YK_PRECALC_MIN, defaultMin);
        MAX_NUM_BUILDERS = ctx.getProperty(PROP_YK_PRECALC_MAX, defaultMax);
        CALC_DELAY = ctx.getProperty(PROP_YK_PRECALC_DELAY, DEFAULT_YK_PRECALC_DELAY);
        _values = new LinkedBlockingQueue<>(MAX_NUM_BUILDERS);
    }

    /**
     * Start the background precalc thread.
     * Must be called for normal operation.
     * If not called, all generation happens in the foreground.
     * Not required for unit tests.
     *
     * @since 0.9.14
     */
    public synchronized void start() {
        if (_isRunning) {
            return;
        }
        _precalcThread = new I2PThread(new YKPrecalcRunner(MIN_NUM_BUILDERS, MAX_NUM_BUILDERS), "YKPrecalc", true);
        _precalcThread.setPriority(Thread.NORM_PRIORITY - 2);
        _isRunning = true;
        _precalcThread.start();
    }

    /**
     * Stop the background precalc thread.
     * Can be restarted.
     * Not required for unit tests.
     *
     * @since 0.8.8
     */
    public synchronized void shutdown() {
        _isRunning = false;
        if (_precalcThread != null) {
            _precalcThread.interrupt();
        }
        _values.clear();
        _size.set(0);
    }

    /**
     * Add a precomputed YK value to the buffer.
     * @return true if successful, false if full
     */
    private final boolean addValues(BigInteger[] yk) {
        if (!_values.offer(yk)) {
            return false;
        }
        _size.incrementAndGet();
        return true;
    }

    /**
     * Next precomputed YK value.
     *
     * @return rv[0] = Y; rv[1] = K
     */
    public BigInteger[] getNextYK() {
        BigInteger[] rv = _values.poll();
        if (rv != null) {
            _size.decrementAndGet();
            return rv;
        }
        rv = generateYK();
        Thread precalcThread;
        synchronized (this) {
            precalcThread = _precalcThread;
        }
        if (precalcThread != null) {
            precalcThread.interrupt();
        }
        return rv;
    }

    private static final BigInteger TWO = new NativeBigInteger(1, new byte[] {0x02});

    /**
     * Generate a new YK pair.
     * @return rv[0] = Y; rv[1] = K
     */
    private final BigInteger[] generateYK() {
        NativeBigInteger k = null;
        BigInteger y = null;
        while (k == null) {
            k = new NativeBigInteger(ctx.keyGenerator().getElGamalExponentSize(), ctx.random());
            if (BigInteger.ZERO.compareTo(k) == 0) {
                k = null;
                continue;
            }
            BigInteger kPlus2 = k.add(TWO);
            if (kPlus2.compareTo(CryptoConstants.elgp) > 0) k = null;
        }
        y = CryptoConstants.elgg.modPowCT(k, CryptoConstants.elgp);

        BigInteger[] yk = new BigInteger[2];
        yk[0] = y;
        yk[1] = k;
        return yk;
    }

    /** Precalculation thread. */
    private class YKPrecalcRunner implements Runnable {
        /**
         * Refill headroom for a pool that saw no consumption yet, covering the
         * jitter between a drain and this thread noticing it.
         */
        private static final int MIN_SLACK = 5;

        private final int _minSize;
        private final int _maxSize;

        /** Check every 30 seconds whether fewer than the minimum YK pairs remain. */
        private long _checkDelay = (long) 30 * 1000;

        /** Pool occupancy at the end of the previous cycle. */
        private int _lastSize;
        /** Wall clock time at the end of the previous cycle. */
        private long _lastSample;

        private YKPrecalcRunner(int minSize, int maxSize) {
            _minSize = minSize;
            _maxSize = maxSize;
        }

        /**
         * Precalculate YK pairs until the generator is stopped.
         */
        @Override
        public void run() {
            while (_isRunning) {
                int startSize = _size.get();
                // Nothing adds to the pool but us, so the drop since the last
                // sample is exactly what the encryption path consumed.
                int drained = Math.max(0, _lastSize - startSize);
                long elapsed = ctx.clock().now() - _lastSample;
                // Adjust delay
                if (startSize <= (_minSize * 2 / 3) && _checkDelay > 1000) {
                    _checkDelay -= 1000;
                } else if (startSize > (_minSize * 3 / 2) && _checkDelay < 60 * 1000) {
                    _checkDelay += 1000;
                }
                if (startSize < _minSize) {
                    // Fill to the demand-derived target, do the check here so we
                    // don't throw away one when full in addValues()
                    int target = refillTarget(drained, elapsed);
                    while (_size.get() < target && _isRunning) {
                        if (!addValues(generateYK())) {
                            break;
                        }
                        try {
                            Thread.sleep(CALC_DELAY);
                        } // for some relief...
                        catch (InterruptedException ie) {
                            // interrupted during calculation sleep
                        }
                    }
                }
                if (!_isRunning) {
                    break;
                }
                // Resample after filling, so the next cycle measures drain only
                _lastSize = _size.get();
                _lastSample = ctx.clock().now();
                try {
                    Thread.sleep(_checkDelay);
                } catch (InterruptedException ie) {
                    // interrupted during check sleep
                }
            }
        }

        /**
         * How far above the minimum to refill: scoped to one check interval's
         * worth of observed consumption, so the expensive modular exponentiations
         * track real demand. Only consulted once the pool is already below the
         * minimum, so it never changes when a refill starts, only how far it goes.
         *
         * @param drained how many values the pool lost since the previous cycle
         * @param elapsedMs how long ago the previous cycle ended
         * @return the size to fill to, never above the configured maximum
         */
        private int refillTarget(int drained, long elapsedMs) {
            int room = _maxSize - _minSize;
            if (room <= 0) {
                // misconfigured with max <= min, don't spin on a full queue
                return Math.min(_minSize, _maxSize);
            }
            if (drained <= 0 || elapsedMs <= 0) {
                return _minSize + Math.min(room, MIN_SLACK);
            }
            // scale the drain up to one check interval, rounding up so that even
            // a single consumed value buys one back
            long slack = (((long) drained * _checkDelay) + elapsedMs - 1) / elapsedMs;
            if (slack < 1) {
                slack = 1;
            } else if (slack > room) {
                slack = room;
            }
            return _minSize + (int) slack;
        }
    }
}
