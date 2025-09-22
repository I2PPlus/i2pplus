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

package org.apache.commons.net.tftp;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.DatagramPacket;
import java.net.SocketException;
import java.time.Duration;

import org.apache.commons.net.DatagramSocketClient;

/**
 * The TFTP class exposes a set of methods to allow you to deal with the TFTP protocol directly, in case you want to write your own TFTP client or server.
 * However, almost every user should only be concerend with the {@link org.apache.commons.net.DatagramSocketClient#open open()}, and
 * {@link org.apache.commons.net.DatagramSocketClient#close close()}, methods. Additionally,the a
 * {@link org.apache.commons.net.DatagramSocketClient#setDefaultTimeout setDefaultTimeout()} method may be of importance for performance tuning.
 * <p>
 * Details regarding the TFTP protocol and the format of TFTP packets can be found in RFC 783. But the point of these classes is to keep you from having to
 * worry about the internals.
 * </p>
 *
 * @see org.apache.commons.net.DatagramSocketClient
 * @see TFTPPacket
 * @see TFTPPacketException
 * @see TFTPClient
 */
public class TFTP extends DatagramSocketClient {

    /**
     * The header size.
     */
    private static final int HEADER_SIZE = 4;

    /**
     * The ASCII transfer mode. Its value is 0 and equivalent to NETASCII_MODE
     */
    public static final int ASCII_MODE = 0;

    /**
     * The netascii transfer mode. Its value is 0.
     */
    public static final int NETASCII_MODE = 0;

    /**
     * The binary transfer mode. Its value is 1 and equivalent to OCTET_MODE.
     */
    public static final int BINARY_MODE = 1;

    /**
     * The image transfer mode. Its value is 1 and equivalent to OCTET_MODE.
     */
    public static final int IMAGE_MODE = 1;

    /**
     * The octet transfer mode. Its value is 1.
     */
    public static final int OCTET_MODE = 1;

    /**
     * The default number of milliseconds to wait to receive a datagram before timing out. The default is 5,000 milliseconds (5 seconds).
     *
     * @deprecated Use {@link #DEFAULT_TIMEOUT_DURATION}.
     */
    @Deprecated
    public static final int DEFAULT_TIMEOUT = 5000;

    /**
     * The default duration to wait to receive a datagram before timing out. The default is 5 seconds.
     *
     * @since 3.10.0
     */
    public static final Duration DEFAULT_TIMEOUT_DURATION = Duration.ofSeconds(5);

    /**
     * The default TFTP port according to RFC 783 is 69.
     */
    public static final int DEFAULT_PORT = 69;

    /**
     * The size to use for TFTP packet buffers. Its 4 plus the TFTPPacket.SEGMENT_SIZE, i.e. 516.
     */
    static final int PACKET_SIZE = TFTPPacket.SEGMENT_SIZE + HEADER_SIZE;

    /**
     * Returns the TFTP string representation of a TFTP transfer mode. Will throw an ArrayIndexOutOfBoundsException if an invalid transfer mode is specified.
     *
     * @param mode The TFTP transfer mode. One of the MODE constants.
     * @return The TFTP string representation of the TFTP transfer mode.
     */
    public static final String getModeName(final int mode) {
        return TFTPRequestPacket.modeStrings[mode];
    }

    /** A buffer used to accelerate receives in bufferedReceive() */
    private byte[] receiveBuffer;

    /** A datagram used to minimize memory allocation in bufferedReceive() */
    private DatagramPacket receiveDatagram;

    /** A datagram used to minimize memory allocation in bufferedSend() */
    private DatagramPacket sendDatagram;

    /** Internal packet size, which is the data octet size plus 4 (for TFTP header octets) */
    private int packetSize = PACKET_SIZE;

    /** Internal state to track if the send/receive buffers are initialized */
    private boolean buffersInitialized;

    /**
     * A buffer used to accelerate sends in bufferedSend(). It is left package visible so that TFTPClient may be slightly more efficient during file sends. It
     * saves the creation of an additional buffer and prevents a buffer copy in _newDataPcket().
     */
    byte[] sendBuffer;

    /**
     * Creates a TFTP instance with a default timeout of {@link #DEFAULT_TIMEOUT_DURATION}, a null socket, and buffered operations disabled.
     */
    public TFTP() {
        setDefaultTimeout(DEFAULT_TIMEOUT_DURATION);
        receiveBuffer = null;
        receiveDatagram = null;
    }

    /**
     * Initializes the internal buffers. Buffers are used by {@link #bufferedSend bufferedSend()} and {@link #bufferedReceive bufferedReceive()}. This method
     * must be called before calling either one of those two methods. When you finish using buffered operations, you must call {@link #endBufferedOps
     * endBufferedOps()}.
     */
    public final void beginBufferedOps() {
        receiveBuffer = new byte[packetSize];
        receiveDatagram = new DatagramPacket(receiveBuffer, receiveBuffer.length);
        sendBuffer = new byte[packetSize];
        sendDatagram = new DatagramPacket(sendBuffer, sendBuffer.length);
        buffersInitialized = true;
    }

    /**
     * This is a special method to perform a more efficient packet receive. It should only be used after calling {@link #beginBufferedOps beginBufferedOps()}.
     * beginBufferedOps() initializes a set of buffers used internally that prevent the new allocation of a DatagramPacket and byte array for each send and
     * receive. To use these buffers you must call the bufferedReceive() and bufferedSend() methods instead of send() and receive(). You must also be certain
     * that you don't manipulate the resulting packet in such a way that it interferes with future buffered operations. For example, a TFTPDataPacket received
     * with bufferedReceive() will have a reference to the internal byte buffer. You must finish using this data before calling bufferedReceive() again, or else
     * the data will be overwritten by the call.
     *
     * @return The TFTPPacket received.
     * @throws InterruptedIOException If a socket timeout occurs. The Java documentation claims an InterruptedIOException is thrown on a DatagramSocket timeout,
     *                                but in practice we find a SocketException is thrown. You should catch both to be safe.
     * @throws SocketException        If a socket timeout occurs. The Java documentation claims an InterruptedIOException is thrown on a DatagramSocket timeout,
     *                                but in practice we find a SocketException is thrown. You should catch both to be safe.
     * @throws IOException            If some other I/O error occurs.
     * @throws TFTPPacketException    If an invalid TFTP packet is received.
     */
    public final TFTPPacket bufferedReceive() throws IOException, InterruptedIOException, SocketException, TFTPPacketException {
        receiveDatagram.setData(receiveBuffer);
        receiveDatagram.setLength(receiveBuffer.length);
        checkOpen().receive(receiveDatagram);

        final TFTPPacket newTFTPPacket = TFTPPacket.newTFTPPacket(receiveDatagram);
        trace("<", newTFTPPacket);
        return newTFTPPacket;
    }

    /**
     * This is a special method to perform a more efficient packet send. It should only be used after calling {@link #beginBufferedOps beginBufferedOps()}.
     * beginBufferedOps() initializes a set of buffers used internally that prevent the new allocation of a DatagramPacket and byte array for each send and
     * receive. To use these buffers you must call the bufferedReceive() and bufferedSend() methods instead of send() and receive(). You must also be certain
     * that you don't manipulate the resulting packet in such a way that it interferes with future buffered operations. For example, a TFTPDataPacket received
     * with bufferedReceive() will have a reference to the internal byte buffer. You must finish using this data before calling bufferedReceive() again, or else
     * the data will be overwritten by the call.
     *
     * @param packet The TFTP packet to send.
     * @throws IOException If some I/O error occurs.
     */
    public final void bufferedSend(final TFTPPacket packet) throws IOException {
        trace(">", packet);
        checkOpen().send(packet.newDatagram(sendDatagram, sendBuffer));
    }

    /**
     * This method synchronizes a connection by discarding all packets that may be in the local socket buffer. This method need only be called when you
     * implement your own TFTP client or server.
     *
     * @throws IOException if an I/O error occurs.
     */
    public final void discardPackets() throws IOException {
        final DatagramPacket datagram = new DatagramPacket(new byte[packetSize], packetSize);
        final Duration to = getSoTimeoutDuration();
        setSoTimeout(Duration.ofMillis(1));
        try {
            while (true) {
                checkOpen().receive(datagram);
            }
        } catch (final SocketException | InterruptedIOException e) {
            // Do nothing. We timed out, so we hope we're caught up.
        }
        setSoTimeout(to);
    }

    /**
     * Releases the resources used to perform buffered sends and receives.
     */
    public final void endBufferedOps() {
        receiveBuffer = null;
        receiveDatagram = null;
        sendBuffer = null;
        sendDatagram = null;
        buffersInitialized = false;
    }

    /**
     * Receives a TFTPPacket.
     *
     * @return The TFTPPacket received.
     * @throws InterruptedIOException If a socket timeout occurs. The Java documentation claims an InterruptedIOException is thrown on a DatagramSocket timeout,
     *                                but in practice we find a SocketException is thrown. You should catch both to be safe.
     * @throws SocketException        If a socket timeout occurs. The Java documentation claims an InterruptedIOException is thrown on a DatagramSocket timeout,
     *                                but in practice we find a SocketException is thrown. You should catch both to be safe.
     * @throws IOException            If some other I/O error occurs.
     * @throws TFTPPacketException    If an invalid TFTP packet is received.
     */
    public final TFTPPacket receive() throws IOException, InterruptedIOException, SocketException, TFTPPacketException {
        final DatagramPacket packet;

        packet = new DatagramPacket(new byte[packetSize], packetSize);

        checkOpen().receive(packet);

        final TFTPPacket newTFTPPacket = TFTPPacket.newTFTPPacket(packet);
        trace("<", newTFTPPacket);
        return newTFTPPacket;
    }

    /**
     * Sends a TFTP packet to its destination.
     *
     * @param packet The TFTP packet to send.
     * @throws IOException If some I/O error occurs.
     */
    public final void send(final TFTPPacket packet) throws IOException {
        trace(">", packet);
        checkOpen().send(packet.newDatagram());
    }

    /**
     * Trace facility; this implementation does nothing.
     * <p>
     * Override it to trace the data, for example:<br>
     * {@code System.out.println(direction + " " + packet.toString());}
     *
     * @param direction {@code >} or {@code <}
     * @param packet    the packet to be sent or that has been received respectively
     * @since 3.6
     */
    protected void trace(final String direction, final TFTPPacket packet) {
    }

    /**
     * Sets the size of the buffers used to receive and send packets.
     * This method can be used to increase the size of the buffer to support `blksize`.
     * For which valid values range between "8" and "65464" octets (RFC-2348).
     * This only refers to the number of data octets, it does not include the four octets of TFTP header.
     *
     * @param packetSize The size of the data octets not including 4 octets for the header.
     * @since 3.12.0
     */
    public final void resetBuffersToSize(final int packetSize) {
        // the packet size should be between 8 - 65464 (inclusively) then we add 4 for the header
        this.packetSize = Math.min(Math.max(packetSize, 8), 65464) + HEADER_SIZE;
        // if the buffers are already initialized reinitialize
        if (buffersInitialized) {
            endBufferedOps();
            beginBufferedOps();
        }
    }

    /**
     * Gets the buffer size of the buffered used by {@link #bufferedSend(TFTPPacket)} and {@link #bufferedReceive}.
     *
     * @return current buffer size
     * @since 3.12.0
     */
    public int getPacketSize() {
        return packetSize;
    }
}
