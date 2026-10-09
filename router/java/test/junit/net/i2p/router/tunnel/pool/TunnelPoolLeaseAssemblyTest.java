package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.TunnelId;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelPoolSettings;

/**
 * Tests that every tunnel offered to a pool's LeaseSet survives assembly.
 *
 * <p>The regression: leases were collected into a {@link TreeSet} ordered by end time alone, and
 * a {@code TreeSet} drops any element its comparator calls equal. End times collide by
 * construction — {@code computeLeaseEndDate} clamps to {@code now + maxLeaseMs} and floors at
 * {@code now + 60s} from a single {@code now} for the whole build — so a pool holding eight
 * tunnels published a LeaseSet of one, and a destination advertised through a single tunnel
 * reads as unreachable to every other router. The symptom logged as a supply problem
 * ("Not enough leases") and the LeaseSet itself was the defect.
 *
 * @since 0.9.71+
 */
public class TunnelPoolLeaseAssemblyTest {

    private static RouterContext _ctx;
    private static final int TUNNELS = 8;
    private static final long MINUTE = 60 * 1000L;

    @BeforeClass
    public static void setUp() {
        _ctx = RouterTestHelper.newContext();
    }

    private static TunnelPool pool(int quantity) {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        return new TunnelPool(_ctx, mock(TunnelPoolManager.class), settings(quantity),
                              mock(TunnelPeerSelector.class));
    }

    private static TunnelPoolSettings settings(int quantity) {
        TunnelPoolSettings settings = new TunnelPoolSettings(false);
        settings.setQuantity(quantity);
        return settings;
    }

    /**
     * A one-hop tunnel with a distinct identity, expiring far enough ahead that
     * {@code computeLeaseEndDate} clamps it to the lease cap — the condition that made every
     * lease in the pool share one end time.
     */
    private static TunnelInfo tunnel(int index) {
        return tunnel(index, 1000 + index, index + 1);
    }

    /**  A one-hop tunnel with the given identity, expiring past the lease cap. */
    private static TunnelInfo tunnel(int index, long receiveTunnelId, int gateway) {
        PooledTunnelCreatorConfig cfg =
            new PooledTunnelCreatorConfig(_ctx, 1, true, null, pool(TUNNELS));
        cfg.getConfig(0).setReceiveTunnelId(receiveTunnelId);
        cfg.setPeer(0, hash(gateway));
        cfg.setExpiration(_ctx.clock().now() + 60 * MINUTE);
        return cfg;
    }

    /**  A gateway hash built from an ordinal, so each test tunnel is distinct. */
    private static Hash hash(int ordinal) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) (ordinal >>> 24);
        data[1] = (byte) (ordinal >>> 16);
        data[2] = (byte) (ordinal >>> 8);
        data[3] = (byte) ordinal;
        return Hash.create(data);
    }

    // ---- the regression ----

    /**
     * Tunnels whose leases land on the same end time all have to be kept.
     *
     * <p>Each tunnel here is set to expire well past the lease cap, so
     * {@code computeLeaseEndDate} clamps every one of them to the identical instant — the
     * situation that made the old end-time-only comparator collapse the set to a single lease.
     */
    @Test
    public void everyTunnelKeepsItsLeaseWhenTheEndTimesCollide() {
        List<TunnelInfo> tunnels = new ArrayList<>();
        for (int i = 0; i < TUNNELS; i++) {
            tunnels.add(tunnel(i));
        }
        TreeSet<Lease> leases = pool(TUNNELS).buildLeases(tunnels, null);
        assertEquals("leases were lost when their end times coincided", TUNNELS, leases.size());
    }

    /**
     * Distinct tunnels must produce distinct leases, not merely a set of the right size.
     *
     * <p>Catches a comparator that invents a different tie-break per call, which would satisfy
     * the count while still letting a lease be replaced rather than kept.
     */
    @Test
    public void theKeptLeasesBelongToTheTunnelsOffered() {
        List<TunnelInfo> tunnels = new ArrayList<>();
        for (int i = 0; i < TUNNELS; i++) {
            tunnels.add(tunnel(i));
        }
        TreeSet<Lease> leases = pool(TUNNELS).buildLeases(tunnels, null);

        List<Long> ids = new ArrayList<>();
        for (Lease lease : leases) {
            ids.add(lease.getTunnelId().getTunnelId());
        }
        for (int i = 0; i < TUNNELS; i++) {
            assertTrue("lease for tunnel " + (1000 + i) + " is missing from " + ids,
                       ids.contains((long) (1000 + i)));
        }
    }

    /**
     * A tunnel already in the offered list must not be offered again.
     *
     * <p>The top-up that fills a short LeaseSet draws traffic-proven UNTESTED tunnels from the
     * same pool the first pass read. When a pool has no GOOD tunnel at all,
     * {@code isEligibleForLease} already admitted those UNTESTED ones, so the top-up re-added
     * every tunnel it was about to contribute. The lease set then collapsed the duplicates by
     * identity - correctly - leaving the set exactly as short as it started, which is what the
     * top-up exists to prevent.
     */
    @Test
    public void aTunnelIsNotOfferedTwice() {
        TunnelInfo a = tunnel(0, 1000, 1);
        TunnelInfo b = tunnel(1, 1001, 2);
        List<TunnelInfo> offered = new ArrayList<>();
        offered.add(a);
        offered.add(b);
        // the duplicate the top-up used to append
        offered.add(a);

        TreeSet<Lease> leases = pool(TUNNELS).buildLeases(dedupeByLeaseKey(offered), null);
        assertEquals("a re-offered tunnel must not shrink the LeaseSet", 2, leases.size());
    }

    /**  Distinct receive tunnel ids, in the order offered. */
    private static List<TunnelInfo> dedupeByLeaseKey(List<TunnelInfo> tunnels) {
        List<TunnelInfo> out = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (TunnelInfo t : tunnels) {
            TunnelId id = t.getReceiveTunnelId(0);
            if (id != null && seen.add(id.getTunnelId())) {
                out.add(t);
            }
        }
        return out;
    }

    // ---- the comparator's own contract ----

    /**
     * The comparator may only report equality for leases {@link Lease#equals} calls equal.
     *
     * <p>Without that agreement a sorted set is free to discard elements, which is precisely how
     * the leases went missing.
     */
    @Test
    public void theComparatorAgreesWithEquals() {
        TunnelPool.LeaseComparator cmp = new TunnelPool.LeaseComparator();
        Lease a = lease(5000L, 7, 1);
        Lease sameAsA = lease(5000L, 7, 1);
        Lease otherTunnel = lease(5000L, 8, 1);
        Lease otherGateway = lease(5000L, 7, 2);
        Lease otherEnd = lease(6000L, 7, 1);

        assertEquals("a lease must not equal itself under the comparator", 0, cmp.compare(a, sameAsA));
        assertTrue("equal leases must be equal to equals()", a.equals(sameAsA));
        for (Lease other : new Lease[] { otherTunnel, otherGateway, otherEnd }) {
            assertNotEquals("a lease must not equal a different lease", a, other);
            assertNotEquals("the comparator reported unequal leases as equal", 0, cmp.compare(a, other));
        }
    }

    /**
     * End time still leads the ordering, latest first, which is what keeps an unchanged tunnel
     * set serialising to an unchanged LeaseSet and stops needless republication.
     *
     * <p>The tie-break must not reorder leases that differ in end time, so the tunnel ids here
     * run opposite to the end times: if identity won, the comparison would come out the other
     * way and this would fail.
     */
    @Test
    public void endTimeStillLeadsTheOrdering() {
        TunnelPool.LeaseComparator cmp = new TunnelPool.LeaseComparator();
        Lease soon = lease(1000L, 99, 9);
        Lease later = lease(9000L, 1, 1);
        assertTrue("the later-ending lease must sort first",
                   cmp.compare(later, soon) < 0);
        assertTrue("the ordering must not be antisymmetric", cmp.compare(soon, later) > 0);
    }

    /** Ordering must be antisymmetric on the tie-break, or a sorted set is undefined. */
    @Test
    public void theTieBreakIsAntisymmetric() {
        TunnelPool.LeaseComparator cmp = new TunnelPool.LeaseComparator();
        Lease a = lease(5000L, 3, 1);
        Lease b = lease(5000L, 4, 1);
        assertTrue(cmp.compare(a, b) < 0);
        assertTrue(cmp.compare(b, a) > 0);
    }

    /** A lease is built from its tunnel's identity, so an unset tunnel yields nothing. */
    @Test
    public void aTunnelWithoutIdentityYieldsNoLease() {
        PooledTunnelCreatorConfig cfg =
            new PooledTunnelCreatorConfig(_ctx, 1, true, null, pool(TUNNELS));
        cfg.setExpiration(_ctx.clock().now() + 60 * MINUTE);
        // no receive tunnel id and no gateway set
        assertTrue("an incomplete tunnel must not be advertised",
                   pool(TUNNELS).buildLeases(Collections.singletonList(cfg), null).isEmpty());
    }

    private static Lease lease(long end, long tunnelId, int gateway) {
        Lease lease = new Lease();
        lease.setEndDate(end);
        lease.setTunnelId(new TunnelId(tunnelId));
        lease.setGateway(hash(gateway));
        return lease;
    }
}
