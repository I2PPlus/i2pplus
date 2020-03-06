package net.i2p.data;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.util.LHMCache;
import net.i2p.util.SimpleByteCache;
import net.i2p.util.SystemVersion;

/**
 *  A least recently used cache with a max size, for SimpleDataStructures.
 *  The index to the cache is the first 4 bytes of the data, so
 *  the data must be sufficiently random.
 *
 *  This caches the SDS objects, and also uses SimpleByteCache to cache
 *  the unused byte arrays themselves
 *
 *  Following is sample usage:
 *  <pre>

    private static final SDSCache&lt;Foo&gt; _cache = new SDSCache(Foo.class, LENGTH, 1024);

    public static Foo create(byte[] data) {
        return _cache.get(data);
    }

    public static Foo create(byte[] data, int off) {
        return _cache.get(data, off);
    }

    public static Foo create(InputStream in) throws IOException {
        return _cache.get(in);
    }

 *  </pre>
 *  @since 0.8.3
 *  @author zzz
 */
public class SDSCache<V extends SimpleDataStructure> {
    //private static final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(SDSCache.class);

    private static final double MIN_FACTOR = 0.20;
    private static final double MAX_FACTOR = 5.0;
    private static final double FACTOR;
    static {
        long maxMemory = SystemVersion.getMaxMemory();
        FACTOR = Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, maxMemory / (128*1024*1024d)));
    }

    /** the LRU cache */
    private final Map<Integer, WeakReference<V>> _cache;
    /** the byte array length for the class we are caching */
    private final int _datalen;
    /** the constructor for the class we are caching */
    private final Constructor<V> _rvCon;
    private final String _statName;

    /**
     *  @param rvClass the class that we are storing, i.e. an extension of SimpleDataStructure
     *  @param len the length of the byte array in the SimpleDataStructure
     *  @param max maximum size of the cache assuming 128MB of mem.
     *             The actual max size will be scaled based on available memory.
     */
    public SDSCache(Class<V> rvClass, int len, int max) {
        int size = (int) (max * FACTOR);
        _cache = new LHMCache<Integer, WeakReference<V>>(size);
        _datalen = len;
        try {
            _rvCon = rvClass.getConstructor(byte[].class);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("SDSCache init error", e);
        }
        _statName = "SDSCache." + rvClass.getSimpleName();
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("New SDSCache for " + rvClass + " data size: " + len +
        //               " max: " + size + " max mem: " + (len * size));
        I2PAppContext.getGlobalContext().statManager().createRateStat(_statName, "Hit rate", "Router [SDSCache]", new long[] { 10*60*1000 });
        I2PAppContext.getGlobalContext().addShutdownTask(new Shutdown());
    }

    /**
     * @since 0.8.8
     */
    private class Shutdown implements Runnable {
        public void run() {
            clear();
        }
    }

    /**
     * @since 0.9.17
     */
    public void clear() {
        synchronized(_cache) {
            _cache.clear();
        }
    }

    /**
     *  WARNING - If the SDS is found in the cache, the passed-in
     *  byte array will be returned to the SimpleByteCache for reuse.
     *  Do NOT save a reference to the passed-in data, or use or modify it,
     *  after this call.
     *
     *  @param data non-null, the byte array for the SimpleDataStructure
     *  @return the cached value if available, otherwise
     *          makes a new object and returns it
     *  @throws IllegalArgumentException if data is not the correct number of bytes
     *  @throws NullPointerException
     */
    public V get(byte[] data) {
        if (data == null)
            throw new NullPointerException("Don't pull null data from the cache");
        int found;
        V rv;
        Integer key = hashCodeOf(data);
        synchronized(_cache) {
            WeakReference<V> ref = _cache.get(key);
            if (ref != null)
                rv = ref.get();
            else
                rv = null;
            if (rv != null && Arrays.equals(data, rv.getData())) {
                // found it, we don't need the data passed in any more
                SimpleByteCache.release(data);
                found = 1;
            } else {
                // make a new one
                try {
                    rv = _rvCon.newInstance(new Object[] { data } );
                } catch (InstantiationException e) {
                    throw new RuntimeException("SDSCache error", e);
                } catch (IllegalAccessException e) {
                    throw new RuntimeException("SDSCache error", e);
                } catch (InvocationTargetException e) {
                    throw new RuntimeException("SDSCache error", e);
                }
                _cache.put(key, new WeakReference<V>(rv));
               found = 0;
            }
        }
        I2PAppContext.getGlobalContext().statManager().addRateData(_statName, found);
        return rv;
    }

    /*
     *  @param b non-null byte array containing the data, data will be copied to not hold the reference
     *  @param off offset in the array to start reading from
     *  @return the cached value if available, otherwise
     *          makes a new object and returns it
     *  @throws ArrayIndexOutOfBoundsException if not enough bytes
     *  @throws NullPointerException
     */
    public V get(byte[] b, int off) {
        byte[] data = SimpleByteCache.acquire(_datalen);
        System.arraycopy(b, off, data, 0, _datalen);
        return get(data);
    }

    /*
     *  @param in a stream from which the bytes will be read
     *  @return the cached value if available, otherwise
     *          makes a new object and returns it
     *  @throws IOException if not enough bytes
     */
    public V get(InputStream in) throws IOException {
        byte[] data = SimpleByteCache.acquire(_datalen);
        int read = DataHelper.read(in, data);
        if (read != _datalen)
            throw new EOFException("Not enough bytes to read the data");
        return get(data);
    }

    /**
     * We assume the data has enough randomness in it, so use the first 4 bytes for speed.
     */
    private static Integer hashCodeOf(byte[] data) {
        int rv = data[0];
        for (int i = 1; i < 4; i++)
            rv ^= (data[i] << (i*8));
        return Integer.valueOf(rv);
    }
}
