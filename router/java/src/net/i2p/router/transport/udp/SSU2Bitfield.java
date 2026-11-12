package net.i2p.router.transport.udp;

import java.util.Arrays;
import net.i2p.router.transport.udp.SSU2Payload.AckBlock;

/**
 * Container of a long array representing set and unset bits.
 * When a bit higher than the current size + offset is set,
 * the offset shifts up and the lowest set bits are lost.
 * <p>
 * Also contains methods to convert to/from an ACK block.
 * <p>
 * Locking: Most methods are synchronized here.
 * Do not call methods with the PeerState2 lock held, chance of deadlock.
 *
 * @since 0.9.54
 */
class SSU2Bitfield {

    /**
     * Largest message-number span (in bits) we will build from a received ACK
     * block. A peer's own receive bitfield is 512 bits and shifts up by at most
     * max_shift (4096) before starting over, so a live peer can legitimately
     * describe a span of under 512 + 4096 bits. Anything beyond that is a
     * malformed or hostile block: the span is derived from unvalidated
     * attacker-controlled bytes, so an unchecked value lets a ~1.5KB packet
     * allocate a ~48KB bitfield. The margin here leaves room for
     * implementation differences while capping the allocation at 1KB.
     *
     * @since 0.9.71+
     */
    static final int MAX_ACK_SPAN = 8192;

    /** Bit storage. */
    private final long[] bitfield;
    /** Bitfield size. */
    private final int size;
    /** Maximum shift. */
    private final int max_shift;
    /** Minimum shift. */
    private final int min_shift;
    /** Current offset. */
    private long offset;
    /** Highest set bit before offset, -1 if none. */
    private int highestSet = -1;

    /**
     * Creates a new SSU2Bitfield that represents <code>size</code> unset bits.
     *
     * @param size the bitfield width in bits, rounded up to a multiple of 256
     * @param offset the message number the first bit represents, non-negative
     */
    public SSU2Bitfield(int size, long offset) {
        if (size <= 0 || offset < 0) {throw new IllegalArgumentException("size " + size + " offset " + offset);}
        size = (size + 255) & 0x7FFFFF00; // force mult. of 256
        this.size = size;
        this.offset = offset;
        max_shift = Math.max(1024, size * 8);
        min_shift = Math.max(8, size / 4);
        bitfield = new long[size / 64];
    }

    /**
     * The size in bits.
     *
     * @return the bitfield width in bits
     */
    public int size() {return size;}

    /**
     * The current offset.
     *
     * @return the message number the lowest bit represents
     */
    public long getOffset() {return offset;}

    /**
     * Mark the given bit as set.
     * When a bit higher than the current size + offset is set,
     * the offset shifts up and the lowest set bits are lost.
     *
     * @param bit the absolute message number to set, non-negative
     * @return previous value, true if previously set or unknown
     * @throws IndexOutOfBoundsException if bit is smaller then zero
     *                                   OR if the shift is too big
     */
    public boolean set(long bit) throws IndexOutOfBoundsException {
        if (bit < 0) {throw new IndexOutOfBoundsException(Long.toString(bit));}
        synchronized(this) {
            return setNoSync(bit);
        }
    }

    /**
     * Return true if the bit is set or false if it is not.
     *
     * @param bit the absolute message number to test, non-negative
     * @return true if the bit is set, false if unset or already shifted out
     * @throws IndexOutOfBoundsException if bit is smaller then zero
     */
    public synchronized boolean get(long bit) {
        if (bit < 0) {throw new IndexOutOfBoundsException(Long.toString(bit));}
        bit -= offset;
        if (bit < 0 || bit >= size) {return false;}
        int index = (int) (bit >> 6);
        long mask = 1L << (((int) bit) & 0x3F);
        return (bitfield[index] & mask) != 0;
    }

    /**
     * Return the highest set bit, or -1 if none.
     * @return the highest set
     */
    public synchronized long getHighestSet() {
        if (highestSet < 0) {return -1;}
        return highestSet + offset;
    }

    /**
     * Count consecutive set bits starting at relative bit {@code rel} going
     * down, capped at {@code cap} and stopping at the offset. The walk runs
     * over whole words, not one synchronized {@link #get} per bit: the caller
     * holds the lock, so the words are read directly.
     *
     * @param rel the relative bit to start from, inclusive; negative means none
     * @param cap the maximum count, 255 for ACK counts
     * @return the number of consecutive set bits from {@code rel} downward
     * @since 0.9.72+
     */
    private int countSetDown(long rel, int cap) {
        return countRunDown(rel, cap, true);
    }

    /**
     * Count consecutive unset bits starting at relative bit {@code rel} going
     * down, capped at {@code cap} and stopping at the offset. The word-walking
     * twin of {@link #countSetDown}, with the set and unset roles swapped.
     *
     * @param rel the relative bit to start from, inclusive; negative means none
     * @param cap the maximum count, 255 for ACK counts
     * @return the number of consecutive unset bits from {@code rel} downward
     * @since 0.9.72+
     */
    private int countUnsetDown(long rel, int cap) {
        return countRunDown(rel, cap, false);
    }

    /**
     * Shared word walk behind {@link #countSetDown} and
     * {@link #countUnsetDown}. {@code want} is the bits counted, set or unset:
     * each word contributes its run of {@code want} bits ending at the current
     * position, found by locating the highest opposite bit below it.
     *
     * @param rel the relative bit to start from, inclusive; negative means none
     * @param cap the maximum count, 255 for ACK counts
     * @param want true to count set bits, false to count unset bits
     * @return the number of consecutive {@code want} bits from {@code rel}
     *         downward
     * @since 0.9.72+
     */
    private int countRunDown(long rel, int cap, boolean want) {
        int count = 0;
        while (count < cap && rel >= 0) {
            long word = bitfield[(int) (rel >> 6)];
            int inWord = (int) (rel & 63);
            long mask = (inWord == 63) ? -1L : ((1L << (inWord + 1)) - 1);
            // The bits counted, as ones at or below the current position,
            // and the opposite bits that end the run.
            long bits = want ? (word & mask) : (~word & mask);
            long zeros = ~bits & mask;
            int run;
            if (zeros == 0) {
                run = inWord + 1;
            } else {
                run = inWord - (63 - Long.numberOfLeadingZeros(zeros));
            }
            if (run <= 0) {break;}
            if (count + run > cap) {run = cap - count;}
            count += run;
            rel -= run;
        }
        return count;
    }

    /**
     * Walk the (nack, ack) ranges below the top run, top-down.
     *
     * @param curRel the relative bit to start from, inclusive; negative means
     *               no range below the top run
     * @param maxRanges the maximum number of ranges to emit
     * @param sink where to store the pairs, {@code maxRanges * 2} bytes, or
     *             null to count only
     * @return the number of ranges the walk produces
     * @since 0.9.72+
     */
    private int walkRanges(long curRel, int maxRanges, byte[] sink) {
        int rangeCount = 0;
        for (int r = 0; r < maxRanges && curRel >= 0; r++) {
            int ncnt = countUnsetDown(curRel, 255);
            curRel -= ncnt;
            int aacnt = countSetDown(curRel, 255);
            curRel -= aacnt;
            if (ncnt == 0 && aacnt == 0) {break;}
            if (sink != null) {
                sink[rangeCount * 2] = (byte) ncnt;
                sink[(rangeCount * 2) + 1] = (byte) aacnt;
            }
            rangeCount++;
        }
        return rangeCount;
    }

    /**
     * Render the set bits as an ACK block, collapsing the top acnt bits into
     * the ACK count and the rest into at most maxRanges ranges.
     *
     * @param maxRanges may be 0
     * @return null if nothing is set
     */
    public synchronized AckBlock toAckBlock(int maxRanges) {
        long highest = getHighestSet();
        if (highest < 0) {return null;} // nothing to ack
        long hb = highest - offset;
        int acnt = countSetDown(hb - 1, 255);
        // top run is acnt bits; the range walk starts just below it
        long curRel = hb - (acnt + 1);
        // Count first, then allocate exactly rangeCount * 2 bytes: the old code
        // allocated maxRanges * 2 (up to 1024 bytes) and AckBlock kept the whole
        // array alive even though typical range counts are 0-20 bytes.
        int rangeCount = walkRanges(curRel, maxRanges, null);
        byte[] ranges = new byte[rangeCount * 2];
        walkRanges(curRel, maxRanges, ranges);
        return new AckBlock(highest, acnt, ranges, rangeCount);
    }

    /**
     * Compute the number of message numbers (bits) the bitfield for an ACK block
     * must cover, without allocating one.
     * <p>
     * Pure and package-visible so the hostile-input case can be tested directly.
     *
     * @param thru the highest acked message number, an unsigned 32-bit value
     *             held in a long
     * @param acnt number of contiguous acks below thru, 0-255
     * @param ranges non-null, at least rangeCount * 2 bytes
     * @param rangeCount number of (nack, ack) range pairs, must be non-zero
     * @return the span in bits, always at least 1
     * @throws IndexOutOfBoundsException if ranges is too short for rangeCount
     * @since 0.9.71+
     */
    static long calculateAckSpan(long thru, int acnt, byte[] ranges, int rangeCount) {
        // long math throughout: packet numbers are unsigned 32-bit, and a hostile
        // block can push the implied low message number below zero, which would
        // wrap in an int and defeat the size check
        long min = thru - acnt;
        for (int i = 0; i < rangeCount * 2; i++) {min -= ranges[i] & 0xff;}
        // fixup if the last ack count was zero
        // this doesn't handle multiple ranges with a zero ack count
        if (ranges[(rangeCount * 2) - 1] == 0) {min += ranges[(rangeCount * 2) - 2] & 0xff;}
        return 1L + thru - min;
    }

    /**
     * Mark the given bit as set without entering the monitor - this is the
     * body {@link #set} runs inside its lock. For use on a bitfield that is
     * not published yet ({@link #fromACKBlock}), where there is nobody to
     * exclude. The shift path is kept because {@link #set} reaches it too,
     * even though the window {@link #fromACKBlock} builds always covers the
     * whole ACK block.
     *
     * @param bit the absolute message number to set, non-negative
     * @return previous value, true if previously set or unknown
     * @throws IndexOutOfBoundsException if bit is smaller then zero
     *                                   OR if the shift is too big
     * @since 0.9.72+
     */
    private boolean setNoSync(long bit) throws IndexOutOfBoundsException {
        if (bit < 0) {throw new IndexOutOfBoundsException(Long.toString(bit));}
        boolean rv;
        bit -= offset;
        if (bit < 0) {return true;} // too old?
        if (bit >= size) {
            long shift = bit + 1 - size;
            if (shift > max_shift) {throw new IndexOutOfBoundsException("Shift too big: " + shift);}
            if (shift < min_shift) {shift = min_shift;}
            if ((shift & 0x3f) != 0) {shift = 64 + (shift & 0x7fffffc0);} // round up
            if (shift < size) {
                // shift down
                int bshift = (int) (shift / 64);
                System.arraycopy(bitfield, bshift, bitfield, 0, bitfield.length - bshift);
                // 2nd idx is exclusive
                Arrays.fill(bitfield, bitfield.length - bshift, bitfield.length, 0L);
                if (highestSet >= 0)
                    highestSet -= shift;
            } else {
                // start over
                Arrays.fill(bitfield, 0L);
                highestSet = -1;
            }
            offset += shift;
            bit -= shift;
        }
        int index = (int) (bit >> 6);
        long mask = 1L << (((int) bit) & 0x3F);
        rv = (bitfield[index] & mask) != 0;
        if (!rv) {
            bitfield[index] |= mask;
            if (bit > highestSet) {highestSet = (int) bit;}
        }
        return rv;
    }

    /**
     * Rebuild a bitfield from a received ACK block, rejecting a block whose
     * implied span would push the offset below zero or exceed
     * {@link #MAX_ACK_SPAN}.
     *
     * @param thru the highest acked message number, an unsigned 32-bit value
     *             held in a long
     * @param acnt number of contiguous acks below thru, 0-255
     * @param ranges may be null
     * @param rangeCount number of (nack, ack) range pairs
     * @return a bitfield covering every message the block acks
     * @throws IllegalArgumentException if the ranges describe a span larger than
     *                                  {@link #MAX_ACK_SPAN}
     */
    public static SSU2Bitfield fromACKBlock(long thru, int acnt, byte[] ranges, int rangeCount) {
        // Packet numbers are unsigned 32-bit, so they only stay ordered as longs -
        // an int counter goes negative above 0x7fffffff and stops matching the
        // peer's numbering. Only the bitfield size, bounded by MAX_ACK_SPAN, is an int.
        if (ranges == null || rangeCount == 0) {
            // easy case, no ranges
            SSU2Bitfield rv = new SSU2Bitfield(acnt + 1, thru - acnt);
            for (long i = thru; i >= thru - acnt; i--) {rv.setNoSync(i);}
            return rv;
        }
        // Bound the span before allocating: it is derived from unvalidated
        // attacker-controlled bytes, and the bitfield is size/8 bytes.
        long span = calculateAckSpan(thru, acnt, ranges, rangeCount);
        if (span > MAX_ACK_SPAN)
            throw new IllegalArgumentException("ACK block span too large: " + span);
        // get the minimum acked value
        long min = thru + 1 - span;

        SSU2Bitfield rv = new SSU2Bitfield((int) span, min);
        for (long i = thru; i >= thru - acnt; i--) {rv.setNoSync(i);}

        long j = thru - (acnt + 1);
        for (int i = 0; i < rangeCount * 2; i += 2) {
            j -= ranges[i] & 0xff; // nack count
            int toAck = ranges[i + 1] & 0xff; // ack count
            // stop at the bottom of the bitfield, so a range claiming more acked
            // bits than the span covers cannot write below the offset
            for (int k = 0; k < toAck && j >= min; k++) {rv.setNoSync(j--);}
        }
        return rv;
    }

    /**
     * Callback interface for bitfield operations.
     * Called when a bit is set in the bitfield.
     */
    public interface Callback {
        /**
         * Called when a bit is set.
         *
         * @param bit the absolute message number that was newly set
         */
        public void bitSet(long bit);
    }

    /**
     * Callback for all bits set in this bitfield but not set in bf2.
     * <p>
     * If this offset is greater than bf2's highest bit set, i.e. this bitfield
     * is completely newer, calls back for all bits in this bitfield.
     * <p>
     * If this highest bit set is less than than bf2's offset, i.e. this bitfield
     * is completely older, the callback will not be called.
     * <p>
     * Synchs on this and then on bf2.
     * <p>
     * Usage: this is the received acks, bf2 is previously acked,
     * callback for each newly acked.
     *
     * @param bf2 the previously acknowledged bitfield, updated in place
     * @param cb called once per message newly acked by this bitfield
     */
    public synchronized void forEachAndNot(SSU2Bitfield bf2, Callback cb) {
        synchronized(bf2) {
            long highest = getHighestSet();
            if (highest < bf2.offset) {return;} // completely older
            // We MUST go bottom-up, because bf2 may shift
            // overlap portion
            long start = Math.max(offset, bf2.offset);
            long bf2Highest = bf2.getHighestSet();
            // Overlap [start, min(highest, bf2Highest)): walked word-wise over
            // this bitfield's grid, with each candidate's bf2 membership read
            // through bf2's own offset. Only bits bf2 lacks pay a bf2.set(), so
            // the cost follows the diff, not the span. bf2Highest itself is
            // excluded: that bit is set in bf2 by definition.
            long overlapEnd = Math.min(highest, bf2Highest - 1);
            if (start <= overlapEnd) {
                long chunkStart = offset + ((start - offset) & ~63L);
                for (long base = chunkStart; base <= overlapEnd; base += 64) {
                    long word = bitfield[(int) ((base - offset) >> 6)];
                    if (base < start) {
                        word &= ~((1L << (start - base)) - 1);
                    }
                    long chunkEnd = Math.min(63, overlapEnd - base);
                    word &= (chunkEnd == 63) ? -1L : ((1L << (chunkEnd + 1)) - 1);
                    while (word != 0) {
                        long bit = base + Long.numberOfTrailingZeros(word);
                        long rel = bit - bf2.offset;
                        boolean inBf2 = rel >= 0 && rel < bf2.size
                                && ((bf2.bitfield[(int) (rel >> 6)] >>> (rel & 63)) & 1L) != 0;
                        if (!inBf2 && !bf2.set(bit)) {cb.bitSet(bit);}
                        word &= word - 1;
                    }
                }
            }
            // portion that is strictly newer
            for (long bit = Math.max(bf2Highest + 1, offset); bit <= highest; bit++) {
                if (get(bit)) {
                    bf2.set(bit);
                    cb.bitSet(bit);
                }
            }
        }
    }

    /**
     * Pretty print an ACK block.
     *
     * @param thru the highest acked message number
     * @param acnt number of contiguous acks below thru, 0-255
     * @param ranges may be null
     * @param rangeCount number of (nack, ack) range pairs
     * @return the block as "ACK 1234-1200 NACK 1199 ACK 1198-1190" style text
     */
    public static String toString(long thru, int acnt, byte[] ranges, int rangeCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("ACK ").append(thru);
        // unsigned 32-bit packet numbers stay ordered only as longs
        long cur = thru;
        if (acnt > 0) {
            sb.append('-').append(thru - acnt);
            cur -= acnt;
        }
        if (ranges != null) {
            cur--;
            for (int i = 0; i < rangeCount * 2; i += 2) {
                int nacks = ranges[i] & 0xff;
                if (nacks > 0) {
                    sb.append(" NACK ").append(cur);
                    if (nacks > 1) {sb.append('-').append(cur - (nacks - 1));}
                    cur -= nacks;
                }
                int acks = ranges[i+1] & 0xff;
                if (acks > 0) {
                    sb.append(" ACK ").append(cur);
                    if (acks > 1) {sb.append('-').append(cur - (acks - 1));}
                    cur -= acks;
                }
            }
        }
        return sb.toString();
    }

    /**
     * String representation of this bitfield.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("SSU2Bitfield(");
        sb.append(size).append(")");
        sb.append(" offset: ").append(offset);
        sb.append(" highest set: ").append(getHighestSet());
        sb.append(" [");
        for (long i = offset; i <= getHighestSet(); i++) {
            if (get(i)) {sb.append(' ').append(i);}
        }
        sb.append(" ]");
        return sb.toString();
    }

}
