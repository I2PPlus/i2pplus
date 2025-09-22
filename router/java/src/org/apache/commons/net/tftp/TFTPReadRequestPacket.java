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

import java.net.DatagramPacket;
import java.net.InetAddress;

/**
 * A class derived from TFTPRequestPacket defining a TFTP read request packet type.
 * <p>
 * Details regarding the TFTP protocol and the format of TFTP packets can be found in RFC 783. But the point of these classes is to keep you from having to
 * worry about the internals. Additionally, only very few people should have to care about any of the TFTPPacket classes or derived classes. Almost all users
 * should only be concerned with the {@link org.apache.commons.net.tftp.TFTPClient} class {@link org.apache.commons.net.tftp.TFTPClient#receiveFile
 * receiveFile()} and {@link org.apache.commons.net.tftp.TFTPClient#sendFile sendFile()} methods.
 * </p>
 *
 * @see TFTPPacket
 * @see TFTPRequestPacket
 * @see TFTPPacketException
 * @see TFTP
 */

public final class TFTPReadRequestPacket extends TFTPRequestPacket {

    /**
     * Creates a read request packet of based on a received datagram and assumes the datagram has already been identified as a read request. Assumes the
     * datagram is at least length 4, else an ArrayIndexOutOfBoundsException may be thrown.
     *
     * @param datagram The datagram containing the received request.
     * @throws TFTPPacketException If the datagram isn't a valid TFTP request packet.
     */
    TFTPReadRequestPacket(final DatagramPacket datagram) throws TFTPPacketException {
        super(READ_REQUEST, datagram);
    }

    /**
     * Creates a read request packet to be sent to a host at a given port with a file name and transfer mode request.
     *
     * @param destination The host to which the packet is going to be sent.
     * @param port        The port to which the packet is going to be sent.
     * @param fileName    The requested file name.
     * @param mode        The requested transfer mode. This should be on of the TFTP class MODE constants (e.g., TFTP.NETASCII_MODE).
     */
    public TFTPReadRequestPacket(final InetAddress destination, final int port, final String fileName, final int mode) {
        super(destination, port, READ_REQUEST, fileName, mode);
    }

    /**
     * For debugging
     *
     * @since 3.6
     */
    @Override
    public String toString() {
        return super.toString() + " RRQ " + getFilename() + " " + TFTP.getModeName(getMode());
    }
}
