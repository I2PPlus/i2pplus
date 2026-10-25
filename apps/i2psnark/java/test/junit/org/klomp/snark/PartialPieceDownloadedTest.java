package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.File;
import org.junit.Test;

/**
 * Tests for PartialPiece.getDownloaded, the byte count used for progress
 * display and partial-piece replacement decisions. The short tail sub-block
 * must be credited by its real length only when the LAST sub-block is
 * present; the pre-fix code tested the bit at the set-bit COUNT instead of
 * the last index, miscounting out-of-order and tail-missing pieces.
 *
 * @since 0.9.72+
 */
public class PartialPieceDownloadedTest {

    private static final int SUB = 4 * 1024; // PeerState.PARTSIZE / 4

    /**
     * A memory-resident partial piece with the given sub-blocks marked.
     *
     * @param len piece length in bytes
     * @param subs sub-block indices to mark as received
     * @return the partial piece, fully in memory
     */
    private static PartialPiece piece(int len, int... subs) {
        PartialPiece pp = new PartialPiece(new Piece(1), len, new File("/tmp"));
        for (int s : subs) {
            pp.markSubBlock(s);
        }
        return pp;
    }

    @Test
    public void testTailCreditedOnlyWhenLastSubBlockPresent() {
        // 25568 = 6*4096 + 992, so 7 sub-blocks with a 992-byte tail
        int len = 6 * SUB + 992;
        // tail present: last sub-block counts 992, not 4096
        assertEquals(992, piece(len, 6).getDownloaded());
        // head only: full sub-block, no tail adjustment
        assertEquals(SUB, piece(len, 0).getDownloaded());
        // head + tail: the pre-fix code double-subtracted here
        assertEquals(SUB + 992, piece(len, 0, 6).getDownloaded());
    }

    @Test
    public void testNoTailWhenPieceIsExactMultiple() {
        int len = 4 * SUB;
        assertEquals(2 * SUB, piece(len, 0, 3).getDownloaded());
    }

    @Test
    public void testEmptyAndComplete() {
        int len = 6 * SUB + 1; // 7 sub-blocks
        assertEquals(0, piece(len).getDownloaded());
        PartialPiece all = piece(len, 0, 1, 2, 3, 4, 5, 6);
        assertEquals(len, all.getDownloaded()); // complete() short-circuits
    }

    @Test
    public void testSubPieceSizedPiece() {
        // piece shorter than one sub-block: single short sub-block
        assertEquals(100, piece(100, 0).getDownloaded());
    }
}
