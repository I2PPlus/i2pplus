package org.xlattice.crypto.filters;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * A Bloom filter for sets of SHA1 digests.  A Bloom filter uses a set
 * of k hash functions to determine set membership.  Each hash function
 * produces a value in the range 0..M-1.  The filter is of size M.  To
 * add a member to the set, apply each function to the new member and
 * set the corresponding bit in the filter.  For M very large relative
 * to k, this will normally set k bits in the filter.  To check whether
 * x is a member of the set, apply each of the k hash functions to x
 * and check whether the corresponding bits are set in the filter.  If
 * any are not set, x is definitely not a member.  If all are set, x
 * may be a member.  The probability of error (the false positive rate)
 * is f = (1 - e^(-kN/M))^k, where N is the number of set members.
 *
 * This class takes advantage of the fact that SHA1 digests are good-
 * quality pseudo-random numbers.  The k hash functions are the values
 * of distinct sets of bits taken from the 20-byte SHA1 hash.  The
 * number of bits in the filter, M, is constrained to be a power of
 * 2; M == 2^m.  The number of bits in each hash function may not
 * exceed floor(m/k).
 *
 * This class is designed to be thread-safe, but this has not been
 * exhaustively tested.
 *
 * @author <A HREF="mailto:jddixon@users.sourceforge.net">Jim Dixon</A>
 *
 * BloomSHA1.java and KeySelector.java are BSD licensed from the xlattice
 * app - http://xlattice.sourceforge.net/
 *
 * minor tweaks by jrandom, exposing unsynchronized access and
 * allowing larger M and K.  changes released into the public domain.
 *
 * Note that this is used only by DecayingBloomFilter, which uses only
 * the unsynchronized locked_foo() methods.
 * Deprecated for use outside of the router; to be moved to router.jar.
 *
 * As of 0.8.11, the locked_foo() methods are thread-safe, in that they work,
 * but there is a minor risk of false-negatives if two threads are
 * accessing the same bloom filter integer.
 */

public class BloomSHA1 {
    private final int m;
    private final int k;
    private int count;

    private int[] filter;
    private final KeySelector ks;

    // convenience variables
    private final int filterBits;
    private final int filterWords;

    /**
     *  Maximum scratch arrays retained per thread.
     *  @since 0.9.71+
     */
    private static final int POOL_DEPTH = 4;
    /**
     *  Per-thread pool of offset scratch arrays, so that hashing a key does not
     *  allocate its scratch space or contend on a shared queue. Thread
     *  confined, so no locking is needed: a FilterKey is always created and
     *  released by the same thread. Deques rather than single slots so nested
     *  use stays correct. The FilterKey itself is still allocated per
     *  getFilterKey() call, which is a small object holding the two arrays.
     */
    private final ThreadLocal<ArrayDeque<int[]>> bufPool;

    /**
     *  Creates a filter with 2^m bits and k 'hash functions', where
     * each hash function is portion of the 160-bit SHA1 hash.
     *
     * @param m determines number of bits in filter
     * @param k number of hash functions
     *
     * See KeySelector for important restriction on max m and k
     */
    public BloomSHA1( int m, int k) {
        // XXX need to devise more reasonable set of checks
        //if ( m < 2 || m > 20) {
        //    throw new IllegalArgumentException("m out of range");
        //}
        //if ( k < 1 || ( k * m > 160 )) {
        //    throw new IllegalArgumentException(
        //        "too many hash functions for filter size");
        //}
        this.m = m;
        this.k = k;
        filterBits = 1 << m;
        filterWords = (filterBits + 31)/32;     // round up
        filter = new int[filterWords];
        ks = new KeySelector(m, k);
        // sized for the steady state (one bitOffset + one wordOffset in hand);
        // recycle() caps it at POOL_DEPTH, and ArrayDeque grows if it is deeper
        bufPool = ThreadLocal.withInitial(() -> new ArrayDeque<int[]>(2));

        // DEBUG
        //System.out.println("Bloom constructor: m = " + m + ", k = " + k
        //    + "\n    filterBits = " + filterBits
        //    + ", filterWords = " + filterWords);
        // END
    }

    /**
     * Creates a filter of 2^m bits, with the number of 'hash functions"
     * k defaulting to 8.
     * @param m determines size of filter
     */
    public BloomSHA1 (int m) {
        this(m, 8);
    }

    /**
     * Creates a filter of 2^20 bits with k defaulting to 8.
     */
    public BloomSHA1 () {
        this (20, 8);
    }

    /**
     *  Allocate a zeroed bit array of this filter's size, away from whatever
     *  lock protects the filter. Zeroing is the expensive part of a reset, so
     *  doing it here keeps a decay from stalling every concurrent insert.
     *
     *  <p>Pair with {@link #applyReset(Reset)}, which is O(1).
     *
     *  <p>Tradeoff: this allocates a whole filter's worth of array per reset
     *  rather than reusing it, so it costs more total work than an in-place
     *  fill on a cache-resident filter, and leaves the collector a large
     *  short-lived array to account for. That is the right trade at the sizes
     *  where the stall would hurt: measured at m=29 an in-place fill under the
     *  lock runs about 2.6ms against about 1.4ms to allocate, and the ordering
     *  only reverses below m=28, where the whole operation is already under
     *  0.6ms. Reusing the array on a background thread would avoid both costs,
     *  but needs a spare buffer held permanently, which is another 50% of the
     *  filter's memory.
     *
     *  @return a reset token holding a fresh zeroed array
     * @since 0.9.71+
     */
    public Reset prepareReset() {
        return new Reset(new int[filterWords]);
    }

    /**
     *  Install a prepared reset. O(1), because the caller has already paid for
     *  the zeroed array. The caller must hold exclusive access to this filter,
     *  which for the decaying filters means holding their write lock.
     *
     *  @param reset a token from {@link #prepareReset()} for this filter
     *  @throws IllegalArgumentException if the token is not for this filter
     *  @since 0.9.71+
     */
    public void applyReset(Reset reset) {
        if (reset == null || reset.bits.length != filterWords)
            throw new IllegalArgumentException("Reset is not for this filter");
        filter = reset.bits;
        count = 0;
    }

    /**
     * Clears the filter, synchronized.
     *
     * <p>On a latency path prefer {@link #prepareReset()} plus
     * {@link #applyReset(Reset)}, which moves the memset off the lock.
     */
    public void clear() {
        synchronized (this) {
            applyReset(prepareReset());
        }
    }

    /**
     *  A pre-zeroed bit array waiting to be installed, so the memset can happen
     *  away from the lock that guards the filter.
     *
     *  @since 0.9.71+
     */
    public static final class Reset {
        private final int[] bits;

        private Reset(int[] bits) {
            this.bits = bits;
        }
    }

    /**
     * Returns the number of keys which have been inserted.  This
     * class (BloomSHA1) does not guarantee uniqueness in any sense; if the
     * same key is added N times, the number of set members reported
     * will increase by N.
     *
     * @return number of set members
     */
    public final int size() {
        synchronized (this) {
            return count;
        }
    }

    /**
     * Returns the number of bits in the filter.
     *
     * @return number of bits in filter
     */
    public final int capacity () {
        return filterBits;
    }

    /**
     * Add a key to the set represented by the filter.
     *
     * XXX This version does not maintain 4-bit counters, it is not
     * a counting Bloom filter.
     *
     * @param b byte array representing a key (SHA1 digest)
     */
    public void insert (byte[]b) { insert(b, 0, b.length); }

    /**
     * Add a key to the set represented by the filter.
     *
     * XXX This version does not maintain 4-bit counters, it is not
     * a counting Bloom filter.
     *
     * @param b byte array representing a key (SHA1 digest)
     * @param offset starting offset in the byte array
     * @param len number of bytes to use
     */
    public void insert (byte[]b, int offset, int len) {
        synchronized(this) {
            locked_insert(b, offset, len);
        }
    }

    /**
     * Add a key to the filter without synchronization.
     *
     * @param b byte array representing a key (SHA1 digest)
     */
    public final void locked_insert(byte[]b) { locked_insert(b, 0, b.length); }

    /**
     * Add a key to the filter without synchronization.
     *
     * @param b byte array representing a key (SHA1 digest)
     * @param offset starting offset in the byte array
     * @param len number of bytes to use
     */
    public final void locked_insert(byte[]b, int offset, int len) {
        int[] bitOffset = acquire();
        int[] wordOffset = acquire();
        ks.getOffsets(b, offset, len, bitOffset, wordOffset);
        for (int i = 0; i < k; i++) {
            filter[wordOffset[i]] |=  1 << bitOffset[i];
        }
        count++;
        recycle(bitOffset);
        recycle(wordOffset);
    }

    /**
     * Checks if a key is in the filter. Sets up the bit and word offset arrays.
     *
     * @param b byte array representing a key (SHA1 digest)
     * @return true if b is in the filter
     */
    private final boolean isMember(byte[] b) { return isMember(b, 0, b.length); }

    /**
     * Checks if a key is in the filter. Sets up the bit and word offset arrays.
     *
     * @param b byte array representing a key (SHA1 digest)
     * @param offset starting offset in the byte array
     * @param len number of bytes to use
     * @return true if b is in the filter
     */
    private final boolean isMember(byte[] b, int offset, int len) {
        int[] bitOffset = acquire();
        int[] wordOffset = acquire();
        ks.getOffsets(b, offset, len, bitOffset, wordOffset);
        for (int i = 0; i < k; i++) {
            if (! ((filter[wordOffset[i]] & (1 << bitOffset[i])) != 0) ) {
                recycle(bitOffset);
                recycle(wordOffset);
                return false;
            }
        }
        recycle(bitOffset);
        recycle(wordOffset);
        return true;
    }

    /**
     * Checks if a key is in the filter without synchronization.
     *
     * @param b byte array representing a key (SHA1 digest)
     * @return true if b is in the filter
     */
    public final boolean locked_member(byte[]b) { return isMember(b); }

    /**
     * Checks if a key is in the filter without synchronization.
     *
     * @param b byte array representing a key (SHA1 digest)
     * @param offset starting offset in the byte array
     * @param len number of bytes to use
     * @return true if b is in the filter
     */
    public final boolean locked_member(byte[]b, int offset, int len) { return isMember(b, offset, len); }

    /**
     * Is a key in the filter.  External interface, internally synchronized.
     *
     * @param b byte array representing a key (SHA1 digest)
     * @return true if b is in the filter
     */
    public final boolean member(byte[]b) { return member(b, 0, b.length); }

    /**
     * Is a key in the filter.  External interface, internally synchronized.
     *
     * @param b byte array representing a key (SHA1 digest)
     * @param offset starting offset in the byte array
     * @param len number of bytes to use
     * @return true if b is in the filter
     */
    public final boolean member(byte[]b, int offset, int len) {
        synchronized (this) {
            return isMember(b, offset, len);
        }
    }

    /**
     * Get the bloom filter offsets for reuse.
     * Caller should call release(rv) when done with it.
     *
     * @param b byte array representing a key (SHA1 digest)
     * @param offset starting offset in the byte array
     * @param len number of bytes to use
     * @return filter key containing the offsets
     * @since 0.8.11
     */
    public FilterKey getFilterKey(byte[] b, int offset, int len) {
        int[] bitOffset = acquire();
        int[] wordOffset = acquire();
        ks.getOffsets(b, offset, len, bitOffset, wordOffset);
        return new FilterKey(bitOffset, wordOffset);
    }

    /**
     * Add the key to the filter.
     *
     * @param fk filter key containing offsets
     * @since 0.8.11
     */
    public void locked_insert(FilterKey fk) {
        for (int i = 0; i < k; i++) {
            filter[fk.wordOffset[i]] |=  1 << fk.bitOffset[i];
        }
        count++;
    }


    /**
     * Is the key in the filter.
     *
     * @param fk filter key containing offsets
     * @return true if the key is in the filter
     * @since 0.8.11
     */
    public boolean locked_member(FilterKey fk) {
        for (int i = 0; i < k; i++) {
            if (! ((filter[fk.wordOffset[i]] & (1 << fk.bitOffset[i])) != 0) )
                return false;
        }
        return true;
    }

    /**
     * Acquires offset arrays from the buffer pool.
     *
     * @return int array of size k containing bit offsets
     * @since 0.8.11
     */
    private int[] acquire() {
        int[] rv = bufPool.get().pollFirst();
        if (rv != null)
            return rv;
        return new int[k];
    }

    /**
     * Returns a scratch array to this thread's pool, dropping it if the pool
     * is already full so that a burst cannot retain an unbounded number.
     *
     * @param arr the array to recycle
     * @since 0.9.71+
     */
    private void recycle(int[] arr) {
        ArrayDeque<int[]> pool = bufPool.get();
        if (pool.size() < POOL_DEPTH)
            pool.addLast(arr);
    }

    /**
     * Releases filter key arrays back to the buffer pool.
     *
     * @param fk filter key to release
     * @since 0.8.11
     */
    public void release(FilterKey fk) {
        recycle(fk.bitOffset);
        recycle(fk.wordOffset);
    }

    /**
     * Stores the (opaque) bloom filter offsets for reuse.
     *
     * @since 0.8.11
     */
    public static class FilterKey {

        private final int[] bitOffset;
        private final int[] wordOffset;

        private FilterKey(int[] bitOffset, int[] wordOffset) {
            this.bitOffset = bitOffset;
            this.wordOffset = wordOffset;
        }
    }

    /**
     * Calculates the approximate false positive rate for a given number of set members.
     *
     * @param n number of set members
     * @return approximate false positive rate
     */
    public final double falsePositives(int n) {
        // (1 - e(-kN/M))^k
        return java.lang.Math.pow (
                (1L - java.lang.Math.exp(0d- ((double)k) * (long)n / filterBits)), k);
    }

    /**
     * Calculates the approximate false positive rate for the current number of set members.
     *
     * @return approximate false positive rate
     */
    public final double falsePositives() {
        return falsePositives(count);
    }

 /*****
    // DEBUG METHODS
    public static String keyToString(byte[] key) {
        StringBuilder sb = new StringBuilder().append(key[0]);
        for (int i = 1; i < key.length; i++) {
            sb.append(".").append(Integer.toString(key[i], 16));
        }
        return sb.toString();
    }
 *****/

    /** convert 64-bit integer to hex String */
/*****
    public static String ltoh (long i) {
        StringBuilder sb = new StringBuilder().append("#")
                                .append(Long.toString(i, 16));
        return sb.toString();
    }
 *****/

    /** convert 32-bit integer to String */
/*****
    public static String itoh (int i) {
        StringBuilder sb = new StringBuilder().append("#")
                                .append(Integer.toString(i, 16));
        return sb.toString();
    }
 *****/

    /** convert single byte to String */
/*****
    public static String btoh (byte b) {
        int i = 0xff & b;
        return itoh(i);
    }
 *****/
}
