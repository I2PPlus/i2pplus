package net.i2p.i2psnark;

import java.util.Collections;
import java.util.List;

import org.junit.Test;
import static org.junit.Assert.*;

import org.klomp.snark.web.InclusiveByteRange;

public class InclusiveByteRangeTest {

    @Test
    public void testSimpleRange() {
        List<InclusiveByteRange> ranges = InclusiveByteRange.satisfiableRanges(
                Collections.enumeration(Collections.singleton("bytes=100-499")), 1000);
        assertNotNull(ranges);
        assertEquals(1, ranges.size());
        InclusiveByteRange r = ranges.get(0);
        assertEquals(100, r.getFirst(1000));
        assertEquals(499, r.getLast(1000));
        assertEquals(400, r.getSize(1000));
        assertEquals("bytes 100-499/1000", r.toHeaderRangeString(1000));
    }

    @Test
    public void testOpenEndedRange() {
        List<InclusiveByteRange> ranges = InclusiveByteRange.satisfiableRanges(
                Collections.enumeration(Collections.singleton("bytes=100-")), 1000);
        assertNotNull(ranges);
        assertEquals(1, ranges.size());
        InclusiveByteRange r = ranges.get(0);
        assertEquals(100, r.getFirst(1000));
        assertEquals(999, r.getLast(1000));
        assertEquals(900, r.getSize(1000));
    }

    @Test
    public void testSuffixRange() {
        List<InclusiveByteRange> ranges = InclusiveByteRange.satisfiableRanges(
                Collections.enumeration(Collections.singleton("bytes=-300")), 1000);
        assertNotNull(ranges);
        assertEquals(1, ranges.size());
        InclusiveByteRange r = ranges.get(0);
        assertEquals(700, r.getFirst(1000));
        assertEquals(999, r.getLast(1000));
        assertEquals(300, r.getSize(1000));
    }

    @Test
    public void testSingleByteRange() {
        List<InclusiveByteRange> ranges = InclusiveByteRange.satisfiableRanges(
                Collections.enumeration(Collections.singleton("bytes=0-0")), 1000);
        assertNotNull(ranges);
        assertEquals(1, ranges.size());
        InclusiveByteRange r = ranges.get(0);
        assertEquals(0, r.getFirst(1000));
        assertEquals(0, r.getLast(1000));
        assertEquals(1, r.getSize(1000));
    }

    @Test
    public void test416HeaderRangeString() {
        assertEquals("bytes */500", InclusiveByteRange.to416HeaderRangeString(500));
        assertEquals("bytes */0", InclusiveByteRange.to416HeaderRangeString(0));
    }

    @Test
    public void testMultipleRanges() {
        List<InclusiveByteRange> ranges = InclusiveByteRange.satisfiableRanges(
                Collections.enumeration(Collections.singleton("bytes=1-2,2-3,6-,-2")), 100);
        assertNotNull(ranges);
        assertEquals(4, ranges.size());
        assertEquals(1, ranges.get(0).getFirst(100));
        assertEquals(2, ranges.get(0).getLast(100));
        assertEquals(2, ranges.get(1).getFirst(100));
        assertEquals(3, ranges.get(1).getLast(100));
        assertEquals(6, ranges.get(2).getFirst(100));
        assertEquals(99, ranges.get(2).getLast(100));
        assertEquals(98, ranges.get(3).getFirst(100));
        assertEquals(99, ranges.get(3).getLast(100));
    }

    @Test
    public void testRangeExceedingSize() {
        List<InclusiveByteRange> ranges = InclusiveByteRange.satisfiableRanges(
                Collections.enumeration(Collections.singleton("bytes=50-200")), 100);
        assertNotNull(ranges);
        assertEquals(1, ranges.size());
        InclusiveByteRange r = ranges.get(0);
        assertEquals(50, r.getFirst(100));
        assertEquals(99, r.getLast(100));
        assertEquals(50, r.getSize(100));
    }

    @Test
    public void testRangeCompletelyBeyondSize() {
        List<InclusiveByteRange> ranges = InclusiveByteRange.satisfiableRanges(
                Collections.enumeration(Collections.singleton("bytes=100-200")), 100);
        assertNull(ranges);
    }

    @Test
    public void testToString() {
        InclusiveByteRange r = new InclusiveByteRange(10, 19);
        assertEquals("10:19", r.toString());
    }

    @Test
    public void testInvalidRangeSpecifier() {
        List<InclusiveByteRange> ranges = InclusiveByteRange.satisfiableRanges(
                Collections.enumeration(Collections.singleton("bytes=100-50")), 1000);
        assertNull(ranges);
    }
}
