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
    /** @return remote host ID */
    RemoteHostId getRemoteHostId();
    /** @return true for IPv6 */
    boolean isIPv6();
    /** @return remote IP address */
    InetAddress getRemoteIPAddress();
    /** @return remote port */
    int getRemotePort();
    /** @return MTU */
    int getMTU();
    /** @return next packet number */
    long getNextPacketNumber() throws IOException;
    /** @return send connection ID */
    long getSendConnID();
    /** @return send cipher */
    CipherState getSendCipher();
    /** @return send header encrypt key 1 */
    byte[] getSendHeaderEncryptKey1();
    /** @return send header encrypt key 2 */
    byte[] getSendHeaderEncryptKey2();
    /** @param reason destroy reason */
    void setDestroyReason(int reason);
    /** @return received messages bitfield */
    SSU2Bitfield getReceivedMessages();
    /** @return acked messages bitfield */
    SSU2Bitfield getAckedMessages();
    /** @param pktNum packet number, @param length fragment length, @param fragments fragment list */
    void fragmentsSent(long pktNum, int length, List<PacketBuilder.Fragment> fragments);
    /** @return flags byte */
    byte getFlags();
}
