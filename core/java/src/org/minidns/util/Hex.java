/*
 * Copyright 2015-2020 the original author or authors
 *
 * This software is licensed under the Apache License, Version 2.0,
 * the GNU Lesser General Public License version 2 or later ("LGPL")
 * and the WTFPL.
 * You may choose either license to govern your use of this software only
 * upon the condition that you accept all of the terms of either
 * the Apache License 2.0, the LGPL 2.1+ or the WTFPL.
 */
package org.minidns.util;

/**
 * Utility class for hexadecimal conversion.
 */
public class Hex {

    /**
     * Converts a byte array to a hexadecimal string representation.
     * 
     * @param bytes the byte array to convert
     * @return a StringBuilder containing the hexadecimal representation (uppercase with spaces)
     */
    public static StringBuilder from(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb;
    }
}
