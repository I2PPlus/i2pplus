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

/**
 * DNS record payload for unknown or unsupported record types.<br>
 * Provides raw byte-level access to record data when the specific
 * record type is not recognized by the library.
 */
public final class UNKNOWN extends Data {

    /** The type this record stands for. */
    private final TYPE type;
    /** The record's bytes, as read. */
    private final byte[] data;

    /**
     * Reads a record of a type nothing else parses.
     *
     * @param dis the stream to read from
     * @param payloadLength the number of bytes to read
     * @param type the unrecognized type
     * @throws IOException if the reading fails
     */
    private UNKNOWN(DataInputStream dis, int payloadLength, TYPE type) throws IOException {
        this.type = type;
        this.data = new byte[payloadLength];
        dis.readFully(data);
    }

    /**
     * Return the record type this payload was read under.
     */
    @Override
    public TYPE getType() {
        return type;
    }

    /**
     * Write the retained payload bytes verbatim to the stream.
     */
    @Override
    public void serialize(DataOutputStream dos) throws IOException {
        dos.write(data);
    }

    /**
     * Read the payload of a record whose type this library does not support.
     */
    /**
     * Parse an unknown record from a stream.
     *
     * @param dis the stream positioned at the first byte of the record payload
     * @param payloadLength the payload size in bytes, read and retained verbatim
     * @param type the record type the payload was read under
     * @return the record holding the raw payload bytes
     * @throws IOException if the stream ends before payloadLength bytes have been read
     */
    public static UNKNOWN parse(DataInputStream dis, int payloadLength, TYPE type) throws IOException {
        return new UNKNOWN(dis, payloadLength, type);
    }
}
