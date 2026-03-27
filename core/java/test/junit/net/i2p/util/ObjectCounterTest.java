package net.i2p.util;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Set;

public class ObjectCounterTest {

    @Test
    public void testIncrementNewKey() {
        ObjectCounter<String> oc = new ObjectCounter<>();
        assertEquals(1, oc.increment("a"));
    }

    @Test
    public void testIncrementMultipleTimes() {
        ObjectCounter<String> oc = new ObjectCounter<>();
        assertEquals(1, oc.increment("a"));
        assertEquals(2, oc.increment("a"));
        assertEquals(3, oc.increment("a"));
    }

    @Test
    public void testCountUnknownKey() {
        ObjectCounter<String> oc = new ObjectCounter<>();
        assertEquals(0, oc.count("unknown"));
    }

    @Test
    public void testCountAfterIncrement() {
        ObjectCounter<String> oc = new ObjectCounter<>();
        oc.increment("x");
        oc.increment("x");
        assertEquals(2, oc.count("x"));
    }

    @Test
    public void testMax() {
        ObjectCounter<String> oc = new ObjectCounter<>();
        oc.max("a");
        assertEquals(Integer.MAX_VALUE / 2, oc.count("a"));
    }

    @Test
    public void testClearAll() {
        ObjectCounter<String> oc = new ObjectCounter<>();
        oc.increment("a");
        oc.increment("b");
        oc.clear();
        assertEquals(0, oc.count("a"));
        assertEquals(0, oc.count("b"));
        assertTrue(oc.objects().isEmpty());
    }

    @Test
    public void testClearSingleKey() {
        ObjectCounter<String> oc = new ObjectCounter<>();
        oc.increment("a");
        oc.increment("b");
        oc.clear("a");
        assertEquals(0, oc.count("a"));
        assertEquals(1, oc.count("b"));
    }

    @Test
    public void testObjects() {
        ObjectCounter<String> oc = new ObjectCounter<>();
        oc.increment("a");
        oc.increment("b");
        oc.increment("c");
        Set<String> objs = oc.objects();
        assertEquals(3, objs.size());
        assertTrue(objs.contains("a"));
        assertTrue(objs.contains("b"));
        assertTrue(objs.contains("c"));
    }

    @Test
    public void testObjectsExcludesCleared() {
        ObjectCounter<String> oc = new ObjectCounter<>();
        oc.increment("a");
        oc.increment("b");
        oc.clear("a");
        Set<String> objs = oc.objects();
        assertEquals(1, objs.size());
        assertTrue(objs.contains("b"));
    }

    @Test
    public void testIntegerKeys() {
        ObjectCounter<Integer> oc = new ObjectCounter<>();
        assertEquals(1, oc.increment(1));
        assertEquals(1, oc.increment(2));
        assertEquals(2, oc.increment(1));
        assertEquals(2, oc.count(1));
        assertEquals(1, oc.count(2));
    }
}
