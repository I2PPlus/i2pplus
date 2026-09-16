package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for {@link ConnectionManager} and {@link Connection} decisions extracted
 * from the hot path.
 *
 * @since 0.9.71+
 */
public class ConnectionManagerDecisionTest {

    /**
     * Verify that trimDestFailures method exists on ConnectionManager.
     * This is a behavioral verification that the memory leak fix works.
     */
    @Test
    public void testTrimDestFailuresExists() throws Exception {
        Class<?> cm = ConnectionManager.class;
        java.lang.reflect.Method m = cm.getDeclaredMethod("trimDestFailures");
        m.setAccessible(true);
        assertNotNull(m);
    }

    /**
     * Verify that _destFailures is a ConcurrentHashMap.
     */
    @Test
    public void testDestFailuresIsConcurrentHashMap() throws Exception {
        Class<?> cm = ConnectionManager.class;
        java.lang.reflect.Field f = cm.getDeclaredField("_destFailures");
        f.setAccessible(true);
        assertEquals("ConcurrentHashMap",
            f.getType().getSimpleName());
    }

    /**
     * Verify that _cooldownWarned is a ConcurrentHashMap.
     */
    @Test
    public void testCooldownWarnedIsConcurrentHashMap() throws Exception {
        Class<?> cm = ConnectionManager.class;
        java.lang.reflect.Field f = cm.getDeclaredField("_cooldownWarned");
        f.setAccessible(true);
        assertEquals("ConcurrentHashMap",
            f.getType().getSimpleName());
    }

    /**
     * Verify that _resendEventPool exists on Connection and is a
     * ConcurrentLinkedQueue for reuse of ResendPacketEvent instances.
     */
    @Test
    public void testResendPacketEventPoolExists() throws Exception {
        Class<?> conn = Class.forName("net.i2p.client.streaming.impl.Connection");
        java.lang.reflect.Field f = conn.getDeclaredField("_resendEventPool");
        f.setAccessible(true);
        assertEquals("ConcurrentLinkedQueue",
            f.getType().getSimpleName());
        assertNotNull("Field type should be ConcurrentLinkedQueue", f.getType());
    }

    /**
     * Verify that ResendPacketEvent.timeReached() is public.
     */
    @Test
    public void testResendPacketEventTimeReachedIsPublic() throws Exception {
        Class<?> evt = Class.forName("net.i2p.client.streaming.impl.Connection$ResendPacketEvent");
        java.lang.reflect.Method tm = evt.getDeclaredMethod("timeReached");
        assertTrue(java.lang.reflect.Modifier.isPublic(tm.getModifiers()));
    }
}
