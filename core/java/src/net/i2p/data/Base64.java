package net.i2p.data;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Encodes and decodes to and from Base64 notation.
 *
 * <p>
 * Change Log:
 * </p>
 * <ul>
 * <li>v1.3.6 - Fixed OutputStream.flush() so that 'position' is reset.</li>
 * <li>v1.3.5 - Added flag to turn on and off line breaks. Fixed bug in input stream
 * where last buffer being read, if not completely full, was not returned.</li>
 * <li>v1.3.4 - Fixed when "improperly padded stream" error was thrown at the wrong time.</li>
 * <li>v1.3.3 - Fixed I/O streams which were totally messed up.</li>
 * </ul>
 *
 * <p>
 * I am placing this code in the Public Domain. Do with it as you will.
 * This software comes with no guarantees or warranties but with
 * plenty of well-wishing instead!
 * Please visit <a href="http://iharder.net/xmlizable">http://iharder.net/xmlizable</a>
 * periodically to check for updates or to contribute improvements.
 * </p>
 *
 * Modified by jrandom for i2p, using safeEncode / safeDecode to create filesystem and URL safe
 * base64 values (replacing / with ~, and + with -)
 *
 * @author Robert Harder
 * @author rob@iharder.net
 * @version 1.3.4
 */
public class Base64 {


    /**
     * Output will be a multiple of 4 chars, including 0-2 trailing '='
     * As of 0.9.14, encodes the UTF-8 encoding of source. Prior to that, used the platform's encoding.
     *
     * @param source the string to encode, if null will return ""
     * @return the Base64 encoded string
     */
    public static String encode(String source) {
        return (source != null ? encode(DataHelper.getUTF8(source)) : "");
    }

    /**
     * Output will be a multiple of 4 chars, including 0-2 trailing '='
     *
     * @param source the byte array to encode, if null will return ""
     * @return the Base64 encoded string
     */
    public static String encode(byte[] source) {
        return (source != null ? encode(source, 0, source.length) : "");
    }

    /**
     * Output will be a multiple of 4 chars, including 0-2 trailing '='
     *
     * @param source the byte array to encode, if null will return ""
     * @param off the offset in the source array
     * @param len the length of data to encode
     * @return the Base64 encoded string
     */
    public static String encode(byte[] source, int off, int len) {
        return (source != null ? encode(source, off, len, false) : "");
    }

    /**
     * Output will be a multiple of 4 chars, including 0-2 trailing '='
     *
     * @param source the byte array to encode, if null will return ""
     * @param useStandardAlphabet Warning, must be false for I2P compatibility
     * @return the Base64 encoded string
     */
    public static String encode(byte[] source, boolean useStandardAlphabet) {
        return (source != null ? encode(source, 0, source.length, useStandardAlphabet) : "");
    }

    /**
     * Output will be a multiple of 4 chars, including 0-2 trailing '='
     *
     * @param source the byte array to encode, if null will return ""
     * @param off the offset in the source array
     * @param len the length of data to encode
     * @param useStandardAlphabet Warning, must be false for I2P compatibility
     * @return the Base64 encoded string
     */
    public static String encode(byte[] source, int off, int len, boolean useStandardAlphabet) {
        return (source != null ? safeEncode(source, off, len, useStandardAlphabet) : "");
    }

    /**
     * Decodes data from Base64 notation using the I2P alphabet.
     *
     * As of 0.9.14, does not require trailing '=' if remaining bits are zero.
     * Prior to that, trailing 1, 2, or 3 chars were ignored.
     *
     * As of 0.9.14, trailing garbage after an '=' will cause an error.
     * Prior to that, it was ignored.
     *
     * As of 0.9.14, whitespace will cause an error.
     * Prior to that, it was ignored.
     *
     * @param s Base 64 encoded string using the I2P alphabet A-Z, a-z, 0-9, -, ~
     * @return the decoded data, null on error
     */
    public static byte[] decode(String s) {
        return safeDecode(s, false);
    }

    /**
     * Decodes data from Base64 notation using the I2P alphabet.
     *
     * @param s the Base64 encoded string
     * @param useStandardAlphabet Warning, must be false for I2P compatibility
     * @return the decoded data, null on error
     * @since 0.9.25
     */
    public static byte[] decode(String s, boolean useStandardAlphabet) {
        return safeDecode(s, useStandardAlphabet);
    }

    /** Maximum line length (76) of Base64 output. */
    private final static int MAX_LINE_LENGTH = 76;

    /** The equals sign (=) as a byte. */
    private final static byte EQUALS_SIGN = (byte) '=';

    /** The 64 valid Base64 values. */
    private final static byte[] ALPHABET = { (byte) 'A', (byte) 'B', (byte) 'C', (byte) 'D', (byte) 'E', (byte) 'F',
                                            (byte) 'G', (byte) 'H', (byte) 'I', (byte) 'J', (byte) 'K', (byte) 'L',
                                            (byte) 'M', (byte) 'N', (byte) 'O', (byte) 'P', (byte) 'Q', (byte) 'R',
                                            (byte) 'S', (byte) 'T', (byte) 'U', (byte) 'V', (byte) 'W', (byte) 'X',
                                            (byte) 'Y', (byte) 'Z', (byte) 'a', (byte) 'b', (byte) 'c', (byte) 'd',
                                            (byte) 'e', (byte) 'f', (byte) 'g', (byte) 'h', (byte) 'i', (byte) 'j',
                                            (byte) 'k', (byte) 'l', (byte) 'm', (byte) 'n', (byte) 'o', (byte) 'p',
                                            (byte) 'q', (byte) 'r', (byte) 's', (byte) 't', (byte) 'u', (byte) 'v',
                                            (byte) 'w', (byte) 'x', (byte) 'y', (byte) 'z', (byte) '0', (byte) '1',
                                            (byte) '2', (byte) '3', (byte) '4', (byte) '5', (byte) '6', (byte) '7',
                                            (byte) '8', (byte) '9', (byte) '+', (byte) '/'};

    /**
     * The I2P Alphabet.
     *
     * @since 0.9.29
     */
    public static final String ALPHABET_I2P = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-~";

    /** The 64 valid Base64 values for I2P. */
    private final static byte[] ALPHABET_ALT = DataHelper.getASCII(ALPHABET_I2P);

    /**
     * Translates a Base64 value to either its 6-bit reconstruction value
     * or a negative number indicating some other meaning.
     * As of 0.9.14 this is the decoding for the I2P alphabet. See safeDecode().
     */
    private final static byte[] DECODABET = {
        -9, -9, -9, -9, -9, -9, -9, -9, -9,                     // Decimal  0 -  8
        -5, -5,                                                 // Whitespace: Tab and Linefeed
        -9, -9,                                                 // Decimal 11 - 12
        -5,                                                     // Whitespace: Carriage Return
        -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9,     // Decimal 14 - 26
        -9, -9, -9, -9, -9,                                     // Decimal 27 - 31
        -5,                                                     // Whitespace: Space
        -9, -9, -9, -9, -9, -9, -9, -9, -9, -9,                 // Decimal 33 - 42
        //62, -9, -9, -9, 63,                                   // + , - . / (43-47) NON-I2P
        -9, -9, 62, -9, -9,                                     // + , - . / (43-47) I2P
        52, 53, 54, 55, 56, 57, 58, 59, 60, 61,                 // Numbers zero through nine
        -9, -9, -9,                                             // Decimal 58 - 60
        -1,                                                     // Equals sign at decimal 61
        -9, -9, -9,                                             // Decimal 62 - 64
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13,           // Letters 'A' through 'N'
        14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25,         // Letters 'O' through 'Z'
        -9, -9, -9, -9, -9, -9,                                 // Decimal 91 - 96
        26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38,     // Letters 'a' through 'm'
        39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51,     // Letters 'n' through 'z'
        //-9, -9, -9, -9                                        // Decimal 123 - 126 (126 is '~') NON-I2P
        -9, -9, -9, 63,                                         // Decimal 123 - 126 (126 is '~') I2P
        -9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,                 // Decimal 127 - 139
        -9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,                 // Decimal 140 - 152
        -9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,                 // Decimal 153 - 165
        -9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,                 // Decimal 166 - 178
        -9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,                 // Decimal 179 - 191
        -9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,                 // Decimal 192 - 204
        -9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,                 // Decimal 205 - 217
        -9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,                 // Decimal 218 - 230
        -9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,                 // Decimal 231 - 243
        -9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9                     // Decimal 244 - 255
    };

    /** Defeats instantiation. */
    private Base64() { // nop
    }

    /**
     * Command-line tool for encoding and decoding Base64.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            help();
        }
        runApp(args);
    }

    private static void runApp(String[] args) {
        String cmd = args[0].toLowerCase(Locale.US);
        if ("encodestring".equals(cmd)) {
            if (args.length != 2)
                help();
            System.out.println(encode(DataHelper.getUTF8(args[1])));
            return;
        }
        if ("decodestring".equals(cmd)) {
            if (args.length != 2)
                help();
            byte[] dec = decode(args[1]);
            if (dec != null) {
                try {
                    System.out.write(dec);
                } catch (IOException ioe) {
                    System.err.println("output error " + ioe);
                    System.exit(1);
                }
            } else {
                System.err.println("decode error");
                System.exit(1);
            }
            return;
        }
        if ("test".equals(cmd)) {
            System.err.println("test disabled");
            System.exit(1);
        }
        if (!("encode".equals(cmd) || "decode".equals(cmd))) {
            System.err.println("unknown command " + cmd);
            System.exit(1);
        }
        try (InputStream fin = args.length >= 2 ? new FileInputStream(args[1]) : null;
             OutputStream fout = args.length >= 3 ? new FileOutputStream(args[2]) : null) {
            InputStream in = fin != null ? fin : System.in;
            OutputStream out = fout != null ? fout : System.out;
            if ("encode".equals(cmd)) {
                encode(in, out);
            } else {
                decode(in, out);
            }
        } catch (IOException ioe) {
            ioe.printStackTrace(System.err);
        }
    }

    private static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
        DataHelper.copy(in, baos);
        return baos.toByteArray();
    }

    private static void encode(InputStream in, OutputStream out) throws IOException {
        String encoded = encode(read(in));
        for (int i = 0; i < encoded.length(); i++)
            out.write((byte)(encoded.charAt(i) & 0xFF));
    }

    private static void decode(InputStream in, OutputStream out) throws IOException {
        byte[] decoded = decode(DataHelper.getUTF8(read(in)));
        if (decoded == null)
            throw new IOException("Invalid base 64 string");
        out.write(decoded);
    }

    /** Exits 1, never returns. */
    private static void help() {
        System.err.println("Usage: Base64 encode <inFile> <outFile>");
        System.err.println("       Base64 encode <inFile>");
        System.err.println("       Base64 encode (stdin to stdout)");
        System.err.println("       Base64 decode <inFile> <outFile>");
        System.err.println("       Base64 decode <inFile>");
        System.err.println("       Base64 decode (stdin to stdout)");
        System.err.println("       Base64 encodestring 'string to encode'");
        System.err.println("       Base64 decodestring 'string to decode'");
        System.err.println("       Base64 test");
        System.exit(1);
    }

    /* ********  E N C O D I N G   M E T H O D S  ******** */

/* unused (standard alphabet)
    private static byte[] encode3to4(byte[] source, int srcOffset, int numSigBytes, byte[] destination, int destOffset) {
        //           1         2         3
        // 01234567890123456789012345678901 Bit position
        // --------000000001111111122222222 Array position from threeBytes
        // --------|    ||    ||    ||    | Six bit groups to index ALPHABET
        //          >>18  >>12  >> 6  >> 0  Right shift necessary
        //                0x3f  0x3f  0x3f  Additional AND

        // Create buffer with zero-padding if there are only one or two
        // significant bytes passed in the array.
        // We have to shift left 24 in order to flush out the 1's that appear
        // when Java treats a value as negative that is cast from a byte to an int.
        int inBuff = (numSigBytes > 0 ? ((source[srcOffset] << 24) >>> 8) : 0)
                     | (numSigBytes > 1 ? ((source[srcOffset + 1] << 24) >>> 16) : 0)
                     | (numSigBytes > 2 ? ((source[srcOffset + 2] << 24) >>> 24) : 0);

        switch (numSigBytes) {
        case 3:
            destination[destOffset] = ALPHABET[(inBuff >>> 18)];
            destination[destOffset + 1] = ALPHABET[(inBuff >>> 12) & 0x3f];
            destination[destOffset + 2] = ALPHABET[(inBuff >>> 6) & 0x3f];
            destination[destOffset + 3] = ALPHABET[(inBuff) & 0x3f];
            return destination;

        case 2:
            destination[destOffset] = ALPHABET[(inBuff >>> 18)];
            destination[destOffset + 1] = ALPHABET[(inBuff >>> 12) & 0x3f];
            destination[destOffset + 2] = ALPHABET[(inBuff >>> 6) & 0x3f];
            destination[destOffset + 3] = EQUALS_SIGN;
            return destination;

        case 1:
            destination[destOffset] = ALPHABET[(inBuff >>> 18)];
            destination[destOffset + 1] = ALPHABET[(inBuff >>> 12) & 0x3f];
            destination[destOffset + 2] = EQUALS_SIGN;
            destination[destOffset + 3] = EQUALS_SIGN;
            return destination;

        default:
            return destination;
        } // end switch
    } // end encode3to4
******/

    /**
     * Alphabet.
     * @param alpha alphabet
     */
    private static void encode3to4(byte[] source, int srcOffset, int numSigBytes, StringBuilder buf, byte[] alpha) {

        // Create buffer with zero-padding if there are only one or two
        // significant bytes passed in the array.
        // We have to shift left 24 in order to flush out the 1's that appear
        // when Java treats a value as negative that is cast from a byte to an int.
        int inBuff = (numSigBytes > 0 ? ((source[srcOffset] << 24) >>> 8) : 0)
                     | (numSigBytes > 1 ? ((source[srcOffset + 1] << 24) >>> 16) : 0)
                     | (numSigBytes > 2 ? ((source[srcOffset + 2] << 24) >>> 24) : 0);

        switch (numSigBytes) {
        case 3:
            buf.append((char)alpha[(inBuff >>> 18)]);
            buf.append((char)alpha[(inBuff >>> 12) & 0x3f]);
            buf.append((char)alpha[(inBuff >>> 6) & 0x3f]);
            buf.append((char)alpha[(inBuff) & 0x3f]);
            return;

        case 2:
            buf.append((char)alpha[(inBuff >>> 18)]);
            buf.append((char)alpha[(inBuff >>> 12) & 0x3f]);
            buf.append((char)alpha[(inBuff >>> 6) & 0x3f]);
            buf.append((char)EQUALS_SIGN);
            return;

        case 1:
            buf.append((char)alpha[(inBuff >>> 18)]);
            buf.append((char)alpha[(inBuff >>> 12) & 0x3f]);
            buf.append((char)EQUALS_SIGN);
            buf.append((char)EQUALS_SIGN);
            return;

        default:
            return;
        } // end switch
    } // end encode3to4

/* unused
    private static String encodeBytes(byte[] source) {
        return encodeBytes(source, false); // don't add newlines
    } // end encodeBytes
******/

    /**
     * Same as encodeBytes, except uses a filesystem / URL friendly set of characters,
     * replacing / with ~, and + with -
     *
     * @return the Base64 encoded string
     */
    private static String safeEncode(byte[] source, int off, int len, boolean useStandardAlphabet) {
        if (len + off > source.length)
            throw new ArrayIndexOutOfBoundsException("Trying to encode too much!  source.len=" + source.length + " off=" + off + " len=" + len);
        // The encoded length is ceil(len / 3) * 4. Anything less makes the
        // StringBuilder grow (and copy) part way through the encoding.
        StringBuilder buf = new StringBuilder(((len + 2) / 3) * 4);
        if (useStandardAlphabet)
            encodeBytes(source, off, len, false, buf, ALPHABET);
        else
            encodeBytes(source, off, len, false, buf, ALPHABET_ALT);
        return buf.toString();
    }

    /**
     * Same as decode, except from a filesystem / URL friendly set of characters,
     * replacing / with ~, and + with -
     *
     * @return the decoded data, null on error
     */
    private static byte[] safeDecode(String source, boolean useStandardAlphabet) {
        if (source == null) return null;
        if (!useStandardAlphabet)
            return standardDecode(source);
        // single-pass char replacement
        char[] chars = source.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == '/')
                chars[i] = '~';
            else if (c == '+')
                chars[i] = '-';
        }
        return standardDecode(new String(chars));
    }

/* unused
    private static String encodeBytes(byte[] source, boolean breakLines) {
        return encodeBytes(source, 0, source.length, breakLines);
    } // end encodeBytes
******/

    /**
     * Encodes a byte array into Base64 notation.
     *
     * @param source The data to convert
     * @param off Offset in array where conversion should begin
     * @param len Length of data to convert
     * @param breakLines Break lines at 80 characters or less.
     */
    private static void encodeBytes(byte[] source, int off, int len, boolean breakLines, StringBuilder out, byte[] alpha) {
        int d = 0;
        int len2 = len - 2;
        int lineLength = 0;
        for (; d < len2; d += 3) {
            encode3to4(source, d + off, 3, out, alpha);

            lineLength += 4;
            if (breakLines && lineLength == MAX_LINE_LENGTH) {
                out.append('\n');
                lineLength = 0;
            } // end if: end of line
        } // en dfor: each piece of array

        if (d < len) {
            encode3to4(source, d + off, len - d, out, alpha);
        } // end if: some padding needed

    } // end encodeBytes

    /* ********  D E C O D I N G   M E T H O D S  ******** */

    /**
     * The DECODABET value of the equals sign. No other character maps to it,
     * so a decoded value of -1 means the char was '='.
     */
    private static final byte DECODABET_EQUALS = -1;

    /**
     * Decodes four Base64 chars, already mapped through {@link #DECODABET},
     * and writes the resulting bytes (up to three of them) to
     * <var>destination</var> at <var>destOffset</var>.
     * This method does not check that <var>destination</var> is large enough for
     * <var>destOffset</var> + 3.
     *
     * @param d0..d3 the DECODABET values of the four source chars
     * @param destination the array to hold the conversion
     * @param destOffset the index where output will be put
     * @return the number of decoded bytes converted 1-3, or -1 on error, never zero
     */
    private static int decode4to3(byte d0, byte d1, byte d2, byte d3, byte[] destination, int destOffset) {
        if (d0 < 0 || d1 < 0)
            return -1;

        // Example: Dk==
        if (d2 == DECODABET_EQUALS) {
            if (d3 != DECODABET_EQUALS)
                return -1;
            // verify no extra bits
            if ((d1 & 0x0f) != 0)
                return -1;
            int outBuff = (d0 << 18)
                          | (d1 << 12);
            destination[destOffset] = (byte) (outBuff >> 16);
            return 1;
        }

        // Example: DkL=
        if (d3 == DECODABET_EQUALS) {
            if (d2 < 0)
                return -1;
            // verify no extra bits
            if ((d2 & 0x03) != 0)
                return -1;
            int outBuff = (d0 << 18)
                          | (d1 << 12)
                          | (d2 << 6);
            destination[destOffset++] = (byte) (outBuff >> 16);
            destination[destOffset] = (byte) (outBuff >> 8);
            return 2;
        }

        // Example: DkLE
        if (d2 < 0 || d3 < 0)
            return -1;
        int outBuff = (d0 << 18)
                      | (d1 << 12)
                      | (d2 << 6)
                      | d3;
        destination[destOffset++] = (byte) (outBuff >> 16);
        destination[destOffset++] = (byte) (outBuff >> 8);
        destination[destOffset] = (byte) (outBuff);
        return 3;
    } // end decodeToBytes

    /**
     * Decodes data from Base64 notation.
     * As of 0.9.14, this uses the I2P alphabet, so it is not "standard".
     *
     * @param s the string to decode
     * @return the decoded data, null on error
     */
    private static byte[] standardDecode(String s) {
        final int len = s.length();
        // Only ASCII is valid. We used to run DataHelper.getUTF8(s) and compare
        // the length to s.length(), so any char that encodes to more than one
        // byte (anything >= 0x80, including an unpaired surrogate) was rejected.
        // Comparing the chars is the same test without the intermediate byte[].
        for (int i = 0; i < len; i++) {
            if (s.charAt(i) > 0x7f) return null;
        }
        return decode(s, 0, len);
    } // end decode

    /**
     * Decodes data from Base64 notation and
     * returns it as a string.
     * Equivlaent to calling
     * <code>new String( decode( s ) )</code>
     *
     * As of 0.9.14, decodes as UTF-8. Prior to that, it used the platform's encoding.
     * For best results, decoded data should be 7 bit.
     *
     * As of 0.9.14, does not require trailing '=' if remaining bits are zero.
     * Prior to that, trailing 1, 2, or 3 chars were ignored.
     *
     * As of 0.9.14, trailing garbage after an '=' will cause an error.
     * Prior to that, it was ignored.
     *
     * As of 0.9.14, whitespace will cause an error.
     * Prior to that, it was ignored.
     *
     * @param s the string to decode
     * @return The data as a string, or null on error
     */
    public static String decodeToString(String s) {
        byte[] b = decode(s);
        if (b == null)
            return null;
        return DataHelper.getUTF8(b);
    } // end decodeToString

    /**
     * Decodes Base64 content in string format and returns
     * the decoded byte array. The chars are looked up in the DECODABET
     * directly, so no UTF-8 byte[] copy of the string is made. The caller
     * must have verified that every char is ASCII.
     *
     * As of 0.9.14, does not require trailing '=' if remaining bits are zero.
     * Prior to that, trailing 1, 2, or 3 chars were ignored.
     *
     * As of 0.9.14, trailing garbage after an '=' will cause an error.
     * Prior to that, it was ignored.
     *
     * As of 0.9.14, whitespace will cause an error.
     * Prior to that, it was ignored.
     *
     * @param source The Base64 encoded data
     * @param off    The offset of where to begin decoding
     * @param len    The length of characters to decode
     * @return decoded data, null on error
     */
    private static byte[] decode(String source, int off, int len) {
        int len34 = len * 3 / 4;
        byte[] outBuff = new byte[len34]; // size of output
        int outBuffPosn = 0;

        int i = off;
        int end = off + len;
        int converted = 0;
        while (i + 3 < end) {
            converted = decode4to3(DECODABET[source.charAt(i)],
                                   DECODABET[source.charAt(i + 1)],
                                   DECODABET[source.charAt(i + 2)],
                                   DECODABET[source.charAt(i + 3)],
                                   outBuff, outBuffPosn);
            if (converted < 0) return null;
            outBuffPosn += converted;
            i += 4;
            if (converted < 3)
                break;
        }

        // process any remaining without '='
        int remaining = end - i;
        if (remaining > 0) {
            if (converted > 0 && converted < 3) return null;
            if (remaining == 1 || remaining > 3) return null;
            // pad the tail out to four chars rather than building a byte[4]
            byte decode0 = DECODABET[source.charAt(i++)];
            byte decode1 = DECODABET[source.charAt(i++)];
            byte decode2 = (remaining == 3 ? DECODABET[source.charAt(i)] : DECODABET_EQUALS);
            converted = decode4to3(decode0, decode1, decode2, DECODABET_EQUALS, outBuff, outBuffPosn);
            if (converted < 0) return null;
            outBuffPosn += converted;
        }

        // don't copy unless we have to
        if (outBuffPosn == outBuff.length)
            return outBuff;
        // and we shouldn't ever... would have returned null before
        byte[] out = new byte[outBuffPosn];
        System.arraycopy(outBuff, 0, out, 0, outBuffPosn);
        return out;
    } // end decode
} // end class Base64
