package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;

import net.i2p.data.DatabaseEntry;
import net.i2p.data.LeaseSet;
import net.i2p.data.LeaseSet2;
import net.i2p.router.Router;

/**
 * Tests the replace/reject decision for incoming LeaseSet stores.
 * The decision is the choke point shared by KademliaNetworkDatabaseFacade.store()
 * and TransientDataStore.put(): keep the freshest copy, but never let a lapsed
 * entry hold a destination unreachable when an equal- or older-dated copy arrives.
 */
public class LeaseSetReplaceDecisionTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long FUDGE = Router.CLOCK_FUDGE_FACTOR;

    private LeaseSet ls1(long end) {
        LeaseSet ls = mock(LeaseSet.class);
        when(ls.getType()).thenReturn(DatabaseEntry.KEY_TYPE_LEASESET);
        when(ls.getEarliestLeaseDate()).thenReturn(end);
        when(ls.getLatestLeaseDate()).thenReturn(end);
        return ls;
    }

    private LeaseSet2 ls2(long published, long latest) {
        LeaseSet2 ls = mock(LeaseSet2.class);
        when(ls.getType()).thenReturn(DatabaseEntry.KEY_TYPE_LS2);
        when(ls.getPublished()).thenReturn(published);
        when(ls.getLatestLeaseDate()).thenReturn(latest);
        return ls;
    }

    @Test
    public void testNewerReplaces() {
        LeaseSet stored = ls1(NOW + 60_000);
        LeaseSet incoming = ls1(NOW + 120_000);
        assertTrue(KademliaNetworkDatabaseFacade.shouldAcceptLeaseSet(stored, incoming, NOW));
    }

    @Test
    public void testDuplicateRejectedWhileCurrent() {
        LeaseSet stored = ls1(NOW + 300_000);
        LeaseSet incoming = ls1(NOW + 300_000);
        assertFalse(KademliaNetworkDatabaseFacade.shouldAcceptLeaseSet(stored, incoming, NOW));
    }

    @Test
    public void testOlderRejectedWhileCurrent() {
        LeaseSet stored = ls1(NOW + 300_000);
        LeaseSet incoming = ls1(NOW - 120_000);
        assertFalse(KademliaNetworkDatabaseFacade.shouldAcceptLeaseSet(stored, incoming, NOW));
    }

    @Test
    public void testDuplicateAcceptedWhenLapsed() {
        LeaseSet stored = ls1(NOW - 60_000);
        LeaseSet incoming = ls1(NOW - 60_000);
        assertTrue(KademliaNetworkDatabaseFacade.shouldAcceptLeaseSet(stored, incoming, NOW));
    }

    @Test
    public void testOlderAcceptedWhenLapsed() {
        LeaseSet stored = ls1(NOW - 60_000);
        LeaseSet incoming = ls1(NOW - 120_000);
        assertTrue(KademliaNetworkDatabaseFacade.shouldAcceptLeaseSet(stored, incoming, NOW));
    }

    @Test
    public void testBoundaryLapsedAtExactGrace() {
        LeaseSet stored = ls1(NOW - FUDGE);
        LeaseSet incoming = ls1(NOW - FUDGE);
        assertTrue(KademliaNetworkDatabaseFacade.shouldAcceptLeaseSet(stored, incoming, NOW));
    }

    @Test
    public void testBoundaryOneMsInsideGraceRejects() {
        LeaseSet stored = ls1(NOW - FUDGE + 1);
        LeaseSet incoming = ls1(NOW - FUDGE + 1);
        assertFalse(KademliaNetworkDatabaseFacade.shouldAcceptLeaseSet(stored, incoming, NOW));
    }

    @Test
    public void testLs2SamePublishedRejectedWhileCurrent() {
        LeaseSet2 stored = ls2(NOW - 10_000, NOW + 300_000);
        LeaseSet2 incoming = ls2(NOW - 10_000, NOW + 300_000);
        assertFalse(KademliaNetworkDatabaseFacade.shouldAcceptLeaseSet(stored, incoming, NOW));
    }

    @Test
    public void testLs2SamePublishedAcceptedWhenLapsed() {
        LeaseSet2 stored = ls2(NOW - 10_000, NOW - 60_000);
        LeaseSet2 incoming = ls2(NOW - 10_000, NOW - 60_000);
        assertTrue(KademliaNetworkDatabaseFacade.shouldAcceptLeaseSet(stored, incoming, NOW));
    }

    @Test
    public void testLs2NewerPublishedReplacesCurrent() {
        LeaseSet2 stored = ls2(NOW - 10_000, NOW + 300_000);
        LeaseSet2 incoming = ls2(NOW + 10_000, NOW + 300_000);
        assertTrue(KademliaNetworkDatabaseFacade.shouldAcceptLeaseSet(stored, incoming, NOW));
    }

    @Test
    public void testCrossTypeLs2NewerReplacesLs1() {
        LeaseSet stored = ls1(NOW + 300_000);
        LeaseSet2 incoming = ls2(NOW + 400_000, NOW + 300_000);
        assertTrue(KademliaNetworkDatabaseFacade.shouldAcceptLeaseSet(stored, incoming, NOW));
    }

    @Test
    public void testCrossTypeLs2SameLeaseDateRejectedWhileCurrent() {
        LeaseSet stored = ls1(NOW + 300_000);
        LeaseSet2 incoming = ls2(NOW + 300_000, NOW + 300_000);
        assertFalse(KademliaNetworkDatabaseFacade.shouldAcceptLeaseSet(stored, incoming, NOW));
    }
}