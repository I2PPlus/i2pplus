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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;

import org.apache.commons.io.IOUtils;

/**
 * The TelnetClient class implements the simple network virtual terminal (NVT) for the Telnet protocol according to RFC 854. It does not implement any of the
 * extra Telnet options because it is meant to be used within a Java program providing automated access to Telnet accessible resources.
 * <p>
 * The class can be used by first connecting to a server using the SocketClient {@link org.apache.commons.net.SocketClient#connect connect} method. Then an
 * InputStream and OutputStream for sending and receiving data over the Telnet connection can be obtained by using the {@link #getInputStream getInputStream()}
 * and {@link #getOutputStream getOutputStream()} methods. When you finish using the streams, you must call {@link #disconnect disconnect} rather than simply
 * closing the streams.
 * </p>
 */
public class TelnetClient extends Telnet {
    private static final int DEFAULT_MAX_SUBNEGOTIATION_LENGTH = 512;

    /**
     * the size of the subnegotiation buffer.
     */
    final int maxSubnegotiationLength;

    /** Input stream. */
    private InputStream input;

    /** Output stream. */
    private OutputStream output;

    /**
     * Whether to enable the reader thread.
     */
    protected boolean readerThread = true;

    /**
     * Telnet input listener.
     */
    private TelnetInputListener inputListener;

    /**
     * Default TelnetClient constructor, sets terminal-type {@code VT100}.
     */
    public TelnetClient() {
        this("VT100", DEFAULT_MAX_SUBNEGOTIATION_LENGTH);
    }

    /**
     * Constructs an instance with the specified max subnegotiation length and the default terminal-type {@code VT100}.
     *
     * @param maxSubnegotiationLength the size of the subnegotiation buffer.
     */
    public TelnetClient(final int maxSubnegotiationLength) {
        this("VT100", maxSubnegotiationLength);
    }

    /**
     * Constructs an instance with the specified terminal type.
     *
     * @param termtype the terminal type to use, e.g. {@code VT100}
     */
    public TelnetClient(final String termtype) {
        this(termtype, DEFAULT_MAX_SUBNEGOTIATION_LENGTH);
    }

    /**
     * Constructs an instance with the specified terminal type and max subnegotiation length
     *
     * @param termType                the terminal type to use, e.g. {@code VT100}
     * @param maxSubnegotiationLength the size of the subnegotiation buffer
     */
    public TelnetClient(final String termType, final int maxSubnegotiationLength) {
        /* TERMINAL-TYPE option (start) */
        super(termType);
        /* TERMINAL-TYPE option (end) */
        this.input = null;
        this.output = null;
        this.maxSubnegotiationLength = maxSubnegotiationLength;
    }

    /**
     * Handles special connection requirements.
     *
     * @throws IOException If an error occurs during connection setup.
     */
    @Override
    protected void _connectAction_() throws IOException {
        super._connectAction_();
        final TelnetInputStream tmp = new TelnetInputStream(_input_, this, readerThread);
        if (readerThread) {
            tmp.start();
        }
        // input CANNOT refer to the TelnetInputStream. We run into
        // blocking problems when some classes use TelnetInputStream, so
        // we wrap it with a BufferedInputStream which we know is safe.
        // This blocking behavior requires further investigation, but right
        // now it looks like classes like InputStreamReader are not implemented
        // in a safe manner.
        input = new BufferedInputStream(tmp);
        output = new TelnetOutputStream(this);
    }

    /**
     * Registers a new TelnetOptionHandler for this Telnet client to use.
     *
     * @param opthand   option handler to be registered.
     * @throws InvalidTelnetOptionException on error
     * @throws IOException                  on error
     */
    @Override
    public void addOptionHandler(final TelnetOptionHandler opthand) throws InvalidTelnetOptionException, IOException {
        super.addOptionHandler(opthand);
    }
    /* open TelnetOptionHandler functionality (end) */

    void closeOutputStream() throws IOException {
        if (_output_ == null) {
            return;
        }
        try {
            _output_.close();
        } finally {
            _output_ = null;
        }
    }

    /**
     * Unregisters a TelnetOptionHandler.
     *
     * @param optcode   Code of the option to be unregistered.
     * @throws InvalidTelnetOptionException on error
     * @throws IOException                  on error
     */
    @Override
    public void deleteOptionHandler(final int optcode) throws InvalidTelnetOptionException, IOException {
        super.deleteOptionHandler(optcode);
    }

    /**
     * Disconnects the Telnet session, closing the input and output streams as well as the socket. If you have references to the input and output streams of the
     * Telnet connection, you should not close them yourself, but rather call disconnect to properly close the connection.
     */
    @Override
    public void disconnect() throws IOException {
        try {
            IOUtils.close(input);
            IOUtils.close(output);
        } finally { // NET-594
            output = null;
            input = null;
            super.disconnect();
        }
    }

    void flushOutputStream() throws IOException {
        if (_output_ == null) {
            throw new IOException("Stream closed");
        }
        _output_.flush();
    }

    /**
     * Gets the Telnet connection input stream. You should not close the stream when you finish with it. Rather, you should call {@link #disconnect
     * disconnect }.
     *
     * @return The Telnet connection input stream.
     */
    public InputStream getInputStream() {
        return input;
    }

    /**
     * Gets the state of the option on the local side.
     *
     * @param option   Option to be checked.
     * @return The state of the option on the local side.
     */
    public boolean getLocalOptionState(final int option) {
        /* BUG (option active when not already acknowledged) (start) */
        return stateIsWill(option) && requestedWill(option);
        /* BUG (option active when not already acknowledged) (end) */
    }

    /* Code Section added for supporting AYT (start) */

    /**
     * Gets the Telnet connection output stream. You should not close the stream when you finish with it. Rather, you should call {@link #disconnect
     * disconnect }.
     *
     * @return The Telnet connection output stream.
     */
    public OutputStream getOutputStream() {
        return output;
    }

    /**
     * Gets the status of the reader thread.
     *
     * @return true if the reader thread is enabled, false otherwise
     */
    public boolean getReaderThread() {
        return readerThread;
    }

    /**
     * Gets the state of the option on the remote side.
     *
     * @param option   Option to be checked.
     * @return The state of the option on the remote side.
     */
    public boolean getRemoteOptionState(final int option) {
        /* BUG (option active when not already acknowledged) (start) */
        return stateIsDo(option) && requestedDo(option);
        /* BUG (option active when not already acknowledged) (end) */
    }
    /* open TelnetOptionHandler functionality (end) */

    /* open TelnetOptionHandler functionality (start) */

    // Notify input listener
    void notifyInputListener() {
        final TelnetInputListener listener;
        synchronized (this) {
            listener = this.inputListener;
        }
        if (listener != null) {
            listener.telnetInputAvailable();
        }
    }

    /**
     * Register a listener to be notified when new incoming data is available to be read on the {@link #getInputStream input stream}. Only one listener is
     * supported at a time.
     *
     * <p>
     * More precisely, notifications are issued whenever the number of bytes available for immediate reading (i.e., the value returned by
     * {@link InputStream#available}) transitions from zero to non-zero. Note that (in general) multiple reads may be required to empty the buffer and reset
     * this notification, because incoming bytes are being added to the internal buffer asynchronously.
     * </p>
     *
     * <p>
     * Notifications are only supported when a {@link #setReaderThread reader thread} is enabled for the connection.
     * </p>
     *
     * @param listener listener to be registered, replaces any previous listener.
     * @since 3.0
     */
    public synchronized void registerInputListener(final TelnetInputListener listener) {
        this.inputListener = listener;
    }

    /* Code Section added for supporting spystreams (start) */
    /**
     * Registers an OutputStream for spying what's going on in the TelnetClient session.
     *
     * @param spystream   OutputStream on which session activity will be echoed.
     */
    public void registerSpyStream(final OutputStream spystream) {
        super._registerSpyStream(spystream);
    }

    /**
     * Sends an {@code Are You There (AYT)} sequence and waits for the result.
     *
     * @param timeout   Time to wait for a response.
     * @return true if AYT received a response, false otherwise.
     * @throws InterruptedException     on error
     * @throws IllegalArgumentException on error
     * @throws IOException              on error
     * @since 3.10.0
     */
    public boolean sendAYT(final Duration timeout) throws IOException, IllegalArgumentException, InterruptedException {
        return _sendAYT(timeout);
    }

    /**
     * Sends an {@code Are You There (AYT)} sequence and waits for the result.
     *
     * @param timeout   Time to wait for a response (millis.)
     * @return true if AYT received a response, false otherwise
     * @throws InterruptedException     on error
     * @throws IllegalArgumentException on error
     * @throws IOException              on error
     * @deprecated Use {@link #sendAYT(Duration)}.
     */
    @Deprecated
    public boolean sendAYT(final long timeout) throws IOException, IllegalArgumentException, InterruptedException {
        return _sendAYT(Duration.ofMillis(timeout));
    }

    /* Code Section added for supporting AYT (start) */

    /**
     * Sends a command byte to the remote peer, adding the IAC prefix.
     *
     * <p>
     * This method does not wait for any response. Messages sent by the remote end can be handled by registering an approrpriate {@link TelnetOptionHandler}.
     * </p>
     *
     * @param command the code for the command
     * @throws IOException              if an I/O error occurs while writing the message
     * @throws IllegalArgumentException on error
     * @since 3.0
     */
    public void sendCommand(final byte command) throws IOException, IllegalArgumentException {
        _sendCommand(command);
    }

    /**
     * Sends a protocol-specific subnegotiation message to the remote peer. {@link TelnetClient} will add the IAC SB &amp; IAC SE framing bytes; the first byte
     * in {@code message} should be the appropriate Telnet option code.
     *
     * <p>
     * This method does not wait for any response. Subnegotiation messages sent by the remote end can be handled by registering an approrpriate
     * {@link TelnetOptionHandler}.
     * </p>
     *
     * @param message option code followed by subnegotiation payload
     * @throws IllegalArgumentException if {@code message} has length zero
     * @throws IOException              if an I/O error occurs while writing the message
     * @since 3.0
     */
    public void sendSubnegotiation(final int[] message) throws IOException, IllegalArgumentException {
        if (message.length < 1) {
            throw new IllegalArgumentException("zero length message");
        }
        _sendSubnegotiation(message);
    }

    /**
     * Sets the status of the reader thread.
     *
     * <p>
     * When enabled, a seaparate internal reader thread is created for new connections to read incoming data as it arrives. This results in immediate handling
     * of option negotiation, notifications, etc. (at least until the fixed-size internal buffer fills up). Otherwise, no thread is created an all negotiation
     * and option handling is deferred until a read() is performed on the {@link #getInputStream input stream}.
     * </p>
     *
     * <p>
     * The reader thread must be enabled for {@link TelnetInputListener} support.
     * </p>
     *
     * <p>
     * When this method is invoked, the reader thread status will apply to all subsequent connections; the current connection (if any) is not affected.
     * </p>
     *
     * @param readerThread true to enable the reader thread, false to disable.
     * @see #registerInputListener
     */
    public void setReaderThread(final boolean readerThread) {
        this.readerThread = readerThread;
    }

    /**
     * Stops spying this TelnetClient.
     */
    public void stopSpyStream() {
        super._stopSpyStream();
    }
    /* Code Section added for supporting spystreams (end) */

    /**
     * Unregisters the current {@link TelnetInputListener}, if any.
     *
     * @since 3.0
     */
    public synchronized void unregisterInputListener() {
        this.inputListener = null;
    }

}
