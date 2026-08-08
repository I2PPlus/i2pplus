package net.i2p.router.transport.udp;

import com.southernstorm.noise.protocol.CipherState;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;

/**
 * Basic interface over top of PeerState2 and PeerStateDestroyed,
 * so we can pass them both to PacketBuilder2 to send packets.
 *
 * @since 0.9.57
 */
interface SSU2Sender {
    /**
     *  Remote host ID.
     *
     *  @return remote host ID
     */
    RemoteHostId getRemoteHostId();
    /**
     *  Whether the remote is IPv6.
     *
     *  @return true for IPv6
     */
    boolean isIPv6();
    /**
     *  Remote IP address.
     *
     *  @return remote IP address
     */
    InetAddress getRemoteIPAddress();
    /**
     *  Remote port.
     *
     *  @return remote port
     */
    int getRemotePort();
    /**
     *  Maximum transmission unit.
     *
     *  @return MTU
     */
    int getMTU();
    /**
     *  Next packet number to use.
     *
     *  @return next packet number
     */
    long getNextPacketNumber() throws IOException;
    /**
     *  Connection ID to use when sending.
     *
     *  @return send connection ID
     */
    long getSendConnID();
    /**
     *  Cipher to use when sending.
     *
     *  @return send cipher
     */
    CipherState getSendCipher();
    /**
     *  Header encryption key 1.
     *
     *  @return send header encrypt key 1
     */
    byte[] getSendHeaderEncryptKey1();
    /**
     *  Header encryption key 2.
     *
     *  @return send header encrypt key 2
     */
    byte[] getSendHeaderEncryptKey2();
    /**
     *  Reason the connection was destroyed.
     *
     *  @param reason destroy reason
     */
    void setDestroyReason(int reason);
    /**
     *  Bitfield of received messages.
     *
     *  @return received messages bitfield
     */
    SSU2Bitfield getReceivedMessages();
    /**
     *  Bitfield of acked messages.
     *
     *  @return acked messages bitfield
     */
    SSU2Bitfield getAckedMessages();
    /**
     *  Records that a set of fragments was sent.
     *
     *  @param pktNum packet number
     *  @param length fragment length
     *  @param fragments fragment list
     */
    void fragmentsSent(long pktNum, int length, List<PacketBuilder.Fragment> fragments);
    /**
     *  Flags byte.
     *
     *  @return flags byte
     */
    byte getFlags();
}
