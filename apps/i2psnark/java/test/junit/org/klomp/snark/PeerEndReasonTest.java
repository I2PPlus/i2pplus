package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * Pins what the one line a peer's removal says about why the connection ended. The
 * cause is only visible at the site that caught it - the coordinator takes the peer out
 * of its lists long after the dial failed, the reader stopped, or the torrent was
 * stopped - so the peer carries the reason across that gap, keeping the cause it was
 * given first rather than the generic label the cleanup cascade reaches it with.
 *
 * @since 0.9.72+
 */
public class PeerEndReasonTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     */
    public PeerEndReasonTest() {}

    /**
     * A two-piece torrent's metadata, enough to build a peer the way the coordinators do.
     *
     * @return metadata for a tiny torrent
     * @throws Throwable on a bencode error in the fixture
     */
    private static MetaInfo metainfo() throws Throwable {
        byte[] hashes = new byte[2 * 20];
        StringBuilder sb = new StringBuilder(256);
        sb.append("d4:infod6:lengthi2048e4:name9:test.file12:piece lengthi1024e6:pieces");
        sb.append(hashes.length).append(':');
        byte[] head = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] tail = "ee".getBytes(StandardCharsets.ISO_8859_1);
        byte[] raw = new byte[head.length + hashes.length + tail.length];
        System.arraycopy(head, 0, raw, 0, head.length);
        System.arraycopy(hashes, 0, raw, head.length, hashes.length);
        System.arraycopy(tail, 0, raw, head.length + hashes.length, tail.length);
        return new MetaInfo(new ByteArrayInputStream(raw));
    }

    /**
     * A peer with no socket and no connection yet - the state a dial is in before it
     * opens one.
     *
     * @return the peer to record an end on
     * @throws Throwable on a bencode error in the fixture
     */
    private static Peer peer() throws Throwable {
        MetaInfo mi = metainfo();
        return new Peer(new PeerID(new byte[32], (I2PSnarkUtil) null), new byte[20],
                        mi.getInfoHash(), mi);
    }

    /**
     * Marks a peer as having connected, the way {@code Peer.run()} does once the
     * handshake is done, without running a connection.
     *
     * @param peer the peer to mark
     * @throws Exception if the field cannot be reached
     */
    private static void markConnected(Peer peer) throws Exception {
        Field f = Peer.class.getDeclaredField("connected");
        f.setAccessible(true);
        f.setLong(peer, System.currentTimeMillis());
    }

    /**
     * The cause recorded first is the reason shown: the cleanup that follows an ended
     * connection must not overwrite it with a generic label.
     *
     * @throws Throwable on a bencode error in the fixture
     */
    @Test
    public void testTheFirstReasonRecordedWins() throws Throwable {
        Peer peer = peer();
        peer.setEndReason("Inactive too long");
        peer.setEndReason("Torrent stopped");
        peer.setEndReason(null);
        assertEquals("Inactive too long", peer.endReason());
    }

    /**
     * Where nothing records a cause, the line says what the peer's own state shows - a
     * peer that never finished its handshake was dropped while dialing, one that did was
     * closed by the reader or by us.
     *
     * @throws Throwable on a bencode error in the fixture
     * @throws Exception if the field cannot be reached
     */
    @Test
    public void testAnUnrecordedEndSaysWhatThePeerStateShows() throws Throwable {
        Peer dial = peer();
        assertEquals("The dial never connected", dial.endReason());
        markConnected(dial);
        assertEquals("The connection closed", dial.endReason());
    }

    /**
     * A reason taken from an exception message is flattened and cut to fit one log line,
     * so the peer named beside it stays on the same row; a missing reason is named in
     * prose.
     */
    @Test
    public void testTheReasonFitsOneLine() {
        assertEquals("none", SnarkLogFormatter.reason(null));
        assertEquals("Read timed out", SnarkLogFormatter.reason("Read timed out"));
        StringBuilder longMessage = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            longMessage.append("streaming window\nfull while ");
        }
        String shown = SnarkLogFormatter.reason(longMessage.toString());
        assertEquals(SnarkLogFormatter.REASON_CHARS, shown.length());
        assertTrue(shown, shown.endsWith("..."));
        assertFalse(shown, shown.contains("\n"));
    }
}
