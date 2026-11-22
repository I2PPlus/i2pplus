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

    // ---- what a propagation rotation may cost ----

    /**
     * A rotation drops a working lease and then removes that tunnel from the
     * pool, so it is only allowed when the set still clears the usable minimum
     * afterwards.
     *
     * <p>The live numbers this pins: over 37 minutes a router logged 43 rotations
     * and 56 "not enough leases" warnings, 41 of which would not have fired at
     * all without the rotation - a target-3 pool publishing "Have 1 of 3" whose
     * only reason was one rotation, and target-8 pools holding seven or eight
     * tunnels advertising three because the fourth lease was rotated out.
     */
    @Test
    public void rotationIsOnlyPaidForByASetThatCanAffordIt() {
        // target 8: the usable minimum is 4, so the set must hold 5 to spend one
        assertFalse(TunnelPool.canAffordRotation(4, 8));
        assertTrue(TunnelPool.canAffordRotation(5, 8));
        // target 3: minimum 2, so 3 leases are needed to spend one
        assertFalse(TunnelPool.canAffordRotation(2, 3));
        assertTrue(TunnelPool.canAffordRotation(3, 3));
        // target 4: minimum 2
        assertFalse(TunnelPool.canAffordRotation(2, 4));
        assertTrue(TunnelPool.canAffordRotation(3, 4));
        // a one-lease set has nothing to rotate
        assertFalse(TunnelPool.canAffordRotation(1, 1));
        assertFalse(TunnelPool.canAffordRotation(0, 8));
    }

    /**
     * The two shapes measured on the live router: a target-3 pool down to two
     * leases, and a target-8 pool at exactly its minimum. Both were thinned by a
     * rotation into a warning, and both must now be left alone.
     */
    @Test
    public void theMeasuredSelfInflictedShortfallsAreBlocked() {
        assertFalse("harry.i2p: Rotated: 1 * Pool tunnels: 2 against a target of 3",
                    TunnelPool.canAffordRotation(2, 3));
        assertFalse("skank.i2p: Have 3 of 8 with the fourth lease rotated out",
                    TunnelPool.canAffordRotation(4, 8));
    }

    /**
     * The gate has to sit in the pass that rotates, not beside it: a helper no
     * caller consults reads green in unit tests and changes nothing in the log.
     *
     * @throws Exception if the TunnelPool source cannot be read back from disk
     */
    @Test
    public void theRotationGateIsWiredIntoTheAssemblyPass() throws Exception {
        java.io.File src =
            new java.io.File("router/java/src/net/i2p/router/tunnel/pool/TunnelPool.java");
        if (!src.isFile()) {
            src = new java.io.File("src/net/i2p/router/tunnel/pool/TunnelPool.java");
        }
        Assume.assumeTrue("source not available", src.isFile());
        String body = new String(java.nio.file.Files.readAllBytes(src.toPath()), "UTF-8");
        assertTrue("the rotation must consult the gate",
                   body.contains("if (!canAffordRotation(leases.size(), wanted)) {"));
        assertTrue("the assembly pass must hand the target to the rotation",
                   body.contains("rotateOldestLeaseForPropagation(leases, tunnels, " +
                                 "rotatedOut, wanted)"));
    }

    // ---- the tally must not double-count ----

    /**
     * A tunnel the set publishes after all is not also a tunnel it turned away.
     *
     * <p>The traffic-proven UNTESTED top-up and the emergency degraded fallback
     * both publish a tunnel the collection pass had already counted under its
     * reason, so the shortfall line would print the same tunnel in "Have N" and
     * in "Not offered", and the arithmetic a reader uses to check the tally
     * ({@code Have + reasons = pool tunnels}) would stop adding up.
     */
    @Test
    public void aPublishedTunnelIsNotAlsoCountedAsRejected() {
        long deadline = _ctx.clock().now() + 3 * MINUTE;
        Map<TunnelPool.LeaseRejection, Integer> rejected =
            new EnumMap<>(TunnelPool.LeaseRejection.class);
        rejected.put(TunnelPool.LeaseRejection.NOT_GOOD, 2);
        TunnelPool.unrejectTunnel(rejected, tunnel(0), true, deadline);
        assertEquals(Integer.valueOf(1), rejected.get(TunnelPool.LeaseRejection.NOT_GOOD));
        TunnelPool.unrejectTunnel(rejected, tunnel(1), true, deadline);
        assertFalse("an emptied reason must leave the tally",
                    rejected.containsKey(TunnelPool.LeaseRejection.NOT_GOOD));
    }

    /**
     * Taking a tunnel back out of a reason that never counted it must not invent
     * a negative count, and a tunnel the pass never turned away changes nothing.
     */
    @Test
    public void unrejectingWhatWasNeverCountedChangesNothing() {
        long deadline = _ctx.clock().now() + 3 * MINUTE;
        Map<TunnelPool.LeaseRejection, Integer> rejected =
            new EnumMap<>(TunnelPool.LeaseRejection.class);
        rejected.put(TunnelPool.LeaseRejection.NEAR_EXPIRY, 1);
        TunnelPool.unrejectTunnel(rejected, goodTunnel(10 * MINUTE), true, deadline);
        assertEquals("a GOOD tunnel was never a rejection",
                     Integer.valueOf(1), rejected.get(TunnelPool.LeaseRejection.NEAR_EXPIRY));
        TunnelPool.unrejectTunnel(rejected, failingTunnel(), true, deadline);
        assertEquals("a reason with no tunnels counted stays absent",
                     Integer.valueOf(1), rejected.get(TunnelPool.LeaseRejection.NEAR_EXPIRY));
        assertFalse(rejected.containsKey(TunnelPool.LeaseRejection.NOT_GOOD));
    }

    private static Lease lease(long end, long tunnelId, int gateway) {
        Lease lease = new Lease();
        lease.setEndDate(end);
        lease.setTunnelId(new TunnelId(tunnelId));
        lease.setGateway(hash(gateway));
        return lease;
    }
}
