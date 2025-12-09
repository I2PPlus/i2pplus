package org.xlattice.crypto.filters;

/**
 * Utility class for extracting word and bit offsets from cryptographic keys for Bloom filter operations.
 *
 * <p>KeySelector provides the core functionality needed to map cryptographic keys
 * to the specific bit and word positions used by Bloom filter hash functions.
 * This is essential for implementing efficient Bloom filters where each hash function
 * needs to examine specific bits or words from the key.</p>
 *
 * <p>The class supports two types of selectors:<br>
 * - <strong>BitSelector</strong>: Extracts individual bit positions (5-bit stride)<br>
 * - <strong>WordSelector</strong>: Extracts word positions ((m-5)-bit stride)</p>
 *
 * <p>Usage example:<br>
 * <pre>
 * // Create a key selector for a Bloom filter with m=1024, k=5
 * KeySelector selector = new KeySelector(1024, 5);
 *
 * // Extract offsets from a 32-byte key
 * byte[] key = new byte[32]; // your cryptographic key
 * int[] bitOffsets = new int[5];
 * int[] wordOffsets = new int[5];
 *
 * selector.getOffsets(key, bitOffsets, wordOffsets);
 *
 * // Use offsets in Bloom filter operations
 * for (int i = 0; i < 5; i++) {
 *     System.out.println("Hash " + i + " uses bit " + bitOffsets[i] +
 *                        ", word " + wordOffsets[i]);
 * }
 * </pre></p>
 *
 * <p>Constraints and limitations:<br>
 * - Maximum supported for 32-byte keys: m=23, k=11<br>
 * - Formula constraint: ((5k + (k-1)(m-5)) / 8) + 2 ≤ keySizeInBytes<br>
 * - All methods are thread-safe for concurrent access</p>
 *
 * <p>Algorithm details:<br>
 * - Bit selectors use 5-bit stride for optimal distribution<br>
 * - Word selectors use (m-5)-bit stride to avoid bit selector overlap<br>
 * - Both use lookup tables (UNMASK/MASK) for efficient bit operations</p>
 *
 * @author <A HREF="mailto:jddixon@users.sourceforge.net">Jim Dixon</A>
 *
 * BloomSHA1.java and KeySelector.java are BSD licensed from xlattice
 * app - http://xlattice.sourceforge.net/
 *
 * minor tweaks by jrandom, exposing unsynchronized access and
 * allowing larger M and K.  changes released into the public domain.
 *
 * As of 0.8.11, bitoffset and wordoffset out parameters moved from fields
 * to selector arguments, to allow concurrency.
 * ALl methods are now thread-safe.
 */
public class KeySelector {

    private final int m;
    private final int k;
    private final BitSelector  bitSel;
    private final WordSelector wordSel;

    /**
     * Interface for extracting bit offsets from cryptographic keys for Bloom filter operations.
     *
     * <p>Bit selectors are used to determine which bits in a key should be examined
     * or set when performing Bloom filter operations. Each bit offset represents
     * a specific bit position within the key that will be used by one of the
     * k hash functions in the Bloom filter.</p>
     *
     * <p>The bit selection algorithm extracts k bit positions from the key,
     * where each bit position corresponds to a specific hash function's target bit
     * in the filter's bit array. This enables efficient mapping from key data
     * to Bloom filter bit positions.</p>
     *
     * <p>Implementations must be thread-safe as they may be used concurrently
     * by multiple threads accessing the same Bloom filter.</p>
     *
     * @author Jim Dixon
     * @since 0.8.11
     */
    public interface BitSelector {
        /**
         * Extracts k bit offsets from a key for Bloom filter operations.
         *
         * <p>This method populates the provided bitOffset array with k integer
         * values, each representing a bit position within the key that should
         * be used by a hash function. The bit positions are calculated
         * based on the key data and the specific bit selection algorithm.</p>
         *
         * <p>The offset and length parameters allow processing of subsets
         * of the key data, enabling flexible key handling for different
         * key sizes and Bloom filter configurations.</p>
         *
         * @param b the key data as byte array
         * @param offset starting position within the key array
         * @param length number of bytes to process from the key
         * @param bitOffset output array of length k to store calculated bit offsets
         * @since 0.8.11 out parameter added
         */
        public void getBitSelectors(byte[] b, int offset, int length, int[] bitOffset);
    }

    /**
     * Interface for extracting word offsets from cryptographic keys for Bloom filter operations.
     *
     * <p>Word selectors are used to determine which words (multi-bit groups)
     * in a key should be examined or set when performing Bloom filter operations.
     * Each word offset represents a specific word position within the key that
     * corresponds to a hash function's target in the filter's word array.</p>
     *
     * <p>Word selection is complementary to bit selection - while bit selectors
     * identify individual bit positions, word selectors identify groups of bits
     * (words) that can be processed more efficiently in some Bloom filter
     * implementations. The word size is determined by the filter's m parameter
     * (filter size as power of 2).</p>
     *
     * <p>The word selection algorithm extracts k word positions from key data,
     * where each word position corresponds to a specific hash function's target
     * word in filter's word array. This enables efficient mapping from key
     * data to Bloom filter word positions.</p>
     *
     * <p>Implementations must be thread-safe as they may be used concurrently
     * by multiple threads accessing the same Bloom filter.</p>
     *
     * @author Jim Dixon
     * @since 0.8.11
     */
    public interface WordSelector {
        /**
         * Extracts k word offsets from a key for Bloom filter operations.
         *
         * <p>This method populates the provided wordOffset array with k integer
         * values, each representing a word position within the key that should
         * be used by a hash function. The word positions are calculated
         * based on key data and the specific word selection algorithm.</p>
         *
         * <p>The offset and length parameters allow processing of subsets
         * of key data, enabling flexible key handling for different
         * key sizes and Bloom filter configurations. The word size is
         * determined by (m-5) where m is the filter size parameter.</p>
         *
         * @param b key data as byte array
         * @param offset starting position within the key array
         * @param length number of bytes to process from the key
         * @param wordOffset output array of length k to store calculated word offsets
         * @since 0.8.11 out parameter added
         */
        public void getWordSelectors(byte[] b, int offset, int length, int[] wordOffset);
    }

    /** AND with byte to expose index-many bits */
    private final static int[] UNMASK = {
 // 0  1  2  3   4   5   6    7    8   9     10   11     12    13     14     15
    0, 1, 3, 7, 15, 31, 63, 127, 255, 511, 1023, 2047, 4095, 8191, 16383, 32767};
    /** AND with byte to zero out index-many bits */
    private final static int[] MASK   = {
    ~0,~1,~3,~7,~15,~31,~63,~127,~255,~511,~1023,~2047,~4095,~8191,~16383,~32767};

    private final static int TWO_UP_15 = 32 * 1024;

/**
     * Creates a key selector for a Bloom filter with specified parameters.
     *
     * <p>This constructor initializes both bit and word selectors with the
     * appropriate algorithms for the given m and k values. The selectors
     * will then be used to extract offsets from keys for Bloom filter
     * operations.</p>
     *
     * <p>Performance characteristics:<br>
     * - Bit selection: O(k) time complexity, constant space<br>
     * - Word selection: O(k) time complexity, constant space<br>
     * - Both implementations use lookup tables for efficiency<br>
     * - Thread-safe: no shared mutable state</p>
     *
     * <p>Memory usage: O(1) additional space for lookup tables
     * and selector instances.</p>
     *
     * @param m size of filter as a power of 2 (determines stride)
     * @param k number of 'hash functions' (determines number of offsets to extract)
     *
     * @throws IllegalArgumentException if parameters exceed implementation limits
     *
     * <p>Implementation constraints:<br>
     * - Maximum for 32-byte keys: m=23, k=11<br>
     * - Formula constraint: ((5k + (k-1)(m-5)) / 8) + 2 ≤ keySizeInBytes<br>
     * - Larger values may cause ArrayIndexOutOfBoundsException<br>
     * - m must be ≥ 2 for valid stride calculations</p>
     *
     * <p>Note: The constraint formula ensures that the calculated offsets
     * don't exceed the key boundaries. For larger m/k values,
     * consider using larger key sizes or alternative selection algorithms.</p>
     */
    public KeySelector (int m, int k) {
        //if ( (m < 2) || (m > 20)|| (k < 1)
        //             || (bitOffset == null) || (wordOffset == null)) {
        //    throw new IllegalArgumentException();
        //}
        this.m = m;
        this.k = k;
        bitSel  = new GenericBitSelector();
        wordSel = new GenericWordSelector();
    }

    /**
     * Generic implementation of BitSelector for extracting bit offsets from cryptographic keys.
     *
     * <p>This implementation uses a 5-bit stride algorithm to extract k bit
     * positions from key data. It's suitable for general values of m (filter size)
     * and k (number of hash functions) within reasonable bounds.</p>
     *
     * <p>Algorithm overview:<br>
     * - Processes key data in 5-bit increments<br>
     * - Uses bit manipulation to extract specific bit positions<br>
     * - Handles cases where bits span across byte boundaries<br>
     * - Employs lookup tables (UNMASK/MASK) for efficient bit operations</p>
     *
     * <p>The 5-bit stride is chosen as it provides good distribution
     * while keeping implementation complexity manageable. Each hash function
     * will examine bits that are 5 positions apart in the key.</p>
     *
     * <p>Thread Safety: This implementation is thread-safe as it operates
     * only on method parameters and local variables.</p>
     *
     * @author Jim Dixon
     */
    public class GenericBitSelector implements BitSelector {
        /**
         * Extracts k bit offsets from key data using 5-bit stride algorithm.
         *
         * <p>This method processes the key data byte by byte, extracting
         * bit positions at 5-bit intervals. For each bit position, it
         * calculates which byte contains the bit and extracts the appropriate
         * bits using bit masking and shifting operations.</p>
         *
         * <p>Algorithm details:<br>
         * - Tracks current bit position across byte boundaries<br>
         * - Uses UNMASK array to isolate right-aligned bits<br>
         * - Uses MASK array to isolate left-aligned bits<br>
         * - Handles cases where bits span multiple bytes</p>
         *
         * @param b key data as byte array
         * @param offset starting position within key array
         * @param length number of bytes to process
         * @param bitOffset output array of length k to store calculated bit offsets
         */
        public void getBitSelectors(byte[] b, int offset, int length, int[] bitOffset) {
            int curBit = 8 * offset;
            int curByte;
            for (int j = 0; j < k; j++) {
                curByte = curBit / 8;
                int bitsUnused = ((curByte + 1) * 8) - curBit;    // left in byte

//              // DEBUG
//              System.out.println (
//                  "this byte = " + btoh(b[curByte])
//                  + ", next byte = " + btoh(b[curByte + 1])
//                  + "; curBit=" + curBit + ", curByte= " + curByte
//                  + ", bitsUnused=" + bitsUnused);
//              // END
                if (bitsUnused > 5) {
                    bitOffset[j] = ((0xff & b[curByte])
                                        >> (bitsUnused - 5)) & UNMASK[5];
//                  // DEBUG
//                  System.out.println(
//                      "    before shifting: " + btoh(b[curByte])
//                  + "\n    after shifting:  "
//                          + itoh( (0xff & b[curByte]) >> (bitsUnused - 5))
//                  + "\n    mask:            " + itoh(UNMASK[5]) );
//                  // END
                } else if (bitsUnused == 5) {
                    bitOffset[j] = b[curByte] & UNMASK[5];
                } else {
                    bitOffset[j] = (b[curByte]          & UNMASK[bitsUnused])
                              | (((0xff & b[curByte + 1]) >> 3)
                                                        &   MASK[bitsUnused]);
//                  // DEBUG
//                  System.out.println(
//                    "    contribution from first byte:  "
//                    + itoh(b[curByte] & UNMASK[bitsUnused])
//                + "\n    second byte: " + btoh(b[curByte + 1])
//                + "\n    shifted:     " + itoh((0xff & b[curByte + 1]) >> 3)
//                + "\n    mask:        " + itoh(MASK[bitsUnused])
//                + "\n    contribution from second byte: "
//                    + itoh((0xff & b[curByte + 1] >> 3) & MASK[bitsUnused]));
//                  // END
                }
//              // DEBUG
//              System.out.println ("    bitOffset[j] = " + bitOffset[j]);
//              // END
                curBit += 5;
            }
        }
    }
/**
     * Generic implementation of WordSelector for extracting word offsets from cryptographic keys.
     *
     * <p>This implementation uses a variable stride algorithm to extract k word
     * positions from key data. It's suitable for general values of m (filter size)
     * and k (number of hash functions) within reasonable bounds.</p>
     *
     * <p>Algorithm overview:<br>
     * - Uses stride of (m-5) bits between word positions<br>
     * - Processes key data in multi-byte chunks as needed<br>
     * - Handles cases where words span across byte boundaries<br>
     * - Employs lookup tables for efficient bit manipulation</p>
     *
     * <p>The stride calculation (m-5) ensures optimal distribution
     * of word positions across the key space while avoiding overlap
     * with the 5-bit bit selector stride.</p>
     *
     * <p>Constraints:<br>
     * - Maximum supported values: m=23, k=11 for 32-byte keys<br>
     * - Formula constraint: ((5k + (k-1)(m-5)) / 8) + 2 ≤ keySizeInBytes<br>
     * - Larger values may cause ArrayIndexOutOfBoundsException</p>
     *
     * <p>Thread Safety: This implementation is thread-safe as it operates
     * only on method parameters and local variables.</p>
     *
     * @author Jim Dixon
     */
    public class GenericWordSelector implements WordSelector {
        /**
         * Extracts k word offsets from key data using variable stride algorithm.
         *
         * <p>This method processes the key data to calculate word positions
         * for Bloom filter operations. Each word offset represents the starting
         * bit position for one of the k hash functions.</p>
         *
         * <p>Algorithm details:<br/>
         * - Calculates stride as (m-5) bits between word positions<br/>
         * - Processes key data in chunks spanning multiple bytes when needed<br/>
         * - Uses bit manipulation to extract word-aligned bit groups<br/>
         * - Handles edge cases where words cross byte boundaries</p>
         *
         * @param b key data as byte array
         * @param offset starting position within key array
         * @param length number of bytes to process
         * @param wordOffset output array of length k to store calculated word offsets
         */
        public void getWordSelectors(byte[] b, int offset, int length, int[] wordOffset) {
            int stride = m - 5;
            //assert true: stride<16;
            int curBit = (k * 5) + (offset * 8);
            int curByte;
            for (int j = 0; j < k; j++) {
                curByte = curBit / 8;
                int bitsUnused = ((curByte + 1) * 8) - curBit;    // left in byte

//              // DEBUG
//              System.out.println (
//                  "curr 3 bytes: " + btoh(b[curByte])
//                  + (curByte < 19 ?
//                      " " + btoh(b[curByte + 1]) : "")
//                  + (curByte < 18 ?
//                      " " + btoh(b[curByte + 2]) : "")
//                  + "; curBit=" + curBit + ", curByte= " + curByte
//                  + ", bitsUnused=" + bitsUnused);
//              // END

                if (bitsUnused > stride) {
                    // the value is entirely within the current byte
                    wordOffset[j] = ((0xff & b[curByte])
                                        >> (bitsUnused - stride))
                                                & UNMASK[stride];
                } else if (bitsUnused == stride) {
                    // the value fills the current byte
                    wordOffset[j] = b[curByte] & UNMASK[stride];
                } else {    // bitsUnused < stride
                    // value occupies more than one byte
                    // bits from first byte, right-aligned in result
                    wordOffset[j] = b[curByte] & UNMASK[bitsUnused];
//                  // DEBUG
//                  System.out.println("    first byte contributes "
//                          + itoh(wordOffset[j]));
//                  // END
                    // bits from second byte
                    int bitsToGet = stride - bitsUnused;
                    if (bitsToGet >= 8) {
                        // 8 bits from second byte
                        wordOffset[j] |= (0xff & b[curByte + 1]) << bitsUnused;
//                      // DEBUG
//                      System.out.println("    second byte contributes "
//                          + itoh(
//                          (0xff & b[curByte + 1]) << bitsUnused
//                      ));
//                      // END

                        // bits from third byte
                        bitsToGet -= 8;
                        if (bitsToGet > 0) {
                            // AIOOBE here if m and k too big (23,11 is the max)
                            // for a 32-byte key - see above
                            wordOffset[j] |=
                                ((0xff & b[curByte + 2]) >> (8 - bitsToGet))
                                                    << (stride - bitsToGet) ;
//                          // DEBUG
//                          System.out.println("    third byte contributes "
//                              + itoh(
//                              (((0xff & b[curByte + 2]) >> (8 - bitsToGet))
//                                                  << (stride - bitsToGet))
//                              ));
//                          // END
                        }
                    } else {
                        // all remaining bits are within second byte
                        wordOffset[j] |= ((b[curByte + 1] >> (8 - bitsToGet))
                                            & UNMASK[bitsToGet])
                                                << bitsUnused;
//                      // DEBUG
//                      System.out.println("    second byte contributes "
//                          + itoh(
//                          ((b[curByte + 1] >> (8 - bitsToGet))
//                              & UNMASK[bitsToGet])
//                                      << bitsUnused
//                          ));
//                      // END
                    }
                }
//              // DEBUG
//              System.out.println (
//                  "    wordOffset[" + j + "] = " + wordOffset[j]
//                  + ", "                     + itoh(wordOffset[j])
//              );
//              // END
                curBit += stride;
            }
        }
    }

    /**
     * Given a key, populate the word and bit offset arrays, each
     * of which has k elements.
     *
     * @param key cryptographic key used in populating the arrays
     * @param bitOffset Out parameter of length k
     * @param wordOffset Out parameter of length k
     * @since 0.8.11 out parameters added
     */
    public void getOffsets (byte[] key, int[] bitOffset, int[] wordOffset) {
        getOffsets(key, 0, key.length, bitOffset, wordOffset);
    }

    /**
     * Given a key, populate the word and bit offset arrays, each
     * of which has k elements.
     *
     * @param key cryptographic key used in populating the arrays
     * @param bitOffset Out parameter of length k
     * @param wordOffset Out parameter of length k
     * @since 0.8.11 out parameters added
     */
    public void getOffsets (byte[] key, int off, int len, int[] bitOffset, int[] wordOffset) {
        // skip these checks for speed
        //if (key == null) {
        //    throw new IllegalArgumentException("null key");
        //}
        //if (len < 20) {
        //    throw new IllegalArgumentException(
        //        "key must be at least 20 bytes long");
        //}
//      // DEBUG
//      System.out.println("KeySelector.getOffsets for "
//                                          + BloomSHA1.keyToString(b));
//      // END
        bitSel.getBitSelectors(key, off, len, bitOffset);
        wordSel.getWordSelectors(key, off, len, wordOffset);
    }

/*****
    // DEBUG METHODS ////////////////////////////////////////////////
    String itoh(int i) {
        return BloomSHA1.itoh(i);
    }
    String btoh(byte b) {
        return BloomSHA1.btoh(b);
    }
*****/
}


