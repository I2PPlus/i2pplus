package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import net.i2p.client.I2PSession;

import org.junit.Test;

/**
 * Tests the snapshot contract of {@link I2PTunnel#getTasks()} and
 * {@link I2PTunnel#getSessions()}: both are documented to return an
 * unmodifiable, non-null copy, so a caller can neither reach nor reorder
 * the tunnel's live collections through the returned list.
 *
 * @since 0.9.71+
 */
public class TunnelSnapshotListTest {

    private static I2PTunnel newTunnel() {
        // The no-arg constructor uses "-nocli -die" and returns immediately.
        return new I2PTunnel();
    }

    @Test
    public void testTasksNonNullWhenEmpty() {
        List<I2PTunnelTask> tasks = newTunnel().getTasks();
        assertNotNull(tasks);
        assertTrue(tasks.isEmpty());
    }

    @Test
    public void testSessionsNonNullWhenEmpty() {
        List<I2PSession> sessions = newTunnel().getSessions();
        assertNotNull(sessions);
        assertTrue(sessions.isEmpty());
    }

    @Test
    public void testEmptyTasksListIsUnmodifiable() {
        try {
            newTunnel().getTasks().add(null);
            fail("getTasks() must return an unmodifiable list");
        } catch (UnsupportedOperationException expected) {
            // documented contract
        }
    }

    @Test
    public void testEmptySessionsListIsUnmodifiable() {
        try {
            newTunnel().getSessions().add(null);
            fail("getSessions() must return an unmodifiable list");
        } catch (UnsupportedOperationException expected) {
            // documented contract
        }
    }

    @Test
    public void testPopulatedSessionsListIsUnmodifiable() {
        I2PTunnel tunnel = newTunnel();
        I2PSession sess = newSession();
        tunnel.addSession(sess);
        List<I2PSession> sessions = tunnel.getSessions();
        assertEquals(1, sessions.size());
        assertSame(sess, sessions.get(0));
        try {
            sessions.clear();
            fail("getSessions() must return an unmodifiable list");
        } catch (UnsupportedOperationException expected) {
            // documented contract
        }
        try {
            sessions.remove(0);
            fail("getSessions() must return an unmodifiable list");
        } catch (UnsupportedOperationException expected) {
            // documented contract
        }
    }

    /**
     * The returned list is a copy taken at call time: a session added after the
     * call must not appear in the already-returned list, and removing it from the
     * tunnel must not shrink a snapshot taken while it was present.
     */
    @Test
    public void testSessionListIsASnapshot() {
        I2PTunnel tunnel = newTunnel();
        List<I2PSession> emptySnapshot = tunnel.getSessions();
        I2PSession sess = newSession();
        tunnel.addSession(sess);
        assertTrue("snapshot must not see later additions", emptySnapshot.isEmpty());

        List<I2PSession> populatedSnapshot = tunnel.getSessions();
        assertEquals(1, populatedSnapshot.size());
        tunnel.removeSession(sess);
        assertEquals("snapshot must not shrink on later removal", 1, populatedSnapshot.size());
        assertTrue(tunnel.getSessions().isEmpty());
    }

    /** The session set deduplicates, so the snapshot has one entry per session. */
    @Test
    public void testSessionListDeduplicates() {
        I2PTunnel tunnel = newTunnel();
        I2PSession sess = newSession();
        tunnel.addSession(sess);
        tunnel.addSession(sess);
        assertEquals(1, tunnel.getSessions().size());
    }

    /** A null session is ignored rather than added. */
    @Test
    public void testNullSessionNotAdded() {
        I2PTunnel tunnel = newTunnel();
        tunnel.addSession(null);
        assertTrue(tunnel.getSessions().isEmpty());
    }

    private static I2PSession newSession() {
        return (I2PSession) Proxy.newProxyInstance(I2PSession.class.getClassLoader(),
                new Class<?>[]{I2PSession.class}, new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method m, Object[] args) {
                        String name = m.getName();
                        if ("hashCode".equals(name)) {return Integer.valueOf(System.identityHashCode(proxy));}
                        if ("equals".equals(name)) {return Boolean.valueOf(proxy == args[0]);}
                        if ("toString".equals(name)) {return "mockI2PSession";}
                        Class<?> rt = m.getReturnType();
                        if (rt == boolean.class) {return Boolean.FALSE;}
                        if (rt == int.class) {return Integer.valueOf(0);}
                        if (rt == long.class) {return Long.valueOf(0);}
                        return null;
                    }
                });
    }
}
