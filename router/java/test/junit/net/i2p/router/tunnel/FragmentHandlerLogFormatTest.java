package net.i2p.router.tunnel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.i2p.data.Hash;

import org.junit.Test;

/**
 * The throttled entry for a delivery instruction that names tunnel 0.
 *
 * <p>It is throttled to one line per window, so the fields it carries are the
 * whole finding. Two of them did not say what they claimed: the {@code Type:}
 * segment read the byte at the current offset, which by then is the first byte of
 * the peer's payload, and the router was printed in the 8-character base32 form
 * the console log viewer links as a <em>lease set</em>, so the one clickable thing
 * in the entry went to the wrong netdb object.
 *
 * @since 0.9.72+
 */
public class FragmentHandlerLogFormatTest {

    /** Runs the cases; it holds no state of its own. */
    public FragmentHandlerLogFormatTest() {}

    /** A router hash with a fixed short form, so the entry can be pinned exactly. */
    private static final Hash ROUTER = filled((byte) 0x11);

    /** Any bracketed run in the entry, of which there must be exactly one. */
    private static final Pattern BRACKET = Pattern.compile("\\[([^\\]]+)\\]");

    /** The alphabet a router hash is written in, as the log viewer parses it. */
    private static final Pattern ROUTER_FORM = Pattern.compile("[A-Za-z0-9~\\-]{6}");

    /** The entry's first line, which the fix left alone. */
    private static final String FIRST_LINE =
        "Dropping messages at the tunnel endpoint -> Delivery instruction to tunnel 0";

    /**
     * The entry, in full, for a fragmented instruction at an inbound gateway.
     */
    @Test
    public void fragmentedEntryAtAnInboundGateway() {
        String expected = FIRST_LINE + "\n* Gateway: [" + ROUTER.toShortBase64() + "]" +
                          " * Fragmented: true * Message ID: 1234567 * Size: 1500 bytes";
        assertEquals(expected,
                     FragmentHandler.deliveryToTunnel0(ROUTER, true, 1234567, 1500, true));
    }

    /**
     * An outbound endpoint is delivering to a destination, not on behalf of a
     * gateway, and the entry says which.
     */
    @Test
    public void outboundEndpointNamesATargetNotAGateway() {
        String entry = FragmentHandler.deliveryToTunnel0(ROUTER, false, -1, 64, false);
        assertTrue("an outbound endpoint was labelled a gateway: " + entry,
                   entry.contains("\n* Target: ["));
    }

    /**
     * The one bracketed value is the router in the 6-character base64 form. The
     * 8-character base32 form is what the console linkifier reads as a lease set,
     * and that is what this entry used to print.
     */
    @Test
    public void theBracketedHashLinksAsARouter() {
        String entry = FragmentHandler.deliveryToTunnel0(ROUTER, true, 7, 64, true);
        Matcher m = BRACKET.matcher(entry);
        assertTrue("the entry lost the router hash", m.find());
        String bracketed = m.group(1);
        assertTrue("the bracketed hash is not the 6-character router form: " + bracketed,
                   ROUTER_FORM.matcher(bracketed).matches());
        assertFalse("a second bracketed value reads as a broken link: " + entry, m.find());
    }

    /**
     * A message id is a value, not a link, so it is a plain segment - a bracketed
     * {@code [MsgID 12]} squats on the one form the log viewer parses.
     */
    @Test
    public void messageIdsAreNotBracketed() {
        String entry = FragmentHandler.deliveryToTunnel0(ROUTER, true, 1234567, 1500, true);
        assertTrue("the message id is missing: " + entry, entry.contains(" * Message ID: 1234567"));
        assertFalse("the message id is bracketed: " + entry, entry.contains("[MsgID"));
    }

    /**
     * An unfragmented instruction has no message id at all, so none is printed -
     * the -1 the parser leaves behind is not a measurement.
     */
    @Test
    public void unfragmentedEntryOmitsTheMessageId() {
        String entry = FragmentHandler.deliveryToTunnel0(ROUTER, false, -1, 64, true);
        assertTrue("the entry must still say it is unfragmented: " + entry,
                   entry.contains(" * Fragmented: false"));
        assertFalse("an unfragmented message printed a sentinel id: " + entry,
                    entry.contains("Message ID"));
    }

    /**
     * The delivery type is fixed by the branch that logs this, and the byte that
     * used to be printed there is payload. Neither belongs in the entry.
     */
    @Test
    public void noSegmentPrintsAPayloadByteAsAType() {
        String entry = FragmentHandler.deliveryToTunnel0(ROUTER, true, 7, 64, true);
        assertFalse("a type the branch already fixes, or a payload byte, was printed: " + entry,
                    entry.contains("Type:"));
    }

    /**
     * Every emitted line stays inside the log line budget.
     */
    @Test
    public void everyLineFitsTheBudget() {
        for (boolean inbound : new boolean[] {true, false}) {
            for (boolean fragmented : new boolean[] {true, false}) {
                String entry = FragmentHandler.deliveryToTunnel0(ROUTER, fragmented, 4294967295L,
                                                                 65535, inbound);
                for (String line : entry.split("\n")) {
                    assertTrue("line over 100 chars (" + line.length() + "): " + line,
                               line.length() <= 100);
                }
            }
        }
    }

    /**
     * @param value the byte to fill the hash with
     * @return a hash of 32 copies of value
     */
    private static Hash filled(byte value) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        Arrays.fill(data, value);
        return new Hash(data);
    }
}
