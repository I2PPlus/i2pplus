package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import org.junit.Test;

import net.i2p.data.ByteArray;

/**
 * Pins the wire bytes of every message id, and how many stream writes each one costs. The
 * header used to go out as five separate calls on an unbuffered DataOutputStream, and each
 * writeInt() on that stream is four single-byte writes, so a REQUEST - the most common
 * message by a wide margin, and nothing but a header - cost seventeen writes down the
 * streaming layer, each one a lock acquisition and a copy. The fixed header is now assembled
 * into one array, so a REQUEST is one write and a PIECE is two. The bytes themselves must not
 * move: this is the peer protocol.
 *
 * @since 0.9.72+
 */
public class MessageWireTest {

    /** Piece offset used by the fixture messages, one part in. */
    private static final int OFFSET = 16 * 1024;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public MessageWireTest() {}

    /**
     * @param buf the bytes to render
     * @return them as lowercase hex, zero-padded, so a leading zero byte is visible
     */
    private static String hexBytes(byte[] buf) {
        StringBuilder sb = new StringBuilder(buf.length * 2);
        for (byte b : buf) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * The stream underneath the DataOutputStream, counting what reaches it. A DataOutputStream
     * has no buffer of its own: a writeInt() is four single-byte writes straight through to
     * whatever sits below it, and on a real connection that is the streaming layer, where each
     * call is a lock acquisition and a copy. So this count is the cost.
     */
    private static class CountingStream extends OutputStream {

        /** Bytes written, so the wire bytes can be read back. */
        private final ByteArrayOutputStream _buf = new ByteArrayOutputStream();

        /** Write calls that reached this stream. */
        private int _writes;

        @Override
        public void write(int b) {
            _writes++;
            _buf.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            _writes++;
            _buf.write(b, off, len);
        }

        /**
         * @return the write calls that reached this stream
         */
        int writes() {
            return _writes;
        }

        /**
         * @return everything written, as raw bytes
         */
        byte[] bytes() {
            return _buf.toByteArray();
        }

        /**
         * @return everything written, as hex
         */
        String hex() {
            return hexBytes(bytes());
        }
    }

    /**
     * Sends one message the way a connection does, through a DataOutputStream, and reports what
     * reached the stream underneath it.
     *
     * @param msg the message to send
     * @return the stream underneath, holding the bytes and the write count
     * @throws IOException on stream failure
     */
    private static CountingStream send(Message msg) throws IOException {
        CountingStream s = new CountingStream();
        DataOutputStream dos = new DataOutputStream(s);
        assertTrue(msg.sendMessage(dos));
        dos.flush();
        return s;
    }

    /**
     * @param v the value to render as a 4-byte protocol field
     * @return it as eight hex digits, big-endian, the way a 4-byte protocol field goes out
     */
    private static String be(int v) {
        return String.format("%08x", v);
    }

    /**
     * @param v the value to render as a 1-byte protocol field
     * @return it as two hex digits, the way a 1-byte protocol field goes out
     */
    private static String b1(int v) {
        return String.format("%02x", v);
    }

    /**
     * A 4-byte piece number, offset or length field, checked against the spec rather than
     * against what the code produced.
     *
     * @param id the message id
     * @param piece the piece number field
     * @param begin the offset field
     * @param length the transfer length field
     * @return the expected wire bytes of a REQUEST-shaped message, minus its payload
     */
    private static String fields(byte id, int piece, int begin, int length) {
        return be(13) + b1(id) + be(piece) + be(begin) + be(length);
    }

    /**
     * A keep-alive is the four zero bytes and nothing else.
     *
     * @throws IOException on stream failure
     */
    @Test
    public void testKeepAlive() throws IOException {
        CountingStream s = send(new Message(Message.KEEP_ALIVE));
        assertEquals("00000000", s.hex());
        assertEquals(1, s.writes());
    }

    /**
     * The messages with no fields at all are a length of one and the id.
     *
     * @throws IOException on stream failure
     */
    @Test
    public void testNoFieldMessages() throws IOException {
        byte[] ids = {Message.CHOKE,
                      Message.UNCHOKE,
                      Message.INTERESTED,
                      Message.UNINTERESTED,
                      Message.HAVE_ALL,
                      Message.HAVE_NONE};
        for (byte id : ids) {
            CountingStream s = send(new Message(id));
            assertEquals(be(1) + b1(id), s.hex());
            assertEquals(1, s.writes());
            assertEquals(1, Message.fixedBytes(id));
        }
    }

    /**
     * The messages that carry only a piece number are a length of five, the id, and the piece.
     *
     * @throws IOException on stream failure
     */
    @Test
    public void testPieceNumberMessages() throws IOException {
        byte[] ids = {Message.HAVE, Message.SUGGEST, Message.ALLOWED_FAST};
        for (byte id : ids) {
            CountingStream s = send(new Message(id, 7));
            assertEquals(be(5) + b1(id) + be(7), s.hex());
            assertEquals(1, s.writes());
            assertEquals(5, Message.fixedBytes(id));
        }
    }

    /**
     * A REQUEST is the length, the id and three 4-byte fields, and it is one write - the
     * seventeen it used to cost is the whole point of this change.
     *
     * @throws IOException on stream failure
     */
    @Test
    public void testRequestIsOneWrite() throws IOException {
        byte[] ids = {Message.REQUEST, Message.CANCEL, Message.REJECT};
        for (byte id : ids) {
            CountingStream s = send(new Message(id, 1, OFFSET, OFFSET));
            assertEquals(fields(id, 1, OFFSET, OFFSET), s.hex());
            assertEquals(1, s.writes());
            assertEquals(13, Message.fixedBytes(id));
        }
    }

    /**
     * A PIECE carries the piece number and the offset in its header and the data after it, so
     * it is two writes: the header and the payload, with no copy of the payload into a third
     * buffer.
     *
     * @throws IOException on stream failure
     */
    @Test
    public void testPieceIsHeaderThenPayload() throws IOException {
        byte[] data = {(byte) 0xaa, (byte) 0xbb, (byte) 0xcc};
        Message msg = new Message(2, 0, data.length, (p, b, l) -> new ByteArray(data));
        CountingStream s = send(msg);
        assertEquals(be(1 + 4 + 4 + data.length) + b1(Message.PIECE) + be(2) + be(0)
                     + hexBytes(data), s.hex());
        assertEquals(2, s.writes());
        assertEquals(9, Message.fixedBytes(Message.PIECE));
    }

    /**
     * A BITFIELD is the id and then the bitmap, with the bitmap counted into the length.
     *
     * @throws IOException on stream failure
     */
    @Test
    public void testBitfield() throws IOException {
        byte[] data = {(byte) 0x81, 0x00, (byte) 0xff};
        CountingStream s = send(new Message(data));
        assertEquals(be(1 + data.length) + b1(Message.BITFIELD) + hexBytes(data),
                     s.hex());
        assertEquals(2, s.writes());
        assertEquals(1, Message.fixedBytes(Message.BITFIELD));
    }

    /**
     * A BEP 5 PORT message carries the port in the low two bytes of the piece field and writes
     * it as two bytes, not four.
     *
     * @throws IOException on stream failure
     */
    @Test
    public void testPortIsTwoBytes() throws IOException {
        int port = 6881;
        CountingStream s = send(new Message(Message.PORT, port));
        assertEquals(be(3) + b1(Message.PORT) + String.format("%04x", port), s.hex());
        assertEquals(1, s.writes());
        assertEquals(3, Message.fixedBytes(Message.PORT));
    }

    /**
     * A BEP 10 EXTENSION message carries the extended message id in one byte, not the whole
     * piece field, and the payload after it.
     *
     * @throws IOException on stream failure
     */
    @Test
    public void testExtensionIsOneByteSubType() throws IOException {
        byte[] payload = {1, 2, 3};
        CountingStream s = send(new Message(1, payload));
        assertEquals(be(1 + 1 + payload.length) + b1(Message.EXTENSION) + b1(1)
                     + hexBytes(payload), s.hex());
        assertEquals(2, s.writes());
        assertEquals(2, Message.fixedBytes(Message.EXTENSION));
    }

    /**
     * An id the writer does not know, such as a BEP 52 hash request, is still just its id, and
     * the length prefix still says one, so a reader cannot desync on it.
     *
     * @throws IOException on stream failure
     */
    @Test
    public void testUnknownIdsCarryNoFields() throws IOException {
        byte[] ids = {Message.HASH_REQUEST, Message.HASHES, Message.HASH_REJECT, 99};
        for (byte id : ids) {
            CountingStream s = send(new Message(id));
            assertEquals(be(1) + b1(id), s.hex());
            assertEquals(1, Message.fixedBytes(id));
        }
    }

    /**
     * The field predicates agree with each other, so the length prefix and the header cannot
     * disagree about how many bytes a message id is followed by.
     */
    @Test
    public void testFixedBytesMatchesTheFields() {
        for (int i = -1; i < 128; i++) {
            byte id = (byte) i;
            int expected = 1;
            if (Message.hasPieceNumber(id)) expected += 4;
            if (Message.hasOffset(id)) expected += 4;
            if (Message.hasTransferLength(id)) expected += 4;
            if (Message.hasSubType(id)) expected += 1;
            if (Message.hasPort(id)) expected += 2;
            assertEquals(expected, Message.fixedBytes(id));
            // the read loop sizes its header buffer from MAX_FIXED_BYTES, so no id may exceed it
            assertTrue(Message.fixedBytes(id) <= Message.MAX_FIXED_BYTES);
            // a transfer length is only sent alongside an offset, and an offset alongside a
            // piece number, so the fields always arrive in the order the reader takes them
            if (Message.hasTransferLength(id)) assertTrue(Message.hasOffset(id));
            if (Message.hasOffset(id)) assertTrue(Message.hasPieceNumber(id));
            if (Message.hasSubType(id) || Message.hasPort(id))
                assertFalse(Message.hasPieceNumber(id));
        }
    }

    /**
     * The length prefix is the fixed bytes plus the payload, for every message that has one,
     * which is what lets a reader know how much to take before it knows what the message is.
     *
     * @throws IOException on stream failure
     */
    @Test
    public void testLengthPrefixCoversHeaderAndPayload() throws IOException {
        byte[] payload = new byte[1024];
        Message[] msgs = {new Message(Message.REQUEST, 3, OFFSET, 16384),
                          new Message(3, OFFSET, payload.length,
                                      (p, b, l) -> new ByteArray(payload)),
                          new Message(payload),
                          new Message(7, payload),
                          new Message(Message.PORT, 1)};
        for (Message msg : msgs) {
            CountingStream s = send(msg);
            String hex = s.hex();
            int declared = Integer.parseInt(hex.substring(0, 8), 16);
            // the prefix counts everything after itself, and the bytes after it are all there is
            assertEquals((hex.length() / 2) - 4, declared);
            assertTrue(declared >= Message.fixedBytes(msg.type));
        }
    }

    /**
     * The reader takes the fields back in the order and at the offsets the writer laid them
     * down, for every id that carries any, and consumes exactly the header: no less, or a field
     * is missed, and no more, or the payload is eaten. This is the agreement the read loop now
     * relies on, since it takes all the fixed fields in one read and parses them locally.
     *
     * @throws IOException on stream failure
     */
    @Test
    public void testReaderTakesWhatTheWriterPut() throws IOException {
        byte[] payload = new byte[64];
        Message[] msgs = {new Message(Message.HAVE, 7),
                          new Message(Message.SUGGEST, 123456),
                          new Message(Message.ALLOWED_FAST, 2048),
                          new Message(Message.REQUEST, 1, OFFSET, 16384),
                          new Message(Message.CANCEL, 2, 0, 1),
                          new Message(Message.REJECT, 3, OFFSET, 16384),
                          new Message(4, OFFSET, payload.length,
                                      (p, b, l) -> new ByteArray(payload)),
                          new Message(Message.PORT, 6881),
                          new Message(9, payload)};
        for (Message msg : msgs) {
            byte[] wire = send(msg).bytes();
            byte id = wire[4];
            assertEquals(msg.type, id);
            // the reader's own walk of the header, in the order fixedBytes() counts the fields
            int p = 5;
            int piece = 0;
            int begin = 0;
            int length = 0;
            if (Message.hasPieceNumber(id)) {
                piece = Message.readInt(wire, p);
                p += 4;
            }
            if (Message.hasOffset(id)) {
                begin = Message.readInt(wire, p);
                p += 4;
            }
            if (Message.hasTransferLength(id)) {
                length = Message.readInt(wire, p);
                p += 4;
            } else if (Message.hasSubType(id)) {
                piece = Message.readUnsignedByte(wire, p);
                p += 1;
            } else if (Message.hasPort(id)) {
                piece = Message.readUnsignedShort(wire, p);
                p += 2;
            }
            assertEquals(4 + Message.fixedBytes(id), p);
            assertEquals(msg.begin, begin);
            if (Message.hasTransferLength(id)) {
                assertEquals(msg.length, length);
            } else {
                // a PIECE's length is implicit in the prefix, and no other id carries one, so
                // the reader leaves it at zero for all of them
                assertEquals(0, length);
            }
            if (Message.hasSubType(id)) {
                assertEquals(msg.piece & 0xff, piece);
            } else if (Message.hasPort(id)) {
                assertEquals(msg.piece & 0xffff, piece);
            } else {
                assertEquals(msg.piece, piece);
            }
            // the payload begins where the header ends, and is exactly as long as the length
            // prefix promises, so one read of it is enough
            int declared = Message.readInt(wire, 0);
            assertEquals(wire.length - 4, declared);
            assertEquals(wire.length - p, declared - Message.fixedBytes(id));
        }
    }
}
