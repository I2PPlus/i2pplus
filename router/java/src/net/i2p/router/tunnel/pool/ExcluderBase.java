package net.i2p.router.tunnel.pool;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.i2p.data.Hash;

/**
 *  A Set of Hashes that automatically adds to the
 *  Set in the contains() check.
 *
 *  So we don't need to generate the exclude set up front.
 *  Less object churn and copying.
 *
 *  @since 0.9.58
 */
abstract class ExcluderBase implements Set<Hash> {
    /**
     * s.
     */
    protected final Set<Hash> s;

    /**
     *  Maps peer hash to the reason it was excluded, for diagnostic logging.
     *  Populated by subclasses via {@link #recordExclusion}.
     *  @since 0.9.70+
     */
    protected final Map<Hash, String> _reasons = new LinkedHashMap<Hash, String>();

    /**
     *  Automatically check if peer is connected
     *  and add the Hash to the set if not.
     *
     *  @param set not copied, contents will be modified by all methods
     */
    protected ExcluderBase(Set<Hash> set) {s = set;}

    /**
     *  Automatically check if peer is allowed
     *  and add the Hash to the set if not.
     *
     *  @param o a Hash
     *  @return true if peer should be excluded
     */
    @Override
    public abstract boolean contains(Object o);
    /**
     * add.
     */
    public boolean add(Hash h) {return s.add(h);}
    /**
     * addAll.
     */
    public boolean addAll(Collection<? extends Hash> c) {return s.addAll(c);}
    /**
     * clear.
     */
    public void clear() {s.clear();}
    /**
     * containsAll.
     */
    public boolean containsAll(Collection<?> c) {return s.containsAll(c);}
    /**
     * equals.
     */
    public boolean equals(Object o) {return s.equals(o);}
    /**
     * @return whether h code is present
     */
    public int hashCode() {return s.hashCode();}
    /**
     * @return whether empty
     */
    public boolean isEmpty() {return s.isEmpty();}
    /**
     * iterator.
     */
    public Iterator<Hash> iterator() {return s.iterator();}
    /**
     * remove.
     */
    public boolean remove(Object o) {return s.remove(o);}
    /**
     * removeAll.
     */
    public boolean removeAll(Collection<?> c) {return s.removeAll(c);}
    /**
     * retainAll.
     */
    public boolean retainAll(Collection<?> c) {return s.retainAll(c);}
    /**
     * size.
     */
    public int size() {return s.size();}
    /**
     * toArray.
     */
    public Object[] toArray() {return s.toArray();}
    /**
     * toArray.
     */
    public <Hash> Hash[] toArray(Hash[] a) {return s.toArray(a);}

    /**
     *  Record why a peer was excluded, for diagnostic logging.
     *  Each peer is recorded once; subsequent calls for the same peer
     *  overwrite the earlier reason.
     *
     *  @param h the excluded peer
     *  @param reason short reason string like "unreachable" or "not-ibgw"
     *  @since 0.9.70+
     */
    protected void recordExclusion(Hash h, String reason) {
        _reasons.put(h, reason);
    }

    /**
     *  Format exclusion summary grouped by reason (counts only, no peer hashes).
     *  Uses raw reason strings from {@link #_reasons}.
     *
     *  @return string like "128 excluded \n* Reason: 50 unreachable, 30 not-ibgw"
     *  @since 0.9.70+
     */
    protected String getReasonsSummary() {
        if (_reasons.isEmpty()) {return "";}
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (String r : _reasons.values()) {
            Integer c = counts.get(r);
            counts.put(r, c != null ? c + 1 : 1);
        }
        StringBuilder sb = new StringBuilder(64);
        sb.append(s.size()).append(" excluded \n* Reason: ");
        boolean first = true;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (!first) {sb.append(", ");}
            sb.append(e.getValue()).append(' ').append(e.getKey());
            first = false;
        }
        return sb.toString();
    }

    /**
     * toString.
     */
    @Override
    public String toString() {
         return getClass().getSimpleName() + " (" + s.size() + ") " + (s.size() <= 10 ? s.toString() : "");
    }

}
