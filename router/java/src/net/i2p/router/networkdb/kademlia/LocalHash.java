package net.i2p.router.networkdb.kademlia;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;

/**
 * Pull the caching used only by KBucketImpl out of Hash and put it here.
 *
 * @since 0.7.14
 * @author jrandom
 * @author moved from Hash.java by zzz
 */
class LocalHash extends Hash {
    //private final static Log _log = new Log(LocalHash.class);
    private /* FIXME final FIXME */ Map<Hash, byte[]> _xorCache;

    private static final int MAX_CACHED_XOR = 1024;

    public LocalHash(Hash h) {
        super(h.getData());
    }

    public LocalHash(byte[] b) {
        super(b);
    }

    /**
     * Prepare this hash's cache for xor values - very few hashes will need it,
     * so we don't want to waste the memory, and lazy initialization would incur
     * online overhead to verify the initialization.
     *
     */
    public void prepareCache() {
        synchronized (this) {
            if (_xorCache == null)
                _xorCache = new HashMap<Hash, byte[]>(MAX_CACHED_XOR);
        }
    }

    /**
     * Calculate the xor with the current object and the specified hash,
     * caching values where possible.  Currently this keeps up to MAX_CACHED_XOR
     * (1024) entries, and uses an essentially random ejection policy.  Later
     * perhaps go for an LRU or FIFO?
     *
     * @throws IllegalStateException if you try to use the cache without first
     *                               preparing this object's cache via .prepareCache()
     */
    public byte[] cachedXor(Hash key) throws IllegalStateException {
        if (_xorCache == null)
            throw new IllegalStateException("To use the cache, you must first prepare it");
        byte[] distance = _xorCache.get(key);

        if (distance == null) {
            synchronized (_xorCache) {
                int toRemove = _xorCache.size() + 1 - MAX_CACHED_XOR;
                if (toRemove > 0) {
                    Set<Hash> keys = new HashSet<Hash>(toRemove);
                    // this removes essentially random keys - we don't maintain any sort
                    // of LRU or age.  perhaps we should?
                    int removed = 0;
                    for (Iterator<Hash> iter = _xorCache.keySet().iterator(); iter.hasNext() && removed < toRemove; removed++)
                        keys.add(iter.next());
                    for (Iterator<Hash> iter = keys.iterator(); iter.hasNext(); )
                        _xorCache.remove(iter.next());
                }
                distance = DataHelper.xor(key.getData(), getData());
                _xorCache.put(key, distance);
            }
        }
        return distance;
    }

    public void clearXorCache() {
        synchronized (_xorCache) {
            _xorCache.clear();
        }
    }

}
