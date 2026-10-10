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
package org.minidns.record;

import org.minidns.constants.DnssecConstants.DigestAlgorithm;
import org.minidns.constants.DnssecConstants.SignatureAlgorithm;
import org.minidns.record.Record.TYPE;

import java.io.DataInputStream;
import java.io.IOException;

/**
 * DNS DS (Delegation Signer) record payload for DNSSEC.<br>
 * Contains the hash of a DNSKEY record used to authenticate
 * delegated DNS zones in DNSSEC chain of trust.
 *
 * @see <a href="https://tools.ietf.org/html/rfc4034#section-5">RFC 4034 § 5</a>
 */
public class DS extends DelegatingDnssecRR {

    /**
     * Read a DS record payload from a wire-format stream.
     * @param dis the stream positioned at the start of the record payload
     * @param length the length in bytes of the record payload
     * @return the parsed DS record
     * @throws IOException if the payload cannot be read from the stream
     */
    public static DS parse(DataInputStream dis, int length) throws IOException {
        SharedData parsedData = DelegatingDnssecRR.parseSharedData(dis, length);
        return new DS(parsedData.keyTag, parsedData.algorithm, parsedData.digestType, parsedData.digest);
    }

    /**
     * Create a DS record, keeping the signature and digest algorithms as raw
     * numbers for use when MiniDNS does not recognize them.
     *
     * @param keyTag the key tag of the DNSKEY RR that validates this delegation's signature
     * @param algorithm the wire signature algorithm byte
     * @param digestType the wire digest algorithm byte
     * @param digest the DNSKEY digest, which must not be null
     */
    public DS(int keyTag, byte algorithm, byte digestType, byte[] digest) {
        super(keyTag, algorithm, digestType, digest);
    }

    /**
     * Create a DS record with a known signature algorithm but a digest type
     * that is kept as a raw number.
     *
     * @param keyTag the key tag of the DNSKEY RR that validates this delegation's signature
     * @param algorithm the signing algorithm, resolved from its wire value
     * @param digestType the wire digest algorithm byte
     * @param digest the DNSKEY digest, which must not be null
     */
    public DS(int keyTag, SignatureAlgorithm algorithm, byte digestType, byte[] digest) {
        super(keyTag, algorithm, digestType, digest);
    }

    /**
     * Create a DS record with both the signature and the digest algorithm
     * resolved to the constants MiniDNS knows.
     *
     * @param keyTag the key tag of the DNSKEY RR that validates this delegation's signature
     * @param algorithm the signing algorithm, resolved from its wire value
     * @param digestType the hashing algorithm the DNSKEY was digested with
     * @param digest the DNSKEY digest, which must not be null
     */
    public DS(int keyTag, SignatureAlgorithm algorithm, DigestAlgorithm digestType, byte[] digest) {
        super(keyTag, algorithm, digestType, digest);
    }

    /**
     * Report the RR type constant that identifies this payload as a DS record.
     */
    @Override
    public TYPE getType() {
        return TYPE.DS;
    }
}
