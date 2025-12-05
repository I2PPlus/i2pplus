package net.i2p.router.util;

/*
 * Modified from:
 * http://www.lockergnome.com/awarberg/2007/04/22/random-iterator-in-java/
 * No license, free to use
 */

import java.util.BitSet;
import java.util.List;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Random;

import net.i2p.util.RandomSource;
import net.i2p.util.SystemVersion;

/**
 * Random iterator for efficient early-termination iteration over collections.
 * <p>
 * Provides random iteration over a list with the ability to stop
 * iteration early when a condition is met. This can provide
 * significant performance improvements over Collections.shuffle() when
 * the iteration process may terminate before processing all elements.
 * <p>
 * Uses BitSet for tracking served items and I2P RandomSource
 * for better randomness distribution. Includes Android compatibility
 * workarounds and proper bounds checking to prevent infinite loops.
 * <p>
 * Performance characteristics:
 * <ul>
 *   <li>O(N) time complexity for iteration setup</li>
 *   <li>O(1) average time for next() calls</li>
 *   <li>Memory efficient with minimal object allocation</li>
 * </ul>
 * <p>
 * <strong>Note:</strong> Not recommended for small lists or when iterating
 * through a large portion of a collection. Use Collections.shuffle()
 * for those cases instead.
 *
 * @param <E>  type of elements returned by this iterator
 */
public class RandomIterator<E> implements Iterator<E> {
    /**
     * Mapping indicating which items were served (by index).
     * if served[i] then the item with index i in the list
     * has already been served.
     *
     * Note it is possible to save memory here by using
     * BitSet rather than a boolean array, however it will
     * increase the running time slightly.
     */
    private final BitSet served;

    /** The amount of items served so far */
    private int servedCount;
    private final List<E> list;
    private final int LIST_SIZE;

    /**
    * The random number generator has a great influence
    * on the running time of this iterator.
    *
    * See, for instance,
    * <a href="http://www.qbrundage.com/michaelb/pubs/essays/random_number_generation" title="http://www.qbrundage.com/michaelb/pubs/essays/random_number_generation" target="_blank">http://www.qbrundage.com/michaelb/pubs/e&#8230;</a>
    * for some implementations, which are faster than java.util.Random.
    */
    private final Random rand = RandomSource.getInstance();

    /** Used to narrow the range to take random indexes from */
    private int lower, upper;

    private static final boolean hasAndroidBug;
    static {
        if (SystemVersion.isAndroid()) {
            // only present on Gingerbread (API 11), but set if version check failed also
            int ver = SystemVersion.getAndroidVersion();
            hasAndroidBug = ver == 11 || ver == 0;
            if (hasAndroidBug)
                testAndroid();
        } else {
            hasAndroidBug = false;
        }
    }

    public RandomIterator(List<E> list){
        this.list = list;
        LIST_SIZE = list.size();
        served = new BitSet(LIST_SIZE);
        upper = LIST_SIZE - 1;
    }

    public boolean hasNext() {
        return servedCount < LIST_SIZE;
    }

    public E next() {
        if (!hasNext())
            throw new NoSuchElementException();
        int range = upper - lower + 1;

        // This has unbounded behavior, even with lower/upper
        //int index;
        //do {
        //    index = lower + rand.nextInt(range);
        //} while (served.get(index));

        // This tends to "clump" results, escpecially toward the end of the iteration.
        // It also tends to leave the first and last few elements until the end.
        int start = lower + rand.nextInt(range);
        int index;
        if ((start % 2) == 0)  // coin flip
            index = served.nextClearBit(start);
        else
            index = previousClearBit(start);
        if (index < 0)
            throw new NoSuchElementException("shouldn't happen");
        servedCount++;
        served.set(index);

        // check if the range from which random values
        // are taken can be reduced
        // I2P - ensure lower and upper are always clear
        if (hasNext()) {
            if (index == lower)
                // workaround for Android ICS bug - see below
                lower = hasAndroidBug ? nextClearBit(index) : served.nextClearBit(index);
            else if (index == upper)
                upper = previousClearBit(index - 1);
        }
        return list.get(index);
    }

    /** just like nextClearBit() */
    private int previousClearBit(int n) {
        for (int i = n; i >= lower; i--) {
            if (!served.get(i)) {
                return i;
            }
        }
        return -1;
    }

    /**
     *  Workaround for bug in Android (ICS only?)
     *  http://code.google.com/p/android/issues/detail?id=31036
     *  @since 0.9.2
     */
    private int nextClearBit(int n) {
        for (int i = n; i <= upper; i++) {
            if (!served.get(i)) {
                return i;
            }
        }
        return -1;
    }

    /**
     *  @throws UnsupportedOperationException always
     */
    public void remove() {
        throw new UnsupportedOperationException();
    }

/*
    public static void main(String[] args) {
        testAndroid();
        test(0);
        test(1);
        test(2);
        test(1000);
    }

    private static void test(int n) {
        System.out.println("testing with " + n);
        List<Integer> l = new ArrayList<Integer>(n);
        for (int i = 0; i < n; i++) {
            l.add(Integer.valueOf(i));
        }
        for (Iterator<Integer> iter = new RandomIterator<Integer>(l); iter.hasNext(); ) {
            System.out.println(iter.next().toString());
        }
    }
*/

    /**
     *  Test case from android ticket above
     *  @since 0.9.2
     */
    private static void testAndroid() {
        System.out.println("Checking for Android BitSet bug");
        BitSet theBitSet = new BitSet(864);
        for (int exp =0; exp < 864; exp++) {
            int act = theBitSet.nextClearBit(0);
            if (exp != act) {
                System.err.println(String.format("Test failed for: exp=%d, act=%d", exp, act));
                System.err.println("Android BitSet bug detected, workaround implemented!");
                return;
            }
            theBitSet.set(exp);
        }
        System.err.println("Android BitSet bug NOT detected, no workaround needed!");
    }
}
