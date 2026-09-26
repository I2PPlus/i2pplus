package net.i2p.router.client;

import static org.junit.Assert.assertEquals;

import net.i2p.I2PAppContext;
import net.i2p.data.Payload;
import net.i2p.data.i2cp.SessionConfig;

import org.junit.Before;
import org.junit.Test;

/**
 * Contract tests for the receive-path validation in
 * {@link ClientMessageEventListener}.
 *
 * <p>The router retains payloads in a map that is keyed per connection, not per
 * session, so a client-supplied message id alone must never authorize reading or
 * releasing a retained payload: the message must also name a live session.
 *
 * @since 0.9.72
 */
public class ClientMessageEventListenerReceiveTest {

    @Before
    public void setUp() {
        I2PAppContext.getGlobalContext();
    }

    /** A live session on the connection. */
    private static SessionConfig liveSession() {
        return new SessionConfig();
    }

    /** A retained, non-null payload. */
    private static Payload retainedPayload() {
        Payload payload = new Payload();
        payload.setEncryptedData(new byte[] {1, 2, 3, 4});
        return payload;
    }

    @Test
    public void testBeginAllowedForLiveSession() {
        assertEquals(ClientMessageEventListener.ReceiveRequest.ALLOWED,
                     ClientMessageEventListener.classifyReceiveBegin(liveSession(), retainedPayload()));
    }

    @Test
    public void testBeginRejectedForUnknownSession() {
        assertEquals(ClientMessageEventListener.ReceiveRequest.UNKNOWN_SESSION,
                     ClientMessageEventListener.classifyReceiveBegin(null, retainedPayload()));
    }

    /**
     * The unknown-session check must run before the payload check, so a bogus
     * session cannot be masked by an absent payload.
     */
    @Test
    public void testBeginSessionCheckedBeforePayload() {
        assertEquals(ClientMessageEventListener.ReceiveRequest.UNKNOWN_SESSION,
                     ClientMessageEventListener.classifyReceiveBegin(null, null));
    }

    @Test
    public void testBeginNoRetainedPayload() {
        assertEquals(ClientMessageEventListener.ReceiveRequest.NO_PAYLOAD,
                     ClientMessageEventListener.classifyReceiveBegin(liveSession(), null));
    }

    @Test
    public void testEndAllowedForLiveSession() {
        assertEquals(ClientMessageEventListener.ReceiveRequest.ALLOWED,
                     ClientMessageEventListener.classifyReceiveEnd(liveSession(), retainedPayload()));
    }

    @Test
    public void testEndRejectedForUnknownSession() {
        assertEquals(ClientMessageEventListener.ReceiveRequest.UNKNOWN_SESSION,
                     ClientMessageEventListener.classifyReceiveEnd(null, retainedPayload()));
    }

    /**
     * A duplicate ReceiveMessageEndMessage, or one for a message that was never
     * retained, must be a no-op rather than an error or a repeat removal.
     */
    @Test
    public void testEndNoRetainedPayloadIsNoOp() {
        assertEquals(ClientMessageEventListener.ReceiveRequest.NO_PAYLOAD,
                     ClientMessageEventListener.classifyReceiveEnd(liveSession(), null));
        assertEquals(ClientMessageEventListener.ReceiveRequest.NO_PAYLOAD,
                     ClientMessageEventListener.classifyReceiveEnd(liveSession(), null));
    }
}
