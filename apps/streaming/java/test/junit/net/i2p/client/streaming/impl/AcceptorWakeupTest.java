package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.net.ConnectException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.streaming.IncomingConnectionFilter;
import net.i2p.client.streaming.RouterRestartException;
import net.i2p.data.Destination;
import net.i2p.data.Hash;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests what happens to a thread parked in {@link ConnectionHandler#accept} when
 * the handler stops. Nothing is ever offered to the result queue on shutdown -
 * the only producer is an accept worker finishing a connection - so an untimed
 * accept() has to notice the handler went inactive on its own, and it has to
 * hand back or reset what is already queued while doing so.
 *
 * @since 0.9.72+
 */
public class AcceptorWakeupTest {

    /** How long to wait for a parked accept() to come back. */
    private static final int WAKEUP_LIMIT = 5000;

    /** Owns the handler under test. */
    private ConnectionManager _manager;

    /** The accept side of that manager. */
    private ConnectionHandler _handler;

    @Before
    public void setUp() {
        I2PSession session = mock(I2PSession.class);
        Destination myDest = mock(Destination.class);
        when(myDest.calculateHash()).thenReturn(new Hash(new byte[32]));
        when(session.getMyDestination()).thenReturn(myDest);
        _manager = new ConnectionManager(I2PAppContext.getGlobalContext(), session,
                                         new ConnectionOptions(), IncomingConnectionFilter.ALLOW);
        _handler = _manager.getConnectionHandler();
    }

    @After
    public void tearDown() {
        if (_handler != null)
            _handler.setActive(false);
        if (_manager != null)
            _manager.shutdown();
    }

    /**
     * The whole point: an untimed accept() blocked with nothing to do must come
     * back with a ConnectException when the handler is deactivated, not stay
     * parked until a connection that will never arrive.
     *
     * @throws Exception if the accept thread cannot be started or joined
     */
    @Test(timeout = 20000)
    public void testUntimedAcceptWakesWhenHandlerStops() throws Exception {
        _handler.setActive(true);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread waiter = parkAccept(0, null, thrown);
        Thread.sleep(200);
        _handler.setActive(false);
        waiter.join(WAKEUP_LIMIT);
        assertFalse("accept(0) was still parked after the handler stopped", waiter.isAlive());
        assertTrue("expected a ConnectException, got " + thrown.get(),
                   thrown.get() instanceof ConnectException);
    }

    /**
     * Same, for a router restart: the waiter must come back with the restart
     * exception so the hosted server can tell the difference.
     *
     * @throws Exception if the accept thread cannot be started or joined
     */
    @Test(timeout = 20000)
    public void testUntimedAcceptWakesOnRestartPending() throws Exception {
        _handler.setActive(true);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread waiter = parkAccept(0, null, thrown);
        Thread.sleep(200);
        _handler.setRestartPending();
        waiter.join(WAKEUP_LIMIT);
        assertFalse("accept(0) was still parked after the restart was flagged", waiter.isAlive());
        assertTrue("expected a RouterRestartException, got " + thrown.get(),
                   thrown.get() instanceof RouterRestartException);
    }

    /**
     * The bounded poll must not turn into a premature exit: with the handler
     * active and nothing queued, accept(0) keeps waiting, and it still returns
     * the connection when a worker finally produces one.
     *
     * @throws Exception if the accept thread cannot be started or joined
     */
    @Test(timeout = 20000)
    public void testUntimedAcceptStillWaitsForWork() throws Exception {
        _handler.setActive(true);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<Connection> got = new AtomicReference<>();
        Thread waiter = parkAccept(0, got, thrown);
        // longer than the poll interval, so a poll that gave up early would show
        Thread.sleep(2500);
        assertTrue("accept(0) returned on its own: " + got.get() + ' ' + thrown.get(),
                   waiter.isAlive());
        Connection con = mock(Connection.class);
        resultQueue().offer(con);
        waiter.join(WAKEUP_LIMIT);
        assertFalse("accept(0) never took the queued connection", waiter.isAlive());
        assertSame(con, got.get());
        assertNull(thrown.get());
    }

    /**
     * A connection a worker produced before the shutdown is still handed over by
     * an accept() that finds the handler inactive.
     *
     * @throws Exception not expected; the queued connection is handed back
     */
    @Test(timeout = 20000)
    public void testInactiveAcceptHandsBackAQueuedConnection() throws Exception {
        Connection con = mock(Connection.class);
        resultQueue().offer(con);
        assertSame(con, _handler.accept(0));
    }

    /**
     * The SYNs left on the accept queue when the handler goes inactive are reset
     * and their payloads released. Dropping them without releasing leaks a
     * pooled payload buffer per queued SYN.
     *
     * @throws Exception not expected; the refusal is caught in the body
     */
    @Test(timeout = 20000)
    public void testInactiveAcceptResetsAndReleasesQueuedSyns() throws Exception {
        Packet syn = mock(Packet.class);
        // unverifiable -> sendReset() gives up without touching the packet queue
        when(syn.verifySignature(any(I2PAppContext.class), any(byte[].class))).thenReturn(false);
        synQueue().offer(syn);
        try {
            _handler.accept(0);
            fail("An inactive handler must refuse the accept");
        } catch (ConnectException e) {
            // expected
        }
        verify(syn).releasePayload();
    }

    /**
     * Starts a thread parked in accept().
     *
     * @param timeoutMs the timeout to pass to accept()
     * @param result where to store a connection, if one is returned, may be null
     * @param thrown where to store anything the call throws
     * @return the started thread
     */
    private Thread parkAccept(final long timeoutMs, final AtomicReference<Connection> result,
                              final AtomicReference<Throwable> thrown) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Connection con = _handler.accept(timeoutMs);
                    if (result != null)
                        result.set(con);
                } catch (Throwable e) {
                    thrown.set(e);
                }
            }
        }, "accept-waiter");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * The private queue of connections waiting for a caller.
     *
     * @return the handler's result queue
     * @throws NoSuchFieldException if the field was renamed
     * @throws IllegalAccessException if the field is not readable
     */
    @SuppressWarnings("unchecked")
    private LinkedBlockingQueue<Connection> resultQueue() throws NoSuchFieldException,
                                                                IllegalAccessException {
        Field f = ConnectionHandler.class.getDeclaredField("_resultQueue");
        f.setAccessible(true);
        return (LinkedBlockingQueue<Connection>) f.get(_handler);
    }

    /**
     * The private queue of SYNs waiting for an accept worker.
     *
     * @return the handler's SYN queue
     * @throws NoSuchFieldException if the field was renamed
     * @throws IllegalAccessException if the field is not readable
     */
    @SuppressWarnings("unchecked")
    private LinkedBlockingDeque<Packet> synQueue() throws NoSuchFieldException,
                                                         IllegalAccessException {
        Field f = ConnectionHandler.class.getDeclaredField("_synQueue");
        f.setAccessible(true);
        return (LinkedBlockingDeque<Packet>) f.get(_handler);
    }
}
