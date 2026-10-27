package net.i2p.router.tunnel.pool;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.i2p.data.Hash;

import net.i2p.router.util.PeerLogFormatter;

/**
 * A Set of Hashes that automatically adds to the
 * Set in the {@link #contains} check.
 * <p>
 * So we don't need to generate the exclude set up front.
 * Less object churn and copying.
 *
 * @since 0.9.58
 */
abstract class ExcluderBase implements Set<Hash> {
    /**
     * The underlying set of excluded hashes.
     */
    protected final Set<Hash> s;

    /**
     * Maps peer hash to the reason it was excluded, for diagnostic logging.
     * Populated by subclasses via {@link #recordExclusion}.
     * @since 0.9.70+
     */
    protected final Map<Hash, String> _reasons = new LinkedHashMap<Hash, String>();

    /**
     * Automatically check if peer is connected
     * and add the Hash to the set if not.
     *
     * @param set not copied, contents will be modified by all methods
     */
    protected ExcluderBase(Set<Hash> set) {s = set;}

    /**
     * Automatically check if peer is allowed
     * and add the Hash to the set if not.
     *
     * @param o a peer hash to look up in the underlying set
     * @return true if peer should be excluded
     */
    @Override
    public abstract boolean contains(Object o);
    /**
     * Add the hash to the underlying set.
     */
    public boolean add(Hash h) {return s.add(h);}
    /**
     * Add all of the hashes to the underlying set.
     */
    public boolean addAll(Collection<? extends Hash> c) {return s.addAll(c);}
    /**
     * Remove all hashes from the underlying set.
     */
    public void clear() {s.clear();}
    /**
     * Whether the underlying set contains all of the given hashes.
     */
    public boolean containsAll(Collection<?> c) {return s.containsAll(c);}
    /**
     * Whether the underlying set equals the given object.
     */
    public boolean equals(Object o) {return s.equals(o);}
    /**
     * The hash code of the underlying set.
     *
     * @return the hash code
     */
    public int hashCode() {return s.hashCode();}
    /**
     * Whether the underlying set is empty.
     *
     * @return true if nothing is currently excluded
     */
    public boolean isEmpty() {return s.isEmpty();}
    /**
     * An iterator over the underlying set.
     */
    public Iterator<Hash> iterator() {return s.iterator();}
    /**
     * Remove the hash from the underlying set.
     */
    public boolean remove(Object o) {return s.remove(o);}
    /**
     * Remove all of the given hashes from the underlying set.
     */
    public boolean removeAll(Collection<?> c) {return s.removeAll(c);}
    /**
     * Retain only the given hashes in the underlying set.
     */
    public boolean retainAll(Collection<?> c) {return s.retainAll(c);}
    /**
     * The number of hashes in the underlying set.
     */
    public int size() {return s.size();}
    /**
     * The hashes of the underlying set as an array.
     */
    public Object[] toArray() {return s.toArray();}
    /**
     * The hashes of the underlying set in the given array.
     */
    public <Hash> Hash[] toArray(Hash[] a) {return s.toArray(a);}

    /**
     * Record why a peer was excluded, for diagnostic logging.
     * Each peer is recorded once; subsequent calls for the same peer
     * overwrite the earlier reason.
     *
     * @param h the excluded peer
     * @param reason short reason string like "unreachable" or "not-ibgw"
     * @since 0.9.70+
     */
    protected void recordExclusion(Hash h, String reason) {
        _reasons.put(h, reason);
    }

    /**
     * Format the exclusion summary as a flat tally grouped by reason, counts
     * only, no peer hashes - the grouped form that names the peers is
     * {@link TunnelPeerSelector.Excluder#formatByReasonWithPeers}. Reason keys
     * are mapped through the shared display labels.
     *
     * @return "\n* Excluded: N" then the reason tally, empty when nothing is
     *         excluded
     * @since 0.9.70+
     */
    protected String getReasonsSummary() {
        if (_reasons.isEmpty()) {return "";}
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (String r : _reasons.values()) {
            Integer c = counts.get(r);
            counts.put(r, c != null ? c + 1 : 1);
        }
        return PeerLogFormatter.reasonsTally(counts);
    }

    /**
     * Description of the excluded set and its size.
     */
    @Override
    public String toString() {
        return getClass().getSimpleName() + " (" + s.size() + ") " + (s.size() <= 10 ? s.toString() : "");
    }

}
