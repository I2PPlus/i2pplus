package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.TunnelId;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.util.Log;

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

    /**
     * JUnit fixture object; the tests drive static helpers of {@link TunnelPool}
     * and one pool built per call, so the instance carries no state.
     */
    public TunnelPoolLeaseAssemblyTest() {
    }

    /** Shared router context for the pools and tunnels this test builds. */
    private static RouterContext _ctx;
    /** LeaseSet target used throughout, matching the pool size in the regression. */
    private static final int TUNNELS = 8;
    /** One minute in milliseconds, for the lifetimes and deadlines below. */
    private static final long MINUTE = 60 * 1000L;

    /**
     * Builds the router context every test in this class reads.
     */
    @BeforeClass
    public static void setUp() {
        _ctx = RouterTestHelper.newContext();
    }

    /**
     * A pool configured for the given LeaseSet target.
     *
     * @param quantity inbound tunnel count the pool wants, its LeaseSet target
     * @return a pool over the shared context, never null
     */
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
     *
     * @param index selects this tunnel's receive tunnel id and gateway hash, so
     *              every call yields a distinct tunnel
     * @return a one-hop tunnel with id {@code 1000 + index} and gateway
     *         {@code index + 1}
     */
    private static TunnelInfo tunnel(int index) {
        return tunnel(index, 1000 + index, index + 1);
    }

    /**
     * A one-hop tunnel with the given identity, expiring past the lease cap.
     *
     * @param index unused by the body; callers pass the tunnel's position in the
     *              list being offered
     * @param receiveTunnelId receive tunnel id of the single hop, the value the
     *                        lease carries
     * @param gateway ordinal the gateway hash is built from
     * @return a one-hop tunnel config expiring 60 minutes after the current time
     */
    private static TunnelInfo tunnel(int index, long receiveTunnelId, int gateway) {
        PooledTunnelCreatorConfig cfg =
            new PooledTunnelCreatorConfig(_ctx, 1, true, null, pool(TUNNELS));
        cfg.getConfig(0).setReceiveTunnelId(receiveTunnelId);
        cfg.setPeer(0, hash(gateway));
        cfg.setExpiration(_ctx.clock().now() + 60 * MINUTE);
        return cfg;
    }

    /**
     * A gateway hash built from an ordinal, so each test tunnel is distinct.
     *
     * @param ordinal written into the hash's first four bytes, big-endian
     * @return a hash whose first four bytes are the ordinal and the rest zero
     */
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

    /**
     * Distinct receive tunnel ids, in the order offered.
     *
     * @param tunnels the tunnels as offered, repeats included
     * @return the tunnels whose receive tunnel id has not been seen yet, in the
     *         order offered; a tunnel with no receive tunnel id is dropped
     */
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

    /**
     * Finalization must log at INFO without asking the fresh LeaseSet for its
     * wire size: the set carries leases only until the caller adds the
     * destination and signature, and {@code size()} dereferences both. The
     * pre-fix log line threw NPE on every build once the pool logger reached
     * INFO, aborting build completion and LeaseSet republish.
     */
    @Test
    public void finalizationLogsAtInfoWithoutTouchingWireSize() {
        List<TunnelInfo> tunnels = new ArrayList<>();
        for (int i = 0; i < TUNNELS; i++) {
            tunnels.add(tunnel(i));
        }
        TreeSet<Lease> leases = pool(TUNNELS).buildLeases(tunnels, null);
        assertEquals(TUNNELS, leases.size());
        TunnelPool pool = pool(TUNNELS);
        Log log = _ctx.logManager().getLog(TunnelPool.class);
        int saved = log.getMinimumPriority();
        try {
            log.setMinimumPriority(Log.INFO);
            LeaseSet ls = pool.finalizeLeaseSet(leases, TUNNELS, _ctx.clock().now(),
                                                Collections.emptyMap(), TUNNELS);
            assertEquals("all leases made it into the set", TUNNELS, ls.getLeaseCount());
        } finally {
            log.setMinimumPriority(saved);
        }
    }

    // ---- why a short LeaseSet is short ----

    /**
     * A tunnel marked failed by hard failures is withheld, and the reason says so.
     *
     * @return the config, for the caller to read back
     */
    private static PooledTunnelCreatorConfig failedTunnel() {
        PooledTunnelCreatorConfig cfg = (PooledTunnelCreatorConfig) tunnel(0);
        for (int i = 0; i < 4; i++) {
            cfg.incrementTestFailures();
        }
        cfg.setTestFailed();
        return cfg;
    }

    /**
     * A tunnel that failed one test is degraded, not dead.
     *
     * @return the config, in {@code FAILING} status
     */
    private static PooledTunnelCreatorConfig failingTunnel() {
        PooledTunnelCreatorConfig cfg = (PooledTunnelCreatorConfig) tunnel(0);
        cfg.incrementTestFailures();
        cfg.setTestFailed();
        return cfg;
    }

    /**
     * A tunnel that passed its test, expiring at the given offset from now.
     *
     * @param expiresAfter how far past the current time the tunnel expires, ms
     * @return the config, in {@code GOOD} status
     */
    private static PooledTunnelCreatorConfig goodTunnel(long expiresAfter) {
        PooledTunnelCreatorConfig cfg = (PooledTunnelCreatorConfig) tunnel(0);
        cfg.clearTestFailures();
        cfg.setExpiration(_ctx.clock().now() + expiresAfter);
        return cfg;
    }

    /**
     * A pool holding eight sound tunnels must not report the same shortfall as
     * a pool holding three. The old line said only "Have 3 of 8", and the two
     * cases need opposite fixes - build more tunnels, or stop disqualifying the
     * ones that exist - so the reason has to be in the line.
     */
    @Test
    public void eachReasonAShortSetCameFromIsNamed() {
        long now = _ctx.clock().now();
        long deadline = now + 3 * MINUTE;
        assertEquals(TunnelPool.LeaseRejection.FAILED,
                     TunnelPool.leaseRejection(failedTunnel(), true, deadline));
        assertEquals(TunnelPool.LeaseRejection.NOT_GOOD,
                     TunnelPool.leaseRejection(failingTunnel(), true, deadline));
        assertNull("a GOOD tunnel well clear of the deadline is advertised",
                   TunnelPool.leaseRejection(goodTunnel(10 * MINUTE), true, deadline));
        assertEquals(TunnelPool.LeaseRejection.NEAR_EXPIRY,
                     TunnelPool.leaseRejection(goodTunnel(2 * MINUTE), true, deadline));
    }

    /**
     * An UNTESTED tunnel is only a fallback: it is advertised while the pool has
     * nothing better and turned away once a GOOD tunnel exists.
     */
    @Test
    public void untestedTunnelsQualifyOnlyWhileNothingBetterExists() {
        long deadline = _ctx.clock().now() + 3 * MINUTE;
        assertNull("no GOOD tunnel, so the untested one is advertised",
                   TunnelPool.leaseRejection(tunnel(0), false, deadline));
        assertEquals(TunnelPool.LeaseRejection.NOT_GOOD,
                     TunnelPool.leaseRejection(tunnel(0), true, deadline));
    }

    /**
     * The tally line has to be readable by someone with no source open: prose
     * labels, not enum constants, and the house shape - an arrow after the
     * subject, {@code key: value} segments, no emitted line over 100 characters.
     */
    @Test
    public void tallyLineNamesTheReasonsInProse() {
        Map<TunnelPool.LeaseRejection, Integer> rejected =
            new EnumMap<>(TunnelPool.LeaseRejection.class);
        rejected.put(TunnelPool.LeaseRejection.NEAR_EXPIRY, 3);
        rejected.put(TunnelPool.LeaseRejection.FAILED, 1);
        String line = TunnelPool.shortfallDetail(rejected, 9);
        assertTrue(line, line.contains("Not offered -> "));
        assertTrue(line, line.contains("Expiring: 3"));
        assertTrue(line, line.contains("Failed: 1"));
        assertTrue(line, line.contains("Pool tunnels: 9"));
        assertFalse("a reason with no tunnels must not appear: " + line,
                    line.contains("Zero-hop"));
        for (String part : line.split("\n")) {
            assertTrue("emitted line over 100 chars: " + part.length() + ' ' + part,
                       part.length() <= 100);
            assertFalse("trailing whitespace: [" + part + ']', part.endsWith(" "));
        }
    }

    /** A set that lost nothing has nothing to explain. */
    @Test
    public void tallyLineIsSilentWhenNothingWasRejected() {
        assertEquals("", TunnelPool.shortfallDetail(new EnumMap<>(TunnelPool.LeaseRejection.class), -1));
        Map<TunnelPool.LeaseRejection, Integer> zero = new EnumMap<>(TunnelPool.LeaseRejection.class);
        zero.put(TunnelPool.LeaseRejection.ZERO_HOP, 0);
        assertEquals("", TunnelPool.shortfallDetail(zero, -1));
    }

    /**
     * The denominator is what makes the tally actionable - three tunnels turned
     * away out of four is a supply problem, out of nine an eligibility one - so
     * it is printed when known and left out when not.
     */
    @Test
    public void poolSizeIsPrintedOnlyWhenKnown() {
        Map<TunnelPool.LeaseRejection, Integer> none =
            new EnumMap<>(TunnelPool.LeaseRejection.class);
        assertEquals("\n* Pool tunnels: 9", TunnelPool.shortfallDetail(none, 9));
        assertEquals("", TunnelPool.shortfallDetail(none, -1));
    }

    /**
     * The helper has to reach the line the router actually emits. A tally
     * nobody appends reads green in unit tests and changes nothing in the log,
     * and the reason counts have to come from the same pass that chose the
     * tunnels, not from a second walk that could disagree with it.
     *
     * @throws Exception if the TunnelPool source cannot be read back from disk
     */
    @Test
    public void theShortfallLineCarriesTheTally() throws Exception {
        java.io.File src =
            new java.io.File("router/java/src/net/i2p/router/tunnel/pool/TunnelPool.java");
        if (!src.isFile()) {
            src = new java.io.File("src/net/i2p/router/tunnel/pool/TunnelPool.java");
        }
        Assume.assumeTrue("source not available", src.isFile());
        String body = new String(java.nio.file.Files.readAllBytes(src.toPath()), "UTF-8");
        assertTrue("the warning must append the tally",
                   body.contains("shortfallDetail(rejected, poolTunnels)"));
        assertTrue("the tally must come from the collection pass",
                   body.contains("return new LeaseTunnels(goodTunnels, zeroHopTunnel, rejected)"));
        assertTrue("rotation must count as a reason",
                   body.contains("LeaseRejection.ROTATED, rotatedOut.size()"));
    }

    private static Lease lease(long end, long tunnelId, int gateway) {
        Lease lease = new Lease();
        lease.setEndDate(end);
        lease.setTunnelId(new TunnelId(tunnelId));
        lease.setGateway(hash(gateway));
        return lease;
    }
}
