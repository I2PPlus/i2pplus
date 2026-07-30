package net.metanotion.io.block.index;
// License: BSD-3-Clause. See docs/LICENSES.md

import java.io.IOException;
import java.util.NoSuchElementException;
import net.metanotion.util.skiplist.SkipIterator;
import net.metanotion.util.skiplist.SkipSpan;

/**
 * SkipList iterator with on-demand span loading (I2P version).
 *
 * <p>Loads spans from disk only when needed and unloads them
 * when moving to next span to minimize memory usage.</p>
 *
 * <p>Overridden to load span when required and null out keys and values
 * when iterator leaves the span. If caller does not iterate all the way through,
 * the last span will remain in memory.</p>
 *
 * @param <K> type of keys maintained by this iterator
 * @param <V> type of values returned by this iterator
 */
public class IBSkipIterator<K extends Comparable<? super K>, V> extends SkipIterator<K, V> {

    /**
     * Constructor.
     *
     * @param ss the span to iterate from
     * @param index starting position
     */
    public IBSkipIterator(SkipSpan<K, V> ss, int index) {
        super(ss, index);
    }

    /**
     * @return the next value, and advances the index
     * @throws NoSuchElementException
     * @throws RuntimeException on IOE
     */
    @Override
    public V next() {
        V o;
        if(index < ss.nKeys) {
            if (ss.vals == null) {
                try {
                    ((IBSkipSpan)ss).seekAndLoadData();
                } catch (IOException ioe) {
                    throw new RuntimeException("Error in iterator", ioe);
                }
            }
            o = ss.vals[index];
        } else {
            throw new NoSuchElementException();
        }

        if(index < (ss.nKeys-1)) {
            index++;
        } else if(ss.next != null) {
            ss.keys = null;
            ss.vals = null;
            ss = ss.next;
            index = 0;
        } else {
            ss.keys = null;
            ss.vals = null;
            index = ss.nKeys;
        }
        return o;
    }

    /**
     * The key. Does NOT advance the index.
     * @return the key for which the value will be returned in the subsequent call to next()
     * @throws NoSuchElementException
     * @throws RuntimeException on IOE
     */
    @Override
    public K nextKey() {
        if(index < ss.nKeys) {
            if (ss.keys == null) {
                try {
                    ((IBSkipSpan)ss).seekAndLoadData();
                } catch (IOException ioe) {
                    throw new RuntimeException("Error in iterator", ioe);
                }
            }
            return ss.keys[index];
        }
        throw new NoSuchElementException();
    }

    /**
     * @return the previous value, and decrements the index
     * @throws NoSuchElementException
     * @throws RuntimeException on IOE
     */
    @Override
    public V previous() {
        if(index > 0) {
            index--;
        } else if(ss.prev != null) {
            ss.keys = null;
            ss.vals = null;
            ss = ss.prev;
            if(ss.nKeys <= 0) { throw new NoSuchElementException(); }
            index = (ss.nKeys - 1);
        } else {
            ss.keys = null;
            ss.vals = null;
            throw new NoSuchElementException();
        }
        if (ss.vals == null) {
            try {
                ((IBSkipSpan)ss).seekAndLoadData();
            } catch (IOException ioe) {
                throw new RuntimeException("Error in iterator", ioe);
            }
        }
        return ss.vals[index];
    }
}
