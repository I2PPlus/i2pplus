/* BDecoder - Converts an InputStream to BEValues.
   Copyright (C) 2003 Mark J. Wielaard
   This file is part of Snark.
   Licensed under the GPL version 2 or later.
*/

package org.klomp.snark.bencode;

import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
/**
 * Decodes a bencoded stream to <code>BEValue</code>s.
 *
 * <p>A bencoded byte stream can represent byte arrays, numbers, lists and maps (dictionaries).
 *
 * <p>It also calculates, on request, the SHA-1 digest over the original bencoded bytes of the
 * dictionary named "info", the info dictionary of a torrent. The info hash is defined as
 * the digest of those bytes, which cannot be recomputed from the decoded map without
 * re-encoding it byte for byte, so the bytes are hashed as they stream past.
 *
 * <p>What it decodes is often written by somebody else — a peer's extension message, a DHT
 * datagram, a tracker's reply — so nothing is allocated before it is bounded: a byte string
 * over the decoder's limit, a number with more digits than it will read, and nesting deeper
 * than it will follow are rejected, and a list or a map the stream stops in the middle of is
 * an error rather than a value. Only a stream that ends before its first top-level value is
 * not an error: {@link #bdecode()} answers <code>null</code> for that.
 */
public class BDecoder {

    /**
     * Largest byte array that may be allocated, to keep an attacker-controlled
     * length from exhausting memory.
     */
    private static final int MAX_BYTE_ARRAY_LENGTH = 64 * 1024 * 1024;

    /**
     * Deepest nesting of lists and maps allowed, to stop a StackOverflowError
     * on attacker-controlled input.
     */
    private static final int MAX_DEPTH = 64;

    /**
     * Most digits a bencoded number may carry, to stop a stream of digits from
     * filling memory before it is parsed. A number longer than this is not a
     * number anyone meant: the widest value that fits a BigInteger usefully in
     * this protocol is a 40-character SHA-1 hash, and that is a string, not a
     * number.
     */
    private static final int MAX_DIGITS = 256;

    /** The stream bencode is read from. */
    private final InputStream in;

    /**
     * Largest byte string this decoder may allocate, from the constructor. It is
     * the size of the message being decoded wherever the caller has the whole
     * message in hand, so a length in the header cannot claim more memory than
     * the message could possibly hold; a stream of unknown length, a torrent
     * file, keeps {@link #MAX_BYTE_ARRAY_LENGTH}.
     */
    private final int maxStringLength;

    /**
     * The last indicator read, zero if unknown: '0'..'9' indicates a byte[],
     * 'i' a Number, 'l' a List, 'd' a Map, 'e' the end of a Number, List or
     * Map (only used internally), and -1 the end of the stream. Call
     * getNextIndicator to get the current value (it will never return zero).
     */
    private int indicator = 0;

    /** The current nesting depth of lists and maps being decoded. */
    private int depth = 0;

    /**
     * Name of the dictionary whose bytes are hashed: the info dictionary of a torrent.
     *
     * @see #get_special_map_digest()
     */
    private static final String special_map = "info";

    /** True while the bytes being read belong to the info dictionary. */
    private boolean in_special_map = false;

    /**
     * Creation deferred until we encounter the special map, to make processing of announce replies
     * more efficient.
     */
    private MessageDigest sha_digest;

    /**
     * Returns the SHA-1 digest over the original bytes of the info dictionary, which is the
     * torrent's info hash. Must be called after that dictionary has been decoded, and only
     * once: the digest is finalized by this call.
     *
     * @return the SHA-1 digest of the info dictionary bytes, or null if there was no info
     *         dictionary in the stream
     */
    public byte[] get_special_map_digest() {
        if (sha_digest == null) return null;
        byte[] result = sha_digest.digest();
        return result;
    }

    /**
     * Initializes a new BDecoder. Nothing is read from the given InputStream yet.
     * A byte string it decodes may be as large as {@link #MAX_BYTE_ARRAY_LENGTH}.
     *
     * @param in the InputStream to decode from
     */
    public BDecoder(InputStream in) {
        this(in, MAX_BYTE_ARRAY_LENGTH);
    }

    /**
     * Initializes a new BDecoder that will not allocate a byte string bigger than
     * the message it is decoding. Nothing is read from the stream yet.
     *
     * Use this wherever the whole message is already in memory — a peer extension
     * message, a DHT datagram — so a length in the bencode cannot ask for more
     * than the message could hold.
     *
     * @param in the InputStream to decode from
     * @param maxStringLength the size of that message in bytes, a length of 0 or
     *                        less allowing only an empty string
     * @since 0.9.72+
     */
    public BDecoder(InputStream in, int maxStringLength) {
        this.in = in;
        this.maxStringLength = Math.max(maxStringLength, 0);
    }

    /**
     * Creates a new BDecoder and immediately decodes the first value it sees.
     *
     * @param in the InputStream to decode from
     * @return the first BEValue on the stream, or null when the stream has ended
     * @throws IOException when something bad happens with the stream
     * @throws StackOverflowError if the stream is nested too deeply to decode
     */
    public static BEValue bdecode(InputStream in) throws IOException, Throwable {
        return new BDecoder(in).bdecode();
    }

    /**
     * Start hashing the info dictionary bytes. Only the first info dictionary of a stream is
     * hashed: a second one, which the spec does not allow, would make the digest
     * wrong, and MetaInfo's constructor rejects the resulting info hash mismatch.
     */
    private void createDigest() {
        if (sha_digest == null) {
            try {
                sha_digest = MessageDigest.getInstance("SHA");
            } catch (NoSuchAlgorithmException nsa) {
                throw new InternalError(nsa.toString());
            }
        }
    }

    /**
     * Returns what the next bencoded object will be on the stream, or -1 at end of stream.
     * Can return something unexpected when the stream isn't bencoded.
     * This might or might not read one extra byte from the stream.
     *
     * @return the next indicator character, or -1 at end of stream
     * @throws IOException if an I/O error occurs
     */
    public int getNextIndicator() throws IOException {
        if (indicator == 0) {
            indicator = in.read();
            // part of the info dictionary: it belongs in the info hash
            if (in_special_map) sha_digest.update((byte) indicator);
        }
        return indicator;
    }

    /**
     * Read the next indicator, returning null when the stream has ended, or bdecode the
     * rest of the stream and returns the appropriate BEValue encoded object.
     *
     * @return the next BEValue on the stream, or null if the stream has ended
     * @throws IOException if an I/O error occurs, the stream is not bencoded, or a
     *                     list or map in it is not closed before the stream ends
     * @throws StackOverflowError if the stream is nested too deeply to decode
     */
    public BEValue bdecode() throws IOException, Throwable {
        indicator = getNextIndicator();
        if (indicator == -1) return null;

        if (indicator >= '0' && indicator <= '9') return bdecodeBytes();
        else if (indicator == 'i') return bdecodeNumber();
        else if (indicator == 'l') return bdecodeList();
        else if (indicator == 'd') return bdecodeMap();
        else throw new InvalidBEncodingException("Unknown indicator '" + indicator + "'");
    }

    /**
     * Returns the next bencoded value on the stream and makes sure it is a byte array. If it is not
     * a bencoded byte array it will throw InvalidBEncodingException.
     *
     * @return the next BEValue as a byte array
     * @throws IOException if an I/O error occurs
     * @throws InvalidBEncodingException if the next value is not a byte array
     */
    public BEValue bdecodeBytes() throws IOException {
        int c = getNextIndicator();
        int num = c - '0';
        if (num < 0 || num > 9)
            throw new InvalidBEncodingException("Number expected, not '" + (char) c + "'");
        indicator = 0;

        c = read();
        int i = c - '0';
        while (i >= 0 && i <= 9) {
            if (num > (maxStringLength - i) / 10) {
                throw new InvalidBEncodingException("Byte array length too large");
            }
            num = num * 10 + i;
            c = read();
            i = c - '0';
        }

        if (c != ':') throw new InvalidBEncodingException("Colon expected, not '" + (char) c + "'");

        return new BEValue(read(num));
    }

    /**
     * Returns the next bencoded value on the stream and makes sure it is a number. If it is not a
     * number it will throw InvalidBEncodingException.
     *
     * @return the next BEValue as a Number
     * @throws IOException if an I/O error occurs
     * @throws InvalidBEncodingException if the next value is not a number, or if it
     *                                   carries more digits than the decoder will
     *                                   read
     */
    public BEValue bdecodeNumber() throws IOException {
        int c = getNextIndicator();
        if (c != 'i') throw new InvalidBEncodingException("Expected 'i', not '" + (char) c + "'");
        indicator = 0;

        c = read();
        if (c == '0') {
            c = read();
            if (c == 'e') return new BEValue(Integer.valueOf(0));
            else
                throw new InvalidBEncodingException(
                        "'e' expected after zero," + " not '" + (char) c + "'");
        }

        // collected as text, then parsed as the narrowest type that fits, down to
        // BigInteger for 19 digits or more
        StringBuilder chars = new StringBuilder(16);

        if (c == '-') {
            chars.append((char) c);
            c = read();
        }

        if (c < '1' || c > '9')
            throw new InvalidBEncodingException("Invalid Integer start '" + (char) c + "'");
        chars.append((char) c);
        int digits = 1;

        c = read();
        while (c >= '0' && c <= '9') {
            // bounded as it is read: an unbounded run of digits would fill memory
            // before a length check after the fact could see it
            if (++digits > MAX_DIGITS)
                throw new InvalidBEncodingException("Too many digits: " + digits);
            chars.append((char) c);
            c = read();
        }

        if (c != 'e') throw new InvalidBEncodingException("Integer should end with 'e'");

        String s = chars.toString();
        int len = s.length();
        // save a little space if we're sure it will fit
        Number num;
        if (len < 10) {
            try {
                num = Integer.valueOf(s);
            } catch (NumberFormatException nfe) {
                num = Long.valueOf(s);
            }
        } else if (len < 19) {
            try {
                num = Long.valueOf(s);
            } catch (NumberFormatException nfe) {
                num = new BigInteger(s);
            }
        } else num = new BigInteger(s);
        return new BEValue(num);
    }

    /**
     * Returns the next bencoded value on the stream and makes sure it is a list. If it is not a
     * list it will throw InvalidBEncodingException.
     *
     * @return the next BEValue as a List
     * @throws IOException if an I/O error occurs or the list is not closed before the stream ends
     * @throws InvalidBEncodingException if the next value is not a list
     * @throws StackOverflowError if the stream is nested too deeply to decode
     */
    public BEValue bdecodeList() throws IOException, Throwable {
        int c = getNextIndicator();
        if (c != 'l') throw new InvalidBEncodingException("Expected 'l', not '" + (char) c + "'");
        indicator = 0;
        if (++depth > MAX_DEPTH) {
            throw new InvalidBEncodingException("Too many nested lists or maps");
        }

        try {
            List<BEValue> result = new ArrayList<>();
            c = getNextIndicator();
            while (c != 'e') {
                // End of stream inside a list is a truncated message, not an element:
                // bdecode() answers null for it, and adding nulls forever would grow
                // this list until the heap is gone.
                if (c == -1)
                    throw new EOFException("List ends without a closing 'e'");
                BEValue value = bdecode();
                if (value == null)
                    throw new EOFException("List ends without a closing 'e'");
                result.add(value);
                c = getNextIndicator();
            }
            indicator = 0;

            return new BEValue(result);
        } finally {
            depth--;
        }
    }

    /**
     * Returns the next bencoded value on the stream and makes sure it is a map (dictionary). If it
     * is not a map it will throw InvalidBEncodingException.
     *
     * @return the next BEValue as a Map
     * @throws IOException if an I/O error occurs or the dictionary is not closed
     *                     before the stream ends, or ends without a value for a key
     * @throws InvalidBEncodingException if the next value is not a map
     * @throws StackOverflowError if the stream is nested too deeply to decode
     */
    public BEValue bdecodeMap() throws IOException, Throwable {
        int c = getNextIndicator();
        if (c == '<')
            throw new InvalidBEncodingException(
                    "Expected a .torrent metainfo file but found HTML? Check URL or file!");
        else if (c != 'd')
            throw new InvalidBEncodingException("Expected 'd', not '" + (char) c + "'");
        indicator = 0;
        if (++depth > MAX_DEPTH) {
            throw new InvalidBEncodingException("Too many nested lists or maps");
        }

        try {
            Map<String, BEValue> result = new HashMap<>();
            c = getNextIndicator();
            while (c != 'e') {
                // End of stream inside a dictionary is a truncated message, not a
                // missing key: bdecode() answers null for it, and the only report the
                // reader got was a bare NullPointerException.
                if (c == -1)
                    throw new EOFException("Dictionary ends without a closing 'e'");
                // Dictionary keys are always strings.
                BEValue keyVal = bdecode();
                if (keyVal == null)
                    throw new EOFException("Dictionary ends without a closing 'e'");
                String key = keyVal.getString();

                // Hash the info dictionary's bytes as they are read. An info dictionary
                // nested in another one is handled correctly, because in_special_map is
                // already set; a second, unnested info dictionary is hashed incorrectly,
                // which MetaInfo's info hash comparison rejects.
                boolean special = (!in_special_map) && special_map.equals(key);
                if (special) {
                    createDigest();
                    in_special_map = true;
                }

                BEValue value = bdecode();
                if (value == null)
                    throw new EOFException("Dictionary ends without a value for " + key);
                BEValue old = result.put(key, value);
                if (old != null)
                    throw new InvalidBEncodingException("Duplicate key " + key + " in dictionary");

                if (special) in_special_map = false;

                c = getNextIndicator();
            }
            indicator = 0;

            return new BEValue(result);
        } finally {
            depth--;
        }
    }

    /**
     * Returns the next byte read from the InputStream.
     *
     * @return the next byte as an int (0-255)
     * @throws EOFException if end of stream is reached
     * @throws IOException if an I/O error occurs
     */
    private int read() throws IOException {
        int c = in.read();
        if (c == -1) throw new EOFException();
        if (in_special_map) sha_digest.update((byte) c);
        return c;
    }

    /**
     * Reads the specified number of bytes from the InputStream.
     *
     * @param length the number of bytes to read
     * @return a byte array containing the requested bytes
     * @throws EOFException if end of stream is reached before all bytes are read
     * @throws IOException if an I/O error occurs
     */
    private byte[] read(int length) throws IOException {
        byte[] result = new byte[length];

        int read = 0;
        while (read < length) {
            int i = in.read(result, read, length - read);
            if (i == -1) throw new EOFException();
            read += i;
        }

        if (in_special_map) sha_digest.update(result, 0, length);
        return result;
    }

    /**
     * Prints out the decoded data from a torrent file.
     *
     * @param args command line arguments (expects one argument: the torrent file path)
     */
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: BDecoder file.torrent");
            System.exit(1);
        }
        try {
            BEValue bev = bdecode(new FileInputStream(args[0]));
            System.out.println(bev.toString());
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }
}
