/*
 * Created on Nov 12, 2004
 * 
 *  This file is part of susimail project, see http://susi.i2p/
 *  
 *  Copyright (C) 2004-2005  <susi23@mail.i2p>
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *  
 * $Revision: 1.2 $
 */
package i2p.susi.util;

/**
 * Utility class for hex table operations.
 * @author susi
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

    private static String intToHex(int b) {
        if (b == 0) {return "0";}
        else {
            String str = "";
            while(b > 0) {
                byte c = (byte)(b % 16);
                if (c < 10) {c += '0';}
                else {c += 'A' - 10;}
                str = "" + (char)c + str;
                b = (byte) (b / 16);
            }
            return str;
        }
    }

}
