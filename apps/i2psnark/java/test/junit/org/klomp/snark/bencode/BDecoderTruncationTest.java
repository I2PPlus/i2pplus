package org.klomp.snark.bencode;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Tests that BDecoder stops on a bencoded message that ends in the middle of a list or a
 * dictionary, and that it bounds what a length in the message may ask it to allocate.
 *
 * <p>Every message tested here is the kind a peer, a DHT node or a tracker writes: the
 * decoder is told how long the message is, or has nothing but the message to go on, so a
 * container that is never closed, a length larger than the message that carries it, and a
 * number that is really a run of digits are all things somebody can put on the wire.
 *
 * <p>The tests carry a timeout. Before the decoder treated end of stream as an ordinary list
 * element, an unclosed list decoded forever, growing a list of nulls until the heap was gone,
 * so a test that only checked the exception would have hung rather than failed.
 *
 * @since 0.9.72+
 */
public class BDecoderTruncationTest {

    /** How long one decode may take before the test calls it a hang. */
    private static final long TIMEOUT_MS = 5000;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one; the
     * decoders under test are built inside each method and carry no state here.
     *
     * @since 0.9.72+
     */
    public BDecoderTruncationTest() {}

    /**
     * A decoder over the given bencoded text, with no limit tighter than the decoder's own
     * default — the shape used for a torrent file, whose size is not known in advance.
     *
     * @param bencoded the bencoded text to decode
     * @return a decoder positioned at the start of it
     */
    private static BDecoder decoder(String bencoded) {
        return new BDecoder(new ByteArrayInputStream(bencoded.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * A decoder limited to the size of the message it is given — the shape used for a peer
     * extension message or a DHT datagram, which is already wholly in memory.
     *
     * @param bencoded the bencoded text to decode
     * @return a decoder that will not allocate a string longer than that text
     */
    private static BDecoder sizedDecoder(String bencoded) {
        byte[] bs = bencoded.getBytes(StandardCharsets.UTF_8);
        return new BDecoder(new ByteArrayInputStream(bs), bs.length);
    }

    /**
     * A bencoded number: {@code i}, the digits, then the closing {@code e}.
     *
     * @param digits the digits to write, without a sign
     * @return the bencoded text
     */
    private static String number(String digits) {
        return "i" + digits + "e";
    }

    /**
     * A run of one digit, long enough to be a problem rather than a value.
     *
     * @param count how many digits to write
     * @return that many sevens
     */
    private static String digits(int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append('7');
        }
        return sb.toString();
    }

    /**
     * A list that is never closed: its elements are there, the terminating {@code e} is not.
     * This is what a truncated peer message looks like.
     *
     * @throws Throwable if the decode does not stop at the truncation
     */
    @Test(timeout = TIMEOUT_MS)
    public void testUnclosedListIsAnError() throws Throwable {
        try {
            decoder("li1e").bdecodeList();
            fail("a list that never ends decoded as a value");
        } catch (EOFException e) {
            // expected
        }
    }

    /**
     * An unclosed list nested in another unclosed list.
     *
     * @throws Throwable if the decode does not stop at the truncation
     */
    @Test(timeout = TIMEOUT_MS)
    public void testUnclosedNestedListIsAnError() throws Throwable {
        try {
            decoder("ll").bdecodeList();
            fail("a nested list that never ends decoded as a value");
        } catch (EOFException e) {
            // expected
        }
    }

    /**
     * The PEX shape from the field: a dictionary whose "added" value is a list the stream
     * stops in the middle of.
     *
     * @throws Throwable if the decode does not stop at the truncation
     */
    @Test(timeout = TIMEOUT_MS)
    public void testUnclosedListInADictionaryIsAnError() throws Throwable {
        try {
            decoder("d5:addedl").bdecodeMap();
            fail("a dictionary holding an unclosed list decoded as a value");
        } catch (EOFException e) {
            // expected
        }
    }

    /**
     * A dictionary that ends after a key with no value. This used to be a bare
     * NullPointerException, which says nothing about the message that caused it.
     *
     * @throws Throwable if the decode does not stop at the truncation
     */
    @Test(timeout = TIMEOUT_MS)
    public void testDictionaryKeyWithNoValueIsAnError() throws Throwable {
        try {
            decoder("d3:foo").bdecodeMap();
            fail("a key with no value decoded as a dictionary");
        } catch (EOFException e) {
            // expected
        }
    }

    /**
     * A dictionary that ends in the middle of its key.
     *
     * @throws Throwable if the decode does not stop at the truncation
     */
    @Test(timeout = TIMEOUT_MS)
    public void testDictionaryKeyCutShortIsAnError() throws Throwable {
        try {
            decoder("d3:fo").bdecodeMap();
            fail("a key shorter than it claims decoded as a dictionary");
        } catch (EOFException e) {
            // expected
        }
    }

    /**
     * A byte string that ends before the length in its header has been read.
     *
     * @throws Throwable if the decode does not stop at the truncation
     */
    @Test(timeout = TIMEOUT_MS)
    public void testByteStringCutShortIsAnError() throws Throwable {
        try {
            decoder("d5:addedl2:ab2:c").bdecodeMap();
            fail("a byte string shorter than it claims decoded as a value");
        } catch (EOFException e) {
            // expected
        }
    }

    /**
     * What a whole message looks like: the same PEX dictionary, closed, still decodes to the
     * list it holds. The bounds added for truncated input must not reject valid input.
     *
     * @throws Throwable if the dictionary does not decode
     */
    @Test(timeout = TIMEOUT_MS)
    public void testClosedDictionaryStillDecodes() throws Throwable {
        Map<String, BEValue> map = sizedDecoder("d5:addedl2:ab2:cdee").bdecodeMap().getMap();
        List<BEValue> added = map.get("added").getList();
        assertEquals(2, added.size());
        assertArrayEquals(new byte[] {'a', 'b'}, added.get(0).getBytes());
        assertArrayEquals(new byte[] {'c', 'd'}, added.get(1).getBytes());
    }

    /**
     * Empty containers are closed containers, and stay valid.
     *
     * @throws Throwable if either empty container does not decode
     */
    @Test(timeout = TIMEOUT_MS)
    public void testEmptyContainersDecode() throws Throwable {
        assertEquals(0, decoder("le").bdecodeList().getList().size());
        assertEquals(0, decoder("de").bdecodeMap().getMap().size());
    }

    /**
     * End of stream is only a value at the very top: a stream with nothing on it answers
     * null, so a caller reading a sequence of values can tell where it ends.
     *
     * @throws Throwable if the empty stream does not decode to null
     */
    @Test(timeout = TIMEOUT_MS)
    public void testEmptyStreamIsStillNotNullAtTheTopLevel() throws Throwable {
        assertNull(decoder("").bdecode());
    }

    /**
     * A number that is a run of digits with no end. The digit count is bounded while it is
     * read, so the text never gets big enough to be the problem itself.
     *
     * @throws Throwable if the run of digits is accepted
     */
    @Test(timeout = TIMEOUT_MS)
    public void testEndlessDigitsIsAnError() throws Throwable {
        try {
            decoder("i" + digits(200000)).bdecodeNumber();
            fail("a number with two hundred thousand digits decoded");
        } catch (InvalidBEncodingException e) {
            // expected
        }
    }

    /**
     * One digit past the limit is rejected.
     *
     * @throws Throwable if the 257-digit number is accepted
     */
    @Test(timeout = TIMEOUT_MS)
    public void testTooManyDigitsIsAnError() throws Throwable {
        try {
            decoder(number(digits(257))).bdecodeNumber();
            fail("a number with 257 digits decoded");
        } catch (InvalidBEncodingException e) {
            // expected
        }
    }

    /**
     * The limit itself is still a number, and keeps its value.
     *
     * @throws Throwable if the 256-digit number does not decode
     */
    @Test(timeout = TIMEOUT_MS)
    public void testLimitOfDigitsStillDecodes() throws Throwable {
        String s = digits(256);
        BEValue v = decoder(number(s)).bdecodeNumber();
        assertEquals(new BigInteger(s), v.getNumber());
    }

    /**
     * A length that claims more bytes than the message holding it could contain. A decoder
     * built for a message in hand is sized to that message, so the allocation is refused
     * before it happens.
     *
     * @throws Throwable if the oversized string is accepted
     */
    @Test(timeout = TIMEOUT_MS)
    public void testStringLongerThanItsMessageIsRefused() throws Throwable {
        try {
            sizedDecoder("40000000:x").bdecodeBytes();
            fail("a 40 MB string claimed by a 10 byte message was allocated");
        } catch (InvalidBEncodingException e) {
            // expected
        }
    }

    /**
     * The same claim against a decoder that has no message size to go on is still refused, at
     * the decoder's own ceiling, and a string under that ceiling is read.
     *
     * @throws Throwable if the 70 MB string is accepted or the short one is refused
     */
    @Test(timeout = TIMEOUT_MS)
    public void testDefaultCeilingStillApplies() throws Throwable {
        try {
            decoder("70000000:x").bdecodeBytes();
            fail("a 70 MB string was allocated by the default decoder");
        } catch (InvalidBEncodingException e) {
            // expected
        }
        assertArrayEquals(new byte[] {'h', 'e', 'l', 'l', 'o'},
                          sizedDecoder("5:hello").bdecodeBytes().getBytes());
    }
}
