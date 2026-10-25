package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import org.junit.Test;

import net.i2p.i2ptunnel.TunnelController.TunnelState;

/**
 * Tests the pure destroy-gate decision {@link TunnelController#mayDestroy}.
 * A half-started tunnel still holds sessions, tasks and the private-key
 * checker, so destroy must proceed from any started state, not only RUNNING.
 *
 * @since 0.9.72+
 */
public class TunnelControllerMayDestroyTest {

    @Test
    public void destroyProceedsFromRunning() {
        assertTrue(TunnelController.mayDestroy(TunnelState.RUNNING));
    }

    @Test
    public void destroyProceedsFromHalfStartedStates() {
        assertTrue(TunnelController.mayDestroy(TunnelState.START_ON_LOAD));
        assertTrue(TunnelController.mayDestroy(TunnelState.DELAYED_START_PENDING));
        assertTrue(TunnelController.mayDestroy(TunnelState.STARTING));
    }

    @Test
    public void destroyIsNoOpWhenAlreadyStoppedOrGone() {
        assertFalse(TunnelController.mayDestroy(TunnelState.STOPPED));
        assertFalse(TunnelController.mayDestroy(TunnelState.STOPPING));
        assertFalse(TunnelController.mayDestroy(TunnelState.DESTROYING));
        assertFalse(TunnelController.mayDestroy(TunnelState.DESTROYED));
    }

    @Test
    public void decisionCoversEveryState() {
        // Exhaustive so a new TunnelState constant forces a deliberate choice here.
        for (TunnelState state : TunnelState.values()) {
            boolean expected = state == TunnelState.START_ON_LOAD ||
                               state == TunnelState.DELAYED_START_PENDING ||
                               state == TunnelState.STARTING ||
                               state == TunnelState.RUNNING;
            assertEquals(state.name(), expected, TunnelController.mayDestroy(state));
        }
    }
}
