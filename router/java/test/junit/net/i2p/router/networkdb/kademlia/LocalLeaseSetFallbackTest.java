package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the client sub-NetDb local-destination LeaseSet fallback contract:
 * a miss for a destination we host must consult the main NetDb (where
 * publish() mirrors the copy) and must never launch a client-facade remote
 * search that can only fail and poison the outbound send cooldown.
 */
public class LocalLeaseSetFallbackTest {

    @Test
    public void clientDbLocalDestMissFallsBackToMain() {
        assertTrue(KademliaNetworkDatabaseFacade.shouldFallbackLocalLookupToMain(true, true, false));
    }

    @Test
    public void clientDbLocalDestHitSkipsFallback() {
        assertFalse(KademliaNetworkDatabaseFacade.shouldFallbackLocalLookupToMain(true, true, true));
    }

    @Test
    public void clientDbRemoteDestNeverFallsBack() {
        assertFalse(KademliaNetworkDatabaseFacade.shouldFallbackLocalLookupToMain(true, false, false));
    }

    @Test
    public void mainDbNeverFallsBack() {
        assertFalse(KademliaNetworkDatabaseFacade.shouldFallbackLocalLookupToMain(false, true, false));
        assertFalse(KademliaNetworkDatabaseFacade.shouldFallbackLocalLookupToMain(false, false, false));
    }

    @Test
    public void clientDbLocalDestFailsWithoutRemoteSearch() {
        assertTrue(KademliaNetworkDatabaseFacade.shouldFailLocalWithoutRemoteSearch(true, true));
    }

    @Test
    public void clientDbRemoteDestStillSearches() {
        assertFalse(KademliaNetworkDatabaseFacade.shouldFailLocalWithoutRemoteSearch(true, false));
    }

    @Test
    public void mainDbAlwaysSearchesOnMiss() {
        assertFalse(KademliaNetworkDatabaseFacade.shouldFailLocalWithoutRemoteSearch(false, true));
        assertFalse(KademliaNetworkDatabaseFacade.shouldFailLocalWithoutRemoteSearch(false, false));
    }
}
