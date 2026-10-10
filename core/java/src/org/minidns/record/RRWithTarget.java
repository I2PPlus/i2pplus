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

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Abstract base class for DNS resource records that point to a target domain name.<br>
 * Extended by record types like CNAME, NS, and others that contain a target field.
 */
public abstract class RRWithTarget extends Data {

    /** The target domain name */
    public final DnsName target;

    /**
     * The target of this resource record.
     * @deprecated {@link #target} instead.
     */
    @Deprecated public final DnsName name;

    @Override
    public void serialize(DataOutputStream dos) throws IOException {
        target.writeToStream(dos);
    }

    /**
     * RRWithTarget.
     * @param target the owner name the rdata is anchored to, stored under both
     *        the current field and the deprecated {@link #name} alias
     */
    protected RRWithTarget(DnsName target) {
        this.target = target;
        this.name = target;
    }

    /**
     * toString.
     */
    @Override
    public String toString() {
        return target + ".";
    }

    /**
     * Gets the owner name this record's rdata is anchored to.
     * @return the target domain name
     */
    public final DnsName getTarget() {
        return target;
    }
}
