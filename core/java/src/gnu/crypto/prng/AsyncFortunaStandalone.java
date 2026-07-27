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
 * fortuna instance that tries to avoid blocking if at all possible by using separate
 * filled buffer segments rather than one buffer (and blocking when that buffer's data
 * has been eaten)
 *
 * Note that this class is not fully Thread safe!
 * The following methods must be synchronized externally, they are not
 * synced here or in super():
 *   addRandomByte(), addRandomBytes(), nextByte(), nextBytes(), seed()
 *
 */
@SuppressWarnings("java:S2975")
public class AsyncFortunaStandalone extends FortunaStandalone implements Runnable {
    /** ignored */
    private static final int DEFAULT_BUFFERS = 2;
    /** ignored */
    private static final int DEFAULT_BUFSIZE = SystemVersion.isAndroid() ? 64*1024 : 256*1024;
    /** ignored */
    private final int _bufferCount;
    /** ignored */
    private final int _bufferSize;
    /** the lock */
    private final Object asyncBuffers = new Object();
    /** ignored */
    private final I2PAppContext _context;
    /** ignored */
    private final Log _log;
    /** ignored */
    private volatile boolean _isRunning;
    /** ignored */
    private Thread _refillThread;
    /** ignored */
    private final LinkedBlockingQueue<AsyncBuffer> _fullBuffers;
    /** ignored */
    private final LinkedBlockingQueue<AsyncBuffer> _emptyBuffers;
    /** ignored */
    private AsyncBuffer _currentBuffer;

    /**
     * Create a new AsyncFortunaStandalone.
     * @param context the I2P app context
     */
    public AsyncFortunaStandalone(I2PAppContext context) {
        super(context.getBooleanPropertyDefaultTrue("prng.useDevRandom") && !SystemVersion.isWindows() && !SystemVersion.isSlow());
        _bufferCount = Math.max(context.getProperty("prng.buffers", DEFAULT_BUFFERS), 2);
        _bufferSize = Math.max(context.getProperty("prng.bufferSize", DEFAULT_BUFSIZE), 16*1024);
        _emptyBuffers = new LinkedBlockingQueue<>(_bufferCount);
        _fullBuffers = new LinkedBlockingQueue<>(_bufferCount);
        _context = context;
        _log = context.logManager().getLog(AsyncFortunaStandalone.class);
    }

    @Override
    /** Return a copy of this object */
    public Object clone() {
        return super.clone();
    }

    /** Start the PRNG */
    public void startup() {
        for (int i = 0; i < _bufferCount; i++) {_emptyBuffers.offer(new AsyncBuffer(_bufferSize));}
        _isRunning = true;
        _refillThread = new I2PThread(this, "PRNG");
        _refillThread.setDaemon(true);
        _refillThread.setPriority(Thread.NORM_PRIORITY - 2);
        _refillThread.start();
    }

    /** Stop the PRNG */
    public void shutdown() {
        _isRunning = false;
        _emptyBuffers.clear();
        _fullBuffers.clear();
        _refillThread.interrupt();
        // unsynchronized to avoid hanging, may NPE elsewhere
        _currentBuffer = null;
        buffer = null;
    }

    /** the seed is only propogated once the prng is started with startup() */
    @Override
    public void seed(byte[] val) {
        Map<String, byte[]> props = Collections.singletonMap(SEED, val);
        init(props);
    }

    private static class AsyncBuffer {
        /**
         * buffer.
         */
        public final byte[] buffer;

        /**
         * AsyncBuffer.
         */
        public AsyncBuffer(int size) {
            buffer = new byte[size];
        }
    }

    /**
     * make the next available filled buffer current, scheduling any unfilled
     * buffers for refill, and blocking until at least one buffer is ready
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
     *  The refiller thread
     */
    @Override
    /**
     * Execute the task.
     */
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

    /**
     * fillBlock.
     */
    @Override
    /**
     * Fill the PRNG output block.
     */
    public void fillBlock() {rotateBuffer();}

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
