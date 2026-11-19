package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Pins that a frame's length prefix says exactly how much frame follows.
 *
 * <p>The reader takes the prefix, the message id and the id's fixed fields, and then
 * dispatches. It used to compare the prefix only against the minimum that id needs, so
 * a CHOKE carrying 40 bytes was accepted, dispatched, and its 39 surplus bytes were
 * left in the stream - the next pass read them as a length prefix and a message id,
 * and every message after that was out of step with the sender. The ids that carry no
 * payload have one correct length each, which is what the check now uses.
 *
 * @since 0.9.72+
 */
public class PeerFrameLengthTest {

    /** Surplus bytes a peer hangs off a CHOKE, enough to look like a frame. */
    private static final int FILLER = 39;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public PeerFrameLengthTest() {}

    /**
     * A two-piece torrent, enough for a peer to have a state on.
     *
     * @return the parsed metadata
     * @throws Throwable on a bencode error in the fixture
     */
    private static MetaInfo torrent() throws Throwable {
        byte[] torrentBytes = PeerStateTest.buildTorrentBytes(new byte[40]);
        return new MetaInfo(new ByteArrayInputStream(torrentBytes));
    }

    /**
     * One length-prefixed frame: the prefix, the id when the prefix is non-zero, and
     * that many zero bytes of payload.
     *
     * @param length the prefix to write
     * @param type the message id, ignored for a keep-alive
     * @param filler zero bytes written after the id
     * @return the frame as bytes
     * @throws IOException on a stream error in the fixture
     */
    private static byte[] frame(int length, byte type, int filler) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(length);
        if (length > 0) {out.writeByte(type);}
        for (int i = 0; i < filler; i++) {out.writeByte(0);}
        out.flush();
        return baos.toByteArray();
    }

    /**
     * Runs the read loop over the wire bytes and returns what the peer's state was
     * told, in the order it was told.
     *
     * @param wire the bytes the peer sent
     * @return the messages dispatched, in order
     * @throws Throwable on a bencode error in the fixture
     */
    private static List<String> dispatch(byte[] wire) throws Throwable {
        MetaInfo mi = torrent();
        PeerID pid = new PeerID(new byte[32], (I2PSnarkUtil) null);
        Peer peer = new Peer(pid, new byte[20], mi.getInfoHash(), mi);
        Recorder ps = new Recorder(peer, mi);
        peer.state = ps;
        new PeerConnectionIn(peer, new DataInputStream(new ByteArrayInputStream(wire))).run();
        return ps.seen;
    }

    /**
     * The defect: the surplus of an over-long CHOKE was read as the next frame. The
     * 39 filler bytes start with a zero length prefix, which the loop took as a
     * keep-alive, so the peer was credited with a message it never sent and every
     * frame after it was out of step. A frame whose length does not match its id is
     * malformed, so nothing from it is acted on at all.
     *
     * @throws Throwable on a bencode error in the fixture
     */
    @Test
    public void testTheFillerOfAnOverlongChokeIsNotAFrame() throws Throwable {
        List<String> seen = dispatch(frame(1 + FILLER, Message.CHOKE, FILLER));
        assertTrue("an over-long CHOKE was dispatched: " + seen, seen.isEmpty());
    }

    /**
     * The same for an id with fixed fields: HAVE takes exactly four bytes after its
     * id, and a fifth is not part of the message, so nothing is dispatched at all.
     *
     * @throws Throwable on a bencode error in the fixture
     */
    @Test
    public void testAnOverlongHaveIsNotDispatched() throws Throwable {
        List<String> seen = dispatch(frame(6, Message.HAVE, 5));
        assertTrue("a HAVE with a byte to spare was dispatched: " + seen, seen.isEmpty());
    }

    /**
     * A HAVE that stops short of its four bytes is refused as it always was: the
     * piece number would be read out of the next message.
     *
     * @throws Throwable on a bencode error in the fixture
     */
    @Test
    public void testAShortHaveIsNotDispatched() throws Throwable {
        List<String> seen = dispatch(frame(3, Message.HAVE, 0));
        assertTrue("a short HAVE was dispatched: " + seen, seen.isEmpty());
    }

    /**
     * Frames that are what they claim still get through: a CHOKE of exactly one byte
     * and the keep-alive behind it are both dispatched.
     *
     * @throws Throwable on a bencode error in the fixture
     */
    @Test
    public void testWellFormedFramesStillDispatch() throws Throwable {
        byte[] choke = frame(1, Message.CHOKE, 0);
        byte[] keepAlive = frame(0, (byte) 0, 0);
        byte[] wire = new byte[choke.length + keepAlive.length];
        System.arraycopy(choke, 0, wire, 0, choke.length);
        System.arraycopy(keepAlive, 0, wire, choke.length, keepAlive.length);
        List<String> seen = dispatch(wire);
        assertEquals(2, seen.size());
        assertEquals("choke", seen.get(0));
        assertEquals("keepalive", seen.get(1));
    }

    /**
     * An id that does carry payload is still allowed to run long - that is what a
     * bitfield is - so the exact-length rule must not reach it.
     *
     * @throws Throwable on a bencode error in the fixture
     */
    @Test
    public void testAPayloadMessageMayStillRunLong() throws Throwable {
        List<String> seen = dispatch(frame(3, Message.BITFIELD, 2));
        assertEquals(1, seen.size());
        assertEquals("bitfield 2 bytes", "bitfield2", seen.get(0));
    }

    /**
     * A peer state that records what it is told instead of acting on it, so the read
     * loop can be driven without a coordinator, a storage or a socket.
     */
    private static class Recorder extends PeerState {

        /** The messages dispatched, in order, as short names. */
        private final List<String> seen = new ArrayList<>(4);

        /**
         * A state on the given torrent with no coordinator or connections.
         *
         * @param peer the peer this state belongs to
         * @param mi the torrent metadata
         */
        Recorder(Peer peer, MetaInfo mi) {
            super(peer, null, null, mi, null, null);
        }

        /**
         * Records the keep-alive.
         */
        @Override
        void keepAliveMessage() {
            seen.add("keepalive");
        }

        /**
         * Records the choke state change.
         *
         * @param choke true if the peer choked us
         */
        @Override
        void chokeMessage(boolean choke) {
            seen.add(choke ? "choke" : "unchoke");
        }

        /**
         * Records the piece number the peer announced.
         *
         * @param piece the piece the peer now has
         */
        @Override
        void haveMessage(int piece) {
            seen.add("have" + piece);
        }

        /**
         * Records the bitfield by its length, which is what the framing decides.
         *
         * @param bitmap the peer's piece map
         */
        @Override
        void bitfieldMessage(byte[] bitmap) {
            seen.add("bitfield" + bitmap.length);
        }
    }
}
