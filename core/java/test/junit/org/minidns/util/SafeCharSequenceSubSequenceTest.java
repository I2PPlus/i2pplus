package org.minidns.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests for {@link SafeCharSequence#subSequence(int, int)}, the one method of
 * the base class that is not a plain delegation to the safe string form.
 *
 * <p>The slice used to be taken as {@code subSequence(end, end)} - the
 * {@code start} argument was passed nowhere - so every slice of a safe
 * character sequence came back empty. Nothing in this tree noticed, because
 * {@code DnsName} strips a trailing dot off a {@code String} before wrapping it,
 * so the only in-tree caller never reaches this method; but a {@code CharSequence}
 * that answers {@code length()} with the full length and {@code subSequence()}
 * with nothing is a lie to any caller that walks it, including the JDK helpers
 * that copy one sequence into another.
 *
 * <p>The safe-form contract is pinned too: the slice must come from
 * {@code toSafeString()}, not from {@code toString()}, which is the whole reason
 * the class exists.
 */
public class SafeCharSequenceSubSequenceTest {

    /** The text every case slices. */
    private static final String TEXT = "abcdef";

    /**
     * A sequence whose safe form is {@link #TEXT}.
     *
     * @return a sequence over a known string
     */
    private static SafeCharSequence sequence() {
        return new SafeCharSequence() {
            @Override
            public String toString() {
                return TEXT;
            }
        };
    }

    /**
     * A sequence whose {@code toString()} is not safe to expose, so only
     * {@code toSafeString()} may reach the output.
     *
     * @return a sequence whose two forms differ
     */
    private static SafeCharSequence unsafeToStringSequence() {
        return new SafeCharSequence() {
            @Override
            public String toString() {
                return "<<unsafe>>";
            }

            @Override
            public String toSafeString() {
                return TEXT;
            }
        };
    }

    /**
     * The slice is the characters from start up to end, not the empty string the
     * discarded argument left behind.
     */
    @Test
    public void testSubSequenceReturnsTheRequestedSlice() {
        assertEquals("bcd", sequence().subSequence(1, 4).toString());
        assertEquals("a", sequence().subSequence(0, 1).toString());
        assertEquals("f", sequence().subSequence(5, 6).toString());
    }

    /**
     * The whole sequence is its own slice, so a caller that copies a sequence
     * end to end gets the text back.
     */
    @Test
    public void testFullSliceIsTheWholeSafeString() {
        SafeCharSequence seq = sequence();
        assertEquals(TEXT, seq.subSequence(0, seq.length()).toString());
    }

    /**
     * The slice agrees with the two methods that describe the same sequence, so
     * {@code length()} and {@code charAt()} cannot report a text that
     * {@code subSequence()} then withholds.
     */
    @Test
    public void testSliceAgreesWithLengthAndCharAt() {
        SafeCharSequence seq = sequence();
        for (int start = 0; start < TEXT.length(); start++) {
            for (int end = start; end <= TEXT.length(); end++) {
                CharSequence slice = seq.subSequence(start, end);
                assertEquals("slice length must match the range asked for",
                             end - start, slice.length());
                for (int i = 0; i < slice.length(); i++) {
                    assertEquals("slice character must match the sequence it came from",
                                 seq.charAt(start + i), slice.charAt(i));
                }
            }
        }
    }

    /**
     * The slice comes from the safe form. A subclass that overrides
     * {@code toSafeString()} because its {@code toString()} is unsafe would
     * otherwise leak the unsafe text through a slice.
     */
    @Test
    public void testSliceComesFromTheSafeForm() {
        SafeCharSequence seq = unsafeToStringSequence();
        assertEquals(TEXT, seq.toSafeString());
        assertEquals("bcd", seq.subSequence(1, 4).toString());
    }
}
