package net.i2p.client.streaming.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.stat.StatManager;
import net.i2p.util.Clock;
import net.i2p.util.LogManager;

/**
 * Tests that the fast-retransmit decision is observable. Both of its outcomes
 * used to be a DEBUG line on {@code PacketLocal}, a logger the shipped
 * {@code logger.config} does not enable - in 11.7 minutes of live log the
 * packet description printed "NACKed 3 times" 46 times, the threshold at which
 * the decision runs, and the decision itself printed nothing at all. It is now
 * counted in {@code stream.fastRetransmit} and
 * {@code stream.fastRetransmitDeclined}, and the action is logged throttled at
 * INFO.
 *
 * <p>The declined counter has to read as "we chose not to act", not "every NACK
 * we ever got", so the cases that are not a decision - below the threshold,
 * already retransmitted - must record nothing, which is what the last two cases
 * pin.
 *
 * @since 0.9.72+
 */
public class PacketFastRetransmitStatTest {

    /**
     * Exercises the NACK path of a packet bound to a mocked connection.
     */
    public PacketFastRetransmitStatTest() {
    }

    /** The mocked context every packet under test is built against. */
    private I2PAppContext _ctx;
    /** The stat manager, to check both outcomes are counted. */
    private StatManager _stats;
    /** The clock, parked so "since send" is a known duration. */
    private Clock _clock;
    /** The connection the packet is sent over. */
    private Connection _con;
    /** The packet whose NACK count is driven up to and past the threshold. */
    private PacketLocal _packet;

    /**
     * Assembles a packet bound to a mocked connection with a 1s RTT and a
     * mocked stat manager. The log is the real one - {@code LogManager.getLog}
     * is final and cannot be stubbed - and the assertion here is that the
     * decision reaches the {@link StatManager} at all, which is the part that
     * was missing entirely.
     */
    @Before
    public void setUp() {
        _ctx = mock(I2PAppContext.class);
        _clock = mock(Clock.class);
        // the send is stamped at the second reading and the NACKs arrive at the
        // third, so the packet has been in flight 2s - one way round at the
        // 1s RTT the mocked connection reports
        when(_clock.now()).thenReturn(1_000_000L, 1_002_000L, 1_004_000L);
        when(_ctx.clock()).thenReturn(_clock);
        // a real LogManager, so the real Log is used: getLog() is final and
        // cannot be stubbed, and the config file is deliberately absent
        when(_ctx.getConfigDir()).thenReturn(new java.io.File("/tmp"));
        when(_ctx.getProperty(any()))
            .thenReturn("/tmp/i2p-junit-none.logger.config");
        when(_ctx.getProperty(any(), any()))
            .thenReturn("/tmp/i2p-junit-none.logger.config");
        LogManager lm = new LogManager(_ctx);
        when(_ctx.logManager()).thenReturn(lm);

        _stats = mock(StatManager.class);
        when(_ctx.statManager()).thenReturn(_stats);

        ConnectionOptions opts = mock(ConnectionOptions.class);
        when(opts.getRTT()).thenReturn(1000);
        _con = mock(Connection.class);
        when(_con.getSession()).thenReturn(mock(I2PSession.class));
        when(_con.getOptions()).thenReturn(opts);
        Connection.ResendPacketEvent evt =
            mock(Connection.ResendPacketEvent.class);
        when(_con.newResendPacketEvent(any(PacketLocal.class))).thenReturn(evt);

        Destination to = mock(Destination.class);
        Hash hash = new Hash();
        when(to.calculateHash()).thenReturn(hash);
        _packet = new PacketLocal(_ctx, to, _con);
    }

    /**
     * A packet that has been in flight one way round and NACKed
     * FAST_RETRANSMIT_THRESHOLD times is retransmitted and counted.
     */
    @Test
    public void testStormTrustedIsCounted() {
        _packet.incrementSends();
        int threshold = Connection.FAST_RETRANSMIT_THRESHOLD;
        for (int i = 0; i < threshold; i++) {
            _packet.incrementNACKs();
        }
        verify(_con, times(1)).newResendPacketEvent(any(PacketLocal.class));
        verify(_stats, times(1))
            .addRateData(eq("stream.fastRetransmit"), anyLong());
        verify(_stats, never())
            .addRateData(eq("stream.fastRetransmitDeclined"), anyLong());
    }

    /**
     * A storm the packet has not been in flight long enough to explain is
     * declined, and that decline is counted - this is the S-03 trust gate, and
     * without the counter it is invisible in the stats and the log alike.
     */
    @Test
    public void testStormNotTrustedIsCounted() {
        // never sent, so the flight time is unknown and the NACK cannot be
        // evidence about a send
        int threshold = Connection.FAST_RETRANSMIT_THRESHOLD;
        for (int i = 0; i < threshold; i++) {
            _packet.incrementNACKs();
        }
        verify(_stats, times(1))
            .addRateData(eq("stream.fastRetransmitDeclined"), anyLong());
        verify(_stats, never())
            .addRateData(eq("stream.fastRetransmit"), anyLong());
        verify(_con, never()).newResendPacketEvent(any(PacketLocal.class));
    }

    /**
     * Below the threshold there is no storm, so nothing is counted either way.
     * The old code logged a "not resending on the NACK storm" line for each of
     * these, about a storm that had not happened.
     */
    @Test
    public void testBelowThresholdRecordsNothing() {
        _packet.incrementSends();
        for (int i = 1; i < Connection.FAST_RETRANSMIT_THRESHOLD; i++) {
            _packet.incrementNACKs();
        }
        verify(_stats, never()).addRateData(anyString(), anyLong());
    }

    /**
     * The trigger is one-shot per packet: NACKs after the packet was already
     * fast-retransmitted are not further decisions, and counting them would
     * make the declined stat track the peer's NACK rate instead of our choices.
     */
    @Test
    public void testLaterNacksAfterRetransmitRecordNothing() {
        _packet.incrementSends();
        int threshold = Connection.FAST_RETRANSMIT_THRESHOLD;
        for (int i = 0; i < threshold * 3; i++) {
            _packet.incrementNACKs();
        }
        verify(_stats, times(1))
            .addRateData(eq("stream.fastRetransmit"), anyLong());
        verify(_stats, never())
            .addRateData(eq("stream.fastRetransmitDeclined"), anyLong());
        verify(_con, times(1)).newResendPacketEvent(any(PacketLocal.class));
    }
}
