package net.i2p.client.streaming.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.SendMessageOptions;
import net.i2p.data.ByteArray;
import net.i2p.data.Destination;
import net.i2p.stat.Rate;
import net.i2p.stat.RateConstants;
import net.i2p.util.SimpleTimer2;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * The send-size telemetry that {@code PacketQueue.enqueue()} records.
 *
 * <p>The values are sampled every sixteenth send to keep the hot path cheap, and
 * the Tuner reads them two ways: {@code getAverageValue()} as a message size in
 * bytes, compared against thresholds like "a large message is over 1000" and
 * "a small message is under 200", and the event counts as a retransmission
 * ratio. Scaling the recorded value by the sampling period multiplied the first
 * by 16 while leaving the second alone, so an 82-byte packet read as 1312: every
 * stream looked like it was sending large messages, and the small-message
 * branch of three Tuner parameters became unreachable.
 *
 * <p>A uniform sample has the same average as the full series, so the value and
 * the event duration go in unscaled, and the ratio stays unbiased because both
 * counters are sampled at the same rate.
 *
 * <p>The tests also pin which transmissions the duplicate counter accepts,
 * because the Tuner's loss-rate thresholds are calibrated to that gate.
 *
 * @since 0.9.72+
 */
public class SendSizeTelemetryTest {

    /** The sampling period, spelled out so the assertions say what they check. */
    private static final int PERIOD = 16;

    /** Lifetime reported by the test packets, in ms. */
    private static final long LIFETIME = 1234L;

    /**
     * Payload size giving a serialized packet that is a small message by the
     * Tuner's 200-byte line, and whose 16x inflated reading clears its
     * 1000-byte large-message line.
     */
    private static final int PAYLOAD = 60;

    private I2PAppContext _ctx;
    private SimpleTimer2 _timer;
    private PacketQueue _queue;
    /** The session the packets send through, with the send size captured. */
    private I2PSession _session;
    /** Size of the last packet handed to the mocked session. */
    private int _sentSize;

    @Before
    public void setUp() throws I2PSessionException {
        _ctx = I2PAppContext.getGlobalContext();
        createTelemetryRates(_ctx);
        _timer = new SimpleTimer2(_ctx);
        _queue = new PacketQueue(_ctx, _timer);
        _session = mock(I2PSession.class);
        when(_session.sendMessage(any(Destination.class), any(byte[].class), anyInt(), anyInt(),
                                  anyInt(), anyInt(), anyInt(), any(SendMessageOptions.class)))
            .thenAnswer(new Answer<Boolean>() {
                @Override
                public Boolean answer(InvocationOnMock inv) {
                    _sentSize = inv.getArgument(3);
                    return Boolean.TRUE;
                }
            });
    }

    @After
    public void tearDown() {
        _queue.close();
        _timer.stop();
    }

    /**
     * One sampling period of identical sends records exactly one sample, and its
     * value is the size the session was given - not that size times the period.
     * The event duration is the packet's lifetime, likewise unscaled.
     */
    @Test
    public void oneSampleCarriesTheTrueSizeAndLifetime() {
        Rate rate = rate("stream.con.sendMessageSize");
        long events0 = rate.getCurrentEventCount();
        double value0 = rate.getCurrentTotalValue();
        long time0 = rate.getCurrentTotalEventTime();

        for (int i = 0; i < PERIOD; i++) {
            _queue.enqueue(packet());
        }

        assertEquals("one sample per " + PERIOD + " sends",
                     events0 + 1, rate.getCurrentEventCount());
        long recorded = (long) (rate.getCurrentTotalValue() - value0);
        assertEquals("the recorded size is the size that went out, not " + PERIOD + "x it",
                     _sentSize, recorded);
        assertEquals("the recorded event time is the packet lifetime, not " + PERIOD + "x it",
                     LIFETIME, rate.getCurrentTotalEventTime() - time0);
        // The consequence, pinned: this packet is a small message by the Tuner's
        // own line (under 200 bytes), and the 16x inflated reading cleared its
        // "large message" line of 1000, which is what made the small-message
        // branch of three parameters unreachable.
        assertTrue("the serialized packet must be a small message, was " + _sentSize,
                   _sentSize < 200);
        assertTrue("the inflated value must clear the Tuner's 1000-byte line, was "
                   + (_sentSize * PERIOD), _sentSize * PERIOD > 1000);
    }

    /**
     * The retransmission ratio the Tuner computes from the two event counts
     * survives the sampling: each counter advances at 1/16 of its own
     * population, so the ratio of the samples is the ratio of the sends, and
     * the average of the sampled sizes is the average size of a send.
     */
    @Test
    public void theLossRateRatioSurvivesTheSampling() {
        Rate sends = rate("stream.con.sendMessageSize");
        Rate dups = rate("stream.con.sendDuplicateSize");
        long sends0 = sends.getCurrentEventCount();
        long dups0 = dups.getCurrentEventCount();
        double value0 = sends.getCurrentTotalValue();

        int total = 10 * PERIOD;
        int duplicated = total / 2;
        long sentBytes = 0;
        for (int i = 0; i < total; i++) {
            FixedLifetimePacket p = packet();
            if (i < duplicated) {
                // The gate is numSends > 1, and enqueue() increments the count
                // after the send, so a packet is a duplicate to the counter from
                // its third transmission on.
                p.incrementSends();
                p.incrementSends();
            }
            _queue.enqueue(p);
            sentBytes += _sentSize;
        }

        long sendEvents = sends.getCurrentEventCount() - sends0;
        long dupEvents = dups.getCurrentEventCount() - dups0;
        assertEquals(total / PERIOD, sendEvents);
        assertEquals(duplicated / PERIOD, dupEvents);
        assertEquals("the sampled ratio must track the real one",
                     (double) duplicated / total, (double) dupEvents / sendEvents, 0.001);

        double avgSize = (sends.getCurrentTotalValue() - value0) / sendEvents;
        assertEquals("the average of the samples is the real message size",
                     (double) sentBytes / total, avgSize, 1.0);
        assertTrue("the average must stay a small message size, was " + avgSize,
                   avgSize < 200);
    }

    /**
     * The duplicate gate, pinned. {@code enqueue()} counts a transmission as a
     * duplicate only from its third send on, because {@code incrementSends()}
     * runs after a successful send and the gate is {@code numSends > 1}. That is
     * deliberate: {@code Tuner.streamingLossRate()} documents the resulting rate
     * as a conservative estimate, and its {@code STREAM_LOSSY_RATE} threshold is
     * calibrated against that conservatism. This test exists so loosening the
     * gate to {@code >= 1} fails here with the reason, instead of silently
     * retuning every streaming parameter that reads the loss rate.
     */
    @Test
    public void theDuplicateGateCountsFromTheThirdTransmission() {
        Rate dups = rate("stream.con.sendDuplicateSize");
        long dups0 = dups.getCurrentEventCount();

        // One send of each packet, resent once: a retransmission in the plain
        // sense, and invisible to the counter.
        for (int i = 0; i < PERIOD; i++) {
            FixedLifetimePacket p = packet();
            p.incrementSends();
            _queue.enqueue(p);
        }
        assertEquals("a packet resent exactly once is not a duplicate event",
                     dups0, dups.getCurrentEventCount());

        // The second retransmission: counted.
        for (int i = 0; i < PERIOD; i++) {
            FixedLifetimePacket p = packet();
            p.incrementSends();
            p.incrementSends();
            _queue.enqueue(p);
        }
        assertEquals("the third transmission is a duplicate event",
                     dups0 + 1, dups.getCurrentEventCount());
    }

    /**
     * The rate the emitter writes to.
     *
     * @param name the rate stat's name
     * @return its ONE_MINUTE rate
     */
    private Rate rate(String name) {
        return _ctx.statManager().getRate(name).getRate(RateConstants.ONE_MINUTE);
    }

    /**
     * A packet with a fixed payload and a fixed reported lifetime.
     *
     * @return a packet ready to enqueue
     */
    private FixedLifetimePacket packet() {
        FixedLifetimePacket p = new FixedLifetimePacket(_ctx, _session);
        ByteArray ba = new ByteArray(new byte[PAYLOAD]);
        for (int i = 0; i < PAYLOAD; i++) {
            ba.getData()[i] = (byte) i;
        }
        ba.setValid(PAYLOAD);
        p.setPayload(ba);
        return p;
    }

    /**
     * A packet reporting a fixed lifetime, so the recorded event duration is a
     * known value rather than whatever the clock says.
     */
    private static class FixedLifetimePacket extends PacketLocal {

        /**
         * @param ctx the application context
         * @param session the session the packet sends through
         */
        FixedLifetimePacket(I2PAppContext ctx, I2PSession session) {
            super(ctx, new Destination(), session);
        }

        @Override
        public long getLifetime() {
            return LIFETIME;
        }
    }

    /**
     * {@code enqueue()} records the send sizes and the send time, so create the
     * rates and keep the test from logging about missing ones.
     *
     * @param ctx the context whose stat manager records the streaming rates
     */
    private static void createTelemetryRates(I2PAppContext ctx) {
        long[] periods = new long[] {RateConstants.ONE_MINUTE, RateConstants.ONE_HOUR};
        String[] names = new String[] {"stream.con.sendMessageSize", "stream.con.sendDuplicateSize",
                                       "stream.con.sendMessageTime"};
        for (String name : names) {
            if (ctx.statManager().getRate(name) == null) {
                ctx.statManager().createRateStat(name, "probe", "Stream", periods);
            }
        }
    }
}
