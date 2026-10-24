package gnu.crypto.prng;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import net.i2p.I2PAppContext;
import net.i2p.stat.RateConstants;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Fortuna instance that tries to avoid blocking if at all possible by using separate
 * filled buffer segments rather than one buffer (and blocking when that buffer's data
 * has been eaten).
 *
 * Note that this class is not fully Thread safe!
 * The following methods must be synchronized externally, they are not
 * synced here or in super():
 * addRandomByte(), addRandomBytes(), nextByte(), nextBytes(), {@link #seed}
 */
@SuppressWarnings("java:S2975")
public class AsyncFortunaStandalone extends FortunaStandalone implements Runnable {
    /** Number of buffers filled in the background. */
    private static final int DEFAULT_BUFFERS = 2;
    /** Size of each background buffer, in bytes. */
    private static final int DEFAULT_BUFSIZE = SystemVersion.isAndroid() ? 64*1024 : 256*1024;
    /** How many buffers are cycled through, never less than 2. */
    private final int _bufferCount;
    /** The size in bytes of each buffer, never less than 16384. */
    private final int _bufferSize;
    /** The lock. */
    private final Object asyncBuffers = new Object();
    /** The app context. */
    private final I2PAppContext _context;
    /** The logger for this class. */
    private final Log _log;
    /** True until {@link #shutdown()} is called, which stops the refill thread. */
    private volatile boolean _isRunning;
    /** The thread that refills empty buffers in the background. */
    private Thread _refillThread;
    /** Buffers holding freshly generated output, waiting to be consumed. */
    private final LinkedBlockingQueue<AsyncBuffer> _fullBuffers;
    /** Consumed buffers waiting to be refilled. */
    private final LinkedBlockingQueue<AsyncBuffer> _emptyBuffers;
    /** The buffer the PRNG is currently handing out bytes from. */
    private AsyncBuffer _currentBuffer;

    /**
     * Create a new AsyncFortunaStandalone.
     * @param context the I2P app context
     */
    public AsyncFortunaStandalone(I2PAppContext context) {
        // Default to the in-process Fortuna generator. The DevRandom path
        // opens /dev/random afresh for every nextBytes() call (a syscall
        // storm on hot paths, and it can block on low-entropy systems);
        // Fortuna is already seeded from /dev/urandom, the seedfile and
        // SecureRandom at startup. Set prng.useDevRandom=true to opt back in.
        super(context.getBooleanProperty("prng.useDevRandom") &&
              !SystemVersion.isWindows() && !SystemVersion.isSlow());
        _bufferCount = Math.max(context.getProperty("prng.buffers", DEFAULT_BUFFERS), 2);
        _bufferSize = Math.max(context.getProperty("prng.bufferSize", DEFAULT_BUFSIZE), 16*1024);
        _emptyBuffers = new LinkedBlockingQueue<>(_bufferCount);
        _fullBuffers = new LinkedBlockingQueue<>(_bufferCount);
        _context = context;
        _log = context.logManager().getLog(AsyncFortunaStandalone.class);
    }

    /** Return a copy of this object. */
    @Override
    public Object clone() {
        return super.clone();
    }

    /** Start the PRNG. */
    public void startup() {
        for (int i = 0; i < _bufferCount; i++) {_emptyBuffers.offer(new AsyncBuffer(_bufferSize));}
        _isRunning = true;
        _refillThread = new I2PThread(this, "PRNG");
        _refillThread.setDaemon(true);
        _refillThread.setPriority(Thread.NORM_PRIORITY - 2);
        _refillThread.start();
    }

    /** Stop the PRNG. */
    public void shutdown() {
        _isRunning = false;
        _emptyBuffers.clear();
        _fullBuffers.clear();
        _refillThread.interrupt();
        // unsynchronized to avoid hanging, may NPE elsewhere
        _currentBuffer = null;
        buffer = null;
    }

    /** The seed is only propogated once the prng is started with {@link #startup()}. */
    @Override
    public void seed(byte[] val) {
        Map<String, byte[]> props = Collections.singletonMap(SEED, val);
        init(props);
    }

    /** One buffer filled with random bytes in the background. */
    private static class AsyncBuffer {
        /** The buffer data. */
        public final byte[] buffer;

        /**
         * Creates a buffer of the given size.
         *
         * @param size the size of the buffer, in bytes
         */
        public AsyncBuffer(int size) {
            buffer = new byte[size];
        }
    }

    /**
     * Make the next available filled buffer current, scheduling any unfilled
     * buffers for refill, and blocking until at least one buffer is ready.
     */
    protected void rotateBuffer() {
        synchronized (asyncBuffers) {
            AsyncBuffer old = _currentBuffer;
            if (old != null) {_emptyBuffers.offer(old);}
            AsyncBuffer nextBuffer = null;

            while (nextBuffer == null) {
                if (!_isRunning) {throw new IllegalStateException("shutdown");}
                try {nextBuffer = _fullBuffers.take();}
                catch (InterruptedException ie) {continue;}
            }
            _currentBuffer = nextBuffer;
            buffer = nextBuffer.buffer;
        }
    }

    /**
     * The refiller thread. Runs until shutdown() is called, refilling
     * empty buffers and swapping out full ones.
     */
    @Override
    public void run() {
        while (_isRunning) {
            AsyncBuffer aBuff = null;
            try {aBuff = _emptyBuffers.take();}
            catch (InterruptedException ie) {continue;}

            long before = System.currentTimeMillis();
            doFill(aBuff.buffer);
            long after = System.currentTimeMillis();
            boolean shouldWait = _fullBuffers.size() > 1;
            _fullBuffers.offer(aBuff);

            if (shouldWait) {
                Thread.yield();
                long waitTime = (after-before)*5;
                if (waitTime <= 0) {waitTime = 50;} // somehow postman saw waitTime show up as negative
                else if (waitTime > 5000) {waitTime = 5000;}
                try {Thread.sleep(waitTime);}
                catch (InterruptedException ie) { /* ignored */ }
            }
        }
    }

    /** Fill the PRNG output block. */
    @Override
    public void fillBlock() {rotateBuffer();}

    /**
     * Fills one buffer with random bytes.
     *
     * @param buf the buffer to fill
     */
    private void doFill(byte[] buf) {
        if (pools != null) {
            if (pool0Count >= MIN_POOL_SIZE && System.currentTimeMillis() - lastReseed > 100) {
                reseedCount++;
                for (int i = 0; i < NUM_POOLS; i++) {
                    if (reseedCount % (1 << i) == 0) {
                        generator.addRandomBytes(pools[i].digest());
                    }
                }
                lastReseed = System.currentTimeMillis();
            }
        } // else we're using DevRandom
        generator.nextBytes(buf);
    }

}
