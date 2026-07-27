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
package org.minidns.constants;

import java.util.HashMap;
import java.util.Map;

/**
 * Constants and enums for DNSSEC (DNS Security Extensions).<br>
 * This class provides DNSSEC algorithm definitions and constants used
 * for DNSSEC operations including signature and digest algorithms.
 *
 * @author MiniDNS Project
 * @since 0.1
 */
public final class DnssecConstants {
    /**
     * Do not allow to instantiate DNSSECConstants
     */
    private DnssecConstants() {}

    private static final Map<Byte, SignatureAlgorithm> SIGNATURE_ALGORITHM_LUT = new HashMap<>();

    /**
     * DNSSEC Signature Algorithms.
     *
     * @see <a href=
     *      "http://www.iana.org/assignments/dns-sec-alg-numbers/dns-sec-alg-numbers.xhtml">
     *      IANA DNSSEC Algorithm Numbers</a>
     */
    public enum SignatureAlgorithm {
        /** RSA/MD5 (deprecated) */
        @Deprecated
        RSAMD5(1, "RSA/MD5"),
        /** Diffie-Hellman */
        DH(2, "Diffie-Hellman"),
        /** DSA/SHA1 */
        DSA(3, "DSA/SHA1"),
        /** RSA/SHA-1 */
        RSASHA1(5, "RSA/SHA-1"),
        /** DSA NSEC3 SHA1 */
        DSA_NSEC3_SHA1(6, "DSA_NSEC3-SHA1"),
        /** RSASHA1 NSEC3 SHA1 */
        RSASHA1_NSEC3_SHA1(7, "RSASHA1-NSEC3-SHA1"),
        /** RSA/SHA-256 */
        RSASHA256(8, "RSA/SHA-256"),
        /** RSA/SHA-512 */
        RSASHA512(10, "RSA/SHA-512"),
        /** GOST R 34.10-2001 */
        ECC_GOST(12, "GOST R 34.10-2001"),
        /** ECDSA Curve P-256 with SHA-256 */
        ECDSAP256SHA256(13, "ECDSA Curve P-256 with SHA-256"),
        /** ECDSA Curve P-384 with SHA-384 */
        ECDSAP384SHA384(14, "ECDSA Curve P-384 with SHA-384"),
        /** Reserved for Indirect Keys */
        INDIRECT(252, "Reserved for Indirect Keys"),
        /** private algorithm */
        PRIVATEDNS(253, "private algorithm"),
        /** private algorithm oid */
        PRIVATEOID(254, "private algorithm oid"),
       ;

        SignatureAlgorithm(int number, String description) {
            if (number < 0 || number > 255) {
                throw new IllegalArgumentException();
            }
            this.number = (byte) number;
            this.description = description;
            SIGNATURE_ALGORITHM_LUT.put(this.number, this);
        }

        /** the algorithm number */
        public final byte number;
        /** the algorithm description */
        public final String description;

        /**
         * Look up a SignatureAlgorithm by its byte value.
         * @param b the algorithm number
         * @return the matching SignatureAlgorithm, or null if not found
         */
        public static SignatureAlgorithm forByte(byte b) {
            return SIGNATURE_ALGORITHM_LUT.get(b);
        }
    }

    private static final Map<Byte, DigestAlgorithm> DELEGATION_DIGEST_LUT = new HashMap<>();

    /**
     * DNSSEC Digest Algorithms.
     *
     * @see <a href=
     *      "https://www.iana.org/assignments/ds-rr-types/ds-rr-types.xhtml">
     *      IANA Delegation Signer (DS) Resource Record (RR)</a>
     */
    public enum DigestAlgorithm {
        /** SHA-1 */
        SHA1(1, "SHA-1"),
        /** SHA-256 */
        SHA256(2, "SHA-256"),
        /** GOST R 34.11-94 */
        GOST(3, "GOST R 34.11-94"),
        /** SHA-384 */
        SHA384(4, "SHA-384"),
       ;

        DigestAlgorithm(int value, String description) {
            if (value < 0 || value > 255) {
                throw new IllegalArgumentException();
            }
            this.value = (byte) value;
            this.description = description;
            DELEGATION_DIGEST_LUT.put(this.value, this);
        }

        /** the digest algorithm value */
        public final byte value;
        /** the digest algorithm description */
        public final String description;

        /**
         * Look up a DigestAlgorithm by its byte value.
         * @param b the digest algorithm value
         * @return the matching DigestAlgorithm, or null if not found
         */
        public static DigestAlgorithm forByte(byte b) {
            return DELEGATION_DIGEST_LUT.get(b);
        }
    }
}
