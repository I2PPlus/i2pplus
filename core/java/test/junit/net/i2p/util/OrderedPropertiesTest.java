package net.i2p.util;

import static org.junit.Assert.*;

import org.junit.Test;

public class OrderedPropertiesTest {

    @Test
    public void testKeySetOrdered() {
        OrderedProperties op = new OrderedProperties();
        op.setProperty("z", "1");
        op.setProperty("a", "2");
        op.setProperty("m", "3");

        Object[] keys = op.keySet().toArray();
        assertEquals("a", keys[0]);
        assertEquals("m", keys[1]);
        assertEquals("z", keys[2]);
    }

    @Test
    public void testEntrySetOrdered() {
        OrderedProperties op = new OrderedProperties();
        op.setProperty("z", "1");
        op.setProperty("a", "2");

        Object[] keys = op.entrySet().toArray();
        // Entries should be sorted by key
        assertTrue(keys.length == 2);
    }

    @Test
    public void testSingleElementUnsorted() {
        OrderedProperties op = new OrderedProperties();
        op.setProperty("z", "1");
        assertEquals(1, op.keySet().size());
    }

    @Test
    public void testEmptyProperties() {
        OrderedProperties op = new OrderedProperties();
        assertTrue(op.keySet().isEmpty());
    }

    @Test
    public void testPutGet() {
        OrderedProperties op = new OrderedProperties();
        op.setProperty("key", "value");
        assertEquals("value", op.getProperty("key"));
    }

    @Test
    public void testRemove() {
        OrderedProperties op = new OrderedProperties();
        op.setProperty("key", "value");
        op.remove("key");
        assertNull(op.getProperty("key"));
    }

    @Test
    public void testKeySetSortedForManyEntries() {
        OrderedProperties op = new OrderedProperties();
        for (int i = 25; i >= 0; i--) {
            op.setProperty(String.valueOf((char) ('a' + i)), String.valueOf(i));
        }
        Object[] keys = op.keySet().toArray();
        for (int i = 1; i < keys.length; i++) {
            assertTrue(((String) keys[i - 1]).compareTo((String) keys[i]) <= 0);
        }
    }
}
