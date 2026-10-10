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
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * DNS SOA (Start of Authority) record payload.<br>
 * Contains authoritative information about a DNS zone including
 * primary nameserver, admin email, and timing parameters.
 */
public class SOA extends Data {

    /**
     * The domain name of the name server that was the original or primary source of data for this zone.
     */
    public final DnsName mname;

    /**
     * A domain name which specifies the mailbox of the person responsible for this zone.
     */
    public final DnsName rname;

    /**
     * The unsigned 32 bit version number of the original copy of the zone.  Zone transfers preserve this value.  This
     * value wraps and should be compared using sequence space arithmetic.
     */
    public final long /* unsigned int */ serial;

    /**
     * A 32 bit time interval before the zone should be refreshed.
     */
    public final int refresh;

    /**
     * A 32 bit time interval that should elapse before a failed refresh should be retried.
     */
    public final int retry;

    /**
     * A 32 bit time value that specifies the upper limit on the time interval that can elapse before the zone is no
     * longer authoritative.
     */
    public final int expire;

    /**
     * The unsigned 32 bit minimum TTL field that should be exported with any RR from this zone.
     */
    public final long /* unsigned int */ minimum;

    /**
     * Parse an SOA record payload from a DNS message.
     * @param dis the stream positioned at the start of the SOA record payload
     * @param data the raw DNS message data, needed to resolve compressed names
     * @return the parsed SOA record
     * @throws IOException on parse error
     */
    public static SOA parse(DataInputStream dis, byte[] data) throws IOException {
        DnsName mname = DnsName.parse(dis, data);
        DnsName rname = DnsName.parse(dis, data);
        long serial = dis.readInt() & 0xFFFFFFFFL;
        int refresh = dis.readInt();
        int retry = dis.readInt();
        int expire = dis.readInt();
        long minimum = dis.readInt() & 0xFFFFFFFFL;
        return new SOA(mname, rname, serial, refresh, retry, expire, minimum);
    }

    /**
     * Create an SOA record from names in presentation form.
     * @param mname the primary nameserver for the zone, in presentation form
     * @param rname the mailbox of the zone administrator, in presentation form
     * @param serial the unsigned 32 bit version number of the zone copy
     * @param refresh seconds to wait before the zone should be refreshed
     * @param retry seconds to wait before a failed refresh is retried
     * @param expire seconds after which the zone is no longer authoritative
     * @param minimum the unsigned 32 bit minimum TTL exported with any RR from the zone
     */
    public SOA(String mname, String rname, long serial, int refresh, int retry, int expire, long minimum) {
        this(DnsName.from(mname), DnsName.from(rname), serial, refresh, retry, expire, minimum);
    }

    /**
     * Create an SOA record from already parsed names.
     * @param mname the primary nameserver for the zone
     * @param rname the mailbox of the zone administrator
     * @param serial the unsigned 32 bit version number of the zone copy
     * @param refresh seconds to wait before the zone should be refreshed
     * @param retry seconds to wait before a failed refresh is retried
     * @param expire seconds after which the zone is no longer authoritative
     * @param minimum the unsigned 32 bit minimum TTL exported with any RR from the zone
     */
    public SOA(DnsName mname, DnsName rname, long serial, int refresh, int retry, int expire, long minimum) {
        this.mname = mname;
        this.rname = rname;
        this.serial = serial;
        this.refresh = refresh;
        this.retry = retry;
        this.expire = expire;
        this.minimum = minimum;
    }

    /**
     * Return the record type of this payload.
     */
    @Override
    public TYPE getType() {
        return TYPE.SOA;
    }

    /**
     * Write the seven authority fields to a DNS output stream.
     */
    @Override
    public void serialize(DataOutputStream dos) throws IOException {
        mname.writeToStream(dos);
        rname.writeToStream(dos);
        dos.writeInt((int) serial);
        dos.writeInt(refresh);
        dos.writeInt(retry);
        dos.writeInt(expire);
        dos.writeInt((int) minimum);
    }

    /**
     * Return the zone's authority data in presentation format.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder().append(mname).append(". ").append(rname).append(". ").append(serial).append(' ').append(refresh).append(' ').append(retry).append(' ').append(expire).append(' ').append(minimum);
        return sb.toString();
    }
}
