package net.i2p.data;

import net.i2p.I2PAppContext;
import net.i2p.util.SimpleByteCache;
import net.i2p.util.SystemVersion;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReferenceArray;

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
 *
 *  private static final SDSCache&lt; Foo&gt; _cache = new SDSCache(Foo.class, LENGTH, 1024);
 *
 *  public static Foo create(byte[] data) {
 *  return _cache.get(data);
 *  }
 *
 *  public static Foo create(byte[] data, int off) {
 *  return _cache.get(data, off);
 *  }
 *
 *  public static Foo create(InputStream in) throws IOException {
 *  return _cache.get(in);
 *  }
 *
 *  </pre>
 *
 *  @author zzz
 *
 *  @param <V> type of SimpleDataStructure objects cached
 *  @since 0.8.3
 */
public class SDSCache<V extends SimpleDataStructure> {

    private static final double MIN_FACTOR = 0.20;
    private static final double MAX_FACTOR = 5.0;
    private static final double FACTOR;

    static {
        long maxMemory = SystemVersion.getMaxMemory();
        FACTOR = Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, maxMemory / (128 * 1024 * 1024d)));
    }

    /** The cache. */
    private final IntWeakCache<V> _cache;

    /** The byte array length for the class we are caching. */
    private final int _datalen;

    /** The constructor for the class we are caching. */
    private final Constructor<V> _rvCon;

    /**
     *  Class that we are storing, i.e. an extension of SimpleDataStructure.
     *  @param rvClass the class that we are storing, i.e. an extension of SimpleDataStructure
     *  @param len the length of the byte array in the SimpleDataStructure
     *  @param max maximum size of the cache assuming 128MB of mem.
     *             The actual max size will be scaled based on available memory.
     */
    public SDSCache(Class<V> rvClass, int len, int max) {
        int size = (int) (max * FACTOR);
        _cache = new IntWeakCache<>(size);
        _datalen = len;
        try {
            _rvCon = rvClass.getConstructor(byte[].class);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("SDSCache init error", e);
        }
        I2PAppContext.getGlobalContext().addShutdownTask(new Shutdown(this));
    }

    /**
     * @since 0.8.8
     */
    private static class Shutdown implements Runnable {
        private final SDSCache _cache;
        Shutdown(SDSCache cache) { _cache = cache; }
        /** Clear the cache on shutdown. */
        @Override
        public void run() {
            _cache.clear();
        }
    }

    /**
     * Clear all entries from the cache.
     *
     * @since 0.9.17
     */
    public void clear() {
        _cache.clear();
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
     *
     *  @throws IllegalArgumentException if data is not the correct number of bytes
     *  @throws NullPointerException if data is null
     */
    public V get(byte[] data) {
        if (data == null) throw new NullPointerException("Don't pull null data from the cache");
        V rv;
        int key = hashCodeOf(data);
        WeakReference<V> ref = _cache.get(key);
        if (ref != null) rv = ref.get();
        else rv = null;
        if (rv != null && Arrays.equals(data, rv.getData())) {
            // found it, we don't need the data passed in any more
            SimpleByteCache.release(data);
        } else {
            // make a new one
            try {
                rv = _rvCon.newInstance(new Object[] {data});
            } catch (InstantiationException e) {
                throw new RuntimeException("SDSCache error", e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("SDSCache error", e);
            } catch (InvocationTargetException e) {
                throw new RuntimeException("SDSCache error", e);
            }
            _cache.put(key, new WeakReference<>(rv));
        }
        return rv;
    }

    /**
     *  Non-null byte array containing the data, data will be copied to not hold the reference.
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

    /**
     *  Stream from which the bytes will be read.
     *  @param in a stream from which the bytes will be read
     *  @return the cached value if available, otherwise
     *          makes a new object and returns it
     *  @throws IOException if not enough bytes
     */
    public V get(InputStream in) throws IOException {
        byte[] data = SimpleByteCache.acquire(_datalen);
        int read = DataHelper.read(in, data);
        if (read != _datalen) throw new EOFException("Not enough bytes to read the data");
        return get(data);
    }

    /** We assume the data has enough randomness in it, so use the first 4 bytes for speed. */
    private static int hashCodeOf(byte[] data) {
        int rv = data[0];
        for (int i = 1; i < 4; i++) rv ^= (data[i] << (i * 8));
        return rv;
    }

    /**
     *  An int-keyed, weak-value cache, replacing a
     *  ConcurrentHashMap&lt;Integer, WeakReference&lt;V&gt;&gt; so the hot lookup
     *  neither hashes an Integer nor boxes the key - the first 4 bytes of a
     *  SimpleDataStructure almost always fall outside the Integer cache.
     *
     *  <p>Occupancy is marked by the AtomicReferenceArray rather than by the key,
     *  since every int is a legal key (a hash of ff ff ff ff is -1). Its
     *  release/acquire ordering also means a reader that sees a reference sees
     *  its key and everything the writer did before publishing it.
     *
     *  <p>As with the map it replaces, a collected value keeps its slot until the
     *  key is written again, so the table grows with the number of distinct keys
     *  seen rather than with the number of live entries.
     *
     *  @param <V> type of object cached
     *  @since 0.9.72
     */
    private static final class IntWeakCache<V> {

        /** Largest table allocated up front, however big the caller expects the cache to get. */
        private static final int MAX_INITIAL_CAPACITY = 1024;

        /** Replaced wholesale (never resized in place) so a reader always sees a complete table. */
        private static final class Table {
            private final int mask;
            private final int[] keys;
            private final AtomicReferenceArray<WeakReference<?>> refs;

            Table(int capacity) {
                keys = new int[capacity];
                refs = new AtomicReferenceArray<>(capacity);
                mask = capacity - 1;
            }
        }

        private volatile Table _table;

        /** Occupied slots in the current table. Guarded by this. */
        private int _size;

        IntWeakCache(int expectedSize) {
            int capacity = 16;
            while (capacity < expectedSize && capacity < MAX_INITIAL_CAPACITY) capacity <<= 1;
            _table = new Table(capacity);
        }

        /** Mixes the key, so that nearby keys land in different slots. */
        private static int slot(int key, int mask) {
            int h = key * 0x9E3779B1;
            h ^= h >>> 16;
            return h & mask;
        }

        /**
         *  @param key the 4-byte index
         *  @return the reference for the key, or null. A slot whose key is
         *          published but whose reference is not yet reads as empty, so a
         *          reader racing a put may see a miss; the put holds the lock.
         */
        @SuppressWarnings("unchecked")
        WeakReference<V> get(int key) {
            Table t = _table;
            int mask = t.mask;
            int idx = slot(key, mask);
            while (true) {
                WeakReference<?> ref = t.refs.get(idx);
                if (ref == null) return null;
                if (t.keys[idx] == key) return (WeakReference<V>) ref;
                idx = (idx + 1) & mask;
            }
        }

        /**
         *  Store a reference, replacing any existing entry for the key.
         *  Only misses reach here, so this is off the hot path.
         *
         *  @param key the 4-byte index
         *  @param ref the value
         */
        synchronized void put(int key, WeakReference<V> ref) {
            Table t = _table;
            int idx = slot(key, t.mask);
            while (t.refs.get(idx) != null) {
                if (t.keys[idx] == key) {
                    t.refs.set(idx, ref);
                    return;
                }
                idx = (idx + 1) & t.mask;
            }
            // keep the table at most half full, so probes stay short
            if (_size * 2 >= t.keys.length) {
                t = grow();
                idx = slot(key, t.mask);
                while (t.refs.get(idx) != null) idx = (idx + 1) & t.mask;
            }
            t.keys[idx] = key;
            _size++;
            t.refs.set(idx, ref);
        }

        /** Double the table. Callers must synchronize on this. */
        private Table grow() {
            Table old = _table;
            Table t = new Table(old.keys.length * 2);
            for (int i = 0; i < old.keys.length; i++) {
                WeakReference<?> ref = old.refs.get(i);
                if (ref == null) continue;
                int key = old.keys[i];
                int idx = slot(key, t.mask);
                while (t.refs.get(idx) != null) idx = (idx + 1) & t.mask;
                t.keys[idx] = key;
                t.refs.set(idx, ref);
            }
            _table = t;
            return t;
        }

        /** Drop all entries. */
        void clear() {
            synchronized (this) {
                _table = new Table(16);
                _size = 0;
            }
        }
    }
}
