package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.streaming.IncomingConnectionFilter;
import net.i2p.data.Destination;
import net.i2p.data.Hash;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests that {@link ConnectionManager#shutdown()} takes permanent ownership of
 * its BanExpiry sweeper.
 *
 * <p>The sweeper re-arms itself from inside timeReached(), and
 * {@code SimpleTimer2.TimedEvent.schedule()} unconditionally clears the
 * cancel-after-run flag — so a bare cancel() racing an in-flight sweep would be
 * silently undone and the event would keep firing against a shut-down manager.
 * The pin here is that stop() is sticky: even a sweep that somehow runs after
 * shutdown must not schedule itself again.
 *
 * @since 0.9.71+
 */
public class ConnectionManagerShutdownTest {

    private ConnectionManager _manager;

    @Before
    public void setUp() {
        I2PSession session = mock(I2PSession.class);
        Destination myDest = mock(Destination.class);
        when(myDest.calculateHash()).thenReturn(new Hash(new byte[32]));
        when(session.getMyDestination()).thenReturn(myDest);
        _manager = new ConnectionManager(I2PAppContext.getGlobalContext(), session,
                                         new ConnectionOptions(), IncomingConnectionFilter.ALLOW);
    }

    @After
    public void tearDown() {
        if (_manager != null) {
            _manager.shutdown();
            _manager = null;
        }
    }

    /** The private BanExpiry instance the manager owns. */
    private Object sweeper() throws Exception {
        Field f = ConnectionManager.class.getDeclaredField("_banExpiry");
        f.setAccessible(true);
        return f.get(_manager);
    }

    private static Object invokeNoArg(Object target, String name) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(target);
    }

    /**
     * Current SimpleTimer2.TimedEvent state, read by name because the enum is
     * private to SimpleTimer2. Expected values: IDLE, SCHEDULED, RUNNING, CANCELLED.
     */
    private static String timerState(Object event) throws Exception {
        for (Class<?> c = event.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("_state");
                f.setAccessible(true);
                Object v = f.get(event);
                return v == null ? "null" : v.toString();
            } catch (NoSuchFieldException nsfe) {
                // walk up to TimedEvent
            }
        }
        throw new NoSuchFieldException("_state");
    }

    @Test
    public void testSweeperRunsBeforeShutdownAndIsStoppedByIt() throws Exception {
        Object sweeper = sweeper();
        assertNotNull("manager must own its sweeper", sweeper);
        assertEquals("sweeper must be armed before shutdown",
                     Boolean.FALSE, invokeNoArg(sweeper, "isStopped"));
        assertEquals("pending sweep expected before shutdown",
                     "SCHEDULED", timerState(sweeper));

        _manager.shutdown();

        assertEquals("shutdown must stop the sweeper",
                     Boolean.TRUE, invokeNoArg(sweeper, "isStopped"));
        assertEquals("pending sweep must be cancelled",
                     "CANCELLED", timerState(sweeper));
    }

    @Test
    public void testSweepAfterShutdownDoesNotRearm() throws Exception {
        Object sweeper = sweeper();
        _manager.shutdown();
        assertEquals("CANCELLED", timerState(sweeper));

        // Even if a sweep was already in flight when shutdown() landed, it must
        // not schedule the next one: the cancelled state would otherwise flip
        // back to SCHEDULED and the event would outlive the manager.
        invokeNoArg(sweeper, "timeReached");

        assertEquals("a post-shutdown sweep must not re-arm the event",
                     "CANCELLED", timerState(sweeper));
        assertEquals(Boolean.TRUE, invokeNoArg(sweeper, "isStopped"));
    }

    @Test
    public void testShutdownIsIdempotent() throws Exception {
        _manager.shutdown();
        Object sweeper = sweeper();
        _manager.shutdown();
        assertEquals(Boolean.TRUE, invokeNoArg(sweeper, "isStopped"));
        assertEquals("CANCELLED", timerState(sweeper));
    }
}
