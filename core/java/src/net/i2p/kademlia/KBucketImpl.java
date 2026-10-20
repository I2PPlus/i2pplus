package net.i2p.kademlia;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 */

import net.i2p.I2PAppContext;
import net.i2p.data.SimpleDataStructure;
import net.i2p.util.ConcurrentHashSet;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A concurrent implementation using {@link ConcurrentHashSet}.
 * The max size (K) may be temporarily exceeded due to concurrency,
 * a pending split, or the behavior of the supplied trimmer,
 * as explained below.
 * The creator is responsible for splits.
 * <p>
 * This class has no knowledge of the DHT base used for XORing,
 * and thus there are no validity checks in add/remove.
 * <p>
 * The begin and end values are immutable.
 * All entries in this bucket will have at least one bit different
 * from us in the range [begin, end] inclusive.
 * Splits must be implemented by creating two new buckets
 * and discarding this one.
 * <p>
 * The keys are kept in a Set and are NOT sorted by last-seen.
 * Per-key last-seen-time, failures, etc. must be tracked elsewhere.
 * <p>
 * If this bucket is full (i.e. begin == end &amp;&amp; size &gt;= max)
 * then add() will call {@link net.i2p.kademlia.KBucketTrimmer#trim} do
 * (possibly) remove older entries, and indicate whether
 * to add the new entry. If the trimmer returns true without
 * removing entries, this KBucket will exceed the max size.
 * <p>
 * A newcomer rejected by a full bucket is not thrown away but held in a
 * small Kademlia replacement cache and promoted into the bucket when a
 * slot frees up, so live candidates survive transient bucket pressure
 * instead of needing to be re-discovered.
 * <p>
 * Refactored from net.i2p.router.networkdb.kademlia.
 *
 * @param <T> type of SimpleDataStructure objects stored in this bucket
 * @since 0.9.2 in i2psnark, moved to core in 0.9.10
 */
class KBucketImpl<T extends SimpleDataStructure> implements KBucket<T> {
    /**
     * The peers in the kbucket, stored in a set.
     */
    private final Set<T> _entries;

    /** Include if any bits equal or higher to this bit (in big endian order). */
    private final int _begin;

    /** Include if no bits higher than this bit (inclusive) are set. */
    private final int _end;

    private final int _max;
    private final KBucketTrimmer<T> _trimmer;

    /**
     * Kademlia replacement cache — newcomers rejected by a full bucket,
     * insertion-ordered, oldest dropped when over capacity. Guarded by its
     * own monitor; never exposed outside this class except for the test hook.
     */
    private final LinkedHashSet<T> _replacements;

    /** Replacement cache capacity, bounded so 2048 buckets stay cheap. */
    private final int _replacementMax;

    /** When did we last shake things up. */
    private long _lastChanged;

    private final I2PAppContext _context;

    /**
     * All entries in this bucket will have at least one bit different
     * from us in the range [begin, end] inclusive.
     *
     * @param context the router context
     * @param begin the range begin
     * @param end the range end
     * @param max the maximum entries per bucket
     * @param trimmer the trim strategy
     */
    public KBucketImpl(I2PAppContext context, int begin, int end, int max, KBucketTrimmer<T> trimmer) {
        if (begin > end) throw new IllegalArgumentException(begin + " > " + end);
        _context = context;
        _entries = new ConcurrentHashSet<>(max + 4);
        _begin = begin;
        _end = end;
        _max = max;
        _trimmer = trimmer;
        _replacementMax = Math.min(16, max);
        _replacements = new LinkedHashSet<>(4);
    }

    @Override
    public int getRangeBegin() {
        return _begin;
    }

    @Override
    public int getRangeEnd() {
        return _end;
    }

    @Override
    public int getKeyCount() {
        return _entries.size();
    }

    /**
     * Unmodifiable view of the entries in this bucket.
     *
     * @return an unmodifiable view; not a copy
     */
    @Override
    public Set<T> getEntries() {
        return Collections.unmodifiableSet(_entries);
    }

    @Override
    public void getEntries(SelectionCollector<T> collector) {
        for (T h : _entries) {
            collector.add(h);
        }
    }

    @Override
    public void clear() {
        _entries.clear();
        synchronized (_replacements) {
            _replacements.clear();
        }
    }

    /**
     * Updates last-changed if rv is true OR if the peer is already present.
     * Calls the trimmer if begin == end and we are full.
     * If the trimmer rejects the newcomer it is kept in the replacement
     * cache for promotion when a slot frees up.
     * If begin != end then add it and caller must do bucket splitting.
     *
     * @return true if added
     */
    @Override
    public boolean add(T peer) {
        if (_begin != _end || _entries.size() < _max || _entries.contains(peer) || _trimmer.trim(this, peer)) {
            // do this even if already contains, to call setLastChanged()
            boolean rv = _entries.add(peer);
            setLastChanged();
            if (rv) {
                // no longer a candidate, it is in
                synchronized (_replacements) {
                    _replacements.remove(peer);
                }
            }
            return rv;
        }
        offerReplacement(peer);
        return false;
    }

    /**
     * Remove the peer from this bucket. If a slot frees up, the newest
     * replacement candidate (if any) is promoted into the bucket.
     *
     * @return if removed. Does NOT set lastChanged.
     */
    @Override
    public boolean remove(T peer) {
        boolean rv = _entries.remove(peer);
        //    setLastChanged();
        if (rv && _entries.size() < _max) {
            T promoted = pollReplacement();
            if (promoted != null) {
                _entries.add(promoted);
            }
        }
        return rv;
    }

    /**
     * Hold a rejected newcomer as a future slot-filler, dropping the oldest
     * candidate when over capacity.
     */
    private void offerReplacement(T peer) {
        synchronized (_replacements) {
            if (_entries.contains(peer)) {return;}
            _replacements.add(peer);
            if (_replacements.size() > _replacementMax) {
                Iterator<T> iter = _replacements.iterator();
                iter.next();
                iter.remove();
            }
        }
    }

    /**
     * Take the newest replacement candidate, or null if the cache is empty.
     */
    private T pollReplacement() {
        synchronized (_replacements) {
            if (_replacements.isEmpty()) {return null;}
            T newest = null;
            // insertion order — the last one is the most recent arrival
            for (T t : _replacements) {newest = t;}
            _replacements.remove(newest);
            return newest;
        }
    }

    /**
     * Number of held replacement candidates. Package private for testing.
     *
     * @return the count of candidates currently in the replacement cache
     */
    int getReplacementCount() {
        synchronized (_replacements) {
            return _replacements.size();
        }
    }

    /**
     * Update the last-changed timestamp to now.
     */
    @Override
    public void setLastChanged() {
        _lastChanged = _context.clock().now();
    }

    /**
     * The last-changed timestamp, which actually indicates last-added or last-seen.
     * @return the last changed
     */
    @Override
    public long getLastChanged() {
        return _lastChanged;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(1024); // NOPMD - AvoidUnnecessaryStringBuilderCreation
        buf.append(_entries.size());
        buf.append(" entries in (").append(_begin);
        if (_end != _begin) buf.append('-').append(_end);
        buf.append(") : ").append(_entries.toString());
        return buf.toString();
    }
}
