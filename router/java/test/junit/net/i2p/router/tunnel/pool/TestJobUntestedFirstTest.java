package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Test;

import net.i2p.router.TunnelInfo;

/**
 * Tests for the untested-first predicate: a tunnel that has never been
 * measured is recognised as a first-test candidate no matter what status it
 * carries, so it is queued ahead of retests of already-measured tunnels.
 *
 * @since 0.9.71+
 */
public class TestJobUntestedFirstTest {

    private static TunnelInfo withLatency(int lastLatency) {
        TunnelInfo info = mock(TunnelInfo.class);
        when(info.getLastLatency()).thenReturn(lastLatency);
        return info;
    }

    // ---------------- firstReadingPending ----------------

    @Test
    public void testNeverMeasuredIsPending() {
        assertTrue(TestJob.firstReadingPending(withLatency(-1)));
    }

    @Test
    public void testMeasuredIsNotPending() {
        assertFalse(TestJob.firstReadingPending(withLatency(0)));
        assertFalse(TestJob.firstReadingPending(withLatency(120)));
    }

    @Test
    public void testNullIsNotPending() {
        assertFalse(TestJob.firstReadingPending(null));
    }
}
