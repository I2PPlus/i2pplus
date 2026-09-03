package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the effective per-tunnel concurrent-connection cap decision in
 * I2PTunnelClientBase: {@link I2PTunnelClientBase#resolveEffectiveMaxConnections}.
 *
 * <p>An explicitly configured {@code i2ptunnel.maxConnections} override always wins
 * over the Tuner-managed default; a tunnel that left the cap at default inherits the
 * Tuner default so it can be raised/lowered at runtime without a restart.
 *
 * @since 0.9.71+
 */
public class ClientMaxConnectionsTest {

    /** An explicit positive override always wins over the Tuner default. */
    @Test
    public void testCustomizedOverrideWins() {
        assertEquals(512, I2PTunnelClientBase.resolveEffectiveMaxConnections(true, 512, 96));
        assertEquals(128, I2PTunnelClientBase.resolveEffectiveMaxConnections(true, 128, 96));
    }

    /** Explicit zero override (routeable as unlimited) is honored when customized. */
    @Test
    public void testCustomizedZeroIsHonored() {
        assertEquals(0, I2PTunnelClientBase.resolveEffectiveMaxConnections(true, 0, 96));
    }

    /** Un-customized tunnel inherits the Tuner-managed default. */
    @Test
    public void testInheritsTunerDefault() {
        assertEquals(256, I2PTunnelClientBase.resolveEffectiveMaxConnections(false, 96, 256));
    }

    /** Un-customized tunnel tracks a Tuner-raised default upward. */
    @Test
    public void testFollowsTunerRise() {
        int ownCap = I2PTunnelClientBase.DEFAULT_MAX_CONNECTIONS;
        assertEquals(800, I2PTunnelClientBase.resolveEffectiveMaxConnections(false, ownCap, 800));
    }

    /** Un-customized tunnel tracks a Tuner-lowered default downward, but never below 1. */
    @Test
    public void testFollowsTunerFloor() {
        assertEquals(32, I2PTunnelClientBase.resolveEffectiveMaxConnections(false, 96, 32));
        assertEquals(1, I2PTunnelClientBase.resolveEffectiveMaxConnections(false, 96, 1));
    }

    /** An uninitialized (<= 0) shared default must not regress the gate; keep ownCap. */
    @Test
    public void testUninitializedDefaultFallsBackToOwnCap() {
        assertEquals(I2PTunnelClientBase.DEFAULT_MAX_CONNECTIONS,
                     I2PTunnelClientBase.resolveEffectiveMaxConnections(false, I2PTunnelClientBase.DEFAULT_MAX_CONNECTIONS, 0));
        assertEquals(96, I2PTunnelClientBase.resolveEffectiveMaxConnections(false, 96, -1));
    }
}
