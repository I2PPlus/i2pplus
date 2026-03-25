package i2p.susi.util;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 *  Tests for HexTable.
 */
public class HexTableTest {

    @Test
    public void testTableSize() {
        assertEquals(256, HexTable.table.length);
    }

    @Test
    public void testTableEntryFormat() {
        for (int i = 0; i < 256; i++) {
            String entry = HexTable.table[i];
            assertNotNull(entry);
            assertEquals(3, entry.length());
            assertTrue(entry.startsWith("="));
        }
    }

    @Test
    public void testKnownValues() {
        assertEquals("=00", HexTable.getHexString(0));
        assertEquals("=01", HexTable.getHexString(1));
        assertEquals("=09", HexTable.getHexString(9));
        assertEquals("=0A", HexTable.getHexString(10));
        assertEquals("=0F", HexTable.getHexString(15));
        assertEquals("=10", HexTable.getHexString(16));
        assertEquals("=7F", HexTable.getHexString(127));
        assertEquals("=FF", HexTable.getHexString(255));
    }

    @Test
    public void testGetTableEntry() {
        assertEquals("=00", HexTable.getTableEntry(0));
        assertEquals("=FF", HexTable.getTableEntry(255));
    }

    @Test
    public void testGetTableReturnsCopy() {
        String[] t1 = HexTable.getTable();
        String[] t2 = HexTable.getTable();
        assertNotSame(t1, t2);
        assertArrayEquals(t1, t2);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetHexStringNegative() {
        HexTable.getHexString(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetHexStringOver255() {
        HexTable.getHexString(256);
    }
}
