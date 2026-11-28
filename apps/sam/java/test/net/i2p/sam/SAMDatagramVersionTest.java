package net.i2p.sam;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.Set;
import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.I2PSessionListener;
import net.i2p.client.I2PSessionMuxedListener;
import net.i2p.client.SendMessageOptions;
import net.i2p.client.SendMessageStatusListener;
import net.i2p.client.TunnelStatusListener;
import net.i2p.client.impl.I2PSessionImpl;
import net.i2p.crypto.SigType;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import org.junit.Test;

/**
 * The datagram version a SAM DATAGRAM session is built with decides both ends
 * of the wire: the format it wraps outgoing payloads in and the I2CP protocol it
 * sends them on, and which of those formats it accepts coming back. A DG2 or
 * DG3 session fed a DG1 datagram, or the reverse, drops it rather than
 * mis-parsing it.
 *
 * <p>Each case sends a payload out of a session double and feeds the datagram
 * that came out back into that same session, so the maker and the dissector are
 * pinned against each other, not against a fixture.
 *
 * @since 0.9.72+
 */
public class SAMDatagramVersionTest {

    /** Payload carried through every case, short enough for any datagram. */
    private static final byte[] PAYLOAD = DataHelper.getASCII("datagram version test payload");

    /**
     * Test case carrying the version-selection cases. None needs a running
     * router - the session double never connects.
     */
    public SAMDatagramVersionTest() {
    }

    /**
     * A v1 session sends a DG1 datagram on PROTO_DATAGRAM, a v2 session a DG2
     * datagram on PROTO_DATAGRAM2, and a v3 session a DG3 datagram on
     * PROTO_DATAGRAM3. The protocol the caller passes is ignored by all three.
     *
     * @throws I2PSessionException if the generated keys cannot be read back
     * @throws DataFormatException if the generated destination cannot be parsed back
     *         out of base64 by the send
     */
    @Test
    public void versionSelectsTheDatagramProtocol()
            throws I2PSessionException, DataFormatException {
        int[] protos = {I2PSession.PROTO_DATAGRAM, I2PSession.PROTO_DATAGRAM2,
                        I2PSession.PROTO_DATAGRAM3};
        for (int v = 1; v <= 3; v++) {
            RecordingSession sess = newSession();
            SAMDatagramSession dgs = newDatagramSession(v, sess, new RecordingReceiver());
            assertTrue("a v" + v + " session reported the send as failed",
                       dgs.sendBytes(sess.getMyDestination().toBase64(), PAYLOAD, 99, 1, 2));
            assertEquals("a v" + v + " session sent on the wrong protocol", protos[v - 1],
                         sess._proto);
            assertNotNull("a v" + v + " session sent no datagram", sess._dgram);
        }
    }

    /**
     * A version other than 1, 2 or 3 is refused at construction, so a session
     * can never end up with neither a maker nor a dissector.
     *
     * @throws I2PSessionException if the generated keys cannot be read back
     */
    @Test
    public void badVersionIsRefused() throws I2PSessionException {
        for (int v : new int[] {0, 4, -1}) {
            RecordingSession sess = newSession();
            try {
                newDatagramSession(v, sess, new RecordingReceiver());
                fail("version " + v + " built a datagram session");
            } catch (IllegalArgumentException e) {
                // expected
            }
        }
    }

    /**
     * The datagram each version sends is the one that version reads back, and
     * the sender it reports is the one that version carries: a destination for
     * DG1 and DG2, which are repliable, a hash only for DG3, which is not.
     *
     * @throws I2PSessionException if the generated keys cannot be read back
     * @throws DataFormatException if the generated destination cannot be parsed back
     *         out of base64 by the send
     */
    @Test
    public void datagramComesBackWithTheSenderItCarries()
            throws I2PSessionException, DataFormatException {
        RecordingSession sess = newSession();
        Hash ours = sess.getMyDestination().calculateHash();
        for (int v = 1; v <= 3; v++) {
            RecordingReceiver recv = new RecordingReceiver();
            SAMDatagramSession dgs = newDatagramSession(v, sess, recv);
            dgs.sendBytes(sess.getMyDestination().toBase64(), PAYLOAD, 0, 1, 2);
            dgs.messageReceived(sess._dgram, sess._proto, 1, 2);
            if (v == 3) {
                assertNotNull("a v3 session did not deliver its own datagram", recv._hash);
                assertEquals("a v3 session delivered the wrong sender hash", ours, recv._hash);
                assertNull("a v3 session delivered a destination for a DG3 datagram", recv._dest);
            } else {
                assertNotNull("a v" + v + " session did not deliver its own datagram", recv._dest);
                assertEquals("a v" + v + " session delivered the wrong sender",
                             sess.getMyDestination(), recv._dest);
                assertNull("a v" + v + " session used the hash form of the receiver", recv._hash);
            }
            assertArrayEquals("the payload did not survive version " + v, PAYLOAD, recv._payload);
        }
    }

    /**
     * A datagram arriving on a protocol the session was not built for is
     * dropped, not parsed as the version it was built for - the mismatch that
     * made a DG2 or DG3 subsession answer DG1 traffic.
     *
     * @throws I2PSessionException if the generated keys cannot be read back
     * @throws DataFormatException if the generated destination cannot be parsed back
     *         out of base64 by the send
     */
    @Test
    public void mismatchedProtocolIsDropped() throws I2PSessionException, DataFormatException {
        RecordingSession sess = newSession();
        for (int v = 1; v <= 3; v++) {
            RecordingReceiver recv = new RecordingReceiver();
            SAMDatagramSession dgs = newDatagramSession(v, sess, recv);
            dgs.sendBytes(sess.getMyDestination().toBase64(), PAYLOAD, 0, 1, 2);
            int wrong = sess._proto == I2PSession.PROTO_DATAGRAM ? I2PSession.PROTO_DATAGRAM3 :
                        I2PSession.PROTO_DATAGRAM;
            dgs.messageReceived(sess._dgram, wrong, 1, 2);
            assertFalse("a v" + v + " session accepted a datagram on another protocol",
                        recv._delivered);
        }
    }

    /**
     * A session double with a real destination and keys, read out of a freshly
     * generated key file, that records the one send it is asked for instead of
     * building tunnels.
     *
     * @return the session, never connected and never closed
     * @throws I2PSessionException if the generated keys cannot be read back
     */
    private static RecordingSession newSession() throws I2PSessionException {
        ByteArrayOutputStream priv = new ByteArrayOutputStream(700);
        SAMUtils.genRandomKey(priv, null, SigType.EdDSA_SHA512_Ed25519);
        return new RecordingSession(new ByteArrayInputStream(priv.toByteArray()));
    }

    /**
     * Builds a datagram session of the given version over the given session.
     * The listen protocol is left ANY, since the test hands each datagram to
     * {@code messageReceived()} itself rather than going through a listener.
     *
     * @param version datagram version 1/2/3
     * @param sess the session the datagram session runs over
     * @param recv the receiver handed each delivered datagram
     * @return the datagram session, never null
     * @throws I2PSessionException if the session cannot be wired up
     */
    private static SAMDatagramSession newDatagramSession(int version, RecordingSession sess,
                                                         SAMDatagramReceiver recv)
            throws I2PSessionException {
        return new SAMDatagramSession(sess, new Properties(), I2PSession.PROTO_ANY, 0, recv,
                                      version);
    }

    /**
     * Session double: real keys, read by {@link I2PSessionImpl} out of a key
     * file, with no tunnels, keeping the protocol and datagram of the one send
     * it is asked for for the assertions.
     */
    private static class RecordingSession extends I2PSessionImpl {

        /** The I2CP protocol of the recorded send, -1 until one happens. */
        int _proto = -1;
        /** The datagram of the recorded send, null until one happens. */
        byte[] _dgram;

        /**
         * Wraps a generated key file as a session that never connects.
         *
         * @param keys the binary destination and private keys
         * @throws I2PSessionException if the keys cannot be read
         */
        RecordingSession(ByteArrayInputStream keys) throws I2PSessionException {
            super(I2PAppContext.getGlobalContext(), keys, new Properties());
        }

        @Override
        public boolean sendMessage(Destination to, byte[] dgram, int proto,
                                   int fromPort, int toPort) {
            record(dgram, proto);
            return true;
        }

        @Override
        public boolean sendMessage(Destination to, byte[] dgram, int off, int len, int proto,
                                   int fromPort, int toPort, SendMessageOptions opts) {
            byte[] copy = new byte[len];
            System.arraycopy(dgram, off, copy, 0, len);
            record(copy, proto);
            return true;
        }

        /** Not a form a datagram session uses. */
        @Override
        public boolean sendMessage(Destination to, byte[] dgram) { return false; }

        /** Not a form a datagram session uses. */
        @Override
        public boolean sendMessage(Destination to, byte[] dgram, int proto, int fromPort) {
            return false;
        }

        /** Not a form a datagram session uses. */
        @Override
        public boolean sendMessage(Destination to, byte[] dgram, SessionKey key,
                                   Set<SessionTag> tags) { return false; }

        /** Not a form a datagram session uses. */
        @Override
        public boolean sendMessage(Destination to, byte[] dgram, int proto, int fromPort,
                                   SessionKey key, Set<SessionTag> tags) { return false; }

        /** Not a form a datagram session uses. */
        @Override
        public boolean sendMessage(Destination to, byte[] dgram, int proto, int fromPort,
                                   SessionKey key, Set<SessionTag> tags,
                                   int a, int b, int c) { return false; }

        /** Not a form a datagram session uses. */
        @Override
        public boolean sendMessage(Destination to, byte[] dgram, int proto, int fromPort,
                                   SessionKey key, Set<SessionTag> tags, long date) {
            return false;
        }

        /** Not a form a datagram session uses. */
        @Override
        public boolean sendMessage(Destination to, byte[] dgram, int proto, int fromPort,
                                   SessionKey key, Set<SessionTag> tags, long date,
                                   int a, int b, int c) {
            return false;
        }

        /** Not a form a datagram session uses. */
        @Override
        public boolean sendMessage(Destination to, byte[] dgram, int proto, int fromPort,
                                   SessionKey key, Set<SessionTag> tags, long date,
                                   int a, int b, int c, int d) {
            return false;
        }

        /** Not a form a datagram session uses. */
        @Override
        public long sendMessage(Destination to, byte[] dgram, int off, int len, int proto,
                                int fromPort, int toPort, SendMessageOptions opts,
                                SendMessageStatusListener listener) {
            return -1;
        }

        /**
         * Keeps the one datagram and protocol this session is asked to send.
         *
         * @param dgram the datagram handed to I2CP
         * @param proto the I2CP protocol handed to I2CP
         */
        private void record(byte[] dgram, int proto) {
            _dgram = dgram;
            _proto = proto;
        }

        /** Nothing to register - the test calls messageReceived() itself. */
        @Override
        public void addMuxedSessionListener(I2PSessionMuxedListener l, int proto,
                                            int port) { /* no-op */ }

        /** Nothing to register. */
        @Override
        public void addSessionListener(I2PSessionListener l, int proto, int port) { /* no-op */ }

        /** Nothing to unregister. */
        @Override
        public void removeListener(int proto, int port) { /* no-op */ }

        /** No tunnel status to record. */
        @Override
        public void receiveStatus(int status, long expiration, int size) { /* no-op */ }

        /** No tunnels to rebuild. */
        @Override
        public void rebuildTunnels() { /* no-op */ }

        /** No tunnels to add a listener for. */
        @Override
        public void addTunnelStatusListener(TunnelStatusListener l) { /* no-op */ }

        /** No tunnels to remove a listener for. */
        @Override
        public void removeTunnelStatusListener(TunnelStatusListener l) { /* no-op */ }

        /** No tunnels to switch. */
        @Override
        public boolean switchToNewTunnel() { return false; }

        /** No tunnels - the session never connects. */
        @Override
        public TunnelPair getCurrentTunnelPair() { return null; }

        /** No tunnels to record. */
        @Override
        public void setCurrentTunnelPair(TunnelPair pair) { /* no-op */ }
    }

    /**
     * Receiver double recording which of the two forms the datagram was
     * delivered in, and what came with it.
     */
    private static class RecordingReceiver implements SAMDatagramReceiver {

        /** True once either form has delivered a datagram. */
        boolean _delivered;
        /** Sender of the delivered datagram, set for DG1 and DG2 only. */
        Destination _dest;
        /** Sender of the delivered datagram, set for DG3 only. */
        Hash _hash;
        /** Payload of the delivered datagram. */
        byte[] _payload;

        @Override
        public void receiveDatagramBytes(Destination sender, byte[] data, int proto,
                                         int fromPort, int toPort) throws IOException {
            _delivered = true;
            _dest = sender;
            _payload = data;
        }

        @Override
        public void receiveDatagramBytes(Hash sender, byte[] data, int proto,
                                         int fromPort, int toPort) throws IOException {
            _delivered = true;
            _hash = sender;
            _payload = data;
        }

        /** Nothing to stop - the test drives the session directly. */
        @Override
        public void stopDatagramReceiving() { /* no-op */ }
    }
}
