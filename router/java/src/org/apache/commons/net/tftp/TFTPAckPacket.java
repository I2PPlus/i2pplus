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
 * A final class derived from TFTPPacket defining the TFTP Acknowledgement packet type.
 * <p>
 * Details regarding the TFTP protocol and the format of TFTP packets can be found in RFC 783. But the point of these classes is to keep you from having to
 * worry about the internals. Additionally, only very few people should have to care about any of the TFTPPacket classes or derived classes. Almost all users
 * should only be concerned with the {@link org.apache.commons.net.tftp.TFTPClient} class {@link org.apache.commons.net.tftp.TFTPClient#receiveFile
 * receiveFile()} and {@link org.apache.commons.net.tftp.TFTPClient#sendFile sendFile()} methods.
 * </p>
 *
 * @see TFTPPacket
 * @see TFTPPacketException
 * @see TFTP
 */

public final class TFTPAckPacket extends TFTPPacket {
    /** The block number being acknowledged by the packet. */
    int blockNumber;

    /**
     * Creates an acknowledgement packet based from a received datagram. Assumes the datagram is at least length 4, else an ArrayIndexOutOfBoundsException may
     * be thrown.
     *
     * @param datagram The datagram containing the received acknowledgement.
     * @throws TFTPPacketException If the datagram isn't a valid TFTP acknowledgement packet.
     */
    TFTPAckPacket(final DatagramPacket datagram) throws TFTPPacketException {
        super(ACKNOWLEDGEMENT, datagram.getAddress(), datagram.getPort());
        final byte[] data;

        data = datagram.getData();

        if (getType() != data[1]) {
            throw new TFTPPacketException("TFTP operator code does not match type.");
        }

        this.blockNumber = (data[2] & 0xff) << 8 | data[3] & 0xff;
    }

    /**
     * Creates an acknowledgment packet to be sent to a host at a given port acknowledging receipt of a block.
     *
     * @param destination The host to which the packet is going to be sent.
     * @param port        The port to which the packet is going to be sent.
     * @param blockNumber The block number being acknowledged.
     */
    public TFTPAckPacket(final InetAddress destination, final int port, final int blockNumber) {
        super(ACKNOWLEDGEMENT, destination, port);
        this.blockNumber = blockNumber;
    }

    /**
     * Gets the block number of the acknowledgement.
     *
     * @return The block number of the acknowledgement.
     */
    public int getBlockNumber() {
        return blockNumber;
    }

    /**
     * Creates a UDP datagram containing all the TFTP acknowledgement packet data in the proper format. This is a method exposed to the programmer in case he
     * wants to implement his own TFTP client instead of using the {@link org.apache.commons.net.tftp.TFTPClient} class. Under normal circumstances, you should
     * not have a need to call this method.
     *
     * @return A UDP datagram containing the TFTP acknowledgement packet.
     */
    @Override
    public DatagramPacket newDatagram() {
        final byte[] data;

        data = new byte[4];
        data[0] = 0;
        data[1] = (byte) type;
        data[2] = (byte) ((blockNumber & 0xffff) >> 8);
        data[3] = (byte) (blockNumber & 0xff);

        return new DatagramPacket(data, data.length, address, port);
    }

    /**
     * This is a method only available within the package for implementing efficient datagram transport by eliminating buffering. It takes a datagram as an
     * argument, and a byte buffer in which to store the raw datagram data. Inside the method, the data is set as the datagram's data and the datagram returned.
     *
     * @param datagram The datagram to create.
     * @param data     The buffer to store the packet and to use in the datagram.
     * @return The datagram argument.
     */
    @Override
    DatagramPacket newDatagram(final DatagramPacket datagram, final byte[] data) {
        data[0] = 0;
        data[1] = (byte) type;
        data[2] = (byte) ((blockNumber & 0xffff) >> 8);
        data[3] = (byte) (blockNumber & 0xff);

        datagram.setAddress(address);
        datagram.setPort(port);
        datagram.setData(data);
        datagram.setLength(4);

        return datagram;
    }

    /**
     * Sets the block number of the acknowledgement.
     *
     * @param blockNumber the number to set
     */
    public void setBlockNumber(final int blockNumber) {
        this.blockNumber = blockNumber;
    }

    /**
     * For debugging
     *
     * @since 3.6
     */
    @Override
    public String toString() {
        return super.toString() + " ACK " + blockNumber;
    }
}
