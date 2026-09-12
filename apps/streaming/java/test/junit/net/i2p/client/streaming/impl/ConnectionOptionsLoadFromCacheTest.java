package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins the per-destination RTT contract that links the TCB share cache
 * ({@link TCBShare}) to the first-SYN evidence gate
 * ({@link Connection#getSynRetransmitInterval()} / {@link Connection#computeSynRetransmitInterval(int, int)}).
 *
 * <p>When a repeat connection to a recently-contacted destination is opened,
 * {@link Connection#setRemotePeer(net.i2p.data.Destination)} applies the dampened
 * cached RTT/deviation/window via {@link ConnectionOptions#loadFromCache(int, int, int)},
 * which transitions the options to STEADY state.  That state is what {@code receivedAck()}
 * reports as true without a live handshake ACK, so the SYN gate treats the cached RTT as
 * genuine path evidence and the first SYN is paced at {@code 1.5 * cachedRtt} (capped at the
 * configured initial RTO) instead of the flat global RTO.  A fresh destination stays in INIT
 * state, so it falls back to the global RTO.
 *
 * @since 0.9.72
 */
public class ConnectionOptionsLoadFromCacheTest {

    /** Fresh options (no TCB entry) are NOT SYN evidence; the gate returns the global RTO. */
    @Test
    public void testNoCacheIsNotSynEvidence() {
        ConnectionOptions opts = new ConnectionOptions();
        assertFalse("fresh connection has no RTT evidence", opts.receivedAck());
        assertEquals(ConnectionOptions.DEFAULT_INITIAL_RTT, opts.getRTT());
        int gateRtt = opts.receivedAck() ? opts.getRTT() : -1;
        assertEquals("no evidence -> flat global RTO",
                     ConnectionOptions.getInitialRTO(),
                     Connection.computeSynRetransmitInterval(gateRtt, ConnectionOptions.getInitialRTO()));
    }

    /** A cache-seeded destination reports ACK evidence and the first SYN uses 1.5x the cached RTT. */
    @Test
    public void testLoadFromCacheProvidesSynEvidence() {
        ConnectionOptions opts = new ConnectionOptions();
        opts.loadFromCache(2400, 1200, 64);
        assertTrue("TCB cache seed reports as ACK evidence", opts.receivedAck());
        assertEquals(2400, opts.getRTT());
        assertEquals(1200, opts.getRTTDev());
        assertEquals(64, opts.getWindowSize());
        int gateRtt = opts.receivedAck() ? opts.getRTT() : -1;
        int interval = Connection.computeSynRetransmitInterval(gateRtt, ConnectionOptions.getInitialRTO());
        // 1.5 * 2400 = 3600, capped at the 9000 global RTO floor
        assertEquals(3600, interval);
        assertTrue("Tuned per-dest interval is shorter than the flat global RTO",
                   interval < ConnectionOptions.getInitialRTO());
    }

    /** A cached RTT above the SYN cap is clamped to the global RTO, never lengthened. */
    @Test
    public void testSlowCachedRttCappedAtGlobalRto() {
        ConnectionOptions opts = new ConnectionOptions();
        opts.loadFromCache(7000, 4000, 64);
        assertEquals("1.5 * 7000 would be 10500, capped at 9000",
                     ConnectionOptions.getInitialRTO(),
                     Connection.computeSynRetransmitInterval(opts.getRTT(), ConnectionOptions.getInitialRTO()));
    }

    /** The seed only front-loads the first SYN: the first real sample takes over via STEADY dampening. */
    @Test
    public void testFirstMeasurementReplacesSeed() {
        ConnectionOptions opts = new ConnectionOptions();
        opts.loadFromCache(2400, 1200, 64);
        opts.updateRTT(1000);
        // STEADY: dev = 0.75*1200 + 0.25*|1000-2400| = 1250; srtt = 0.875*2400 + 0.125*1000 = 2225
        assertEquals(1250, opts.getRTTDev());
        assertEquals(2225, opts.getRTT());
        assertTrue("seeded value no longer dominates after a real measurement", opts.getRTT() < 2400);
    }
}
