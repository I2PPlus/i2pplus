package net.i2p.i2ptunnel.irc;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the DCC half of the IRC line filters: an untagged DCC offer used to be
 * discarded because the split limit left the tail of the message body in a field
 * the filter never read, a line that is only a message tag threw out of the
 * outbound filter, and the outbound filter forwarded a DCC type it does not know.
 *
 * @since 0.9.72+
 */
public class IRCFilterDccTest {

    /**
     * Every test calls a filter directly and builds its own helper when it needs
     * one, so nothing is held between instances.
     */
    public IRCFilterDccTest() {}

    /** The b32 name the stub substitutes for a local DCC endpoint. */
    private static final String OUR_B32 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.b32.i2p";

    /** The IP the stub reports as local, in the decimal form a DCC offer carries. */
    private static final String OUR_IP = "2130706433";

    /** A DCC helper that accepts everything and hands out fixed ports. */
    private static final DCCHelper HELPER = new StubDCCHelper();

    /** A message tag of the kind a tagged-capable server prepends. */
    private static final String TAG = "@time=2026-11-10T00:00:00Z ";

    /** A plain CTCP CHAT offer gets the local address and a mapped port. */
    @Test
    public void untaggedChatOfferRewritten() {
        assertEquals("PRIVMSG mynick :\u0001DCC CHAT chat " + OUR_IP + " 43211\u0001",
                     IRCFilter.inboundFilter("PRIVMSG mynick :\u0001DCC CHAT chat dest.b32.i2p 20000\u0001",
                                             new StringBuffer(128), HELPER));
    }

    /** A SEND offer is rewritten like CHAT and keeps its trailing length field. */
    @Test
    public void untaggedSendOfferRewritten() {
        assertEquals("PRIVMSG mynick :\u0001DCC SEND myfile " + OUR_IP + " 43211 10240\u0001",
                     IRCFilter.inboundFilter("PRIVMSG mynick :\u0001DCC SEND myfile 3232235777 20000 10240\u0001",
                                             new StringBuffer(128), HELPER));
    }

    /**
     * A tag shifts every field one place to the right, which is why the tagged
     * form worked while the plain form did not. The tag is kept on the way out.
     */
    @Test
    public void taggedChatOfferKeepsTheTag() {
        String plain = IRCFilter.inboundFilter("PRIVMSG mynick :\u0001DCC CHAT chat dest.b32.i2p 20000\u0001",
                                               new StringBuffer(128), HELPER);
        String tagged = IRCFilter.inboundFilter(TAG + "PRIVMSG mynick :\u0001DCC CHAT chat dest.b32.i2p 20000\u0001",
                                                new StringBuffer(128), HELPER);
        assertNotNull(plain);
        assertEquals(TAG + plain, tagged);
    }

    /** A line the server prefixed is filtered the same way, prefix left alone. */
    @Test
    public void serverPrefixedChatOfferRewritten() {
        assertEquals(":nick!user@host PRIVMSG mynick :\u0001DCC CHAT chat " + OUR_IP + " 43211\u0001",
                     IRCFilter.inboundFilter(":nick!user@host PRIVMSG mynick :\u0001DCC CHAT chat dest.b32.i2p 20000\u0001",
                                             new StringBuffer(128), HELPER));
    }

    /**
     * RESUME and ACCEPT carry no address, only the port to rewrite.
     */
    @Test
    public void resumeOfferRewritten() {
        assertEquals("PRIVMSG mynick :\u0001DCC RESUME myfile 43213 1024\u0001",
                     IRCFilter.inboundFilter("PRIVMSG mynick :\u0001DCC RESUME myfile 20000 1024\u0001",
                                             new StringBuffer(128), HELPER));
    }

    /** Inbound ACCEPT, the same port-only rewrite as RESUME. */
    @Test
    public void acceptOfferRewritten() {
        assertEquals("PRIVMSG mynick :\u0001DCC ACCEPT myfile 43215 1024\u0001",
                     IRCFilter.inboundFilter("PRIVMSG mynick :\u0001DCC ACCEPT myfile 20000 1024\u0001",
                                             new StringBuffer(128), HELPER));
    }

    /**
     * A DCC SEND whose filename contains spaces cannot be parsed: the arguments
     * are positional from the left, so the address and port land in the wrong
     * slots. The offer is dropped, which is the safe outcome - it is never
     * forwarded with the remote address still in it.
     */
    @Test
    public void sendOfferWithSpacesInNameDropped() {
        assertNull(IRCFilter.inboundFilter("PRIVMSG mynick :\u0001DCC SEND my file 3232235777 20000 10240\u0001",
                                           new StringBuffer(128), HELPER));
    }

    /** A message that only mentions DCC is not an offer and passes through both ways. */
    @Test
    public void plainPrivmsgMentioningDccUnchanged() {
        String line = "PRIVMSG mynick :we should use DCC CHAT more often";
        assertEquals(line, IRCFilter.inboundFilter(line, new StringBuffer(128), HELPER));
        assertEquals(line, IRCFilter.outboundFilter(line, new StringBuffer(128), HELPER));
    }

    /** A CTCP ACTION is not a DCC offer and passes through both ways. */
    @Test
    public void actionUnchanged() {
        String line = "PRIVMSG mynick :\u0001ACTION waves\u0001";
        assertEquals(line, IRCFilter.inboundFilter(line, new StringBuffer(128), HELPER));
        assertEquals(line, IRCFilter.outboundFilter(line, new StringBuffer(128), HELPER));
    }

    /**
     * Three CTCP markers means more than one CTCP in the line, which the filter
     * does not attempt to parse.
     */
    @Test
    public void multipleCtcpMarkersDropped() {
        assertNull(IRCFilter.inboundFilter("PRIVMSG mynick :\u0001ACTION hi\u0001\u0001VERSION\u0001",
                                           new StringBuffer(128), HELPER));
    }

    /** Our own SEND offer leaves with our address and a mapped port. */
    @Test
    public void outboundSendOfferRewritten() {
        assertEquals("PRIVMSG other :\u0001DCC SEND myfile " + OUR_B32 + " 43210 10240\u0001",
                     IRCFilter.outboundFilter("PRIVMSG other :\u0001DCC SEND myfile 2130706433 1234 10240\u0001",
                                              new StringBuffer(128), HELPER));
    }

    /** The tagged form of our own SEND offer is rewritten the same way. */
    @Test
    public void outboundTaggedSendOfferRewritten() {
        assertEquals("PRIVMSG other :\u0001DCC SEND myfile " + OUR_B32 + " 43210 10240\u0001",
                     IRCFilter.outboundFilter(TAG + "PRIVMSG other :\u0001DCC SEND myfile 2130706433 1234 10240\u0001",
                                              new StringBuffer(128), HELPER));
    }

    /** Our own RESUME carries no address, only the port to rewrite. */
    @Test
    public void outboundResumeOfferRewritten() {
        assertEquals("PRIVMSG other :\u0001DCC RESUME myfile 43212 1024\u0001",
                     IRCFilter.outboundFilter("PRIVMSG other :\u0001DCC RESUME myfile 1234 1024\u0001",
                                              new StringBuffer(128), HELPER));
    }

    /** Our own ACCEPT rewrites the port and allocates no new endpoint. */
    @Test
    public void outboundAcceptOfferRewritten() {
        StubDCCHelper helper = new StubDCCHelper();
        assertEquals("PRIVMSG other :\u0001DCC ACCEPT myfile 43214 1024\u0001",
                     IRCFilter.outboundFilter("PRIVMSG other :\u0001DCC ACCEPT myfile 1234 1024\u0001",
                                              new StringBuffer(128), helper));
        assertTrue("ACCEPT allocates no new endpoint", helper.outgoing.isEmpty());
    }

    /**
     * A DCC type the filter does not know carries no address it could rewrite, and
     * the method's own contract says "DCC xxx -&gt; null". The inbound side kept
     * that; the outbound side fell through into the address and port parsing, so a
     * line from the user's own client paid a DCC proxy port and an outbound
     * connection for a transfer that was never offered.
     */
    @Test
    public void outboundUnknownTypeDropped() {
        StubDCCHelper helper = new StubDCCHelper();
        assertNull(IRCFilter.outboundFilter("PRIVMSG other :\u0001DCC BOGUS myfile 2130706433 1234\u0001",
                                            new StringBuffer(128), helper));
        assertTrue("an unknown type allocated a DCC endpoint", helper.outgoing.isEmpty());
    }

    /**
     * CHAT is the other type the outbound filter forwards, and it is forwarded
     * with an endpoint of its own - the two known types are not caught by the
     * unknown-type guard.
     */
    @Test
    public void outboundChatOfferRewritten() {
        StubDCCHelper helper = new StubDCCHelper();
        assertEquals("PRIVMSG other :\u0001DCC CHAT chat " + OUR_B32 + " 43210\u0001",
                     IRCFilter.outboundFilter("PRIVMSG other :\u0001DCC CHAT chat 2130706433 1234\u0001",
                                              new StringBuffer(128), helper));
        assertEquals(1, helper.outgoing.size());
    }

    /**
     * A line that is only a message tag has no command. It used to run off the end
     * of the split fields and throw, and the caller turned that exception into a
     * dropped tunnel rather than a dropped line.
     */
    @Test
    public void tagOnlyLineDroppedNotCrash() {
        assertNull(IRCFilter.outboundFilter("@id=5", new StringBuffer(128), HELPER));
        assertNull(IRCFilter.inboundFilter("@id=5", new StringBuffer(128), HELPER));
    }

    /** A tag in front of a real command does not stop the filter. */
    @Test
    public void tagOnlyLineWithCommandStillFiltered() {
        assertEquals("QUIT :leaving",
                     IRCFilter.outboundFilter("@id=5 QUIT :see ya", new StringBuffer(128), HELPER));
    }

    /**
     * A DCC offer the helper refuses is dropped rather than forwarded with the
     * remote address still in it.
     */
    @Test
    public void offerRefusedByHelperDropped() {
        assertNull(IRCFilter.inboundFilter("PRIVMSG mynick :\u0001DCC CHAT chat dest.b32.i2p 20000\u0001",
                                           new StringBuffer(128), null));
    }

    /** A DCCHelper that accepts every request and returns fixed ports. */
    private static class StubDCCHelper implements DCCHelper {

        /** The endpoints newOutgoing() was asked for, so a test can see an allocation. */
        final List<String> outgoing = new ArrayList<>(4);

        public boolean isEnabled() {
            return true;
        }

        public String getB32Hostname() {
            return OUR_B32;
        }

        public byte[] getLocalAddress() {
            return new byte[] {127, 0, 0, 1};
        }

        public int newOutgoing(byte[] ip, int port, String type) {
            outgoing.add(type + ' ' + port);
            return 43210;
        }

        public int newIncoming(String b32, int port, String type) {
            return 43211;
        }

        public int resumeOutgoing(int port) {
            return 43212;
        }

        public int resumeIncoming(int port) {
            return 43213;
        }

        public int acceptOutgoing(int port) {
            return 43214;
        }

        public int acceptIncoming(int port) {
            return 43215;
        }
    }
}
