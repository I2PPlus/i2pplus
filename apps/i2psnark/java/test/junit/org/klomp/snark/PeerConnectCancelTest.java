package org.klomp.snark;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Properties;

import org.junit.BeforeClass;
import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.data.Destination;
import net.i2p.data.Hash;

/**
 * Tests that a peer dropped while its connection is still being made does not
 * keep the socket that connection opens.
 *
 * <p>The defect: {@code disconnect()} latches a flag, then reads the socket field
 * and closes what it finds. The connect thread opens that socket seconds later -
 * looking the peer's destination up can block for ten - so a drop in that window
 * finds no socket, closes nothing, and the connect thread then publishes one
 * anyway, completes the handshake and reports the peer connected to a coordinator
 * that has already forgotten it. The socket stays open until its own read timeout
 * fires. Nothing in the connect path consulted the flag at all.
 *
 * <p>No network: the util is a mock whose connect() drops the peer on the way
 * back, which is the interleaving the defect is about.
 *
 * @since 0.9.72+
 */
public class PeerConnectCancelTest {

    /** The info hash of the torrent this peer is being connected for. */
    private static final byte[] IH = new byte[20];

    /** Our own peer id, sent in the handshake. */
    private static final byte[] MY_ID = new byte[20];

    private static I2PAppContext _ctx;

    /**
     * One context for the whole group, real enough to hand out a log.
     *
     * @since 0.9.72+
     */
    @BeforeClass
    public static void globalSetUp() {
        _ctx = new I2PAppContext(new Properties());
    }

    /**
     * A fixture group for the connect-time cancel path; it holds no state of its
     * own.
     *
     * @since 0.9.72+
     */
    public PeerConnectCancelTest() {}

    /**
     * The regression: the peer is dropped while connect() is in flight, so the
     * drop could not see the socket. The connect thread has to close the socket
     * it is handed and give up, rather than publishing it and carrying on.
     *
     * @throws Exception if the connect or the socket close is refused
     */
    @Test
    public void testADropDuringConnectClosesTheSocket() throws Exception {
        I2PSnarkUtil util = newUtil();
        PeerListener listener = mock(PeerListener.class);
        Peer peer = newPeer();
        I2PSocket sock = newSocket();
        // the drop lands after connect() has opened the socket but before the
        // connect thread gets it back
        when(util.connect(any(PeerID.class), any(byte[].class))).thenAnswer(inv -> {
            peer.disconnect();
            return sock;
        });
        peer.runConnection(util, listener, mock(BandwidthListener.class), null, null, false);
        verify(sock).close();
        verify(listener, never()).connected(peer);
    }

    /**
     * A peer dropped before the connect starts must not open a socket at all -
     * the destination lookup above the connect can itself block for seconds.
     *
     * @throws Exception if the connect is refused
     */
    @Test
    public void testADropBeforeConnectOpensNoSocket() throws Exception {
        I2PSnarkUtil util = newUtil();
        PeerListener listener = mock(PeerListener.class);
        Peer peer = newPeer();
        peer.disconnect();
        peer.runConnection(util, listener, mock(BandwidthListener.class), null, null, false);
        verify(util, never()).connect(any(PeerID.class), any(byte[].class));
        verify(listener, never()).connected(peer);
    }

    /**
     * The socket field is read by whichever thread drops the peer and written by
     * the connect thread, so it has to be volatile - without that, the drop can
     * read a stale null and close nothing even after the socket is published.
     *
     * @throws Exception if the field is gone or reflection is not allowed to
     *                   open it
     */
    @Test
    public void testTheSocketFieldIsPublishedAcrossThreads() throws Exception {
        Field f = Peer.class.getDeclaredField("sock");
        assertTrue("the socket field is not visible to the thread that closes it",
                   Modifier.isVolatile(f.getModifiers()));
    }

    /**
     * A util over the shared context, with no DHT and no session.
     *
     * @return the util a connect is driven through
     */
    private static I2PSnarkUtil newUtil() {
        I2PSnarkUtil util = mock(I2PSnarkUtil.class);
        when(util.getContext()).thenReturn(_ctx);
        return util;
    }

    /**
     * A peer whose address resolves, so the connect path reaches the socket.
     *
     * @return an outgoing peer, not yet started
     */
    private static Peer newPeer() {
        PeerID pid = mock(PeerID.class);
        Destination dest = mock(Destination.class);
        when(pid.getAddress()).thenReturn(dest);
        when(dest.calculateHash()).thenReturn(new Hash());
        return new Peer(pid, MY_ID, IH, null);
    }

    /**
     * A socket that reports itself open, so the connect path treats it as a
     * connection that would have been used.
     *
     * @return the socket the mock util hands back
     */
    private static I2PSocket newSocket() {
        I2PSocket sock = mock(I2PSocket.class);
        when(sock.isClosed()).thenReturn(false);
        return sock;
    }
}
