package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins how a protocol message renders in a log line: the protocol name on its own,
 * and what it carried as {@code key: value} pairs with units. The form the log used
 * to print, {@code Sending [peer]: REQUEST(27,0,16384)}, names nothing a reader can
 * act on, and its brackets are the form the console log viewer parses as a
 * destination, so it reads as a broken link.
 *
 * @since 0.9.72+
 */
public class MessageLogFormTest {

    /**
     * Test instance; every test builds a message and reads its form back.
     */
    public MessageLogFormTest() {
    }

    /** A request names the piece, the offset and the size, each with its unit. */
    @Test
    public void requestFieldsBecomeKeyValuesWithUnits() {
        Message m = new Message(Message.REQUEST, 1044, 28672, 16384);
        assertEquals("REQUEST", m.typeName());
        assertEquals(" -> Piece: 1044 * Offset: 28672 bytes * Size: 16384 bytes", m.logArgs());
    }

    /** The other three block messages render the same way, so one reading covers all. */
    @Test
    public void blockMessagesShareOneForm() {
        assertEquals("PIECE", new Message(Message.PIECE, 27, 0, 16384).typeName());
        assertEquals("CANCEL", new Message(Message.CANCEL, 27, 0, 16384).typeName());
        assertEquals("REJECT", new Message(Message.REJECT, 27, 0, 16384).typeName());
        for (byte type : new byte[] {Message.PIECE, Message.CANCEL, Message.REJECT}) {
            assertEquals(" -> Piece: 27 * Offset: 0 bytes * Size: 16384 bytes",
                         new Message(type, 27, 0, 16384).logArgs());
        }
    }

    /** A have names the piece and nothing else. */
    @Test
    public void haveNamesOnlyItsPiece() {
        assertEquals(" -> Piece: 27", new Message(Message.HAVE, 27).logArgs());
        assertEquals(" -> Piece: 27", new Message(Message.SUGGEST, 27).logArgs());
        assertEquals(" -> Piece: 27", new Message(Message.ALLOWED_FAST, 27).logArgs());
    }

    /**
     * A PORT message keeps the UDP port in the field the other messages use for the
     * piece index, and the log has to say port, not piece.
     */
    @Test
    public void portMessageNamesAPortNotAPiece() {
        Message m = new Message(Message.PORT, 8765);
        assertEquals("PORT", m.typeName());
        assertEquals(" -> Port: 8765", m.logArgs());
    }

    /** A message with no fields gets no arrow and no segments. */
    @Test
    public void fieldlessMessagesCarryNothing() {
        for (byte type : new byte[] {Message.CHOKE, Message.UNCHOKE, Message.INTERESTED,
                                     Message.UNINTERESTED, Message.HAVE_ALL,
                                     Message.HAVE_NONE}) {
            Message m = new Message(type);
            assertEquals("", m.logArgs());
            assertTrue(m.typeName(), m.typeName().matches("[A-Z_]+"));
        }
    }

    /** An extension message names the payload size, never the payload. */
    @Test
    public void extensionNamesItsSizeNotItsPayload() {
        Message m = new Message(15, new byte[] {1, 2, 3, 4});
        assertEquals("EXTENSION", m.typeName());
        assertEquals(" -> Size: 4 bytes", m.logArgs());
    }

    /**
     * The old comma-tuple form of {@code toString()} is unchanged, so anything that
     * parses or prints a message outside the log keeps what it had.
     */
    @Test
    public void toStringKeepsTheOldTupleForm() {
        assertEquals("REQUEST(27,0,16384)", new Message(Message.REQUEST, 27, 0, 16384).toString());
        assertEquals("HAVE(27)", new Message(Message.HAVE, 27).toString());
        assertEquals("CHOKE", new Message(Message.CHOKE).toString());
    }

    /** An unrecognised type is named in prose with its number, never as an empty name. */
    @Test
    public void unknownTypeIsNamedWithItsNumber() {
        Message m = new Message((byte) 99);
        assertEquals("UNKNOWN (99)", m.typeName());
        assertEquals("", m.logArgs());
    }
}
