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
package org.minidns.dnslabel;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.minidns.util.SafeCharSequence;

/**
 * A DNS label is an individual component of a DNS name. Labels are usually shown separated by dots.
 * <p>
 * This class implements {@link Comparable} which compares DNS labels according to the Canonical DNS Name Order as
 * specified in <a href="https://tools.ietf.org/html/rfc4034#section-6.1">RFC 4034 § 6.1</a>.
 * </p>
 * <p>
 * Note that as per <a href="https://tools.ietf.org/html/rfc2181#section-11">RFC 2181 § 11</a> DNS labels may contain
 * any byte.
 * </p>
 *
 * @see <a href="https://tools.ietf.org/html/rfc5890#section-2.2">RFC 5890 § 2.2. DNS-Related Terminology</a>
 * @author Florian Schmaus
 *
 */
public abstract class DnsLabel extends SafeCharSequence implements Comparable<DnsLabel> {

    /**
     * The maximum length of a DNS label in octets.
     *
     * @see <a href="https://tools.ietf.org/html/rfc1035">RFC 1035 § 2.3.4.</a>
     */
    public static final int MAX_LABEL_LENGTH_IN_OCTETS = 63;

    /** The wildcard label. */
    public static final DnsLabel WILDCARD_LABEL = DnsLabel.from("*");

    /**
     * Whether or not the DNS label is validated on construction.
     */
private static final boolean VALIDATE = true;

    /** The label string. */
    public final String label;

    /**
     * DnsLabel.
     */
    protected DnsLabel(String label) {
        this.label = label;

        if (!VALIDATE) {
            return;
        }

        setBytesIfRequired();
        if (byteCache.length > MAX_LABEL_LENGTH_IN_OCTETS) {
            throw new LabelToLongException(label);
        }
    }

    private transient String internationalizedRepresentation;

    /**
     * Get the internationalized representation of this label.
     * @return the internationalized label
     */
    public final String getInternationalizedRepresentation() {
        if (internationalizedRepresentation == null) {
            internationalizedRepresentation = getInternationalizedRepresentationInternal();
        }
        return internationalizedRepresentation;
    }

    /**
     * Gets the internal internationalized representation.
     * @return label string
     */
    protected String getInternationalizedRepresentationInternal() {
        return label;
    }

    /**
     * Get the type of this label.
     * @return the simple class name
     */
    public final String getLabelType() {
        return getClass().getSimpleName();
    }

    private transient String safeToStringRepresentation;

    /**
     * toString.
     */
    @Override
    public final String toString() {
        if (safeToStringRepresentation == null) {
            safeToStringRepresentation = toSafeRepesentation(label);
        }

        return safeToStringRepresentation;
    }

    /**
     * Get the raw label. Note that this may return a String containing null bytes.
     * Those Strings are notoriously difficult to handle from a security
     * perspective. Therefore it is recommended to use {@link #toString()} instead,
     * which will return a sanitized String.
     *
     * @return the raw label.
     * @since 1.1.0
     */
    public final String getRawLabel() {
        return label;
    }

    /**
     * equals.
     */
    @Override
    public final boolean equals(Object other) {
        if (!(other instanceof DnsLabel)) {
            return false;
        }
        DnsLabel otherDnsLabel = (DnsLabel) other;
        return label.equals(otherDnsLabel.label);
    }

    /**
     * hashCode.
     */
    @Override
    public final int hashCode() {
        return label.hashCode();
    }

    private transient DnsLabel lowercasedVariant;

    /**
     * Get the lowercase variant of this label.
     * @return the lowercase label
     */
    public final DnsLabel asLowercaseVariant() {
        if (lowercasedVariant == null) {
            String lowercaseLabel = label.toLowerCase(Locale.US);
            lowercasedVariant = DnsLabel.from(lowercaseLabel);
        }
        return lowercasedVariant;
    }

    private transient byte[] byteCache;

    private void setBytesIfRequired() {
        if (byteCache == null) {
            byteCache = label.getBytes(StandardCharsets.US_ASCII);
        }
    }

    /**
     * Write this label to a ByteArrayOutputStream prefixed by its length.
     * @param byteArrayOutputStream the output stream to write to
     */
    public final void writeToBoas(ByteArrayOutputStream byteArrayOutputStream) {
        setBytesIfRequired();

        byteArrayOutputStream.write(byteCache.length);
        byteArrayOutputStream.write(byteCache, 0, byteCache.length);
    }

    /**
     * compareTo.
     */
    @Override
    public final int compareTo(DnsLabel other) {
        String myCanonical = asLowercaseVariant().label;
        String otherCanonical = other.asLowercaseVariant().label;

        return myCanonical.compareTo(otherCanonical);
    }

    /**
     * Create a DnsLabel from a string.
     * @param label the label string
     * @return the DnsLabel
     */
    public static DnsLabel from(String label) {
        if (label == null || label.isEmpty()) {
            throw new IllegalArgumentException("Label is null or empty");
        }

        if (LdhLabel.isLdhLabel(label)) {
            return LdhLabel.fromInternal(label);
        }

        return NonLdhLabel.fromInternal(label);
    }

    /**
     * Create DnsLabel array from string array.
     * @param labels the label strings
     * @return the DnsLabel array
     */
    public static DnsLabel[] from(String[] labels) {
        DnsLabel[] res = new DnsLabel[labels.length];

        for (int i = 0; i < labels.length; i++) {
            res[i] = DnsLabel.from(labels[i]);
        }

        return res;
    }

    /**
     * Check if a string has the IDN ACE prefix.
     * @param string the string to check
     * @return true if the string starts with "xn--"
     */
    public static boolean isIdnAcePrefixed(String string) {
        return string.toLowerCase(Locale.US).startsWith("xn--");
    }

    /**
     * Convert a DNS label string to a safe representation.
     * @param dnsLabel the label to convert
     * @return safe string representation
     */
    public static String toSafeRepesentation(String dnsLabel) {
        if (consistsOnlyOfLettersDigitsHypenAndUnderscore(dnsLabel)) {
            // This label is safe, nothing to do.
            return dnsLabel;
        }

        StringBuilder sb = new StringBuilder(2 * dnsLabel.length());
        for (int i = 0; i < dnsLabel.length(); i++) {
            char c = dnsLabel.charAt(i);
            if (isLdhOrMaybeUnderscore(c, true)) {
                sb.append(c);
                continue;
            }


            // Let's see if we found and unsafe char we want to replace.
            switch (c) {
            case '.':
                sb.append('●'); // U+25CF BLACK CIRCLE;
                break;
            case '\\':
                sb.append('⧷'); // U+29F7 REVERSE SOLIDUS WITH HORIZONTAL STROKE
                break;
            case '\u007f':
                // Convert DEL to U+2421 SYMBOL FOR DELETE
                sb.append('␡');
                break;
            case ' ':
                sb.append('␣'); // U+2423 OPEN BOX
                break;
            default:
                if (c < 32) {
                    // First convert the ASCI control codes to the Unicode Control Pictures
                    int substituteAsInt = c + '\u2400';
                    assert substituteAsInt <= Character.MAX_CODE_POINT;
                    char substitute = (char) substituteAsInt;
                    sb.append(substitute);
                } else if (c < 127) {
                    // Everything smaller than 127 is now safe to directly append.
                    sb.append(c);
                } else if (c > 255) {
                    throw new IllegalArgumentException("The string '" + dnsLabel
                            + "' contains characters outside the 8-bit range: " + c + " at position " + i);
                } else {
                    // Everything that did not match the previous conditions is explicitly escaped.
                    sb.append("〚"); // U+301A
                    // Transform the char to hex notation. Note that we have ensure that c is <= 255
                    // here, hence only two hexadecimal places are ok.
                    String hex = String.format("%02X", (int) c);
                    sb.append(hex);
                    sb.append("〛"); // U+301B
                }
            }
        }

        return sb.toString();
    }

    private static boolean isLdhOrMaybeUnderscore(char c, boolean underscore) {
            // CHECKSTYLE:OFF
            return (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || (underscore && c == '_')
                    ;
            // CHECKSTYLE:ON
    }

    private static boolean consistsOnlyOfLdhAndMaybeUnderscore(String string, boolean underscore) {
        for (int i = 0; i < string.length(); i++) {
            char c = string.charAt(i);
            if (isLdhOrMaybeUnderscore(c, underscore)) {
                continue;
            }
            return false;
        }
        return true;
    }

    /**
     * Check if a string consists of only letters, digits, and hyphens.
     * @param string the string to check
     * @return true if it matches LDH rules
     */
    public static boolean consistsOnlyOfLettersDigitsAndHypen(String string) {
        return consistsOnlyOfLdhAndMaybeUnderscore(string, false);
    }

    /**
     * Check if a string consists of only letters, digits, hyphens, and underscores.
     * @param string the string to check
     * @return true if it matches LDH or underscore rules
     */
    public static boolean consistsOnlyOfLettersDigitsHypenAndUnderscore(String string) {
        return consistsOnlyOfLdhAndMaybeUnderscore(string, true);
    }

    /**
     * Thrown when a DNS label exceeds the maximum length.
     */
    public static class LabelToLongException extends IllegalArgumentException {

        /**
         *
         */
        private static final long serialVersionUID = 1L;

        /** The DNS label that caused this exception */
        public final String label;

        /**
         * Creates a new LabelToLongException for the specified label.
         *
         * @param label the DNS label that exceeds the maximum length
         */
        LabelToLongException(String label) {
            this.label = label;
        }
    }
}
