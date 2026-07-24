// License: GPLv2+. See docs/LICENSES.md
package i2p.susi.util;

/**
 * Hex table utilities for byte-to-string conversion.
 */
public class HexTable {
    /**
     *  Three character strings, upper case, e.g. "=0A"
     *  WARNING: This array is public for backward compatibility but should be treated as immutable
     */
    public static final String[] table = new String[256];

    static {
        for(int i = 0; i < 256; i++) {
            String str = intToHex(i);
            if (str.length() == 1) {str = "0" + str;}
            table[i] = "=" + str;
        }
    }

    /**
     * Get hex string for the given byte value
     * @param b byte value (0-255)
     * @return hex string, e.g. "=0A"
     */
    public static String getHexString(int b) {
        if (b < 0 || b > 255) {
            throw new IllegalArgumentException("Byte value must be between 0 and 255");
        }
        return table[b];
    }

    /**
     * Direct access to table entry for backward compatibility
     * @param index the index (0-255)
     * @return hex string at that index
     */
    public static String getTableEntry(int index) {
        if (index < 0 || index > 255) {
            throw new IllegalArgumentException("Index must be between 0 and 255");
        }
        return table[index];
    }

    /**
     * Get a copy of the hex table for safe access
     * @return a copy of the hex table array
     */
    public static String[] getTable() {
        return table.clone();
    }

    /**
     * Convert an integer to its hexadecimal string representation.
     *
     * @param b the value (0-255)
     * @return the hex string without leading zeros
     */
    private static String intToHex(int b) {
        if (b == 0) {return "0";}
        else {
            StringBuilder buf = new StringBuilder(8);
            while(b > 0) {
                byte c = (byte)(b % 16);
                if (c < 10) {c += '0';}
                else {c = (byte)(c + 'A' - 10);}
                buf.insert(0, (char) c);
                b = (byte) (b / 16);
            }
            return buf.toString();
        }
    }

}
