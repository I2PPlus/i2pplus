package net.i2p.router.transport.ntcp;

import static org.junit.Assert.assertEquals;

import net.i2p.data.router.RouterInfo;

import org.junit.Test;

/**
 * What a message #3 payload failure means for the peer that caused it.
 *
 * <p>A router info that does not verify its own signature and a router info the
 * parser cannot read at all both arrive as the same checked exception out of
 * {@link RouterInfo#readBytes}. The old code called every one of them a
 * signature failure, answered with the signature-failure termination code and
 * added the sender's address to the blocklist — so a fork that serialises the
 * router info's peer list differently, or a block truncated in transit, lost its
 * address to a blocklist that outlives the connection. Only a signature failure
 * says anything about the peer.
 *
 * @since 0.9.72+
 */
public class NTCP2Msg3FailReasonTest {

    /** Runs the cases; it holds no state of its own. */
    public NTCP2Msg3FailReasonTest() {}

    /**
     * One message #3 payload failure through the decision.
     *
     * @param dfeMessage the parse failure's message, possibly null
     * @return the termination reason the connection should carry
     */
    private static int reason(String dfeMessage) {
        return InboundEstablishState.msg3PayloadFailReason(dfeMessage);
    }

    /** A router info that did not verify its own signature is a signature failure. */
    @Test
    public void aBadSignatureIsASignatureFailure() {
        assertEquals(NTCPConnection.REASON_SIGFAIL, reason(RouterInfo.BAD_SIGNATURE_MESSAGE));
    }

    /**
     * Everything else the parse rejected is a format problem: an oversized
     * router info, too many addresses, a structure cut short of its signature.
     */
    @Test
    public void aFormatProblemIsNotASignatureFailure() {
        assertEquals(NTCPConnection.REASON_FRAMING, reason("RouterInfo too big: 5000"));
        assertEquals(NTCPConnection.REASON_FRAMING, reason("Too many addresses"));
        assertEquals(NTCPConnection.REASON_FRAMING, reason("EOF reading RouterInfo"));
    }

    /** A message that merely contains the signature text is not the signature failing. */
    @Test
    public void onlyTheExactSignatureMessageCounts() {
        assertEquals(NTCPConnection.REASON_FRAMING,
                     reason("prefix " + RouterInfo.BAD_SIGNATURE_MESSAGE));
        assertEquals(NTCPConnection.REASON_FRAMING,
                     reason(RouterInfo.BAD_SIGNATURE_MESSAGE + " suffix"));
    }

    /** An exception with no message at all is a format problem, never a forged identity. */
    @Test
    public void noMessageIsNotASignatureFailure() {
        assertEquals(NTCPConnection.REASON_FRAMING, reason(null));
        assertEquals(NTCPConnection.REASON_FRAMING, reason(""));
    }
}
