package net.i2p.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/**
 * Non-thread-safe counter for tracking occurrences of objects.
 * 
 * <p>This class provides a lightweight alternative to ObjectCounter for
 * single-threaded scenarios. It offers better performance with reduced
 * object churn by using primitive integers instead of AtomicInteger.</p>
 * 
 * <p><strong>Key differences from ObjectCounter:</strong></p>
 * <ul>
 * <li>NOT thread-safe - use only in single-threaded contexts</li>
 * <li>Lower memory overhead and better performance</li>
 * <li>Additional add() method for incrementing by arbitrary values</li>
 * <li>sortedObjects() method for retrieving objects by count</li>
 * </ul>
 * 
 * <p>Typical use cases include UI components and Sybil attack detection
 * where thread safety is not required.</p>
 *
 * @since 0.9.58
 * @param <K> type of objects being counted
 */
public class ObjectCounterUnsafe<K> {
    private final HashMap<K, Int> map = new HashMap<K, Int>();

    /**
     *  Add one.
     *  @return count after increment
     */
    public int increment(K h) {
        Int i = map.get(h);
        if (i != null) {
            return ++(i.c);
        }
        map.put(h, new Int(1));
        return 1;
    }

    /**
     *  Add a value
     *  @return count after adding
     */
    public int add(K h, int val) {
        Int i = map.get(h);
        if (i != null) {
            i.c += val;
            return i.c;
        }
        map.put(h, new Int(val));
        return val;
    }

    /**
     *  @return current count
     */
    public int count(K h) {
        Int i = map.get(h);
        if (i != null)
            return i.c;
        return 0;
    }

    /**
     *  @return set of objects with counts &gt; 0
     */
    public Set<K> objects() {
        return map.keySet();
    }

    /**
     *  @return list of objects reverse sorted by count, highest to lowest
     */
    public List<K> sortedObjects() {
        List<K> rv = new ArrayList<K>(map.keySet());
        Collections.sort(rv, new ObjComparator());
        return rv;
    }

    /**
     *  Start over. Reset the count for all keys to zero.
     */
    public void clear() {
        map.clear();
    }

    /**
     *  Reset the count for this key to zero
     */
    public void clear(K h) {
        map.remove(h);
    }

    /**
     *  Modifiable integer
     */
    private static class Int {
        int c;
        public Int(int i) { c = i; }
    }

    /**
     *  reverse sort
     */
    private class ObjComparator implements Comparator<K> {
        public int compare(K l, K r) {
            int rv = map.get(r).c - map.get(l).c;
            if (rv != 0)
                return rv;
            return l.toString().compareTo(r.toString());
        }
    }
}

