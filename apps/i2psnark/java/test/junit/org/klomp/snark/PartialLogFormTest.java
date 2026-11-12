package org.klomp.snark;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Pins how a partial piece renders in a log line: which piece it is and how much of
 * it is in. The form the object prints, {@code "Partial(1220,28672,65536,262144)"},
 * is a comma tuple that names none of its four numbers, so a line about a stalled
 * piece could not be read at all, and the list form printed one such tuple per
 * partial with no cap on the line.
 *
 * @since 0.9.72+
 */
public class PartialLogFormTest {

    /**
     * Test instance; every test builds a partial piece and reads its form back.
     */
    public PartialLogFormTest() {
    }

    /**
     * A partial piece of the given number and length, held in memory, with no data
     * in it yet.
     *
     * @param number the piece number
     * @param length the piece length in bytes, small enough to stay in memory
     * @return the partial piece, never null
     */
    private static PartialPiece partial(int number, int length) {
        return new PartialPiece(new Piece(number), length, null);
    }

    /** A partial is its piece number and how much of it is in. */
    @Test
    public void partialNamesItsPieceAndHowMuchIsIn() {
        assertEquals("Piece 1220 (0 of 32768 bytes in)",
                     SnarkLogFormatter.partial(partial(1220, 32768)));
    }

    /** The bytes in come from the piece's own bitfield, not from an offset field. */
    @Test
    public void partialReportsTheBytesActuallyHeld() {
        PartialPiece pp = partial(959, 32768);
        // one 4096-byte sub-block of a 32768-byte piece
        pp.markSubBlock(0);
        assertEquals("Piece 959 (4096 of 32768 bytes in)", SnarkLogFormatter.partial(pp));
    }

    /** A missing partial is named in prose, never as a null value. */
    @Test
    public void nullPartialIsNamedNone() {
        assertEquals("none", SnarkLogFormatter.partial(null));
    }

    /** A list of partials is their piece numbers, the only part a reader acts on. */
    @Test
    public void partialListNamesPieceNumbers() {
        List<PartialPiece> l = new ArrayList<>();
        l.add(partial(305, 32768));
        l.add(partial(50, 32768));
        l.add(partial(1111, 32768));
        assertEquals(" * Ours (3): 305 50 1111", SnarkLogFormatter.partialList(l, "Ours"));
        assertFalse(SnarkLogFormatter.partialList(l, "Ours").contains("\n"));
    }

    /** Four numbers is the cap, and the rest are counted behind them. */
    @Test
    public void partialListStopsAtFourNumbersAndCountsTheRest() {
        List<PartialPiece> l = new ArrayList<>();
        for (int i = 0; i < 27; i++) {
            l.add(partial(i, 32768));
        }
        String s = SnarkLogFormatter.partialList(l, "Ours");
        assertEquals(" * Ours (27): 0 1 2 3 (+23 more)", s);
    }

    /** An empty list is a zero count, not a label with nothing under it. */
    @Test
    public void emptyPartialListIsAZeroCount() {
        assertEquals(" * Ours: 0",
                     SnarkLogFormatter.partialList(
                            Collections.<PartialPiece> emptyList(), "Ours"));
    }

    /**
     * The whole list a downloading torrent can hold, at the widest piece numbers,
     * still has to fit on the line beside the caller's lead-in.
     */
    @Test
    public void partialListIsBoundedInWidth() {
        List<PartialPiece> l = new ArrayList<>();
        for (int i = 0; i < 4000; i++) {
            l.add(partial(i, 32768));
        }
        String s = SnarkLogFormatter.partialList(l, "Ours");
        assertTrue(s.length() + " " + s, s.length() <= 45);
    }
}
