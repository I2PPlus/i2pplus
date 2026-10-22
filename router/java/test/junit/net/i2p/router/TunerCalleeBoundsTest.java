package net.i2p.router;

import static org.junit.Assert.*;

import org.junit.Test;

import net.i2p.router.crypto.pqc.MLKEMKeyFactory;
import net.i2p.router.transport.TransportImpl;
import net.i2p.router.transport.udp.PeerState;

/**
 * A tuner param may only propose values its own setter accepts.
 *
 * <p>Several setters clamp to a band of their own - a platform default, a core
 * count, a byte budget. When the tuner's declared range is wider, the setter
 * rewrites the target back to where it already was, and the cycle reports
 * "did not take effect" forever while the console keeps offering the unreachable
 * part of the range. These tests pin the two halves together: the setter's band
 * round-trips, and the tuner's bound never leaves it.
 */
public class TunerCalleeBoundsTest {

    /** The I2CP queue bounds the tuner uses are the ones setInternalQueueSize honours. */
    @Test
    public void internalQueueBoundsRoundTrip() {
        int min = Tuner.internalQueueSizeMin();
        int max = Tuner.internalQueueSizeMax();
        assertTrue("min must not exceed max", min <= max);
        int before = Tuner.getInternalQueueSize();
        try {
            Tuner.setInternalQueueSize(min);
            assertEquals(min, Tuner.getInternalQueueSize());
            Tuner.setInternalQueueSize(max);
            assertEquals(max, Tuner.getInternalQueueSize());
        } finally {
            Tuner.setInternalQueueSize(before);
        }
    }

    /** The NTCP send pool bounds the tuner uses are the ones setSendPoolCapacity honours. */
    @Test
    public void sendPoolBoundsRoundTrip() {
        int min = TransportImpl.sendPoolCapacityMin();
        int max = TransportImpl.sendPoolCapacityMax();
        assertTrue("min must not exceed max", min <= max);
        int before = TransportImpl.getSendPoolCapacity();
        try {
            TransportImpl.setSendPoolCapacity(min);
            assertEquals(min, TransportImpl.getSendPoolCapacity());
            TransportImpl.setSendPoolCapacity(max);
            assertEquals(max, TransportImpl.getSendPoolCapacity());
        } finally {
            TransportImpl.setSendPoolCapacity(before);
        }
    }

    /**
     * PeerState's own ceiling for initial concurrent messages, and the tuner bound
     * that must stay inside it. The 257 probe pins the ceiling itself: if the setter
     * ever moves it, the tuner bound has to move with it.
     */
    @Test
    public void udpInitConcurrentMsgsStaysInsideTheSetter() {
        int before = PeerState.getInitConcurrentMsgs();
        try {
            PeerState.setInitConcurrentMsgs(256);
            assertEquals(256, PeerState.getInitConcurrentMsgs());
            PeerState.setInitConcurrentMsgs(257);
            assertEquals("the setter's ceiling is 256, so the tuner must not exceed it",
                         256, PeerState.getInitConcurrentMsgs());
            assertTrue(Tuner.initConcurrentMsgsMax() <= PeerState.getInitConcurrentMsgs());
        } finally {
            PeerState.setInitConcurrentMsgs(before);
        }
    }

    /** The ML-KEM pool is capped by its byte budget, and the tuner bound respects it. */
    @Test
    public void mlkemPreCalcStaysInsideTheByteBudget() {
        int cap = MLKEMKeyFactory.getByteCap();
        assertTrue("the byte budget must be a usable pool size", cap > 0);
        assertTrue("the tuner ceiling must not exceed the pool's own cap",
                   Tuner.mlkemPreCalcMax() <= cap);
    }

    /**
     * The throttle applies only its own validity window on the read path, so a
     * target outside it is never observable. The -1 restore puts the value back on
     * the config property, which is the untuned state. No context is needed: a
     * tuned value short-circuits the property read.
     */
    @Test
    public void throttleRejectExponentStaysInsideTheWindow() {
        int min = RouterThrottleImpl.throttleRejectExponentMin();
        int max = RouterThrottleImpl.throttleRejectExponentMax();
        try {
            RouterThrottleImpl.setThrottleRejectExponent(min);
            assertEquals(min, RouterThrottleImpl.getThrottleRejectExponent(null));
            RouterThrottleImpl.setThrottleRejectExponent(max);
            assertEquals(max, RouterThrottleImpl.getThrottleRejectExponent(null));
            RouterThrottleImpl.setThrottleRejectExponent(min - 1);
            assertEquals("the window floor is " + min + ", so the tuner must not go below it",
                         min, RouterThrottleImpl.getThrottleRejectExponent(null));
        } finally {
            RouterThrottleImpl.setThrottleRejectExponent(-1);
        }
    }
}
