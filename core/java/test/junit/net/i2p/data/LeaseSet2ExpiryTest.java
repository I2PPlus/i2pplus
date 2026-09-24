package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import org.junit.Test;

/**
 * LeaseSet2.writeHeader() rejects expires &lt; published with
 * "LeaseSet expired". Client-side empty or short lease ends hit this path.
 */
public class LeaseSet2ExpiryTest {

    private static LeaseSet2 ls2WithDest(long published) {
        LeaseSet2 ls2 = new LeaseSet2();
        try {
            ls2.setDestination((Destination) new DestinationTest().createDataStructure());
        } catch (DataFormatException dfe) {
            throw new AssertionError(dfe);
        }
        ls2.setPublished(published);
        return ls2;
    }

    @Test
    public void writeHeaderRejectsExpiresBeforePublished() {
        long published = 1_700_000_000_000L;
        LeaseSet2 ls2 = ls2WithDest(published);
        Lease2 lease = new Lease2();
        lease.setGateway(Hash.FAKE_HASH);
        lease.setTunnelId(new TunnelId(42));
        lease.setEndDate(published - 10_000L);
        ls2.addLease(lease);
        try {
            ls2.writeHeader(new ByteArrayOutputStream());
            fail("expected DataFormatException for expired LeaseSet2");
        } catch (DataFormatException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("expired"));
        } catch (java.io.IOException ioe) {
            throw new AssertionError(ioe);
        }
    }

    @Test
    public void writeHeaderAcceptsEndAfterPublished() throws Exception {
        long published = 1_700_000_000_000L;
        LeaseSet2 ls2 = ls2WithDest(published);
        Lease2 lease = new Lease2();
        lease.setGateway(Hash.FAKE_HASH);
        lease.setTunnelId(new TunnelId(7));
        lease.setEndDate(published + 60_000L);
        ls2.addLease(lease);
        ls2.writeHeader(new ByteArrayOutputStream());
    }

    @Test
    public void emptyLs2WriteHeaderReportsExpired() {
        LeaseSet2 ls2 = ls2WithDest(1_700_000_000_000L);
        try {
            ls2.writeHeader(new ByteArrayOutputStream());
            fail("expected DataFormatException for empty LeaseSet2");
        } catch (DataFormatException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("expired"));
        } catch (java.io.IOException ioe) {
            throw new AssertionError(ioe);
        }
    }

    @Test
    public void addLeaseUpdatesExpiresFromLeaseEnd() {
        LeaseSet2 ls2 = new LeaseSet2();
        long published = 1_700_000_000_000L;
        ls2.setPublished(published);
        assertEquals(0, ls2.getExpires());
        Lease2 lease = new Lease2();
        lease.setGateway(Hash.FAKE_HASH);
        lease.setTunnelId(new TunnelId(1));
        lease.setEndDate(published + 90_000L);
        ls2.addLease(lease);
        assertEquals(published + 90_000L, ls2.getExpires());
        assertTrue(ls2.getExpires() > ls2.getPublished());
    }
}
