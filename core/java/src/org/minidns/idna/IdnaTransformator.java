/*
 * Copyright 2015-2024 the original author or authors
 *
 * This software is licensed under the Apache License, Version 2.0,
 * the GNU Lesser General Public License version 2 or later ("LGPL")
 * and the WTFPL.
 * You may choose either license to govern your use of this software only
 * upon the condition that you accept all of the terms of either
 * the Apache License 2.0, the LGPL 2.1+ or the WTFPL.
 */
package org.minidns.idna;

/**
 * IdnaTransformator.
 */
public interface IdnaTransformator {

    /**
     * Convert a Unicode domain name to ASCII Compatible Encoding.
     *
     * @param input the Unicode domain name
     * @return the ACE-encoded domain name
     */
    String toASCII(String input);

    /**
     * Convert an ASCII Compatible Encoding to Unicode.
     *
     * @param input the ACE-encoded domain name
     * @return the Unicode domain name
     */
    String toUnicode(String input);
}
