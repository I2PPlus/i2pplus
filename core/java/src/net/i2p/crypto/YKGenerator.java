package net.i2p.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't  make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.math.BigInteger;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.I2PAppContext;
import net.i2p.util.I2PThread;
import net.i2p.util.NativeBigInteger;
import net.i2p.util.SystemVersion;

/**
 * Precalculate the Y and K for ElGamal encryption operations.
 *
 * This class precalcs a set of values on its own thread, using those transparently
 * when a new instance is created.  By default, the minimum threshold for creating
 * new values for the pool is 20, and the max pool size is 50.  Whenever the pool has
 * less than the minimum, it fills it up again to the max.  There is a delay after
 * each precalculation so that the CPU isn't hosed during startup.
 * These three parameters are controlled by java environmental variables and
 * can be adjusted via:
 *  -Dcrypto.yk.precalc.min=40 -Dcrypto.yk.precalc.max=100 -Dcrypto.yk.precalc.delay=60000
 *
 * (delay is milliseconds)
 *
 * To disable precalculation, set min to 0
 *
 * @author jrandom
 */
final class YKGenerator {
    private final int MIN_NUM_BUILDERS;
    private final int MAX_NUM_BUILDERS;
    private final int CALC_DELAY;
    private final LinkedBlockingQueue<BigInteger[]> _values;
    private Thread _precalcThread;
    private final I2PAppContext ctx;
    private volatile boolean _isRunning;

    public final static String PROP_YK_PRECALC_MIN = "crypto.yk.precalc.min";
    public final static String PROP_YK_PRECALC_MAX = "crypto.yk.precalc.max";
    public final static String PROP_YK_PRECALC_DELAY = "crypto.yk.precalc.delay";
    public final static int DEFAULT_YK_PRECALC_MIN = SystemVersion.isSlow() ? 30 : 50;
    public final static int DEFAULT_YK_PRECALC_MAX = SystemVersion.isSlow() ? 100 : 200;
    public final static int DEFAULT_YK_PRECALC_DELAY =  SystemVersion.isSlow() ? 200 : 150;

    /**
     *  Caller must also call start() to start the background precalc thread.
     *  Unit tests will still work without calling start().
     */
    public YKGenerator(I2PAppContext context) {
        ctx = context;

        // Add to the defaults for every 128MB of RAM, up to 1GB
        long maxMemory = SystemVersion.getMaxMemory();
        int factor = (int) Math.max(1l, Math.min(8l, 1 + (maxMemory / (128*1024*1024l))));
        int defaultMin = DEFAULT_YK_PRECALC_MIN * factor;
        int defaultMax = DEFAULT_YK_PRECALC_MAX * factor;
        MIN_NUM_BUILDERS = ctx.getProperty(PROP_YK_PRECALC_MIN, defaultMin);
        MAX_NUM_BUILDERS = ctx.getProperty(PROP_YK_PRECALC_MAX, defaultMax);
        CALC_DELAY = ctx.getProperty(PROP_YK_PRECALC_DELAY, DEFAULT_YK_PRECALC_DELAY);
        _values = new LinkedBlockingQueue<BigInteger[]>(MAX_NUM_BUILDERS);
        ctx.statManager().createRateStat("crypto.YKUsed", "How often a precalculated ephemeral key (YK) is needed from queue", "Encryption", new long[] { 60*1000, 60*60*1000 });
        ctx.statManager().createRateStat("crypto.YKEmpty", "How often precalculated ephemeral key (YK) queue is empty", "Encryption", new long[] { 60*1000, 60*60*1000 });
    }

    /**
     *  Start the background precalc thread.
     *  Must be called for normal operation.
     *  If not called, all generation happens in the foreground.
     *  Not required for unit tests.
     *
     *  @since 0.9.14
     */
    public synchronized void start() {
        if (_isRunning) {return;}
        _precalcThread = new I2PThread(new YKPrecalcRunner(MIN_NUM_BUILDERS, MAX_NUM_BUILDERS), "YK Precalc", true);
        _precalcThread.setPriority(Thread.NORM_PRIORITY - 2);
        _isRunning = true;
        _precalcThread.start();
    }

    /**
     *  Stop the background precalc thread.
     *  Can be restarted.
     *  Not required for unit tests.
     *
     *  @since 0.8.8
     */
    public synchronized void shutdown() {
        _isRunning = false;
        if (_precalcThread != null) {_precalcThread.interrupt();}
        _values.clear();
    }

    private final int getSize() {return _values.size();}

    /** @return true if successful, false if full */
    private final boolean addValues(BigInteger yk[]) {return _values.offer(yk);}

    /** @return rv[0] = Y; rv[1] = K */
    public BigInteger[] getNextYK() {
        ctx.statManager().addRateData("crypto.YKUsed", 1);
        BigInteger[] rv = _values.poll();
        if (rv != null) {return rv;}
        ctx.statManager().addRateData("crypto.YKEmpty", 1);
        rv = generateYK();
        if (_precalcThread != null) {_precalcThread.interrupt();}
        return rv;
    }

    private final static BigInteger TWO = new NativeBigInteger(1, new byte[] { 0x02});

    /** @return rv[0] = Y; rv[1] = K */
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
        y = CryptoConstants.elgg.modPow(k, CryptoConstants.elgp);

        BigInteger yk[] = new BigInteger[2];
        yk[0] = y;
        yk[1] = k;
        return yk;
    }

    /** the thread */
    private class YKPrecalcRunner implements Runnable {
        private final int _minSize;
        private final int _maxSize;

        /** check every 30 seconds whether we have less than the minimum */
        private long _checkDelay = 30 * 1000;

        private YKPrecalcRunner(int minSize, int maxSize) {
            _minSize = minSize;
            _maxSize = maxSize;
        }

        public void run() {
            while (_isRunning) {
                int startSize = getSize();
                // Adjust delay
                if (startSize <= (_minSize * 2 / 3) && _checkDelay > 1000) {_checkDelay -= 1000;}
                else if (startSize > (_minSize * 3 / 2) && _checkDelay < 60*1000) {_checkDelay += 1000;}
                if (startSize < _minSize) {
                    // Fill all the way up, do the check here so we don't throw away one when full in addValues()
                    while (getSize() < _maxSize && _isRunning) {
                        if (!addValues(generateYK())) {break;}
                        try {Thread.sleep(CALC_DELAY);} // for some relief...
                        } catch (InterruptedException ie) {} // no-op
                    }
                }
                if (!_isRunning) {break;}
                try {Thread.sleep(_checkDelay);}
                } catch (InterruptedException ie) {} // no-op
            }
        }
    }

}