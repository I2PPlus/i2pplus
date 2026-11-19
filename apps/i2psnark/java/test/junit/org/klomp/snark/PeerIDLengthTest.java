package org.klomp.snark;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.i2p.client.I2PClientFactory;
import net.i2p.data.Destination;

import org.junit.Test;

import org.klomp.snark.bencode.BEValue;
import org.klomp.snark.bencode.InvalidBEncodingException;

/**
 * A legacy tracker entry is checked when it is parsed, not when the peer is
 * dialed.
 *
 * <p>A non-compact announce hands us a peer id of whatever length the tracker
 * sent. The BitTorrent protocol defines it as 20 bytes, and a value of any other
 * length can never match the 20 bytes the peer puts in its handshake - so
 * accepting it did not tolerate the bad entry, it only moved the failure to the
 * far end of an outbound I2P connection. A tracker that answers with a list of
 * them therefore buys one session connect, one handshake and two connection
 * threads per entry, and drops every one.
 *
 * @since 0.9.72+
 */
public class PeerIDLengthTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one; the class holds no state between cases.
     *
     * @since 0.9.72+
     */
    public PeerIDLengthTest() {}

    /** A peer id of the length the protocol defines (20 bytes). */
    private static final byte[] GOOD_ID = filled((byte) 0x2a, 20);

    /**
     * The length the protocol defines is accepted, and the id is kept as sent.
     *
     * @throws Exception if the destination cannot be generated
     */
    @Test
    public void twentyByteIdIsAccepted() throws Exception {
        PeerID id = new PeerID(entry(GOOD_ID, destination()));
        assertEquals("the peer id did not survive the parse", 20, id.getID().length);
    }

    /**
     * A short id is refused at the parse, with the length it was sent as.
     *
     * @throws Exception if the destination cannot be generated
     */
    @Test
    public void nineteenByteIdIsRefused() throws Exception {
        assertRefused(filled((byte) 0x2a, 19), 19);
    }

    /**
     * A long id is refused too - the handshake compares all 20 bytes, so an extra
     * byte is as unusable as a missing one.
     *
     * @throws Exception if the destination cannot be generated
     */
    @Test
    public void twentyOneByteIdIsRefused() throws Exception {
        assertRefused(filled((byte) 0x2a, 21), 21);
    }

    /**
     * An empty id is refused. At HEAD it parsed, and the peer was only dropped
     * once a connection had been opened to it.
     *
     * @throws Exception if the destination cannot be generated
     */
    @Test
    public void emptyIdIsRefused() throws Exception {
        assertRefused(new byte[0], 0);
    }

    /**
     * One malformed entry in a peer list costs that entry and nothing else: the
     * good entry beside it is still handed to the torrent.
     *
     * @throws Exception if the destination cannot be generated
     */
    @Test
    public void oneBadEntryDoesNotSpoilTheGoodOnes() throws Exception {
        String base64 = destination();
        List<BEValue> list = new ArrayList<>(2);
        list.add(new BEValue(entry(filled((byte) 0x2a, 7), base64)));
        list.add(new BEValue(entry(GOOD_ID, base64)));
        Set<Peer> peers = TrackerInfo.getPeers(list, GOOD_ID, filled((byte) 0x11, 20), null, null);
        assertEquals("the good entry was lost with the bad one", 1, peers.size());
    }

    /**
     * A peer id of the given length is refused by the parse.
     *
     * @param id the id the tracker sent
     * @param length its length, expected in the message
     * @throws Exception if the destination cannot be generated
     */
    private static void assertRefused(byte[] id, int length) throws Exception {
        try {
            new PeerID(entry(id, destination()));
            fail("A " + length + "-byte peer id was accepted");
        } catch (InvalidBEncodingException ibe) {
            // expected, and the caller in TrackerInfo already catches this one and
            // moves on to the next entry
        }
    }

    /**
     * One entry of a non-compact tracker peer list.
     *
     * @param id the peer id the tracker sent, of any length
     * @param base64 the peer's destination, base64-encoded
     * @return the map the parser is handed
     */
    private static Map<String, BEValue> entry(byte[] id, String base64) {
        Map<String, BEValue> m = new HashMap<>(4);
        m.put("peer id", new BEValue(id));
        m.put("ip", new BEValue(base64.getBytes(StandardCharsets.UTF_8)));
        return m;
    }

    /**
     * @return a freshly generated destination, base64-encoded as a tracker sends it
     * @throws Exception if the keys cannot be generated
     */
    private static String destination() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        I2PClientFactory.createClient().createDestination(out);
        Destination dest = Destination.create(new ByteArrayInputStream(out.toByteArray()));
        return dest.toBase64();
    }

    /**
     * @param value the byte to fill with
     * @param length how many bytes
     * @return the filled array
     */
    private static byte[] filled(byte value, int length) {
        byte[] data = new byte[length];
        Arrays.fill(data, value);
        return data;
    }
}
