package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import net.i2p.router.TunnelPoolSettings;

import org.junit.Test;

/**
 * The subnet-diversity mask a tunnel build runs with.
 *
 * <p>The value is the number of leading address bytes compared, so a higher one
 * is a <em>looser</em> gate: 2 rejects a peer sharing a /16 with a hop already
 * chosen, 3 only one sharing a /24. The attack-mode relaxation used to subtract
 * from it, which compared /8s and rejected strictly more peers at exactly the
 * moment the pool was collapsing — the opposite of its own comment. These tests
 * pin the direction, because the name and the comment both read the other way.
 *
 * @since 0.9.74+
 */
public class ClientPeerSelectorIpRestrictionTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one; the class carries no per-test state, only the mask the decision is
     * called with.
     *
     * @since 0.9.74+
     */
    public ClientPeerSelectorIpRestrictionTest() {}

    /** Build success above the attack threshold, so no relaxation applies. */
    private static final double HEALTHY = 0.75d;

    private static TunnelPoolSettings settings(int configured) {
        TunnelPoolSettings settings = mock(TunnelPoolSettings.class);
        when(settings.getIPRestriction()).thenReturn(configured);
        return settings;
    }

    /** A healthy network keeps whatever the operator configured. */
    @Test
    public void healthyBuildSuccessKeepsTheConfiguredMask() {
        assertEquals(2, ClientPeerSelector.ipRestrictionFor(settings(2), 4, HEALTHY, false));
        assertEquals(3, ClientPeerSelector.ipRestrictionFor(settings(3), 4, HEALTHY, false));
    }

    /**
     * Under attack the gate loosens by matching a finer prefix. Subtracting here
     * took the default /16 down to a /8 match, which rejects more peers, not
     * fewer.
     */
    @Test
    public void attackModeLoosensTowardAFinerPrefix() {
        assertEquals(3, ClientPeerSelector.ipRestrictionFor(settings(2), 4, 0.20d, false));
        assertEquals(4, ClientPeerSelector.ipRestrictionFor(settings(3), 4, 0.20d, false));
    }

    /** An exact-address match is the loosest gate there is, so the ramp stops there. */
    @Test
    public void attackModeStopsAtAnExactMatch() {
        assertEquals(4, ClientPeerSelector.ipRestrictionFor(settings(4), 4, 0.20d, false));
    }

    /** A gate the operator switched off stays off; it is not revived by attack mode. */
    @Test
    public void zeroMeansNoRestriction() {
        assertEquals(0, ClientPeerSelector.ipRestrictionFor(settings(0), 4, 0.20d, false));
        assertEquals(0, ClientPeerSelector.ipRestrictionFor(settings(0), 4, HEALTHY, false));
    }

    /** A single hop has no second peer to be diverse from. */
    @Test
    public void singleHopDisablesTheGate() {
        assertEquals(0, ClientPeerSelector.ipRestrictionFor(settings(2), 1, HEALTHY, false));
        assertEquals(0, ClientPeerSelector.ipRestrictionFor(settings(2), 1, 0.20d, false));
    }

    /** Local peers are allowed to share an address, so the gate is off entirely. */
    @Test
    public void allowLocalDisablesTheGate() {
        assertEquals(0, ClientPeerSelector.ipRestrictionFor(settings(2), 4, HEALTHY, true));
        assertEquals(0, ClientPeerSelector.ipRestrictionFor(settings(2), 4, 0.20d, true));
    }
}
