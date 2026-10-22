package net.i2p.router;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

import org.junit.Assume;
import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.crypto.EncType;
import net.i2p.router.crypto.pqc.MLKEMKeyFactory;
import net.i2p.router.transport.TransportImpl;
import net.i2p.router.transport.udp.PeerState;

/**
 * A tuner param may only propose values its own setter accepts, and it must verify
 * against the place its apply actually wrote.
 *
 * <p>Several setters clamp to a band of their own - a platform default, a core
 * count, a byte budget, an admission gate. When the tuner's declared range is
 * wider, the setter rewrites the target back to where it already was, and the
 * cycle reports "did not take effect" forever while the console keeps offering the
 * unreachable part of the range. Other params push a value into a component that
 * offers no reading back, and their verify read a config property the tuning path
 * never writes - the same unreadable result from the other side. These tests pin
 * both halves together: the setter's band round-trips, the tuner's bound never
 * leaves it, and apply and verify share one source.
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

    /**
     * The ML-KEM pool is capped by its byte budget rather than its hard cap, and the
     * tuner ceiling stays inside it. The setter is exercised as well as the bound:
     * the tuner's own ceiling has to survive {@code setMinSize} untouched, and a
     * value above the budget has to come back capped, which is what made the param
     * look stuck.
     */
    @Test
    public void mlkemPreCalcStaysInsideTheByteBudget() {
        int cap = MLKEMKeyFactory.getByteCap();
        assertTrue("the byte budget must be a usable pool size", cap > 0);
        assertTrue("the tuner ceiling must not exceed the pool's own cap",
                   Tuner.mlkemPreCalcMax() <= cap);
        MLKEMKeyFactory factory = new MLKEMKeyFactory(I2PAppContext.getGlobalContext(),
                                                      EncType.MLKEM768_X25519_INT);
        try {
            factory.setMinSize(Tuner.mlkemPreCalcMax());
            assertEquals("the tuner ceiling must survive the setter's clamp",
                         Tuner.mlkemPreCalcMax(), factory.getMinSize());
            factory.setMinSize(cap + 1);
            assertEquals("the setter caps at the byte budget", cap, factory.getMinSize());
        } finally {
            factory.shutdown();
        }
    }

    /**
     * The throttle applies only its own validity window on the read path, so a
     * target outside it is never observable. The -1 restore puts the value back on
     * the config property, which is the untuned state.
     */
    @Test
    public void throttleRejectExponentStaysInsideTheWindow() {
        int min = RouterThrottleImpl.throttleRejectExponentMin();
        int max = RouterThrottleImpl.throttleRejectExponentMax();
        // A real context, not null: the property read is only skipped while a tuned
        // value is in force, and a test must not depend on that staying true.
        RouterContext ctx = mock(RouterContext.class);
        try {
            RouterThrottleImpl.setThrottleRejectExponent(min);
            assertEquals(min, RouterThrottleImpl.getThrottleRejectExponent(ctx));
            RouterThrottleImpl.setThrottleRejectExponent(max);
            assertEquals(max, RouterThrottleImpl.getThrottleRejectExponent(ctx));
            RouterThrottleImpl.setThrottleRejectExponent(min - 1);
            assertEquals("the window floor is " + min + ", so the tuner must not go below it",
                         min, RouterThrottleImpl.getThrottleRejectExponent(ctx));
        } finally {
            RouterThrottleImpl.setThrottleRejectExponent(-1);
        }
    }

    /**
     * The client runner pool is floored by the i2ptunnel admission gate, and the
     * router cannot compile against that constant, so the tuner's floor is read
     * back reflectively. Skipped when i2ptunnel is not on the classpath.
     */
    @Test
    public void clientRunnerFloorStaysAtTheAdmissionGate() {
        int gate;
        try {
            gate = Class.forName("net.i2p.i2ptunnel.I2PTunnelClientBase")
                        .getField("DEFAULT_MAX_CONNECTIONS").getInt(null);
        } catch (ReflectiveOperationException e) {
            Assume.assumeNoException("i2ptunnel is not on this test classpath", e);
            return;
        }
        assertTrue("the tuner floor " + Tuner.clientRunnerMaxMin() + " must not be below the " +
                   "gate the setter enforces (" + gate + ')',
                   Tuner.clientRunnerMaxMin() >= gate);
    }

    /**
     * The decision the params with no callee getter share: an applied value wins,
     * and until one is applied the config-or-default reading is used. Zero is a
     * real value, not the "nothing applied" sentinel.
     */
    @Test
    public void tunedValueWinsUntilNothingIsApplied() {
        assertEquals("nothing applied yet -> the config reading", 5, Tuner.tunedOr(-1, 5));
        assertEquals("an applied value wins", 7, Tuner.tunedOr(7, 5));
        assertEquals("zero is a value, not the sentinel", 0, Tuner.tunedOr(0, 5));
    }

    /**
     * Apply and verify share one field for the params whose consumer reads a Tuner
     * static. Both of these verified against a config property the tuning path never
     * writes, so every cycle reported a value that could not move; the setters below
     * are the only way in, and the getters the consumers use are the only way out.
     */
    @Test
    public void dispatchAgeAndHandlerPriorityHaveOneSource() {
        int age = Tuner.getMaxDispatchAgeMs();
        try {
            Tuner.setMaxDispatchAgeMs(age + 500);
            assertEquals(age + 500, Tuner.getMaxDispatchAgeMs());
        } finally {
            Tuner.setMaxDispatchAgeMs(age);
        }
        int priority = Tuner.getHandlerThreadPriority();
        try {
            Tuner.setHandlerThreadPriority(priority + 1);
            assertEquals(priority + 1, Tuner.getHandlerThreadPriority());
        } finally {
            Tuner.setHandlerThreadPriority(priority);
        }
    }
}
