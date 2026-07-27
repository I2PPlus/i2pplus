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

import org.minidns.dnsname.DnsName;
import org.minidns.record.Record.TYPE;

import java.io.DataInputStream;
import java.io.IOException;

/**
 * DNS CNAME (Canonical Name) record payload.<br>
 * Maps an alias domain name to a canonical domain name for DNS resolution.
 */
public class CNAME extends RRWithTarget {

    /**
     * Parse a CNAME record from a data stream.
     *
     * @param dis the data input stream
     * @param data the raw record data
     * @return the parsed CNAME record
     * @throws IOException if parsing fails
     */
    public static CNAME parse(DataInputStream dis, byte[] data) throws IOException {
        DnsName target = DnsName.parse(dis, data);
        return new CNAME(target);
    }

    /**
     * Create a CNAME record from a string target.
     *
     * @param target the target domain name
     */
    public CNAME(String target) {
        this(DnsName.from(target));
    }

    /**
     * Create a CNAME record from a DnsName target.
     *
     * @param target the target domain name
     */
    public CNAME(DnsName target) {
        super(target);
    }

    @Override
    public TYPE getType() {
        return TYPE.CNAME;
    }
}
