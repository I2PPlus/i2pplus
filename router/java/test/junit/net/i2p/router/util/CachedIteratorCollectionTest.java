package net.i2p.router.util;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

/**
 * Unit tests for the CachedIteratorCollection class.
 * Verifies it behaves similarly to LinkedList in iteration, removal, and state changes.
 */
public class CachedIteratorCollectionTest {

    private void addNObjects(Collection<String> collection, int n) {
        if (n <= 0) throw new IllegalArgumentException("Please use a positive integer");
        for (int j = 0; j < n; j++) {
            collection.add("test" + j);
        }
    }

    @Test
    public void add1Test() {
        CachedIteratorCollection<String> testCollection = new CachedIteratorCollection<>();
        addNObjects(testCollection, 1);

        StringBuilder sb = new StringBuilder();
        Iterator<String> itr = testCollection.iterator();
        while (itr.hasNext()) sb.append(itr.next());

        assertEquals("test0", sb.toString());
        assertEquals(1, testCollection.size());
        assertFalse(itr.hasNext());
        assertFalse(testCollection.isEmpty());
    }

    @Test
    public void add10Test() {
        CachedIteratorCollection<String> testCollection = new CachedIteratorCollection<>();
        addNObjects(testCollection, 10);

        StringBuilder sb = new StringBuilder();
        Iterator<String> itr = testCollection.iterator();
        while (itr.hasNext()) sb.append(itr.next());

        assertEquals("test0test1test2test3test4test5test6test7test8test9", sb.toString());
        assertEquals(10, testCollection.size());
        assertFalse(itr.hasNext());
        assertFalse(testCollection.isEmpty());
    }

    @Test
    public void addAllTest() {
        CachedIteratorCollection<String> testCollection = new CachedIteratorCollection<>();
        List<String> sourceList = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            sourceList.add("test" + i);
        }
        testCollection.addAll(sourceList);

        StringBuilder sb = new StringBuilder();
        Iterator<String> itr = testCollection.iterator();
        while (itr.hasNext()) sb.append(itr.next());

        assertEquals("test0test1test2test3test4test5test6test7test8test9", sb.toString());
        assertEquals(10, testCollection.size());
        assertFalse(testCollection.isEmpty());
    }

    @Test
    public void singleRemoveTest() {
        CachedIteratorCollection<String> testCollection = new CachedIteratorCollection<>();
        addNObjects(testCollection, 1);

        StringBuilder sb = new StringBuilder();
        Iterator<String> itr = testCollection.iterator();

        while (itr.hasNext()) {
            sb.append(itr.next());
            itr.remove();
        }

        assertEquals("test0", sb.toString());
        assertTrue(testCollection.isEmpty());
        assertEquals(0, testCollection.size());
        assertFalse(itr.hasNext());
    }

    @Test
    public void removeFromMiddleTest() {
        CachedIteratorCollection<String> testCollection = new CachedIteratorCollection<>();
        addNObjects(testCollection, 10);

        StringBuilder sb = new StringBuilder();
        Iterator<String> itr = testCollection.iterator();

        while (itr.hasNext()) {
            String s = itr.next();
            if (!s.equals("test5")) sb.append(s);
            else itr.remove();
        }

        assertEquals("test0test1test2test3test4test6test7test8test9", sb.toString());
        assertEquals(9, testCollection.size());
    }

    @Test
    public void restartIteratorTest() {
        CachedIteratorCollection<String> testCollection = new CachedIteratorCollection<>();
        addNObjects(testCollection, 10);

        StringBuilder sb = new StringBuilder();
        Iterator<String> itr = testCollection.iterator();
        while (itr.hasNext()) sb.append(itr.next());

        assertEquals("test0test1test2test3test4test5test6test7test8test9", sb.toString());
        assertFalse(itr.hasNext());

        itr = testCollection.iterator(); // restart
        assertEquals("test0", itr.next());
        assertTrue(itr.hasNext());
    }

    @Test(expected = NoSuchElementException.class)
    public void nextWhenNoneTest() {
        CachedIteratorCollection<String> testCollection = new CachedIteratorCollection<>();
        addNObjects(testCollection, 2);
        Iterator<String> itr = testCollection.iterator();

        itr.next();
        itr.next();
        itr.next(); // should throw
    }

    @Test(expected = IllegalStateException.class)
    public void removeTwiceWithoutNextTest() {
        CachedIteratorCollection<String> testCollection = new CachedIteratorCollection<>();
        addNObjects(testCollection, 3);

        Iterator<String> itr = testCollection.iterator();
        itr.next();
        itr.remove();
        itr.remove(); // should throw
    }

    @Test
    public void clearTest() {
        CachedIteratorCollection<String> testCollection = new CachedIteratorCollection<>();
        addNObjects(testCollection, 5);

        assertFalse(testCollection.isEmpty());
        testCollection.clear();

        assertTrue(testCollection.isEmpty());
        assertEquals(0, testCollection.size());
        assertFalse(testCollection.iterator().hasNext());
    }
}
