package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Verifies the worker-pool/admission-gate coupling in
 * {@link TunnelControllerGroup}{@code .setClientRunnerMax}: the shared client worker
 * pool must never be shrunk below the Tuner-managed admission gate, or bursts admitted
 * by the gate (up to the gate value) overflow the thread-per-connection executor and are
 * closed empty. This coupling is the fix for the live symptom where the worker pool
 * collapsed to 27 while the gate allowed 256, shedding every excess connection.
 *
 * @since 0.9.71+
 */
public class ClientRunnerFloorTest {

    /** A low worker ceiling is clamped up to the admission gate, never below it. */
    @Test
    public void testRunnerFloorTracksGate() {
        TunnelControllerGroup.setClientDefaultMaxConnections(256);
        TunnelControllerGroup.setClientRunnerMax(4);
        assertEquals("Worker pool must not fall below the admission gate",
                     256, TunnelControllerGroup.getClientRunnerMax());
    }

    /** A worker ceiling above the gate is honored (burst headroom is preserved). */
    @Test
    public void testRunnerAboveGateIsKept() {
        TunnelControllerGroup.setClientDefaultMaxConnections(256);
        TunnelControllerGroup.setClientRunnerMax(1024);
        assertEquals(1024, TunnelControllerGroup.getClientRunnerMax());
    }

    /** If the gate later drops, the worker pool stays up (never forced down by decay). */
    @Test
    public void testRunnerDoesNotFollowGateDown() {
        TunnelControllerGroup.setClientDefaultMaxConnections(256);
        TunnelControllerGroup.setClientRunnerMax(512);
        TunnelControllerGroup.setClientDefaultMaxConnections(64);
        assertEquals("Worker pool should not shrink just because the gate shrank",
                     512, TunnelControllerGroup.getClientRunnerMax());
    }

    /** Worker ceiling is still bounded by the 16384 hardware cap. */
    @Test
    public void testRunnerCappedAtMax() {
        TunnelControllerGroup.setClientDefaultMaxConnections(256);
        TunnelControllerGroup.setClientRunnerMax(5000);
        assertEquals("Worker pool above the gate is preserved",
                     5000, TunnelControllerGroup.getClientRunnerMax());
        TunnelControllerGroup.setClientRunnerMax(50000);
        assertEquals("Worker pool clamps at the 16384 hardware cap",
                     16384, TunnelControllerGroup.getClientRunnerMax());
    }
}
