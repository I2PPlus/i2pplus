package org.klomp.snark.bencode;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Tests for BEncoder map key ordering.
 *
 * <p>The bencode spec requires dictionary keys to be sorted as raw byte strings, compared
 * byte by byte, with a key that is a prefix of another one ordered first. That is not the
 * same as sorting the key Strings, because bencode writes a String as its UTF-8 bytes:
 * UTF-16 code unit order and UTF-8 byte order disagree for every character above ASCII.
 *
 * <p>Ordering matters beyond cosmetics here. MetaInfo re-encodes the parsed info dictionary
 * to recompute the info hash, so a wrong key order makes a correctly bencoded torrent fail
 * with "Infohash mismatch".
 *
 * @since 0.9.71+
 */
public class BEncoderKeyOrderTest {

    /** A value of a single byte, keeping the generated encodings short. */
    private static final byte[] V = {(byte) 0x61};

    /**
     * Bencodes a map of the given keys to the same one-byte value and returns the keys in
     * the order they were written, decoded as UTF-8.
     */
    private static List<String> orderOf(String... keys) {
        Map<String, byte[]> m = new HashMap<>();
        for (String k : keys) {
            m.put(k, V);
        }
        return writtenKeys(BEncoder.bencode(m));
    }

    /**
     * Bencodes a map of the given byte[] keys to the same one-byte value and returns the
     * keys in the order they were written.
     */
    private static List<byte[]> orderOf(byte[]... keys) {
        Map<byte[], byte[]> m = new HashMap<>();
        for (byte[] k : keys) {
            m.put(k, V);
        }
        return writtenByteKeys(BEncoder.bencode(m));
    }

    /**
     * Reads back the keys of a bencoded dictionary whose values are all byte strings.
     */
    private static List<byte[]> writtenByteKeys(byte[] bencoded) {
        List<byte[]> rv = new ArrayList<>();
        int i = 1; // past the 'd'
        while (bencoded[i] != 'e') {
            int[] key = readByteString(bencoded, i);
            rv.add(Arrays.copyOfRange(bencoded, key[0], key[0] + key[1]));
            i = readByteString(bencoded, key[0] + key[1])[0] + 1; // skip the value
        }
        return rv;
    }

    /**
     * Reads a length-prefixed byte string at the given offset.
     *
     * @return the offset of the first payload byte and the payload length
     */
    private static int[] readByteString(byte[] bencoded, int at) {
        int colon = at;
        while (bencoded[colon] != ':') {
            colon++;
        }
        int len = Integer.parseInt(new String(bencoded, at, colon - at, StandardCharsets.ISO_8859_1));
        return new int[] {colon + 1, len};
    }

    private static List<String> writtenKeys(byte[] bencoded) {
        List<String> rv = new ArrayList<>();
        for (byte[] k : writtenByteKeys(bencoded)) {
            rv.add(new String(k, StandardCharsets.UTF_8));
        }
        return rv;
    }

    private static void assertOrder(List<?> actual, Object... expected) {
        assertEquals(expected.length, actual.size());
        for (int i = 0; i < expected.length; i++) {
            Object e = expected[i];
            if (e instanceof byte[]) {
                assertArrayEquals("key " + i, (byte[]) e, (byte[]) actual.get(i));
            } else {
                assertEquals("key " + i, e, actual.get(i));
            }
        }
    }

    /** Plain ASCII keys keep their ordinary order. */
    @Test
    public void testAsciiKeysSortedLexically() {
        assertOrder(
                orderOf("info", "comment", "announce-list", "announce"),
                "announce", "announce-list", "comment", "info");
    }

    /**
     * A key that is a prefix of another sorts first because it is shorter. A length-only
     * comparison gets "files" and "info" backwards, because the longer one is also the
     * earlier one.
     */
    @Test
    public void testPrefixAndLengthOrdering() {
        assertOrder(
                orderOf("info", "files", "announce-list", "announce"),
                "announce", "announce-list", "files", "info");
    }

    /**
     * Above ASCII the UTF-8 byte order is the reverse of the UTF-16 order: U+0100 encodes
     * as C4 80 and U+00E9 as C3 A9, so U+00E9 sorts first as bytes even though its code
     * point is the lower one.
     */
    @Test
    public void testTwoByteUtf8KeysSortByBytesNotChars() {
        assertOrder(orderOf("Ā", "é"), "é", "Ā");
    }

    /**
     * A three-byte character sorts after a two-byte one in UTF-8, while a supplementary
     * character (a surrogate pair in UTF-16, so a low code unit) sorts before it in UTF-16
     * order: the encodings disagree, so the bytes have to decide.
     */
    @Test
    public void testSupplementaryKeySortsAfterThreeByteKey() {
        // U+0800 (3 UTF-8 bytes) before U+10000 (4 UTF-8 bytes, UTF-16 surrogates D800 DC00)
        assertOrder(orderOf("\uD800\uDC00", "ࠀ"), "ࠀ", "\uD800\uDC00");
    }

    /**
     * Keys that are not valid UTF-8 can only be bencoded from byte[]s. That path must apply
     * the same rule: compare byte by byte, not by length, so "ab" precedes "b" even though
     * it is longer.
     */
    @Test
    public void testByteArrayKeysUseTheSameOrder() {
        byte[] b = {(byte) 0x62};
        byte[] ab = {(byte) 0x61, (byte) 0x62};
        assertOrder(orderOf(b, ab), ab, b);
    }

    /**
     * Both key types must be encoded in the same order, so a dictionary of String keys and
     * the same dictionary of byte[] keys is written identically.
     */
    @Test
    public void testStringAndByteArrayKeysAgree() {
        String[] names = {"announce", "comment", "info"};
        Map<String, byte[]> sm = new HashMap<>();
        Map<byte[], byte[]> bm = new HashMap<>();
        for (String k : names) {
            sm.put(k, V);
            bm.put(k.getBytes(StandardCharsets.ISO_8859_1), V);
        }
        assertArrayEquals(BEncoder.bencode(sm), BEncoder.bencode(bm));
    }

    /**
     * A dictionary decoded from the wire and re-encoded must be byte identical, otherwise
     * the info hash recomputed from the re-encoded bytes would not match the original.
     */
    @Test
    public void testAsciiDictionaryRoundTrips() throws Throwable {
        String[] keys = {
            "announce", "announce-list", "comment", "created by", "creation date", "encoding",
            "info", "url-list"
        };
        Map<String, byte[]> m = new HashMap<>();
        for (String k : keys) {
            m.put(k, V);
        }
        byte[] encoded = BEncoder.bencode(m);
        Map<String, BEValue> decoded = new BDecoder(new ByteArrayInputStream(encoded)).bdecodeMap().getMap();
        assertEquals(keys.length, decoded.size());
        assertArrayEquals(encoded, BEncoder.bencode(decoded));
    }

    /** Encoding to a stream and encoding to a byte array produce the same bytes. */
    @Test
    public void testBencodeToStreamMatchesBencodeToBytes() throws IOException {
        Map<String, byte[]> m = new HashMap<>();
        m.put("k", V);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BEncoder.bencode(m, baos);
        assertArrayEquals(BEncoder.bencode(m), baos.toByteArray());
    }
}
