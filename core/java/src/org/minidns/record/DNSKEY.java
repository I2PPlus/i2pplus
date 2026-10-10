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

import org.minidns.constants.DnssecConstants.SignatureAlgorithm;
import org.minidns.record.Record.TYPE;
import org.minidns.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;

/**
 * DNS DNSKEY record payload for DNSSEC public keys.<br>
 * Contains a DNSSEC public key used to verify DNSSEC signatures
 * for a DNS zone.
 */
public class DNSKEY extends Data {
    /**
     * Whether the key should be used as a secure entry point key.
     *
     * see RFC 3757
     */
    public static final short FLAG_SECURE_ENTRY_POINT = 0x1;

    /**
     * Whether the record holds a revoked key.
     */
    public static final short FLAG_REVOKE = 0x80;

    /**
     * Whether the record holds a DNS zone key.
     */
    public static final short FLAG_ZONE = 0x100;

    /**
     * Use the protocol defined in RFC 4034.
     */
    public static final byte PROTOCOL_RFC4034 = 3;

    /**
     * Bitmap of flags: {@link #FLAG_SECURE_ENTRY_POINT}, {@link #FLAG_REVOKE}, {@link #FLAG_ZONE}.
     *
     * @see <a href="https://www.iana.org/assignments/dnskey-flags/dnskey-flags.xhtml">IANA - DNSKEY RR Flags</a>
     */
    public final short flags;

    /**
     * Must be {@link #PROTOCOL_RFC4034}.
     */
    public final byte protocol;

    /**
     * The public key's cryptographic algorithm used.
     *
     */
    public final SignatureAlgorithm algorithm;

    /**
     * The byte value of the public key's cryptographic algorithm used.
     *
     */
    public final byte algorithmByte;

    /**
     * The public key material. The format depends on the algorithm of the key being stored.
     */
    private final byte[] key;

    /**
     * This DNSKEY's key tag. Calculated just-in-time when using {@link #getKeyTag()}
     */
    private transient Integer keyTag;

    /**
     * Parse a DNSKEY record from the wire format.
     *
     * @param dis the stream positioned at the start of the record payload
     * @param length the size of the record in bytes, including the 4 byte flags, protocol and algorithm preamble
     * @return the parsed record
     * @throws IOException if length is under 4, or the stream ends before the key material is complete
     */
    public static DNSKEY parse(DataInputStream dis, int length) throws IOException {
        if (length < 4) {
            throw new IOException("Invalid DNSKEY record: length too short");
        }
        short flags = dis.readShort();
        byte protocol = dis.readByte();
        byte algorithm = dis.readByte();
        byte[] key = new byte[length - 4];
        dis.readFully(key);
        return new DNSKEY(flags, protocol, algorithm, key);
    }

    private DNSKEY(short flags, byte protocol, SignatureAlgorithm algorithm, byte algorithmByte, byte[] key) {
        this.flags = flags;
        this.protocol = protocol;

        assert algorithmByte == (algorithm != null ? algorithm.number : algorithmByte);
        this.algorithmByte = algorithmByte;
        this.algorithm = algorithm != null ? algorithm : SignatureAlgorithm.forByte(algorithmByte);

        this.key = key;
    }

    /**
     * Create a record from the raw algorithm number rather than a SignatureAlgorithm.
     *
     * @param flags the DNSKEY flags bitmap
     * @param protocol must be {@link #PROTOCOL_RFC4034}
     * @param algorithm the algorithm number as it appears on the wire
     * @param key the public key material, formatted per the algorithm
     */
    public DNSKEY(short flags, byte protocol, byte algorithm, byte[] key) {
        this(flags, protocol, SignatureAlgorithm.forByte(algorithm), algorithm, key);
    }

    /**
     * Create a record from a SignatureAlgorithm.
     *
     * @param flags the DNSKEY flags bitmap
     * @param protocol must be {@link #PROTOCOL_RFC4034}
     * @param algorithm the public key's cryptographic algorithm
     * @param key the public key material, formatted per the algorithm
     */
    public DNSKEY(short flags, byte protocol, SignatureAlgorithm algorithm, byte[] key) {
        this(flags, protocol, algorithm, algorithm.number, key);
    }

    /**
     * Record type of this record.
     *
     * @return {@link TYPE#DNSKEY}
     */
    @Override
    public TYPE getType() {
        return TYPE.DNSKEY;
    }

    /**
     * Retrieve the key tag identifying this DNSKEY.
     * The key tag is used within the DS and RRSIG record to distinguish multiple keys for the same name.
     *
     * This implementation is based on the reference implementation shown in RFC 4034 Appendix B.
     *
     * @return this DNSKEY's key tag
     */
    public /* unsigned short */ int getKeyTag() {
        if (keyTag == null) {
            byte[] recordBytes = toByteArray();
            long ac = 0;

            for (int i = 0; i < recordBytes.length; ++i) {
                ac += ((i & 1) > 0) ? recordBytes[i] & 0xFFL : ((recordBytes[i] & 0xFFL) << 8);
            }
            ac += (ac >> 16) & 0xFFFF;
            keyTag = (int) (ac & 0xFFFF);
        }
        return keyTag;
    }

    /**
     * Write the flags, protocol, algorithm number and key to the stream.
     *
     * @param dos the stream to write the wire format to
     * @throws IOException if the stream rejects the write
     */
    @Override
    public void serialize(DataOutputStream dos) throws IOException {
        dos.writeShort(flags);
        dos.writeByte(protocol);
        dos.writeByte(algorithmByte);
        dos.write(key);
    }

    /**
     * Diagnostic form of the record.
     *
     * @return the flags, protocol, algorithm and base64 key, separated by spaces
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder().append(flags).append(' ').append(protocol).append(' ').append(algorithm).append(' ').append(Base64.encodeToString(key));
        return sb.toString();
    }

    /**
     * Length of the public key material.
     *
     * @return the number of bytes in the public key
     */
    public int getKeyLength() {
        return key.length;
    }

    /**
     * Copy of the public key material.
     *
     * @return a copy of the key bytes, so callers cannot alter this record
     */
    public byte[] getKey() {
        return key.clone();
    }

    /**
     * Wrap the public key material in a stream.
     *
     * @return a stream reading over the key bytes
     */
    public DataInputStream getKeyAsDataInputStream() {
        return new DataInputStream(new ByteArrayInputStream(key));
    }

    private transient String keyBase64Cache;

    /**
     * Base64 encoding of the public key material, cached after the first call.
     *
     * @return the key encoded as base64
     */
    public String getKeyBase64() {
        if (keyBase64Cache == null) {
            keyBase64Cache = Base64.encodeToString(key);
        }
        return keyBase64Cache;
    }

    private transient BigInteger keyBigIntegerCache;

    /**
     * The public key material as a big-endian integer, cached after the first call.
     *
     * @return the key as a BigInteger
     */
    public BigInteger getKeyBigInteger() {
        if (keyBigIntegerCache == null) {
            keyBigIntegerCache = new BigInteger(key);
        }
        return keyBigIntegerCache;
    }

    /**
     * Compare this record's key material against another key.
     *
     * @param otherKey the key bytes to compare against
     * @return true if both keys are byte for byte identical
     */
    public boolean keyEquals(byte[] otherKey) {
        return Arrays.equals(key, otherKey);
    }

    /**
     * Whether the secure entry point flag is set.
     *
     * @return true if this is a secure entry point key
     */
    public boolean isSecureEntryPoint() {
        return (flags & FLAG_SECURE_ENTRY_POINT) == 1;
    }
}
