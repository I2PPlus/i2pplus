package net.i2p.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.i2p.router.transport.udp.PeerState;

import org.junit.Test;

/**
 * Tests the pure predicate {@link Tuner#transportCongested}, which replaced the
 * "is {@code udp.congestionOccurred} greater than zero" test at all five of its
 * call sites in the Tuner.
 *
 * <p>{@code PeerState.messageRetransmitted()} records that stat as
 * {@code _sendWindowBytes.get()} - the peer's congestion window in bytes at the
 * moment of the event - so its average is a size and can never be zero while
 * any event fires. The old test was therefore a presence test: true on any
 * router that retransmits at all. Live here, 141 events/min at an average
 * window of 11,574 bytes, while the average window across all peers was a
 * healthy 39,534 bytes - and the always-true flag held
 * {@code tunnel.build.maxConcurrent} on its hard-decrease branch every pass.
 *
 * @since 0.9.72+
 */
public class TunerTransportCongestionTest {

    /**
     * The congestion-threshold cases. Every case calls the static predicate, so
     * the instance holds no state and needs no transport or peer state.
     */
    public TunerTransportCongestionTest() {
    }

    /**
     * The threshold is the RFC 5681 initial window {@code PeerState} gives a
     * fresh peer - three MTUs - pinned to its source so it cannot drift into a
     * bare magic number.
     */
    @Test
    public void thresholdIsTheInitialCongestionWindow() {
        assertEquals(3 * PeerState.DEFAULT_MTU, Tuner.CONGESTED_CWIN_BYTES);
    }

    /**
     * The defect. A router retransmitting at a healthy window is not congested.
     * The live figures here - an average window of 11,574 bytes at congestion
     * against a 39,534 byte average window across peers - read as congested
     * under the old "greater than zero" test and do not now.
     */
    @Test
    public void retransmittingAtAHealthyWindowIsNotCongestion() {
        assertFalse("live average window at congestion",
                    Tuner.transportCongested(11574));
        assertFalse("live average window across peers",
                    Tuner.transportCongested(39534));
        assertFalse("peak average window",
                    Tuner.transportCongested(94702));
    }

    /**
     * A congestion event that arrives with the window shut down to its initial
     * size is the transport actually collapsing, which is what the flag is for.
     */
    @Test
    public void collapsedWindowIsCongestion() {
        assertTrue(Tuner.transportCongested(Tuner.CONGESTED_CWIN_BYTES));
        assertTrue(Tuner.transportCongested(3 * 1280));
        assertTrue(Tuner.transportCongested(1484));
        assertTrue(Tuner.transportCongested(1));
    }

    /**
     * The boundary sits on the threshold, derived from the Tuner's own constant
     * so the case cannot drift from it.
     */
    @Test
    public void boundaryIsTheThresholdItself() {
        assertTrue(Tuner.transportCongested(Tuner.CONGESTED_CWIN_BYTES));
        assertFalse(Tuner.transportCongested(Tuner.CONGESTED_CWIN_BYTES + 1));
    }

    /**
     * No event has fired, or the stat has no window recorded: neither calls the
     * transport congested, so a router with no UDP traffic is not pushed onto
     * the congestion branches.
     */
    @Test
    public void unmeasuredIsNotCongestion() {
        assertFalse(Tuner.transportCongested(Double.NaN));
        assertFalse(Tuner.transportCongested(0));
        assertFalse(Tuner.transportCongested(-1));
    }
}
