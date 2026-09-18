package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.peermanager.FloodfillReliability;
import net.i2p.router.peermanager.PeerProfile;

/**
 * Unit tests for the floodfill reliability comparison logic in ClientPeerSelector.
 */
public class ClientPeerSelectorCompareReliabilityTest {

    @Test
    public void testOrdinalOrdering() {
        assertEquals(0, FloodfillReliability.BAD.ordinal());
        assertEquals(1, FloodfillReliability.UNKNOWN.ordinal());
        assertEquals(2, FloodfillReliability.OK.ordinal());
        assertEquals(3, FloodfillReliability.GOOD.ordinal());
    }

    @Test
    public void testCompareReliabilityGoodBeforeOk() {
        PeerProfile good = profile(FloodfillReliability.GOOD);
        PeerProfile ok = profile(FloodfillReliability.OK);
        assertTrue(ClientPeerSelector.compareReliability(good, ok) < 0);
    }

    @Test
    public void testCompareReliabilityOkBeforeUnknown() {
        PeerProfile ok = profile(FloodfillReliability.OK);
        PeerProfile unknown = profile(FloodfillReliability.UNKNOWN);
        assertTrue(ClientPeerSelector.compareReliability(ok, unknown) < 0);
    }

    @Test
    public void testCompareReliabilityUnknownBeforeBad() {
        PeerProfile unknown = profile(FloodfillReliability.UNKNOWN);
        PeerProfile bad = profile(FloodfillReliability.BAD);
        assertTrue(ClientPeerSelector.compareReliability(unknown, bad) < 0);
    }

    @Test
    public void testCompareReliabilityNullIsUnknown() {
        PeerProfile ok = profile(FloodfillReliability.OK);
        // null treated as UNKNOWN, so OK > UNKNOWN
        assertTrue(ClientPeerSelector.compareReliability(ok, null) < 0);
        assertTrue(ClientPeerSelector.compareReliability(null, ok) > 0);
    }

    @Test
    public void testCompareReliabilityBothNull() {
        assertEquals(0, ClientPeerSelector.compareReliability(null, null));
    }

    @Test
    public void testCompareReliabilityEqual() {
        PeerProfile p1 = profile(FloodfillReliability.OK);
        PeerProfile p2 = profile(FloodfillReliability.OK);
        assertEquals(0, ClientPeerSelector.compareReliability(p1, p2));
    }

    @Test
    public void testCompareReliabilityGoodBeforeBad() {
        PeerProfile good = profile(FloodfillReliability.GOOD);
        PeerProfile bad = profile(FloodfillReliability.BAD);
        assertTrue(ClientPeerSelector.compareReliability(bad, good) > 0);
    }

    private static PeerProfile profile(FloodfillReliability rel) {
        PeerProfile p = mock(PeerProfile.class);
        when(p.getFloodfillReliability()).thenReturn(rel);
        return p;
    }
}
