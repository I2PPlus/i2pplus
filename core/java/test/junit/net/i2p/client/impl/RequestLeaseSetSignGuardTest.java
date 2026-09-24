package net.i2p.client.impl;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.Lease2;
import net.i2p.data.LeaseSet;
import net.i2p.data.LeaseSet2;
import net.i2p.data.TunnelId;
import org.junit.Test;

/**
 * Guards that keep LeaseSet2.writeHeader() from rejecting a client-built
 * set with "LeaseSet expired": positive TTL vs published, and no empty sign.
 */
public class RequestLeaseSetSignGuardTest {

    @Test
    public void positiveExpiryPassesThroughWhenAlreadySafe() {
        long published = 1_700_000_000_000L;
        long leaseEnd = published + 60_000L;
        assertEquals(leaseEnd,
                RequestLeaseSetMessageHandler.ensurePositiveLs2Expiry(leaseEnd, published));
    }

    @Test
    public void positiveExpiryFloorsStaleEndToPublishedPlusOneSecond() {
        long published = 1_700_000_000_000L;
        long staleEnd = published - 5_000L;
        long floored = RequestLeaseSetMessageHandler.ensurePositiveLs2Expiry(staleEnd, published);
        assertEquals(published + RequestLeaseSetMessageHandler.MIN_LS2_LEASE_TTL_MS, floored);
        assertTrue(floored > published);
    }

    @Test
    public void positiveExpiryLeavesEndUntouchedWhenPublishedUnset() {
        long leaseEnd = 12_345L;
        assertEquals(leaseEnd, RequestLeaseSetMessageHandler.ensurePositiveLs2Expiry(leaseEnd, 0));
        assertEquals(leaseEnd, RequestLeaseSetMessageHandler.ensurePositiveLs2Expiry(leaseEnd, -1));
    }

    @Test
    public void positiveExpiryBumpsEndEqualOrJustAfterPublished() {
        long published = 1_700_000_000_000L;
        long equal = RequestLeaseSetMessageHandler.ensurePositiveLs2Expiry(published, published);
        assertTrue(equal > published);
        long justAfter = RequestLeaseSetMessageHandler.ensurePositiveLs2Expiry(published + 1, published);
        assertTrue(justAfter > published);
    }

    @Test
    public void isSignableRejectsNullAndEmpty() {
        assertFalse(RequestLeaseSetMessageHandler.isSignable(null));
        assertFalse(RequestLeaseSetMessageHandler.isSignable(new LeaseSet()));
        assertFalse(RequestLeaseSetMessageHandler.isSignable(new LeaseSet2()));
    }

    @Test
    public void isSignableAcceptsSetWithOneLease() {
        LeaseSet ls = new LeaseSet();
        ls.addLease(sampleLease());
        assertTrue(RequestLeaseSetMessageHandler.isSignable(ls));

        LeaseSet2 ls2 = new LeaseSet2();
        Lease2 lease = new Lease2();
        lease.setGateway(Hash.FAKE_HASH);
        lease.setTunnelId(new TunnelId(3));
        lease.setEndDate(System.currentTimeMillis() + 60_000L);
        ls2.addLease(lease);
        assertTrue(RequestLeaseSetMessageHandler.isSignable(ls2));
    }

    @Test
    public void flooredEndMakesLs2ExpiresStrictlyAfterPublished() {
        long published = 1_700_000_000_000L;
        LeaseSet2 ls2 = new LeaseSet2();
        ls2.setPublished(published);
        Lease2 lease = new Lease2();
        lease.setGateway(Hash.FAKE_HASH);
        lease.setTunnelId(new TunnelId(7));
        lease.setEndDate(RequestLeaseSetMessageHandler.ensurePositiveLs2Expiry(
                published - 5_000L, published));
        ls2.addLease(lease);
        assertTrue(ls2.getExpires() > ls2.getPublished());
        assertTrue(RequestLeaseSetMessageHandler.isSignable(ls2));
    }

    private static Lease sampleLease() {
        Lease lease = new Lease2();
        lease.setGateway(Hash.FAKE_HASH);
        lease.setTunnelId(new TunnelId(1));
        lease.setEndDate(System.currentTimeMillis() + 60_000L);
        return lease;
    }
}
