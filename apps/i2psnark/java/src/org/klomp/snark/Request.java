/* Request - Holds all information needed for a (partial) piece request.
   Copyright (C) 2003 Mark J. Wielaard
   This file is part of Snark.
   Licensed under the GPL version 2 or later.
*/

package org.klomp.snark;

import java.io.DataInputStream;
import java.io.IOException;

/**
 * Holds all information needed for a partial piece request.
 *
 * <p>This class should be used only by {@link PeerState}, PeerConnectionIn, and PeerConnectionOut.
 *
 * @since 0.1.0
 */
class Request {
    /** The piece this request covers. */
    private final PartialPiece piece;
    /** Offset in the piece. */
    final int off;
    /** Length in bytes. */
    final int len;
    /** Send time. */
    long sendTime;

    /**
     * Creates a new Request.
     *
     * @param piece Piece number requested.
     * @param off the offset in the array.
     * @param len the number of bytes requested.
     */
    Request(PartialPiece piece, int off, int len) {
        this.piece = piece;
        this.off = off;
        this.len = len;

        // Sanity check
        if (off < 0 || len <= 0 || off + len > piece.getLength())
            throw new IndexOutOfBoundsException("Illegal Request " + toString());
    }

    /**
     * Dummy Request for {@code PeerState.returnPartialPieces()}; len is zero.
     *
     * @param piece Piece number requested.
     * @param off the offset in the array.
     * @since 0.9.36
     */
    Request(PartialPiece piece, int off) {
        this.piece = piece;
        this.off = off;
        this.len = 0;

        // Sanity check
        if (off < 0 || off > piece.getLength())
            throw new IndexOutOfBoundsException("Illegal Request " + toString());
    }

    /**
     * Read from stream.
     *
     * @param din stream carrying the requested block data
     * @param bwl listener credited with the downloaded bytes
     * @throws IOException if the stream ends early or the offset is not a block boundary
     */
    public void read(DataInputStream din, BandwidthListener bwl) throws IOException {
        piece.read(din, off, len, bwl);
    }

    /**
     * The piece number this Request is for.
     *
     * @return the index of the piece this request covers
     * @since 0.9.1
     */
    public int getPiece() {
        return piece.getPiece();
    }

    /**
     * The PartialPiece this Request is for.
     *
     * @return the partial piece
     * @since 0.9.1
     */
    public PartialPiece getPartialPiece() {
        return piece;
    }

    /** Hash based on piece number, offset, and length. */
    @Override
    public int hashCode() {
        return piece.getPiece() ^ off ^ len;
    }

    /**
     * The request as log segments - {@code " -> Piece: 3 * Offset: 16384 bytes *
     * Size: 16384 bytes"} - the same shape {@link Message#logArgs()} gives a
     * queued message, so a request line reads the same wherever it is written.
     * {@link #toString()} is the bare {@code (piece,off,len)} tuple, which
     * labels nothing, gives no units, and cannot be read at a glance.
     *
     * @return the arrow and the labelled values, beginning with {@code " -> "}
     */
    String logForm() {
        return logForm(piece.getPiece(), off, len);
    }

    /**
     * A block named by piece, offset and length, as the same log segments
     * {@link #logForm()} gives a request we made. PeerState holds the three
     * numbers loose rather than a Request when a peer asks us for a block, and a
     * block has to read identically whether we asked for it or the peer did, so
     * the form lives here once instead of in a second copy at each call site.
     *
     * @param piece the piece index the block starts in
     * @param begin the byte offset into the piece
     * @param length the size of the block in bytes
     * @return the arrow and the labelled values, beginning with {@code " -> "}
     * @since 0.9.72+
     */
    static String logForm(int piece, int begin, int length) {
        return " -> Piece: " + piece + " * Offset: " + begin + " bytes * Size: " + length
               + " bytes";
    }

    /** Two requests are equal if they share the same piece, offset, and length. */
    @Override
    public boolean equals(Object o) {
        if (o instanceof Request) {
            Request req = (Request) o;
            return req.piece.equals(piece) && req.off == off && req.len == len;
        }

        return false;
    }

    /**
     * A string representation of the request.
     *
     * @return "(piece,off,len)" as the {@link #toString()} form
     */
    @Override
    public String toString() {
        return "(" + piece.getPiece() + "," + off + "," + len + ")";
    }
}
