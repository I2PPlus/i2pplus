package io.pack200;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import junit.framework.TestCase;

/**
 * Invariants of {@link Fixups}, the pending-reference table used while packing.
 *
 * Converted from the {@code main()} self-test that used to live in Fixups.java.
 * That method was never invoked by anything, so none of these invariants were
 * actually checked; they are now.
 *
 * @since EOL fork
 */
public class FixupsTest extends TestCase {

    /**
     * Mirrors the private {@code Fixups.U1_FORMAT}/{@code U2_FORMAT}. They are
     * private to the class under test, so the values are restated here and
     * checked against the package-visible {@link Fixups#fmtLen} in
     * {@link #testFmtLenMatchesFormatConstants()} so they cannot silently drift.
     */
    private static final int U1_FORMAT = 1;
    private static final int U2_FORMAT = 0;

    /** Populated by {@link #build} so the expectations can be derived, not hardcoded. */
    private byte[] bytes;
    private Fixups fixups;
    private int[] locs;
    private int[] fmts;
    private int iptr;
    /** The entries the fixups point at, in insertion order. */
    private final List<ConstantPool.Entry> entries = new ArrayList<ConstantPool.Entry>();

    /**
     * ConstantPool's entry factory reaches into {@link Utils#getTLGlobals()},
     * which is only populated while a pack or unpack is in flight. Stand up the
     * same context the engine would, so the factory works outside a real pack.
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        Utils.currentInstance.set(new PackerImpl());
    }

    @Override
    protected void tearDown() throws Exception {
        Utils.currentInstance.set(null);
        super.tearDown();
    }

    /**
     * Lays down a byte pattern with fixups sprinkled through it, deliberately
     * packing the first ten close together so both formats and the overflow
     * growth paths are exercised.
     */
    private void build() {
        bytes = new byte[1 << 20];
        fixups = new Fixups(bytes);
        boolean isU1 = false;
        int span = 3;
        int nextLoc = 0;
        locs = new int[100];
        fmts = new int[100];
        iptr = 1;
        for (int loc = 0; loc < bytes.length; loc++) {
            if (loc == nextLoc && loc + 1 < bytes.length) {
                int fmt = (isU1 ? U1_FORMAT : U2_FORMAT);
                ConstantPool.Entry e = ConstantPool.getUtf8Entry("L" + loc);
                entries.add(e);
                fixups.add(loc, fmt, e);
                isU1 ^= true;
                if (iptr < 10) {
                    nextLoc += Fixups.fmtLen(fmt) + (iptr < 5 ? 0 : 1);
                } else {
                    nextLoc += span;
                    span = (int) (span * 1.77);
                }
                locs[iptr] = loc;
                fmts[iptr] = fmt;
                iptr++;
                if (fmt == U2_FORMAT) ++loc;  // the U2 form occupies two bytes
                continue;
            }
            bytes[loc] = (byte) loc;
        }
    }

    public void testFmtLenMatchesFormatConstants() {
        assertEquals("U1 is a one-byte format", 1, Fixups.fmtLen(U1_FORMAT));
        assertEquals("U2 is a two-byte format", 2, Fixups.fmtLen(U2_FORMAT));
    }

    public void testBuildPopulatesEntries() {
        build();
        assertTrue("expected the fixture to create fixups", fixups.size() > 0);
        // The iterator yields a terminating entry, so entries == iptr - 1.
        assertEquals(iptr - 1, fixups.size());
    }

    public void testIterationIsSorted() {
        build();
        List<Fixups.Fixup> sorted = new ArrayList<Fixups.Fixup>(fixups);
        Collections.sort(sorted);
        assertEquals("natural order must equal sorted order",
                     sorted, new ArrayList<Fixups.Fixup>(fixups));
    }

    public void testIteratorYieldsAscendingLocations() {
        build();
        Iterator<Fixups.Fixup> it = fixups.iterator();
        int prev = -1;
        int n = 0;
        while (it.hasNext()) {
            int loc = it.next().location();
            assertTrue("locations must ascend: " + loc + " after " + prev, loc > prev);
            prev = loc;
            n++;
        }
        assertEquals("iterator must yield exactly size() entries", fixups.size(), n);
    }

    public void testOrderSurvivesSetBytesAndCopy() {
        build();
        List<Fixups.Fixup> before = new ArrayList<Fixups.Fixup>(fixups);
        // Detaching and reattaching the backing array must not disturb the table.
        fixups.setBytes(null);
        assertEquals("detached order", before, new ArrayList<Fixups.Fixup>(fixups));
        fixups.setBytes(bytes);
        assertEquals("reattached order", before, new ArrayList<Fixups.Fixup>(fixups));
        assertEquals("copy-constructed order",
                     before, new ArrayList<Fixups.Fixup>(new Fixups(fixups)));
    }

    /**
     * finishRefs() rewrites each fixup location in place. Every other byte must
     * come through untouched, which is what the original self-test checked by
     * printing mismatches.
     */
    public void testFinishRefsLeavesUnfixedBytesIntact() throws Exception {
        build();
        // Resolve every reference to an arbitrary index; the bytes at the
        // resolved locations are what change.
        byte[] expected = bytes.clone();
        // A real index over the same entries, so indexOf() yields genuine positions.
        ConstantPool.Index ix = new ConstantPool.Index("ix", entries);
        fixups.finishRefs(ix);
        // finishRefs() clears the table and detaches the array once done.
        assertEquals("finishRefs must drain the fixup table", 0, fixups.size());
        for (int i = 0; i < bytes.length; i++) {
            boolean atFixup = false;
            for (int f = 1; f < iptr && !atFixup; f++) {
                // A fixup occupies fmtLen(fmt) bytes and all of them are rewritten.
                atFixup = (i >= locs[f] && i < locs[f] + Fixups.fmtLen(fmts[f]));
            }
            if (atFixup) continue;
            assertEquals("byte " + i + " was modified", expected[i], bytes[i]);
        }
    }
}