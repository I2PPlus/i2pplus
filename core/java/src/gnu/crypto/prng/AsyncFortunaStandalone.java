package gnu.crypto.prng;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.I2PAppContext;
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
public class AsyncFortunaStandalone extends FortunaStandalone implements Runnable {
    /**
     * This is set to 2 to minimize memory usage for standalone apps.
     * The router must override this via the prng.buffers property in the router context.
     */
    private static final int DEFAULT_BUFFERS = 2;
    private static final int DEFAULT_BUFSIZE = SystemVersion.isAndroid() ? 64*1024 : 256*1024;
    private final int _bufferCount;
    private final int _bufferSize;
    /** the lock */
    private final Object asyncBuffers = new Object();
    private final I2PAppContext _context;
    private final Log _log;
    private volatile boolean _isRunning;
    private Thread _refillThread;
    private final LinkedBlockingQueue<AsyncBuffer> _fullBuffers;
    private final LinkedBlockingQueue<AsyncBuffer> _emptyBuffers;
    private AsyncBuffer _currentBuffer;

    public AsyncFortunaStandalone(I2PAppContext context) {
        super(context.getBooleanPropertyDefaultTrue("prng.useDevRandom") && !SystemVersion.isWindows() && !SystemVersion.isSlow());
        _bufferCount = Math.max(context.getProperty("prng.buffers", DEFAULT_BUFFERS), 2);
        _bufferSize = Math.max(context.getProperty("prng.bufferSize", DEFAULT_BUFSIZE), 16*1024);
        _emptyBuffers = new LinkedBlockingQueue<AsyncBuffer>(_bufferCount);
        _fullBuffers = new LinkedBlockingQueue<AsyncBuffer>(_bufferCount);
        _context = context;
        context.statManager().createRateStat("prng.bufferFillTime", "Time to fill random number buffer (ms)", "Encryption", new long[] { 60*60*1000 } );
        _log = context.logManager().getLog(AsyncFortunaStandalone.class);
    }
    
    public void startup() {
        for (int i = 0; i < _bufferCount; i++)
            _emptyBuffers.offer(new AsyncBuffer(_bufferSize));
        _isRunning = true;
        _refillThread = new I2PThread(this, "PRNG");
        _refillThread.setDaemon(true);
        _refillThread.setPriority(Thread.NORM_PRIORITY - 2);
        _refillThread.start();
    }

    /**
     *  Note - methods may hang or NPE or throw IllegalStateExceptions after this
     *  @since 0.8.8
     */
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
    public void seed(byte val[]) {
        Map<String, byte[]> props = Collections.singletonMap(SEED, val);
        init(props);
    }
  
    private static class AsyncBuffer {
        public final byte[] buffer;

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
            if (old != null)
                _emptyBuffers.offer(old);
            AsyncBuffer nextBuffer = null;

            while (nextBuffer == null) {
                if (!_isRunning)
                    throw new IllegalStateException("shutdown");
                try {
                    nextBuffer = _fullBuffers.take();
                } catch (InterruptedException ie) {
                    continue;
                }
            }
            _currentBuffer = nextBuffer;
            buffer = nextBuffer.buffer;
        }
    }
    
    /**
     *  The refiller thread
     */
    public void run() {
        while (_isRunning) {
            AsyncBuffer aBuff = null;
            try {
                aBuff = _emptyBuffers.take();
            } catch (InterruptedException ie) {
                continue;
            }
            
                long before = System.currentTimeMillis();
                doFill(aBuff.buffer);
                long after = System.currentTimeMillis();
                boolean shouldWait = _fullBuffers.size() > 1;
                _fullBuffers.offer(aBuff);
                _context.statManager().addRateData("prng.bufferFillTime", after - before, 0);
                if (shouldWait) {
                    Thread.yield();
                    long waitTime = (after-before)*5;
                    if (waitTime <= 0) // somehow postman saw waitTime show up as negative
                        waitTime = 50;
                    else if (waitTime > 5000)
                        waitTime = 5000;
                    try { Thread.sleep(waitTime); } catch (InterruptedException ie) {}
                }
        }
    }

    @Override
    public void fillBlock()
    {
        rotateBuffer();
    }
    
    private void doFill(byte buf[]) {
        if (pools != null) {
            if (pool0Count >= MIN_POOL_SIZE
                && System.currentTimeMillis() - lastReseed > 100)
              {
                reseedCount++;
                //byte[] seed = new byte[0];
                for (int i = 0; i < NUM_POOLS; i++)
                  {
                    if (reseedCount % (1 << i) == 0) {
                      generator.addRandomBytes(pools[i].digest());
                    }
                  }
                lastReseed = System.currentTimeMillis();
              }
        }  // else we're using DevRandom
        generator.nextBytes(buf);
    }
    
/*****
    public static void main(String args[]) {
        try {
            AsyncFortunaStandalone rand = new AsyncFortunaStandalone(null);  // Will cause NPEs above; fix this if you want to test! Sorry...
            
            byte seed[] = new byte[1024];
            rand.seed(seed);
            System.out.println("Before starting prng");
            rand.startup();
            System.out.println("Starting prng, waiting 1 minute");
            try { Thread.sleep(60*1000); } catch (InterruptedException ie) {}
            System.out.println("PRNG started, beginning test");

            long before = System.currentTimeMillis();
            byte buf[] = new byte[1024];
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            java.util.zip.GZIPOutputStream gos = new java.util.zip.GZIPOutputStream(baos);
            for (int i = 0; i < 1024; i++) {
                rand.nextBytes(buf);
                gos.write(buf);
            }
            long after = System.currentTimeMillis();
            gos.finish();
            byte compressed[] = baos.toByteArray();
            System.out.println("Compressed size of 1MB: " + compressed.length + " took " + (after-before));
        } catch (Exception e) { e.printStackTrace(); }
        try { Thread.sleep(5*60*1000); } catch (InterruptedException ie) {}
    }
*****/
}
