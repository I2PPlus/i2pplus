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
package org.minidns.idna;

/**
 * Utility class for Internationalized Domain Names in Applications (IDNA) processing.<br>
 * Provides methods to convert between Unicode and ASCII representations of domain names
 * according to IDNA specifications.
 */
public class MiniDnsIdna {

    /** The active IDNA transformator implementation */
    private static IdnaTransformator idnaTransformator = new DefaultIdnaTransformator();

    /**
     * Converts a Unicode string to its ASCII representation using IDNA.
     *
     * @param string the Unicode string to convert
     * @return the ASCII representation
     */
    public static String toASCII(String string) {
        return idnaTransformator.toASCII(string);
    }

    /**
     * Converts an ASCII string back to its Unicode representation using IDNA.
     *
     * @param string the ASCII string to convert
     * @return the Unicode representation
     */
    public static String toUnicode(String string) {
        return idnaTransformator.toUnicode(string);
    }

    /**
     * Sets the active IDNA transformator implementation.
     *
     * @param idnaTransformator the transformator to use, must not be null
     * @throws IllegalArgumentException if idnaTransformator is null
     */
    public static void setActiveTransformator(IdnaTransformator idnaTransformator) {
        if (idnaTransformator == null) {
            throw new IllegalArgumentException();
        }
        MiniDnsIdna.idnaTransformator = idnaTransformator;
    }
}
