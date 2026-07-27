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

import java.io.DataInputStream;
import java.io.IOException;

/**
 * DNS DLV (DNSSEC Lookaside Validation) record payload.<br>
 * Provides DNSSEC trust anchors from external validation repositories.
 * Has same format as DS records but used for lookaside validation.
 *
 * @see <a href="https://tools.ietf.org/html/rfc4431">RFC 4431</a>
 */
public class DLV extends DelegatingDnssecRR {

    /**
     * Parse a DLV record from a data stream.
     *
     * @param dis the data input stream
     * @param length the record length
     * @return the parsed DLV record
     * @throws IOException if parsing fails
     */
    public static DLV parse(DataInputStream dis, int length) throws IOException {
        SharedData parsedData = DelegatingDnssecRR.parseSharedData(dis, length);
        return new DLV(parsedData.keyTag, parsedData.algorithm, parsedData.digestType, parsedData.digest);
    }

    /**
     * Create a DLV record.
     *
     * @param keyTag the key tag
     * @param algorithm the signature algorithm
     * @param digestType the digest algorithm
     * @param digest the digest data
     */
    public DLV(int keyTag, byte algorithm, byte digestType, byte[] digest) {
        super(keyTag, algorithm, digestType, digest);
    }

    /**
     * Create a DLV record with typed algorithms.
     *
     * @param keyTag the key tag
     * @param algorithm the signature algorithm
     * @param digestType the digest algorithm
     * @param digest the digest data
     */
    public DLV(int keyTag, SignatureAlgorithm algorithm, DigestAlgorithm digestType, byte[] digest) {
        super(keyTag, algorithm, digestType, digest);
    }

    @Override
    public Record.TYPE getType() {
        return Record.TYPE.DLV;
    }
}
