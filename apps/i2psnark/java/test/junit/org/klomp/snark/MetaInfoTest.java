package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;

import net.i2p.crypto.SHA1;

import org.junit.Test;
import org.klomp.snark.bencode.BDecoder;
import org.klomp.snark.bencode.BEValue;

/**
 *  Tests for MetaInfo, the torrent-metadata parser: field extraction,
 *  piece-length computation, piece-hash verification, and a full
 *  bencode round-trip preserving the info hash.
 *
 *  @since 0.1.0
 */
public class MetaInfoTest {

    private static final int PIECE_LENGTH = 16384;
    private static final long TOTAL_LENGTH = 30000L;

    /** SHA1 of the piece data used as the stored piece hash */
    private static byte[] hashOf(byte[] data) {
        MessageDigest md = SHA1.getInstance();
        return md.digest(data);
    }

    private static byte[] pieceData() {
        byte[] data = new byte[PIECE_LENGTH];
        Arrays.fill(data, (byte) 0x42);
        return data;
    }

    /** build a bencoded single-file torrent byte stream */
    private static byte[] buildTorrentBytes(byte[] pieceHashes) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('d');
        sb.append("8:announce");
        sb.append("19:http://tracker.test");
        sb.append("4:info");
        sb.append('d');
        sb.append("6:length").append('i').append(TOTAL_LENGTH).append('e');
        sb.append("4:name").append("9:test.file");
        sb.append("12:piece length").append('i').append(PIECE_LENGTH).append('e');
        sb.append("6:pieces").append(pieceHashes.length).append(':');
        byte[] head = sb.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] tail = "ee".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] rv = new byte[head.length + pieceHashes.length + tail.length];
        System.arraycopy(head, 0, rv, 0, head.length);
        System.arraycopy(pieceHashes, 0, rv, head.length, pieceHashes.length);
        System.arraycopy(tail, 0, rv, head.length + pieceHashes.length, tail.length);
        return rv;
    }

    @Test
    public void testParseSingleFileFields() throws Exception {
        byte[] hashes = new byte[40]; // two pieces
        MetaInfo mi = new MetaInfo(new ByteArrayInputStream(buildTorrentBytes(hashes)));
        assertEquals("http://tracker.test", mi.getAnnounce());
        assertEquals("test.file", mi.getName());
        assertEquals(TOTAL_LENGTH, mi.getTotalLength());
        assertEquals(PIECE_LENGTH, mi.getPieceLength(0));
        assertEquals(2, mi.getPieces());
        assertNull(mi.getFiles());
        assertNull(mi.getLengths());
        assertFalse(mi.isPrivate());
    }

    @Test
    public void testLastPieceLength() throws Exception {
        byte[] hashes = new byte[40];
        MetaInfo mi = new MetaInfo(new ByteArrayInputStream(buildTorrentBytes(hashes)));
        // last piece is shorter than the full piece length
        assertEquals(TOTAL_LENGTH - PIECE_LENGTH, mi.getPieceLength(1));
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void testPieceLengthOutOfRange() throws Exception {
        byte[] hashes = new byte[40];
        MetaInfo mi = new MetaInfo(new ByteArrayInputStream(buildTorrentBytes(hashes)));
        mi.getPieceLength(2);
    }

    @Test
    public void testCheckPieceGoodAndBad() throws Exception {
        byte[] good = pieceData();
        byte[] hash0 = hashOf(good);
        byte[] hashes = new byte[40];
        System.arraycopy(hash0, 0, hashes, 0, 20);
        MetaInfo mi = new MetaInfo(new ByteArrayInputStream(buildTorrentBytes(hashes)));
        assertTrue(mi.checkPiece(0, good, 0, good.length));
        byte[] bad = Arrays.copyOf(good, good.length);
        bad[0] ^= 0x01;
        assertFalse(mi.checkPiece(0, bad, 0, bad.length));
    }

    @Test
    public void testMapConstructor() throws Exception {
        byte[] hashes = new byte[40];
        BDecoder bd = new BDecoder(new ByteArrayInputStream(buildTorrentBytes(hashes)));
        Map<String, BEValue> m = bd.bdecodeMap().getMap();
        MetaInfo mi = new MetaInfo(m);
        assertEquals("test.file", mi.getName());
        assertEquals(2, mi.getPieces());
        assertArrayEquals(bd.get_special_map_digest(), mi.getInfoHash());
    }

    @Test
    public void testInfoHashStableAcrossRoundTrip() throws Exception {
        byte[] hashes = new byte[40];
        MetaInfo mi = new MetaInfo(new ByteArrayInputStream(buildTorrentBytes(hashes)));
        byte[] original = mi.getInfoHash();

        byte[] torrent = mi.getTorrentData();
        MetaInfo reparsed = new MetaInfo(new ByteArrayInputStream(torrent));
        assertArrayEquals(original, reparsed.getInfoHash());
        assertEquals("test.file", reparsed.getName());
    }

    @Test
    public void testMissingInfoDictThrows() {
        // dictionary with no info key
        byte[] bad = "d8:announce19:http://tracker.teste".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        try {
            new MetaInfo(new ByteArrayInputStream(bad));
            fail("Expected InvalidBEncodingException");
        } catch (java.io.IOException expected) {
        }
    }

    @Test
    public void testMissingNameThrows() {
        // info dict without a name
        byte[] bad = ("d4:infod6:lengthi30000e12:piece lengthi16384e6:pieces40:" +
                      "0000000000000000000000000000000000000000ee")
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        try {
            new MetaInfo(new ByteArrayInputStream(bad));
            fail("Expected InvalidBEncodingException");
        } catch (java.io.IOException expected) {
        }
    }
}
