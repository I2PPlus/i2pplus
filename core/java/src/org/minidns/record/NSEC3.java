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

import org.minidns.dnslabel.DnsLabel;
import org.minidns.record.Record.TYPE;
import org.minidns.util.Base32;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DNS NSEC3 (Next Secure 3) record payload for DNSSEC.<br>
 * Provides authenticated denial of existence with hashed names
 * to prevent zone enumeration attacks.
 */
public class NSEC3 extends Data {

    /**
     * This Flag indicates whether this NSEC3 RR may cover unsigned
     * delegations.
     */
    public static final byte FLAG_OPT_OUT = 0x1;

    private static final Map<Byte, HashAlgorithm> HASH_ALGORITHM_LUT = new HashMap<>();

    /**
     * DNSSEC NSEC3 Hash Algorithms.
     *
     * @see <a href=
     *      "https://www.iana.org/assignments/dnssec-nsec3-parameters/dnssec-nsec3-parameters.xhtml#dnssec-nsec3-parameters-3">
     *      IANA DNSSEC NSEC3 Hash Algorithms</a>
     */
    public enum HashAlgorithm {
        /** Reserved */
        RESERVED(0, "Reserved"),
        /** SHA-1 */
        SHA1(1, "SHA-1"),
       ;

        HashAlgorithm(int value, String description) {
            if (value < 0 || value > 255) {
                throw new IllegalArgumentException();
            }
            this.value = (byte) value;
            this.description = description;
            HASH_ALGORITHM_LUT.put(this.value, this);
        }

        /**
         * The IANA registry value of the hash algorithm.
         */
        public final byte value;
        /**
         * The human-readable name of the hash algorithm.
         */
        public final String description;

        /**
         * Look up a hash algorithm by its registry value.
         *
         * @param b the hash algorithm byte as it appears in the record
         * @return the matching algorithm, or null if the byte is not registered
         */
        public static HashAlgorithm forByte(byte b) {
            return HASH_ALGORITHM_LUT.get(b);
        }
    }

    /**
     * The cryptographic hash algorithm used. If MiniDNS
     * isn't aware of the hash algorithm, then this field will be
     * <code>null</code>.
     *
     * @see #hashAlgorithmByte
     */
    public final HashAlgorithm hashAlgorithm;

    /**
     * The byte value of the cryptographic hash algorithm used.
     */
    public final byte hashAlgorithmByte;

    /**
     * Bitmap of flags: {@link #FLAG_OPT_OUT}.
     */
    public final byte flags;

    /**
     * The number of iterations the hash algorithm is applied.
     */
    public final int /* unsigned short */ iterations;

    /**
     * The salt appended to the next owner name before hashing.
     */
    /** the salt */
    private final byte[] salt;

    /**
     * The next hashed owner name in hash order.
     */
    /** the next hashed name */
    private final byte[] nextHashed;

    /** the type bitmap */
    private final byte[] typeBitmap;

    /**
     * The RR types existing at the original owner name.
     */
    public final List<TYPE> types;

    /**
     * Parse an NSEC3 record payload from a stream.
     *
     * @param dis the stream positioned at the start of the record
     * @param length the total length of the record in the stream, used to size the type bitmap
     * @return the parsed record
     * @throws IOException if the stream ends inside a field or the declared
     *         lengths do not add up to the record length
     */
    public static NSEC3 parse(DataInputStream dis, int length) throws IOException {
        byte hashAlgorithm = dis.readByte();
        byte flags = dis.readByte();
        int iterations = dis.readUnsignedShort();
        int saltLength = dis.readUnsignedByte();
        byte[] salt = new byte[saltLength];
        if (dis.read(salt) != salt.length) throw new IOException();
        int hashLength = dis.readUnsignedByte();
        byte[] nextHashed = new byte[hashLength];
        if (dis.read(nextHashed) != nextHashed.length) throw new IOException();
        int bitmapLength = length - (6 + saltLength + hashLength);
        if (bitmapLength < 0) {
            throw new IOException("Invalid NSEC3 record: length mismatch");
        }
        byte[] typeBitmap = new byte[bitmapLength];
        if (dis.read(typeBitmap) != typeBitmap.length) throw new IOException();
        List<TYPE> types = NSEC.readTypeBitMap(typeBitmap);
        return new NSEC3(hashAlgorithm, flags, iterations, salt, nextHashed, types);
    }

    private NSEC3(HashAlgorithm hashAlgorithm, byte hashAlgorithmByte, byte flags, int iterations, byte[] salt, byte[] nextHashed, List<TYPE> types) {
        assert hashAlgorithmByte == (hashAlgorithm != null ? hashAlgorithm.value : hashAlgorithmByte);
        this.hashAlgorithmByte = hashAlgorithmByte;
        this.hashAlgorithm = hashAlgorithm != null ? hashAlgorithm : HashAlgorithm.forByte(hashAlgorithmByte);

        this.flags = flags;
        this.iterations = iterations;
        this.salt = salt;
        this.nextHashed = nextHashed;
        this.types = types;
        this.typeBitmap = NSEC.createTypeBitMap(types);
    }

    /**
     * Create an NSEC3 record from its fields.
     *
     * @param hashAlgorithm the hash algorithm byte as it appears in the record
     * @param flags the flag bitmap, see FLAG_OPT_OUT
     * @param iterations the number of extra hash iterations, 0 to 65535
     * @param salt the salt prepended to the owner name before hashing
     * @param nextHashed the next existing owner name in hash order
     * @param types the RR types present at the original owner name
     */
    public NSEC3(byte hashAlgorithm, byte flags, int iterations, byte[] salt, byte[] nextHashed, List<TYPE> types) {
        this(null, hashAlgorithm, flags, iterations, salt, nextHashed, types);
    }

    /**
     * Create an NSEC3 record from its fields.
     *
     * @param hashAlgorithm the hash algorithm byte as it appears in the record
     * @param flags the flag bitmap, see FLAG_OPT_OUT
     * @param iterations the number of extra hash iterations, 0 to 65535
     * @param salt the salt prepended to the owner name before hashing
     * @param nextHashed the next existing owner name in hash order
     * @param types the RR types present at the original owner name
     */
    public NSEC3(byte hashAlgorithm, byte flags, int iterations, byte[] salt, byte[] nextHashed, TYPE... types) {
        this(null, hashAlgorithm, flags, iterations, salt, nextHashed, Arrays.asList(types));
    }

    /**
     * The DNS RR type of this record, always TYPE.NSEC3.
     *
     * @return the NSEC3 record type
     */
    @Override
    public TYPE getType() {
        return TYPE.NSEC3;
    }

    /**
     * Write this record's payload to a stream.
     *
     * @param dos the stream to write the record fields to
     * @throws IOException if the stream rejects the write
     */
    @Override
    public void serialize(DataOutputStream dos) throws IOException {
        dos.writeByte(hashAlgorithmByte);
        dos.writeByte(flags);
        dos.writeShort(iterations);
        dos.writeByte(salt.length);
        dos.write(salt);
        dos.writeByte(nextHashed.length);
        dos.write(nextHashed);
        dos.write(typeBitmap);
    }

    /**
     * Render the record in the dig-style presentation format.
     *
     * @return the algorithm, flags, iteration count, salt, next hashed name and types
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder()
                .append(hashAlgorithm).append(' ')
                .append(flags).append(' ')
                .append(iterations).append(' ')
                .append(salt.length == 0 ? "-" : new BigInteger(1, salt).toString(16).toUpperCase(Locale.ROOT)).append(' ')
                .append(Base32.encodeToString(nextHashed));
        for (TYPE type : types) {
            sb.append(' ').append(type);
        }
        return sb.toString();
    }

    /**
     * getSalt.
     *
     * @return a copy of the salt prepended to the owner name before hashing
     */
    public byte[] getSalt() {
        return salt.clone();
    }

    /**
     * getSaltLength.
     *
     * @return the number of salt bytes, from 0 to 255
     */
    public int getSaltLength() {
        return salt.length;
    }

    /**
     * getNextHashed.
     *
     * @return a copy of the next existing owner name in hash order
     */
    public byte[] getNextHashed() {
        return nextHashed.clone();
    }

    private String nextHashedBase32Cache;

    /**
     * getNextHashedBase32.
     *
     * @return the next hashed owner name, Base32 encoded and cached after the
     *         first call
     */
    public String getNextHashedBase32() {
        if (nextHashedBase32Cache == null) {
            nextHashedBase32Cache = Base32.encodeToString(nextHashed);
        }
        return nextHashedBase32Cache;
    }

    private DnsLabel nextHashedDnsLabelCache;

    /**
     * getNextHashedDnsLabel.
     *
     * @return the next hashed owner name as a DNS label, cached after the first call
     */
    public DnsLabel getNextHashedDnsLabel() {
        if (nextHashedDnsLabelCache == null) {
            String nextHashedBase32 = getNextHashedBase32();
            nextHashedDnsLabelCache = DnsLabel.from(nextHashedBase32);
        }
        return nextHashedDnsLabelCache;
    }

    /**
     * Copy the salt into a caller-supplied buffer.
     *
     * @param dest buffer that receives the salt bytes
     * @param destPos offset in dest at which to write the salt
     */
    public void copySaltInto(byte[] dest, int destPos) {
        System.arraycopy(salt, 0, dest, destPos, salt.length);
    }
}
