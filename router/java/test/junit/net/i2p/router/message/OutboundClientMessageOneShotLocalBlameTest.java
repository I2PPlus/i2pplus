package net.i2p.router.message;

import static org.junit.Assert.*;

import net.i2p.data.i2cp.MessageStatusMessage;

import org.junit.Test;

/**
 * Tests the outbound-tunnel blame rule in
 * {@link OutboundClientMessageOneShotJob#shouldBlameOutboundTunnel(int, boolean)}.
 *
 * <p>The rule exists because a destination this router hosts produces its own
 * reply. When the local eepgsite HTTP server saturates its handler pool, the
 * reply never comes, the send times out with
 * {@code STATUS_SEND_BEST_EFFORT_FAILURE}, and the outbound tunnel that
 * delivered the request perfectly well was condemned for it. The pool then
 * starves its own egress, which guarantees the next request fails too.
 *
 * <p>Pure, so no router is required.
 *
 * @since 0.9.71+
 */
public class OutboundClientMessageOneShotLocalBlameTest {

    private static final int SOFT = MessageStatusMessage.STATUS_SEND_BEST_EFFORT_FAILURE;
    private static final int LOCAL = MessageStatusMessage.STATUS_SEND_FAILURE_LOCAL;
    private static final int ROUTER = MessageStatusMessage.STATUS_SEND_FAILURE_ROUTER;
    private static final int NETWORK = MessageStatusMessage.STATUS_SEND_FAILURE_NETWORK;
    private static final int OVERFLOW = MessageStatusMessage.STATUS_SEND_FAILURE_OVERFLOW;
    private static final int NO_TUNNELS = MessageStatusMessage.STATUS_SEND_FAILURE_NO_TUNNELS;
    private static final int EXPIRED = MessageStatusMessage.STATUS_SEND_FAILURE_EXPIRED;

    /**
     * The regression: a soft timeout to a service we host must never be charged
     * to our outbound tunnel.
     */
    @Test
    public void testSoftTimeoutToLocalServiceIsNotBlamed() {
        assertFalse(OutboundClientMessageOneShotJob.shouldBlameOutboundTunnel(SOFT, true));
    }

    /** No failure status may be blamed when we are the server. */
    @Test
    public void testNoStatusIsBlamedForLocalService() {
        int[] all = {LOCAL, ROUTER, NETWORK, OVERFLOW, SOFT, NO_TUNNELS, EXPIRED};
        for (int status : all) {
            assertFalse("status " + status + " must not be blamed for a local service",
                        OutboundClientMessageOneShotJob.shouldBlameOutboundTunnel(status, true));
        }
    }

    /**
     * The protection must survive for remote destinations: a genuinely dead
     * remote path still has to rotate its tunnel out.
     */
    @Test
    public void testRemoteFailuresStillBlamed() {
        assertTrue(OutboundClientMessageOneShotJob.shouldBlameOutboundTunnel(SOFT, false));
        assertTrue(OutboundClientMessageOneShotJob.shouldBlameOutboundTunnel(LOCAL, false));
        assertTrue(OutboundClientMessageOneShotJob.shouldBlameOutboundTunnel(ROUTER, false));
        assertTrue(OutboundClientMessageOneShotJob.shouldBlameOutboundTunnel(NETWORK, false));
        assertTrue(OutboundClientMessageOneShotJob.shouldBlameOutboundTunnel(OVERFLOW, false));
    }

    /**
     * Pool starvation is handled by a nudge, not by blaming the tunnel, for
     * both local and remote destinations.
     */
    @Test
    public void testStarvationNeverBlames() {
        assertFalse(OutboundClientMessageOneShotJob.shouldBlameOutboundTunnel(NO_TUNNELS, false));
        assertFalse(OutboundClientMessageOneShotJob.shouldBlameOutboundTunnel(EXPIRED, false));
    }

    /** An unrecognised status is not blamed, so unknown codes cannot mass-fail a pool. */
    @Test
    public void testUnknownStatusNeverBlamed() {
        assertFalse(OutboundClientMessageOneShotJob.shouldBlameOutboundTunnel(-1, false));
        assertFalse(OutboundClientMessageOneShotJob.shouldBlameOutboundTunnel(999, false));
    }
}
