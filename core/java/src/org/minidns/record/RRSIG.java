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
import org.minidns.dnsname.DnsName;
import org.minidns.record.Record.TYPE;
import org.minidns.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

/**
 * DNS RRSIG (Resource Record Signature) record payload for DNSSEC.<br>
 * Contains cryptographic signature covering a set of DNS records
 * to provide authenticity and integrity verification.
 */
public class RRSIG extends Data {

    /**
     * The type of RRset covered by this signature.
     */
    public final TYPE typeCovered;

    /**
     * The cryptographic algorithm used to create the signature.
     */
    public final SignatureAlgorithm algorithm;

    /**
     * The cryptographic algorithm used to create the signature.
     */
    public final byte algorithmByte;

    /**
     * The number of labels in the original RRSIG RR owner name.
     */
    public final byte labels;

    /**
     * The TTL of the covered RRset.
     */
    public final long /* unsigned int */ originalTtl;

    /**
     * The date and time this RRSIG records expires.
     */
    public final Date signatureExpiration;

    /**
     * The date and time this RRSIG records starts to be valid.
     */
    public final Date signatureInception;

    /**
     * The key tag value of the DNSKEY RR that validates this signature.
     */
    public final int /* unsigned short */ keyTag;

    /**
     * The owner name of the DNSKEY RR that a validator is supposed to use.
     */
    public final DnsName signerName;

    /**
     * Signature that covers RRSIG RDATA (excluding the signature field) and RRset data.
     */
    private final byte[] signature;

    /**
     * Parse the RDATA of an RRSIG record.
     *
     * @param dis the stream positioned at the start of the RDATA.
     * @param data the full message data, needed to decompress the signer name.
     * @param length the RDLENGTH of this record in bytes.
     * @return the parsed RRSIG payload.
     * @throws IOException if the RDATA is too short for the fixed RRSIG fields
     *                     or for the signature trailing the signer name.
     */
    @SuppressWarnings("JavaUtilDate")
    public static RRSIG parse(DataInputStream dis, byte[] data, int length) throws IOException {
        TYPE typeCovered = TYPE.getType(dis.readUnsignedShort());
        byte algorithm = dis.readByte();
        byte labels = dis.readByte();
        long originalTtl = dis.readInt() & 0xFFFFFFFFL;
        Date signatureExpiration = new Date((dis.readInt() & 0xFFFFFFFFL) * 1000);
        Date signatureInception = new Date((dis.readInt() & 0xFFFFFFFFL) * 1000);
        int keyTag = dis.readUnsignedShort();
        DnsName signerName = DnsName.parse(dis, data);
        int sigSize = length - signerName.size() - 18;
        if (sigSize < 0) {
            throw new IOException("Invalid RRSIG record: length mismatch");
        }
        byte[] signature = new byte[sigSize];
        if (dis.read(signature) != signature.length) throw new IOException();
        return new RRSIG(typeCovered, null, algorithm, labels, originalTtl, signatureExpiration, signatureInception, keyTag, signerName, signature);
    }

    private RRSIG(TYPE typeCovered, SignatureAlgorithm algorithm, byte algorithmByte, byte labels, long originalTtl, Date signatureExpiration, Date signatureInception, int keyTag, DnsName signerName, byte[] signature) {
        this.typeCovered = typeCovered;

        assert algorithmByte == (algorithm != null ? algorithm.number : algorithmByte);
        this.algorithmByte = algorithmByte;
        this.algorithm = algorithm != null ? algorithm : SignatureAlgorithm.forByte(algorithmByte);

        this.labels = labels;
        this.originalTtl = originalTtl;
        this.signatureExpiration = signatureExpiration;
        this.signatureInception = signatureInception;
        this.keyTag = keyTag;
        this.signerName = signerName;
        this.signature = signature;
    }

    /**
     * Create an RRSIG payload.
     *
     * @param typeCovered the RR type of the RRset this signature covers.
     * @param algorithm the DNSSEC signing algorithm number of RFC 4034.
     * @param labels the number of labels of the owner name of the covered RRset,
     *               the wildcard label of a wildcard RRset not counted.
     * @param originalTtl the TTL in seconds of the covered RRset in the signed zone.
     * @param signatureExpiration the time from which on the signature expires.
     * @param signatureInception the time from which on the signature is valid.
     * @param keyTag the key tag of the DNSKEY which must validate this signature.
     * @param signerName the owner name of the DNSKEY RRset holding the signing key.
     * @param signature the signature over the RRSIG RDATA without the signature
     *                  field and over the covered RRset.
     */
    public RRSIG(TYPE typeCovered, int algorithm, byte labels, long originalTtl, Date signatureExpiration, Date signatureInception, int keyTag, DnsName signerName, byte[] signature) {
        this(typeCovered, null, (byte) algorithm, labels, originalTtl, signatureExpiration, signatureInception, keyTag, signerName, signature);
    }

    /**
     * Create an RRSIG payload.
     *
     * @param typeCovered the RR type of the RRset this signature covers.
     * @param algorithm the DNSSEC signing algorithm number of RFC 4034.
     * @param labels the number of labels of the owner name of the covered RRset,
     *               the wildcard label of a wildcard RRset not counted.
     * @param originalTtl the TTL in seconds of the covered RRset in the signed zone.
     * @param signatureExpiration the time from which on the signature expires.
     * @param signatureInception the time from which on the signature is valid.
     * @param keyTag the key tag of the DNSKEY which must validate this signature.
     * @param signerName the owner name of the DNSKEY RRset holding the signing
     *                  key, in presentation format.
     * @param signature the signature over the RRSIG RDATA without the signature
     *                  field and over the covered RRset.
     */
    public RRSIG(TYPE typeCovered, int algorithm, byte labels, long originalTtl, Date signatureExpiration, Date signatureInception, int keyTag, String signerName, byte[] signature) {
        this(typeCovered, null, (byte) algorithm, labels, originalTtl, signatureExpiration, signatureInception, keyTag, DnsName.from(signerName), signature);
    }

    /**
     * Create an RRSIG payload.
     *
     * @param typeCovered the RR type of the RRset this signature covers.
     * @param algorithm the DNSSEC signing algorithm.
     * @param labels the number of labels of the owner name of the covered RRset,
     *               the wildcard label of a wildcard RRset not counted.
     * @param originalTtl the TTL in seconds of the covered RRset in the signed zone.
     * @param signatureExpiration the time from which on the signature expires.
     * @param signatureInception the time from which on the signature is valid.
     * @param keyTag the key tag of the DNSKEY which must validate this signature.
     * @param signerName the owner name of the DNSKEY RRset holding the signing key.
     * @param signature the signature over the RRSIG RDATA without the signature
     *                  field and over the covered RRset.
     */
    public RRSIG(TYPE typeCovered, SignatureAlgorithm algorithm, byte labels, long originalTtl, Date signatureExpiration, Date signatureInception, int keyTag, DnsName signerName, byte[] signature) {
        this(typeCovered, algorithm.number, labels, originalTtl, signatureExpiration, signatureInception, keyTag, signerName, signature);
    }

    /**
     * Create an RRSIG payload.
     *
     * @param typeCovered the RR type of the RRset this signature covers.
     * @param algorithm the DNSSEC signing algorithm.
     * @param labels the number of labels of the owner name of the covered RRset,
     *               the wildcard label of a wildcard RRset not counted.
     * @param originalTtl the TTL in seconds of the covered RRset in the signed zone.
     * @param signatureExpiration the time from which on the signature expires.
     * @param signatureInception the time from which on the signature is valid.
     * @param keyTag the key tag of the DNSKEY which must validate this signature.
     * @param signerName the owner name of the DNSKEY RRset holding the signing
     *                  key, in presentation format.
     * @param signature the signature over the RRSIG RDATA without the signature
     *                  field and over the covered RRset.
     */
    public RRSIG(TYPE typeCovered, SignatureAlgorithm algorithm, byte labels, long originalTtl, Date signatureExpiration, Date signatureInception, int keyTag, String signerName, byte[] signature) {
        this(typeCovered, algorithm.number, labels, originalTtl, signatureExpiration, signatureInception, keyTag, DnsName.from(signerName), signature);
    }

    /**
     * Retrieve a copy of the signature.
     *
     * @return a copy of the signature bytes.
     */
    public byte[] getSignature() {
        return signature.clone();
    }

    /**
     * Expose the signature as a stream.
     *
     * @return a stream to read the signature bytes from.
     */
    public DataInputStream getSignatureAsDataInputStream() {
        return new DataInputStream(new ByteArrayInputStream(signature));
    }

    /**
     * Retrieve the size of the signature in the wire format.
     *
     * @return the length of the signature in bytes.
     */
    public int getSignatureLength() {
        return signature.length;
    }

    private transient String base64SignatureCache;

    /**
     * Retrieve the signature in the base64 encoding of the RFC 4034 presentation
     * format.
     *
     * @return the base64 encoded signature.
     */
    public String getSignatureBase64() {
        if (base64SignatureCache == null) {
            base64SignatureCache = Base64.encodeToString(signature);
        }
        return base64SignatureCache;
    }

    /**
     * Retrieve the type of this record.
     *
     * @return TYPE.RRSIG.
     */
    @Override
    public TYPE getType() {
        return TYPE.RRSIG;
    }

    /**
     * Write the RDATA of this record.
     *
     * @param dos the stream to write to.
     * @throws IOException if an I/O error occurs.
     */
    @Override
    public void serialize(DataOutputStream dos) throws IOException {
        writePartialSignature(dos);
        dos.write(signature);
    }

    /**
     * Write the RRSIG RDATA without the trailing signature, which is the part a
     * validator has to feed into the signature calculation.
     *
     * @param dos the stream to write to.
     * @throws IOException if an I/O error occurs.
     */
    @SuppressWarnings("JavaUtilDate")
    public void writePartialSignature(DataOutputStream dos) throws IOException {
        dos.writeShort(typeCovered.getValue());
        dos.writeByte(algorithmByte);
        dos.writeByte(labels);
        dos.writeInt((int) originalTtl);
        dos.writeInt((int) (signatureExpiration.getTime() / 1000));
        dos.writeInt((int) (signatureInception.getTime() / 1000));
        dos.writeShort(keyTag);
        signerName.writeToStream(dos);
    }

    /**
     * Format this record in the presentation format of RFC 4034.
     *
     * @return a single line holding the covered type, algorithm, label count,
     *         TTL, both validity times, key tag, signer name and signature.
     */
    @Override
    public String toString() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmss");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        StringBuilder sb = new StringBuilder().append(typeCovered).append(' ').append(algorithm).append(' ').append(labels).append(' ').append(originalTtl).append(' ').append(dateFormat.format(signatureExpiration)).append(' ').append(dateFormat.format(signatureInception)).append(' ').append(keyTag).append(' ').append(signerName).append(". ").append(getSignatureBase64());
        return sb.toString();
    }
}
