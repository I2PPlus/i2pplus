/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.commons.net.io;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * This class wraps an output stream, replacing all occurrences of &lt;CR&gt;&lt;LF&gt; (carriage return followed by a linefeed), which is the NETASCII standard
 * for representing a newline, with the local line separator representation. You would use this class to implement ASCII file transfers requiring conversion
 * from NETASCII.
 * <p>
 * Because of the translation process, a call to {@code flush()} will not flush the last byte written if that byte was a carriage return. A call to
 * {@link #close close()}, however, will flush the carriage return.
 * </p>
 */
public final class FromNetASCIIOutputStream extends FilterOutputStream {
    private boolean lastWasCR;

    /**
     * Creates a FromNetASCIIOutputStream instance that wraps an existing OutputStream.
     *
     * @param output The OutputStream to wrap.
     */
    public FromNetASCIIOutputStream(final OutputStream output) {
        super(output);
    }

    /**
     * Closes the stream, writing all pending data.
     *
     * @throws IOException If an error occurs while closing the stream.
     */
    @Override
    public synchronized void close() throws IOException {
        if (FromNetASCIIInputStream.NO_CONVERSION_REQUIRED) {
            super.close();
            return;
        }
        if (lastWasCR) {
            out.write('\r');
        }
        super.close();
    }

    /**
     * Writes a byte array to the stream.
     *
     * @param buffer The byte array to write.
     * @throws IOException If an error occurs while writing to the underlying stream.
     */
    @Override
    public synchronized void write(final byte buffer[]) throws IOException {
        write(buffer, 0, buffer.length);
    }

    /**
     * Writes a number of bytes from a byte array to the stream starting from a given offset.
     *
     * @param buffer The byte array to write.
     * @param offset The offset into the array at which to start copying data.
     * @param length The number of bytes to write.
     * @throws IOException If an error occurs while writing to the underlying stream.
     */
    @Override
    public synchronized void write(final byte buffer[], int offset, int length) throws IOException {
        if (FromNetASCIIInputStream.NO_CONVERSION_REQUIRED) {
            // FilterOutputStream method is very slow.
            // super.write(buffer, offset, length);
            out.write(buffer, offset, length);
            return;
        }
        while (length-- > 0) {
            writeInt(buffer[offset++]);
        }
    }

    /**
     * Writes a byte to the stream. Note that a call to this method might not actually write a byte to the underlying stream until a subsequent character is
     * written, from which it can be determined if a NETASCII line separator was encountered. This is transparent to the programmer and is only mentioned for
     * completeness.
     *
     * @param ch The byte to write.
     * @throws IOException If an error occurs while writing to the underlying stream.
     */
    @Override
    public synchronized void write(final int ch) throws IOException {
        if (FromNetASCIIInputStream.NO_CONVERSION_REQUIRED) {
            out.write(ch);
            return;
        }
        writeInt(ch);
    }

    private void writeInt(final int ch) throws IOException {
        switch (ch) {
        case '\r':
            lastWasCR = true;
            // Don't write anything. We need to see if next one is linefeed
            break;
        case '\n':
            if (lastWasCR) {
                out.write(FromNetASCIIInputStream.LINE_SEPARATOR_BYTES);
                lastWasCR = false;
                break;
            }
            out.write('\n');
            break;
        default:
            if (lastWasCR) {
                out.write('\r');
                lastWasCR = false;
            }
            out.write(ch);
            break;
        }
    }
}
