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
 * DNS DNAME (Domain Name) redirection record payload.<br>
 * Provides redirection of an entire subtree of the DNS name space
 * to another domain, similar to CNAME but for entire subtrees.
 *
 * @see <a href="https://tools.ietf.org/html/rfc6672">RFC 6672 - DNAME Redirection in the DNS</a>
 */
public class DNAME extends RRWithTarget {

    /**
     * parse.
     *
     * @param dis the stream positioned at the wire-format name starting this record's rdata
     * @param data the whole message, needed to resolve compression pointers in the name
     * @return the parsed record, holding the redirected subtree as its target
     * @throws IOException if the stream ends inside the name
     */
    public static DNAME parse(DataInputStream dis, byte[] data) throws IOException {
        DnsName target = DnsName.parse(dis, data);
        return new DNAME(target);
    }

    /**
     * DNAME.
     *
     * @param target the redirected subtree in presentation form, e.g. "example.com"
     */
    public DNAME(String target) {
        this(DnsName.from(target));
    }

    /**
     * DNAME.
     *
     * @param target the redirected subtree, already parsed into its label form
     */
    public DNAME(DnsName target) {
        super(target);
    }

    /**
     * getType.
     */
    @Override
    public TYPE getType() {
        return TYPE.DNAME;
    }
}
