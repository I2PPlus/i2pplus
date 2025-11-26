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
import java.net.Inet4Address;

import org.minidns.record.Record.TYPE;
import org.minidns.util.InetAddressUtil;

/**
 * A record payload (ip pointer).
 */
public class A extends InternetAddressRR<Inet4Address> {

    @Override
    public TYPE getType() {
        return TYPE.A;
    }

    /**
     * Create A record from IPv4 address.
     * @param inet4Address the IPv4 address
     */
    public A(Inet4Address inet4Address) {
        super(inet4Address);
        assert ip.length == 4;
    }

    /**
     * Create A record from four octets.
     * @param q1 first octet
     * @param q2 second octet
     * @param q3 third octet
     * @param q4 fourth octet
     */
    public A(int q1, int q2, int q3, int q4) {
        super(new byte[] { (byte) q1, (byte) q2, (byte) q3, (byte) q4 });
        if (q1 < 0 || q1 > 255 || q2 < 0 || q2 > 255 || q3 < 0 || q3 > 255 || q4 < 0 || q4 > 255) {
            throw new IllegalArgumentException();
        }
    }

    /**
     * Create A record from byte array.
     * @param ip byte array containing IPv4 address (must be 4 bytes)
     */
    public A(byte[] ip) {
        super(ip);
        if (ip.length != 4) {
            throw new IllegalArgumentException("IPv4 address in A record is always 4 byte");
        }
    }

    /**
     * Create A record from character sequence.
     * @param ipv4CharSequence character sequence containing IPv4 address
     */
    public A(CharSequence ipv4CharSequence) {
        this(InetAddressUtil.ipv4From(ipv4CharSequence));
    }

    /**
     * Parse A record from data input stream.
     * @param dis data input stream to read from
     * @return parsed A record
     * @throws java.io.IOException if I/O error occurs
     */
    public static A parse(DataInputStream dis)
            throws IOException {
        byte[] ip = new byte[4];
        dis.readFully(ip);
        return new A(ip);
    }

    @Override
    public String toString() {
        return Integer.toString(ip[0] & 0xff) + "." +
               Integer.toString(ip[1] & 0xff) + "." +
               Integer.toString(ip[2] & 0xff) + "." +
               Integer.toString(ip[3] & 0xff);
    }

}
