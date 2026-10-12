package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.ProfileOrganizer;

/**
 * First-test dispatch order: fast-tier candidates before high-capacity ones,
 * before the untiered tail, with last-chance candidates ahead of all of them.
 *
 * <p>Dispatch order decides which candidates are tested at all when the buffer
 * outruns the dispatch budget: first-test denials were observed at roughly
 * 71/min. Without a tier-aware order, whether a freshly promoted fast peer gets
 * a prompt test depends on when it happened to enter the buffer.
 *
 * @since 0.9.71+
 */
public class TestJobTierPriorityTest {

    private static final long NOW = 1000L * 60 * 60 * 24;

    private static Hash hash(int b) {
        byte[] d = new byte[Hash.HASH_LENGTH];
        d[0] = (byte) b;
        d[1] = (byte) (7 - b);
        return Hash.create(d);
    }

    /**
     * A candidate whose first hop is {@code first}, and which is or is not
     * last-chance. {@code lastChance} false means far from expiry.
     */
    private static TestJob.PendingTest pending(Hash first, boolean lastChance) {
        PooledTunnelCreatorConfig cfg = mock(PooledTunnelCreatorConfig.class);
        when(cfg.getLength()).thenReturn(1);
        when(cfg.getPeer(0)).thenReturn(first);
        when(cfg.isLastChanceAdmitted(anyLong())).thenReturn(lastChance);
        when(cfg.getExpiration()).thenReturn(NOW + 1000L);
        return new TestJob.PendingTest(cfg, null);
    }

    /**
     * A context whose profile organizer reports the given peers as fast or
     * high-capacity; everything else is untiered.
     */
    private static RouterContext ctxWithTiers(Hash fast, Hash highCap) {
        RouterContext ctx = mock(RouterContext.class);
        ProfileOrganizer po = mock(ProfileOrganizer.class);
        when(ctx.profileOrganizer()).thenReturn(po);
        when(po.isFast(any(Hash.class))).thenAnswer(i -> fast != null && fast.equals(i.getArgument(0)));
        when(po.isHighCapacity(any(Hash.class))).thenAnswer(i -> highCap != null && highCap.equals(i.getArgument(0)));
        return ctx;
    }

    private static List<Hash> orderOf(List<TestJob.PendingTest> batch) {
        List<Hash> out = new ArrayList<>();
        for (TestJob.PendingTest p : batch) out.add(p.cfg.getPeer(0));
        return out;
    }

    // ---------------- tierRank ----------------

    @Test
    public void testTierRankFastIsBest() {
        Hash fast = hash(1);
        RouterContext ctx = ctxWithTiers(fast, null);
        assertEquals(0, TestJob.tierRank(pending(fast, false), ctx));
    }

    @Test
    public void testTierRankHighCapacityIsSecond() {
        Hash hc = hash(2);
        RouterContext ctx = ctxWithTiers(null, hc);
        assertEquals(1, TestJob.tierRank(pending(hc, false), ctx));
    }

    @Test
    public void testTierRankUnrankedIsLast() {
        RouterContext ctx = ctxWithTiers(hash(1), hash(2));
        assertEquals(2, TestJob.tierRank(pending(hash(3), false), ctx));
    }

    @Test
    public void testTierRankNullContextIsUnrankedNotDropped() {
        // no context means the peer cannot be looked up; the candidate must
        // still be dispatchable, just without tier preference
        assertEquals(2, TestJob.tierRank(pending(hash(1), false), null));
    }

    @Test
    public void testTierRankNullCandidateIsUnranked() {
        RouterContext ctx = ctxWithTiers(hash(1), hash(2));
        assertEquals(2, TestJob.tierRank(null, ctx));
    }

    @Test
    public void testTierRankEmptyConfigIsUnranked() {
        RouterContext ctx = ctxWithTiers(hash(1), hash(2));
        PooledTunnelCreatorConfig cfg = mock(PooledTunnelCreatorConfig.class);
        when(cfg.getLength()).thenReturn(0);
        assertEquals(2, TestJob.tierRank(new TestJob.PendingTest(cfg, null), ctx));
    }

    @Test
    public void testTierRankNullPeerIsUnranked() {
        RouterContext ctx = ctxWithTiers(hash(1), hash(2));
        PooledTunnelCreatorConfig cfg = mock(PooledTunnelCreatorConfig.class);
        when(cfg.getLength()).thenReturn(1);
        when(cfg.getPeer(0)).thenReturn(null);
        assertEquals(2, TestJob.tierRank(new TestJob.PendingTest(cfg, null), ctx));
    }

    @Test
    public void testTierRankThrowsIsUnranked() {
        // an unsized peer array throws; the candidate must survive as untiered
        RouterContext ctx = ctxWithTiers(hash(1), hash(2));
        PooledTunnelCreatorConfig cfg = mock(PooledTunnelCreatorConfig.class);
        when(cfg.getLength()).thenReturn(3);
        when(cfg.getPeer(anyInt())).thenThrow(new IllegalStateException("unsized"));
        assertEquals(2, TestJob.tierRank(new TestJob.PendingTest(cfg, null), ctx));
    }

    // ---------------- ordering ----------------

    @Test
    public void testOrderingFastThenHighCapThenRest() {
        Hash fast = hash(1), hc = hash(2), plain = hash(3);
        RouterContext ctx = ctxWithTiers(fast, hc);
        List<TestJob.PendingTest> scan = Arrays.asList(
                pending(plain, false), pending(hc, false), pending(fast, false));

        List<Hash> order = orderOf(TestJob.prioritizeBatch(scan, NOW, ctx));
        assertEquals(Arrays.asList(fast, hc, plain), order);
    }

    @Test
    public void testOrderingIsStableWithinTier() {
        // two fast peers must keep their buffer order relative to each other,
        // so a strict total order cannot reshuffle the queue between passes
        Hash fastA = hash(1), fastB = hash(2);
        RouterContext ctx = ctxWithTiers(fastA, null);
        when(ctx.profileOrganizer().isFast(any(Hash.class))).thenAnswer(i ->
                fastA.equals(i.getArgument(0)) || fastB.equals(i.getArgument(0)));

        List<TestJob.PendingTest> scan = Arrays.asList(
                pending(fastA, false), pending(fastB, false));
        List<Hash> order = orderOf(TestJob.prioritizeBatch(scan, NOW, ctx));
        assertEquals(Arrays.asList(fastA, fastB), order);
    }

    @Test
    public void testLastChanceBeatsTier() {
        // a dying tunnel keeps its only test even against a fast-tier candidate
        Hash fast = hash(1), dying = hash(4);
        RouterContext ctx = ctxWithTiers(fast, null);
        List<TestJob.PendingTest> scan = Arrays.asList(
                pending(fast, false), pending(dying, true));

        List<Hash> order = orderOf(TestJob.prioritizeBatch(scan, NOW, ctx));
        assertEquals(Arrays.asList(dying, fast), order);
    }

    @Test
    public void testLastChanceSortsBySoonestExpiry() {
        Hash a = hash(4), b = hash(5);
        RouterContext ctx = ctxWithTiers(null, null);
        PooledTunnelCreatorConfig late = mock(PooledTunnelCreatorConfig.class);
        when(late.getLength()).thenReturn(1);
        when(late.getPeer(0)).thenReturn(b);
        when(late.isLastChanceAdmitted(anyLong())).thenReturn(true);
        when(late.getExpiration()).thenReturn(NOW + 9999L);
        PooledTunnelCreatorConfig soon = mock(PooledTunnelCreatorConfig.class);
        when(soon.getLength()).thenReturn(1);
        when(soon.getPeer(0)).thenReturn(a);
        when(soon.isLastChanceAdmitted(anyLong())).thenReturn(true);
        when(soon.getExpiration()).thenReturn(NOW + 10L);

        List<TestJob.PendingTest> scan = Arrays.asList(
                new TestJob.PendingTest(late, null), new TestJob.PendingTest(soon, null));
        List<Hash> order = orderOf(TestJob.prioritizeBatch(scan, NOW, ctx));
        assertEquals(Arrays.asList(a, b), order);
    }

    @Test
    public void testNullContextPreservesBufferOrder() {
        // the two-arg form is retained for callers with no context; it must not
        // reorder, so existing behaviour is preserved for them
        Hash a = hash(1), b = hash(2), c = hash(3);
        List<TestJob.PendingTest> scan = Arrays.asList(
                pending(a, false), pending(b, false), pending(c, false));
        assertEquals(Arrays.asList(a, b, c), orderOf(TestJob.prioritizeBatch(scan, NOW)));
    }

    @Test
    public void testNoCandidatesLostOrDuplicated() {
        Hash fast = hash(1), hc = hash(2), plain = hash(3), plain2 = hash(4);
        RouterContext ctx = ctxWithTiers(fast, hc);
        List<TestJob.PendingTest> scan = Arrays.asList(
                pending(plain, false), pending(plain2, true), pending(hc, false),
                pending(fast, false), pending(plain, true));

        List<TestJob.PendingTest> out = TestJob.prioritizeBatch(scan, NOW, ctx);
        assertEquals("every candidate must survive reordering", scan.size(), out.size());
        assertTrue("first two are the last-chance pair", out.get(0).cfg.isLastChanceAdmitted(NOW));
        assertTrue(out.get(1).cfg.isLastChanceAdmitted(NOW));
        assertEquals(fast, out.get(2).cfg.getPeer(0));
        assertEquals(hc, out.get(3).cfg.getPeer(0));
        assertEquals(plain, out.get(4).cfg.getPeer(0));
    }

    @Test
    public void testEmptyScanIsHandled() {
        RouterContext ctx = ctxWithTiers(hash(1), hash(2));
        List<TestJob.PendingTest> out = TestJob.prioritizeBatch(new ArrayList<TestJob.PendingTest>(), NOW, ctx);
        assertNotNull(out);
        assertTrue(out.isEmpty());
    }
}