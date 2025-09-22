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

package org.apache.commons.net.telnet;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Wraps an output stream.
 * <p>
 * In binary mode, the only conversion is to double IAC.
 * </p>
 * <p>
 * In ASCII mode, if convertCRtoCRLF is true (currently always true), any CR is converted to CRLF. IACs are doubled. Also, a bare LF is converted to CRLF and a
 * bare CR is converted to CR\0
 * </p>
 */
final class TelnetOutputStream extends OutputStream {
    private static final boolean CONVERT_TO_CRLF = true;
    private final TelnetClient client;
    // TODO there does not appear to be any way to change this value - should it be a ctor parameter?
    private boolean lastWasCR;

    TelnetOutputStream(final TelnetClient client) {
        this.client = client;
    }

    /** Closes the stream. */
    @Override
    public void close() throws IOException {
        client.closeOutputStream();
    }

    /** Flushes the stream. */
    @Override
    public void flush() throws IOException {
        client.flushOutputStream();
    }

    /**
     * Writes a byte array to the stream.
     *
     * @param buffer The byte array to write.
     * @throws IOException If an error occurs while writing to the underlying stream.
     */
    @Override
    public void write(final byte buffer[]) throws IOException {
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
    public void write(final byte buffer[], int offset, int length) throws IOException {
        synchronized (client) {
            while (length-- > 0) {
                write(buffer[offset++]);
            }
        }
    }

    /**
     * Writes a byte to the stream.
     *
     * @param ch The byte to write.
     * @throws IOException If an error occurs while writing to the underlying stream.
     */
    @Override
    public void write(int ch) throws IOException {

        synchronized (client) {
            ch &= 0xff;

            // i.e. ASCII
            if (client.requestedWont(TelnetOption.BINARY)) {
                if (lastWasCR) {
                    if (CONVERT_TO_CRLF) {
                        client.sendByte('\n');
                        if (ch == '\n') {
                            // i.e. was CRLF anyway
                            lastWasCR = false;
                            return;
                        }
                    } else if (ch != '\n') {
                        // convertCRtoCRLF
                        client.sendByte(Telnet.NUL); // RFC854 requires CR NUL for bare CR
                    }
                }

                switch (ch) {
                case '\r':
                    client.sendByte('\r');
                    lastWasCR = true;
                    break;
                case '\n':
                    if (!lastWasCR) { // convert LF to CRLF
                        client.sendByte('\r');
                    }
                    client.sendByte(ch);
                    lastWasCR = false;
                    break;
                case TelnetCommand.IAC:
                    client.sendByte(TelnetCommand.IAC);
                    client.sendByte(TelnetCommand.IAC);
                    lastWasCR = false;
                    break;
                default:
                    client.sendByte(ch);
                    lastWasCR = false;
                    break;
                }
            // end ASCII
            } else if (ch == TelnetCommand.IAC) {
                client.sendByte(ch);
                client.sendByte(TelnetCommand.IAC);
            } else {
                client.sendByte(ch);
            }
        }
    }
}
