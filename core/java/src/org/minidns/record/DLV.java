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
package org.minidns.record;

import java.io.DataInputStream;
import java.io.IOException;
import org.minidns.constants.DnssecConstants.DigestAlgorithm;
import org.minidns.constants.DnssecConstants.SignatureAlgorithm;

/**
 * DNS DLV (DNSSEC Lookaside Validation) record payload.<br>
 * Provides DNSSEC trust anchors from external validation repositories.
 * Has same format as DS records but used for lookaside validation.
 *
 * @see <a href="https://tools.ietf.org/html/rfc4431">RFC 4431</a>
 */
public class DLV extends DelegatingDnssecRR {

    public static DLV parse (DataInputStream dis, int length) throws IOException {
        SharedData parsedData = DelegatingDnssecRR.parseSharedData(dis, length);
        return new DLV(parsedData.keyTag, parsedData.algorithm, parsedData.digestType, parsedData.digest);
    }

    public DLV(int keyTag, byte algorithm, byte digestType, byte[] digest) {
        super(keyTag, algorithm, digestType, digest);
    }

    public DLV(int keyTag, SignatureAlgorithm algorithm, DigestAlgorithm digestType, byte[] digest) {
        super(keyTag, algorithm, digestType, digest);
    }

    @Override
    public Record.TYPE getType() {
        return Record.TYPE.DLV;
    }
}
