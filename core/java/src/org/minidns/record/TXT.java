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

import org.minidns.record.Record.TYPE;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * DNS TXT record payload for arbitrary text data.<br>
 * Contains one or more text strings, commonly used for verification,
 * policies, or other metadata. Each string is prefixed with a length byte.
 */
public class TXT extends Data {

    private final byte[] blob;

    /**
     * Parse a TXT record's wire data, a sequence of length-prefixed strings.
     *
     * @param dis the stream positioned at the start of the record data
     * @param length how many bytes of record data to read
     * @return a TXT record holding those bytes
     * @throws IOException if the stream ends before length bytes have been read
     */
    public static TXT parse(DataInputStream dis, int length) throws IOException {
        byte[] blob = new byte[length];
        dis.readFully(blob);
        return new TXT(blob);
    }

    /**
     * Create a TXT record from its raw wire data.
     *
     * @param blob the raw TXT record data
     */
    public TXT(byte[] blob) {
        this.blob = blob;
    }

    /**
     * Get the raw wire data of this record.
     *
     * @return copy of the raw blob data
     */
    public byte[] getBlob() {
        return blob.clone();
    }

    private transient String textCache;

    /**
     * Return the strings joined by " / ", built once and then cached.
     *
     * @return the character strings joined by " / ", built once and then cached
     */
    public String getText() {
        if (textCache == null) {
            StringBuilder sb = new StringBuilder();
            Iterator<String> it = getCharacterStrings().iterator();
            while (it.hasNext()) {
                sb.append(it.next());
                if (it.hasNext()) {
                    sb.append(" / ");
                }
            }
            textCache = sb.toString();
        }
        return textCache;
    }

    private transient List<String> characterStringsCache;

    /**
     * Get the character strings of this record, decoding each as UTF-8.
     *
     * @return individual character strings as UTF-8 strings
     */
    public List<String> getCharacterStrings() {
        if (characterStringsCache == null) {
            List<byte[]> extents = getExtents();
            List<String> characterStrings = new ArrayList<>(extents.size());
            for (byte[] extent : extents) {
                characterStrings.add(new String(extent, StandardCharsets.UTF_8));
            }

            characterStringsCache = Collections.unmodifiableList(characterStrings);
        }
        return characterStringsCache;
    }

    /**
     * Get the character strings of this record as undecoded byte arrays.
     *
     * @return individual character strings as raw byte arrays
     */
    public List<byte[]> getExtents() {
        ArrayList<byte[]> extents = new ArrayList<>();
        int segLength = 0;
        for (int used = 0; used < blob.length; used += segLength) {
            segLength = 0x00ff & blob[used];
            int end = ++used + segLength;
            byte[] extent = Arrays.copyOfRange(blob, used, end);
            extents.add(extent);
        }
        return extents;
    }

    /**
     * Write the raw wire data to a DNS output stream.
     */
    @Override
    public void serialize(DataOutputStream dos) throws IOException {
        dos.write(blob);
    }

    /**
     * Return the record type of this payload.
     */
    @Override
    public TYPE getType() {
        return TYPE.TXT;
    }

    /**
     * Return the record's text in quotes, as it appears in a zone file.
     */
    @Override
    public String toString() {
        return "\"" + getText() + "\"";
    }
}
