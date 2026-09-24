package net.i2p.router.message;

import static org.junit.Assert.*;

import org.junit.Test;

import net.i2p.data.i2cp.MessageStatusMessage;

/**
 * Unit tests for the pure decision helpers
 * {@link OutboundClientMessageOneShotJob#isTunnelRelatedFailure(int)} and
 * {@link OutboundClientMessageOneShotJob#isSoftSendFailure(int)}.
 * <p>
 * Hard outbound-dispatch failures (7, 8, 9, 13) are reported at the normal
 * removal bar. Soft send timeout (3) is reported at a higher bar via
 * {@code isSoftSendFailure}. Expired (14), no-tunnels (16), and remote-
 * destination statuses must not blame the outbound tunnel.
 *
 * @since 0.9.71+
 */
public class IsTunnelRelatedFailureTest {

    // ---------- hard dispatch failures: reported ----------

    @Test
    public void hardLocalDispatchFailureIsTunnelRelated() {
        assertTrue(OutboundClientMessageOneShotJob.isTunnelRelatedFailure(
                MessageStatusMessage.STATUS_SEND_FAILURE_LOCAL));
    }

    @Test
    public void hardRouterFailureIsTunnelRelated() {
        assertTrue(OutboundClientMessageOneShotJob.isTunnelRelatedFailure(
                MessageStatusMessage.STATUS_SEND_FAILURE_ROUTER));
    }

    @Test
    public void hardNetworkFailureIsTunnelRelated() {
        assertTrue(OutboundClientMessageOneShotJob.isTunnelRelatedFailure(
                MessageStatusMessage.STATUS_SEND_FAILURE_NETWORK));
    }

    @Test
    public void hardOverflowFailureIsTunnelRelated() {
        assertTrue(OutboundClientMessageOneShotJob.isTunnelRelatedFailure(
                MessageStatusMessage.STATUS_SEND_FAILURE_OVERFLOW));
    }

    // ---------- soft send timeout: reported at higher bar ----------

    @Test
    public void sendTimeoutIsSoftNotHard() {
        // Status 3 fires after a successful dispatch when no reply arrives;
        // reported as soft so congestion cannot cascade-kill healthy tunnels.
        assertFalse(OutboundClientMessageOneShotJob.isTunnelRelatedFailure(
                MessageStatusMessage.STATUS_SEND_BEST_EFFORT_FAILURE));
        assertTrue(OutboundClientMessageOneShotJob.isSoftSendFailure(
                MessageStatusMessage.STATUS_SEND_BEST_EFFORT_FAILURE));
    }

    @Test
    public void hardFailuresAreNotSoft() {
        assertFalse(OutboundClientMessageOneShotJob.isSoftSendFailure(
                MessageStatusMessage.STATUS_SEND_FAILURE_LOCAL));
        assertFalse(OutboundClientMessageOneShotJob.isSoftSendFailure(
                MessageStatusMessage.STATUS_SEND_FAILURE_NETWORK));
    }

    // ---------- soft statuses: NOT reported as hard ----------

    @Test
    public void expiredIsNotTunnelRelated() {
        assertFalse(OutboundClientMessageOneShotJob.isTunnelRelatedFailure(
                MessageStatusMessage.STATUS_SEND_FAILURE_EXPIRED));
        assertFalse(OutboundClientMessageOneShotJob.isSoftSendFailure(
                MessageStatusMessage.STATUS_SEND_FAILURE_EXPIRED));
    }

    @Test
    public void noTunnelsIsNotTunnelRelated() {
        // No tunnels is a pool-empty / reply-path condition, not this tunnel's fault.
        assertFalse(OutboundClientMessageOneShotJob.isTunnelRelatedFailure(
                MessageStatusMessage.STATUS_SEND_FAILURE_NO_TUNNELS));
        assertFalse(OutboundClientMessageOneShotJob.isSoftSendFailure(
                MessageStatusMessage.STATUS_SEND_FAILURE_NO_TUNNELS));
    }

    // ---------- remote-destination / other statuses: NOT reported ----------

    @Test
    public void noLeaseSetIsNotTunnelRelated() {
        assertFalse(OutboundClientMessageOneShotJob.isTunnelRelatedFailure(
                MessageStatusMessage.STATUS_SEND_FAILURE_NO_LEASESET));
    }

    @Test
    public void badLeaseSetIsNotTunnelRelated() {
        assertFalse(OutboundClientMessageOneShotJob.isTunnelRelatedFailure(
                MessageStatusMessage.STATUS_SEND_FAILURE_BAD_LEASESET));
    }

    @Test
    public void unsupportedEncryptionIsNotTunnelRelated() {
        assertFalse(OutboundClientMessageOneShotJob.isTunnelRelatedFailure(
                MessageStatusMessage.STATUS_SEND_FAILURE_UNSUPPORTED_ENCRYPTION));
    }

    @Test
    public void noMetaLeasetIsNotTunnelRelated() {
        assertFalse(OutboundClientMessageOneShotJob.isTunnelRelatedFailure(
                MessageStatusMessage.STATUS_SEND_FAILURE_META_LEASESET));
    }

    @Test
    public void badSessionIsNotTunnelRelated() {
        assertFalse(OutboundClientMessageOneShotJob.isTunnelRelatedFailure(
                MessageStatusMessage.STATUS_SEND_FAILURE_BAD_SESSION));
    }

    @Test
    public void badMessageIsNotTunnelRelated() {
        assertFalse(OutboundClientMessageOneShotJob.isTunnelRelatedFailure(
                MessageStatusMessage.STATUS_SEND_FAILURE_BAD_MESSAGE));
    }

    @Test
    public void badOptionsIsNotTunnelRelated() {
        assertFalse(OutboundClientMessageOneShotJob.isTunnelRelatedFailure(
                MessageStatusMessage.STATUS_SEND_FAILURE_BAD_OPTIONS));
    }

    @Test
    public void successStatusesAreNotTunnelRelated() {
        assertFalse(OutboundClientMessageOneShotJob.isTunnelRelatedFailure(
                MessageStatusMessage.STATUS_SEND_BEST_EFFORT_SUCCESS));
        assertFalse(OutboundClientMessageOneShotJob.isTunnelRelatedFailure(
                MessageStatusMessage.STATUS_SEND_GUARANTEED_SUCCESS));
        assertFalse(OutboundClientMessageOneShotJob.isTunnelRelatedFailure(
                MessageStatusMessage.STATUS_SEND_SUCCESS_LOCAL));
    }

    @Test
    public void unknownStatusIsNotTunnelRelated() {
        assertFalse(OutboundClientMessageOneShotJob.isTunnelRelatedFailure(0));
        assertFalse(OutboundClientMessageOneShotJob.isTunnelRelatedFailure(42));
    }
}
